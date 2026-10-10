#!/usr/bin/env python3
"""Authenticated, task-scoped Docker controller. Runs ONLY inside the QEMU guest.

The Android application owns the bearer token. No Docker socket, guest shell,
host filesystem or account credentials are exposed to containers or agents.
"""
import base64
import hmac
import http.client
import json
import os
import re
import signal
import socket
import stat
import tarfile
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

ROOT = Path(os.environ.get('AA_TASK_ROOT', '/var/lib/androidagent/tasks'))
ROOT.mkdir(parents=True, exist_ok=True)
with open(os.environ.get('AA_TOKEN_DEVICE', '/dev/vdb'), 'rb') as secret:
    TOKEN = secret.read(64).decode('ascii')
if not re.fullmatch('[a-f0-9]{64}', TOKEN):
    raise SystemExit('Missing VM authentication token')
LOCK = threading.RLock()
JOBS = {}
PROCESSES = {}
OUTPUT_LIMIT = 65536
REQUEST_LIMIT = 1024 * 1024
LABEL = 'dev.mateuszsury.androidagent.task'


def command(args, timeout=45, check=True):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise ValueError(result.stdout.decode(errors='replace')[-8192:])
    return result.stdout.decode(errors='replace').strip()


def task_dir(task):
    if not isinstance(task, str) or not re.fullmatch('[a-f0-9]{32}', task):
        raise ValueError('Invalid task id')
    path = ROOT / task
    if not (path / 'task.json').is_file():
        raise ValueError('Task does not exist')
    return path


def task_info(path):
    info = json.loads((path / 'task.json').read_text())
    # Large logs are fetched separately. Heartbeats must not duplicate every
    # job's output and exceed the phone's bounded HTTP response size.
    info['jobs'] = [{k: v for k, v in j.items() if k != 'output'}
                    for j in JOBS.values() if j['task'] == path.name][-5:]
    return info


def engine_info():
    # A heartbeat should not launch and emulate another Go CLI process every
    # two seconds. Read the real engine's private Unix API instead.
    connection = http.client.HTTPConnection('localhost', timeout=3)
    connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.sock.settimeout(3)
    try:
        connection.sock.connect('/var/run/docker.sock')
        connection.request('GET', '/info')
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError('Docker info returned ' + str(response.status))
        return json.loads(response.read(1024 * 1024))
    finally:
        connection.close()


def work_file(path, name):
    if not isinstance(name, str) or not name or len(name) > 240:
        raise ValueError('Invalid file path')
    relative = Path(name)
    if relative.is_absolute() or '..' in relative.parts or '\x00' in name:
        raise ValueError('Use a relative path inside the task workspace')
    work = (path / 'work').resolve()
    target = (work / relative).resolve()
    if not target.is_relative_to(work) or target == work:
        raise ValueError('File escapes task workspace')
    return target


def open_work(path, name, write=False):
    # openat + O_NOFOLLOW on EVERY path component closes rename/symlink races
    # with a concurrently running container. A resolve()-then-open() is not enough.
    work_file(path, name)
    parts = Path(name).parts
    parent = os.open(path / 'work', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            if write:
                try:
                    os.mkdir(part, dir_fd=parent)
                except FileExistsError:
                    pass
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            if write:
                os.fchmod(child, (os.fstat(child).st_mode & 0o777) | 0o222)
            os.close(parent)
            parent = child
        flags = os.O_NOFOLLOW | os.O_NONBLOCK | (os.O_WRONLY | os.O_CREAT | os.O_TRUNC if write else os.O_RDONLY)
        fd = os.open(parts[-1], flags, 0o644, dir_fd=parent)
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            os.close(fd)
            raise ValueError('Only regular files are supported')
        if write:
            # Images may declare any unprivileged USER. Only this task is
            # mounted into the container; its sources must remain editable.
            os.fchmod(fd, (os.fstat(fd).st_mode & 0o777) | 0o222)
        return os.fdopen(fd, 'wb' if write else 'rb')
    finally:
        os.close(parent)


def active(task):
    return any(j['task'] == task and j['state'] in ('queued', 'running') for j in JOBS.values())


def save_job(job):
    (ROOT / job['task'] / ('job-' + job['id'] + '.json')).write_text(json.dumps(job))


def snapshot_context(path, destination):
    # Docker's directory client can follow a malicious Dockerfile/.dockerignore
    # link as guest root. Supply a bounded archive built from guarded descriptors.
    total = 0
    with tarfile.open(destination, 'w') as archive:
        for entry in (path / 'work').rglob('*'):
            if entry.is_symlink():
                raise ValueError('Build context cannot contain symbolic links')
            if entry.is_dir():
                continue
            name = str(entry.relative_to(path / 'work'))
            with open_work(path, name) as source:
                metadata = os.fstat(source.fileno())
                total += metadata.st_size
                if total > 512 * 1024 * 1024:
                    raise ValueError('Build context limit is 512 MiB')
                info = tarfile.TarInfo(name)
                info.size = metadata.st_size
                info.mode = metadata.st_mode & 0o777
                archive.addfile(info, source)


def start_job(task, kind, argv, build_context=False):
    path = task_dir(task)
    if active(task):
        raise ValueError('This task already has an active operation')
    job = {'id': uuid.uuid4().hex, 'task': task, 'kind': kind, 'state': 'queued', 'output': '', 'started_at': int(time.time())}
    JOBS[job['id']] = job
    save_job(job)
    def execute():
        proc = None
        context_archive = path / ('context-' + job['id'] + '.tar')
        context_stream = None
        try:
            if build_context:
                snapshot_context(path, context_archive)
                context_stream = context_archive.open('rb')
            with LOCK:
                if job['state'] == 'cancelled':
                    return
                proc = subprocess.Popen(argv, stdin=context_stream or subprocess.DEVNULL,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
                PROCESSES[job['id']] = proc
                job['state'] = 'running'
            def timeout():
                if proc.poll() is None:
                    with LOCK:
                        job['state'] = 'timed_out'
                    terminate(proc)
                    # docker exec's client can exit while its container command
                    # remains alive. Stop this task's container on timeout too.
                    with LOCK:
                        cancel(task)
            timer = threading.Timer(900, timeout)
            timer.start()
            try:
                while True:
                    chunk = os.read(proc.stdout.fileno(), 4096)
                    if not chunk:
                        break
                    with LOCK:
                        job['output'] = (job['output'] + chunk.decode(errors='replace'))[-OUTPUT_LIMIT:]
                code = proc.wait()
            finally:
                timer.cancel()
            with LOCK:
                job['exit_code'] = code
                if job['state'] == 'running':
                    job['state'] = 'succeeded' if code == 0 else 'failed'
        except Exception as error:
            with LOCK:
                if job['state'] not in ('cancelled', 'timed_out'):
                    job['state'] = 'failed'
                job['output'] = (job['output'] + '\n' + str(error))[-OUTPUT_LIMIT:]
        finally:
            if context_stream:
                context_stream.close()
            context_archive.unlink(missing_ok=True)
            if proc and proc.stdout:
                proc.stdout.close()
            with LOCK:
                PROCESSES.pop(job['id'], None)
                job['finished_at'] = int(time.time())
                if path.exists():
                    save_job(job)
    threading.Thread(target=execute, daemon=True).start()
    return job.copy()


def terminate(proc):
    try:
        os.killpg(proc.pid, signal.SIGTERM)
        proc.wait(timeout=1)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    except ProcessLookupError:
        pass


def cancel(task):
    for job in JOBS.values():
        if job['task'] == task and job['state'] in ('queued', 'running'):
            job['state'] = 'cancelled'
            proc = PROCESSES.get(job['id'])
            if proc:
                terminate(proc)
    # Stop commands running in detached containers as well as Docker CLI jobs.
    ids = command(['docker', 'ps', '-q', '--filter', 'label=' + LABEL + '=' + task], check=False)
    if ids:
        command(['docker', 'stop', '-t', '2'] + ids.split(), check=False)


for path in ROOT.iterdir():
    if not path.is_dir():
        continue
    for f in path.glob('job-*.json'):
        try:
            job = json.loads(f.read_text())
            if job['state'] in ('queued', 'running'):
                job['state'] = 'interrupted'
                save_job(job)
            JOBS[job['id']] = job
        except (ValueError, KeyError):
            pass


def dispatch(a):
    op = a.get('op')
    with LOCK:
        if op == 'status':
            try:
                engine = engine_info()
                ready = bool(engine.get('ServerVersion')) and Path('/var/lib/androidagent/images-ready').exists()
            except (OSError, ValueError, http.client.HTTPException) as error:
                engine, ready = {'error': str(error)}, False
            return {'ready': ready, 'engine_version': engine.get('ServerVersion'), 'architecture': os.uname().machine,
                    'storage_driver': engine.get('Driver'), 'disk_free_bytes': os.statvfs(ROOT).f_bavail * os.statvfs(ROOT).f_frsize,
                    'tasks': [task_info(p) for p in ROOT.iterdir() if (p / 'task.json').is_file()]}
        if op == 'create':
            task = uuid.uuid4().hex
            path = ROOT / task
            (path / 'work').mkdir(parents=True)
            (path / 'work').chmod(0o777)
            info = {'id': task, 'name': str(a.get('name', 'Nowe zadanie'))[:100], 'created_at': int(time.time())}
            (path / 'task.json').write_text(json.dumps(info))
            return task_info(path)
        if op == 'shutdown':
            for p in ROOT.iterdir():
                if (p / 'task.json').is_file():
                    cancel(p.name)
            command(['sync'])
            threading.Timer(1, lambda: subprocess.Popen(['poweroff', '-f'])).start()
            return {'stopping': True}
        path = task_dir(a.get('task'))
        task = path.name
        container = 'aa-' + task
        image = 'androidagent-task:' + task
        if op == 'write':
            if active(task):
                raise ValueError('Wait for the active operation before changing files')
            data = str(a.get('content', '')).encode()
            if len(data) > 256 * 1024:
                raise ValueError('Text file limit is 256 KiB')
            with open_work(path, a.get('path'), write=True) as target:
                target.write(data)
            return {'path': a['path'], 'bytes': len(data)}
        if op == 'files':
            files = []
            for f in (path / 'work').rglob('*'):
                if f.is_file():
                    files.append(str(f.relative_to(path / 'work')))
                    if len(files) == 500:
                        break
            return {'files': files}
        if op == 'read':
            with open_work(path, a.get('path')) as source:
                data = source.read(256 * 1024 + 1)
            if len(data) > 256 * 1024:
                raise ValueError('Text file limit is 256 KiB; export larger files')
            return {'path': a['path'], 'content': data.decode('utf-8')}
        if op == 'build':
            dockerfile = a.get('dockerfile', 'Dockerfile')
            with open_work(path, dockerfile):
                pass
            return start_job(task, 'build', ['docker', 'build', '--label', LABEL + '=' + task,
                             '-t', image, '-f', dockerfile, '-'], build_context=True)
        if op == 'run':
            if active(task):
                raise ValueError('This task already has an active operation')
            source = a.get('image') or image
            if not isinstance(source, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_./:@-]{0,220}', source):
                raise ValueError('Invalid image reference')
            memory = max(64, min(2048, int(a.get('memory_mb', 512))))
            argv = ['docker', 'run', '-d', '--name', container, '--label', LABEL + '=' + task,
                    '--restart=no', '--memory', str(memory) + 'm', '--pids-limit', '256', '--cpus', '1',
                    '--mount', 'type=bind,src=' + str(path / 'work') + ',dst=/workspace']
            port = a.get('port')
            if port is not None:
                port = int(port)
                if not 1 <= port <= 65535:
                    raise ValueError('Invalid container port')
                argv += ['-p', '8080:' + str(port)]
            cmd = a.get('command')
            if cmd:
                argv += ['-w', '/workspace']
            # Preserve the image's WORKDIR for its normal entrypoint/CMD.
            argv += [source]
            if cmd:
                argv += ['sh', '-lc', str(cmd)]
            # Replacement belongs to the asynchronous job too. Starting another
            # Go CLI while holding the request lock can block all heartbeats on
            # a slow phone. The fixed script receives every value as an argument.
            replace = ['sh', '-c', 'docker rm -f "$1" >/dev/null 2>&1; shift; exec "$@"',
                       'androidagent-run', container]
            return start_job(task, 'run', replace + argv)
        if op == 'exec':
            cmd = a.get('command')
            if not isinstance(cmd, str) or not cmd or len(cmd) > 8192:
                raise ValueError('Command must contain 1–8192 characters')
            return start_job(task, 'exec', ['docker', 'exec', '-w', '/workspace', container, 'sh', '-lc', cmd])
        if op == 'logs':
            job_id = a.get('job')
            if job_id:
                job = JOBS.get(job_id)
                if not job or job['task'] != task:
                    raise ValueError('Job does not belong to this task')
                return job.copy()
            return {'output': command(['docker', 'logs', '--tail', '200', container], check=False)[-OUTPUT_LIMIT:]}
        if op == 'cancel':
            cancel(task)
            return task_info(path)
        if op == 'cleanup':
            cancel(task)
            ids = command(['docker', 'ps', '-aq', '--filter', 'label=' + LABEL + '=' + task], check=False)
            if ids:
                command(['docker', 'rm', '-f', '-v'] + ids.split())
            ids = command(['docker', 'images', '-q', '--filter', 'label=' + LABEL + '=' + task], check=False)
            if ids:
                command(['docker', 'image', 'rm'] + list(dict.fromkeys(ids.split())))
            import shutil
            shutil.rmtree(path)
            for key in [key for key, job in JOBS.items() if job['task'] == task]:
                JOBS.pop(key)
            return {'cleaned': task, 'shared_cache_retained': True}
        raise ValueError('Unknown operation')


class Handler(BaseHTTPRequestHandler):
    def authorized(self):
        if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + TOKEN):
            self.reply(401, {'error': 'Unauthorized'})
            return False
        return True

    def reply(self, code, data):
        payload = json.dumps(data).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self):
        if not self.authorized():
            return
        try:
            if self.path != '/request':
                raise ValueError('Unknown endpoint')
            count = int(self.headers.get('Content-Length', '0'))
            if not 0 < count <= REQUEST_LIMIT:
                raise ValueError('Request size exceeds limit')
            data = json.loads(self.rfile.read(count))
            self.reply(200, dispatch(data))
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            self.reply(400, {'error': str(error)[:8192]})

    def do_GET(self):
        if not self.authorized():
            return
        try:
            url = urlsplit(self.path)
            if url.path != '/artifact':
                raise ValueError('Unknown endpoint')
            query = parse_qs(url.query)
            with open_work(task_dir(query['task'][0]), query['path'][0]) as stream:
                if os.fstat(stream.fileno()).st_size > 128 * 1024 * 1024:
                    raise ValueError('Artifact must be no larger than 128 MiB')
                self.send_response(200)
                self.send_header('Content-Type', 'application/octet-stream')
                self.send_header('Content-Length', str(os.fstat(stream.fileno()).st_size))
                self.end_headers()
                import shutil
                shutil.copyfileobj(stream, self.wfile, 65536)
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            self.reply(400, {'error': str(error)[:8192]})

    def log_message(self, fmt, *args):
        # Never log bearer tokens or source file contents.
        print(fmt % args, flush=True)


if __name__ == '__main__':
    ThreadingHTTPServer(('0.0.0.0', 7080), Handler).serve_forever()
