#!/usr/bin/env python3
"""Start actual containerd RPC before Docker; never use its 15s managed startup.

Runs only as guest root. State/log files carry no Android authentication token.
Persistent roots match Docker 0.4.2's managed containerd, preserving its content.
"""
import http.client
import json
import os
import socket
import struct
import signal
import threading
import diagnostics
import subprocess
import time
from pathlib import Path

SOCKET = '/var/run/docker/containerd/containerd.sock'
DOCKER_SOCKET = '/var/run/docker.sock'
CONSOLE_LOCK = threading.Lock()
CONSOLE_FD = None


def emit(data):
    # Console delivery is independent of bounded persistent-file compaction.
    # Losing the console must never interrupt supervision or process cleanup.
    global CONSOLE_FD
    try:
        with CONSOLE_LOCK:
            if CONSOLE_FD is None:
                # A separate open file description avoids making the agent's
                # inherited stdout nonblocking. A full console pipe cannot
                # stall the startup budget or its finally cleanup.
                CONSOLE_FD = os.open('/proc/self/fd/1', os.O_WRONLY | os.O_NONBLOCK | os.O_APPEND)
            view = memoryview(data)
            while view:
                count = os.write(CONSOLE_FD, view)
                if not count:
                    break
                view = view[count:]
    except OSError:
        pass


def alive(process, name):
    if process.poll() is not None:
        raise RuntimeError(f'{name} exited with code {process.returncode}; inspect its startup log')


class DeadlineSocket(socket.socket):
    """Apply one absolute deadline to every recv, including HTTP buffered reads."""
    def __init__(self, deadline):
        super().__init__(socket.AF_UNIX, socket.SOCK_STREAM)
        self.deadline = deadline

    def prepare(self):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Unix API probe absolute deadline exceeded')
        self.settimeout(remaining)

    def connect(self, address):
        self.prepare()
        return super().connect(address)

    def sendall(self, data, flags=0):
        self.prepare()
        return super().sendall(data, flags)

    def recv(self, size, flags=0):
        self.prepare()
        return super().recv(size, flags)

    def recv_into(self, buffer, size=0, flags=0):
        self.prepare()
        return super().recv_into(buffer, size, flags)


def containerd_version(deadline=None):
    """The fixed, empty Version gRPC call over its private Unix HTTP/2 socket.

    Alpine deliberately packages ctr separately. This small probe avoids an
    extra Go CLI and dependencies in startup. Literal HPACK headers need no
    compression table; only protobuf Version/Revision strings are accepted.
    Protocol: containerd.services.version.v1.Version/Version (containerd API).
    """
    def frame(kind, flags, stream, data=b''):
        return len(data).to_bytes(3, 'big') + bytes([kind, flags]) + struct.pack('!I', stream) + data

    def literal(name, value):
        if len(name) >= 127 or len(value) >= 127:
            raise ValueError('Version probe header too long')
        return b'\x00' + bytes([len(name)]) + name + bytes([len(value)]) + value

    def receive(sock, size):
        output = bytearray()
        while len(output) < size:
            block = sock.recv(size - len(output))
            if not block:
                raise OSError('containerd closed its Version RPC before completion')
            output.extend(block)
        return bytes(output)

    headers = b'\x83\x86'  # static HPACK POST/http
    headers += literal(b':path', b'/containerd.services.version.v1.Version/Version')
    headers += literal(b':authority', b'localhost')
    headers += literal(b'content-type', b'application/grpc') + literal(b'te', b'trailers')
    deadline = deadline or time.monotonic() + 8
    with DeadlineSocket(deadline) as sock:
        sock.connect(SOCKET)
        sock.sendall(b'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n' + frame(4, 0, 0) + frame(1, 4, 1, headers) + frame(0, 1, 1, b'\0' * 5))
        data, ended = bytearray(), False
        until = deadline
        while not ended and time.monotonic() < until:
            header = receive(sock, 9)
            length, kind, flags = int.from_bytes(header[:3], 'big'), header[3], header[4]
            stream = struct.unpack('!I', header[5:])[0] & 0x7fffffff
            if length > 65536:
                raise ValueError('Oversized containerd Version RPC frame')
            body = receive(sock, length)
            if kind == 4 and not flags & 1:
                sock.sendall(frame(4, 1, 0))
            elif kind == 6 and not flags & 1:
                sock.sendall(frame(6, 1, 0, body))
            elif kind in (3, 7):
                raise OSError('containerd rejected its Version RPC')
            if stream == 1:
                if kind == 0:
                    if flags & 8:  # DATA padding
                        body = body[1:len(body) - body[0]]
                    data.extend(body)
                ended = kind in (0, 1) and bool(flags & 1)
        if time.monotonic() >= deadline:
            raise TimeoutError('Version RPC absolute deadline exceeded')
        if not ended or len(data) < 5 or data[0] != 0:
            raise ValueError('Incomplete or compressed Version RPC response')
        size = int.from_bytes(data[1:5], 'big')
        message = bytes(data[5:])
        if size != len(message) or size > 4096:
            raise ValueError('Invalid Version RPC response size')
        values, position = {}, 0
        while position < len(message):
            tag = message[position]
            position += 1
            if tag not in (10, 18):  # VersionResponse version=1, revision=2
                raise ValueError('Unexpected Version RPC protobuf field')
            length, shift = 0, 0
            while True:
                if position >= len(message) or shift > 28:
                    raise ValueError('Invalid Version RPC protobuf length')
                item = message[position]
                position += 1
                length |= (item & 127) << shift
                if not item & 128:
                    break
                shift += 7
            value = message[position:position + length].decode('ascii')
            if len(value) != length:
                raise ValueError('Truncated Version RPC protobuf string')
            values[tag] = value
            position += length
        if not values.get(10):
            raise ValueError('Version RPC did not return a server version')
        return {'version': values[10], 'revision': values.get(18, '')}


def docker_api(path, timeout=5, deadline=None):
    connection = http.client.HTTPConnection('localhost', timeout=timeout)
    deadline = deadline or time.monotonic() + timeout
    connection.sock = DeadlineSocket(deadline)
    try:
        connection.sock.connect(DOCKER_SOCKET)
        connection.request('GET', path)
        response = connection.getresponse()
        body = response.read(1024 * 1024)
        if time.monotonic() >= deadline:
            raise TimeoutError('Docker API absolute deadline exceeded')
        if response.status != 200:
            raise RuntimeError(f'Docker {path} returned {response.status}')
        return body
    finally:
        connection.close()


class Cancelled(InterruptedError):
    pass


class PhaseBudget:
    def __init__(self, name, seconds, startup_deadline, cancel, clock=time.monotonic):
        self.name, self.seconds, self.clock = name, seconds, clock
        self.started = clock()
        self.deadline = min(self.started + seconds, startup_deadline)
        self.cancel = cancel

    def remaining(self):
        return max(0.0, self.deadline - self.clock())

    def check(self):
        if self.cancel.exists():
            raise Cancelled('Przerwano rozruch na żądanie użytkownika')
        if self.remaining() <= 0:
            raise TimeoutError(f'{self.name} did not complete within its {self.seconds}s phase/startup budget')

    def timeout(self, maximum):
        self.check()
        return min(maximum, self.remaining())


class Supervisor:
    TOTAL_SECONDS = 900
    PHASE_SECONDS = {'containerd': 180, 'docker': 360, 'image_probe': 15, 'load': 180, 'tag': 60, 'sync': 60}

    def __init__(self, journal=None, run_root=Path('/run'), data_root=Path('/var/lib/androidagent'), image_root=Path('/opt/androidagent')):
        self.journal = journal or diagnostics.Journal()
        self.run_root, self.data_root, self.image_root = Path(run_root), Path(data_root), Path(image_root)
        self.started = time.monotonic()
        self.startup_deadline = self.started + self.TOTAL_SECONDS
        self.cancel = self.run_root / 'androidagent-engine.cancel'
        self.phase = None
        self.phase_name = 'starting'
        self.stage, self.detail = 'starting', 'Przygotowanie nadzoru silnika'
        self.processes = []
        self.probe = {}
        self.extra = {}
        self.last_publish = 0
        self.previous_activity = None
        self.last_change = self.started
        self.state = {}
        self.version = None
        self.info = None
        self.flags = {}
        own = diagnostics.proc_info(os.getpid())
        self.identity = {'pid': os.getpid(), 'start_ticks': own.get('start_ticks')}
        self.log_errors = {}
        self.publish(force=True)

    def begin(self, name, detail):
        self.phase_name, self.stage, self.detail = name, name, detail
        self.phase = PhaseBudget(name, self.PHASE_SECONDS[name], self.startup_deadline, self.cancel)
        self.probe = {}
        self.previous_activity = None
        self.last_change = time.monotonic()
        self.publish(force=True)

    def managed(self):
        output = []
        for item in self.processes:
            process = item['process']
            info = diagnostics.proc_info(process.pid)
            if item['start_ticks'] is not None and info.get('start_ticks') is not None and item['start_ticks'] != info['start_ticks']:
                info = {'pid': process.pid, 'unavailable': True, 'pid_reused': True}
            output.append({**info, 'role': item['role'], 'pid': process.pid, 'start_ticks': item['start_ticks'],
                           'exit_code': process.poll(), 'spawn_after_seconds': item['spawn_after_seconds'],
                           'spawn_duration_seconds': item['spawn_duration_seconds']})
        return output

    def publish(self, force=False, **values):
        now = time.monotonic()
        self.extra.update(values)
        if not force and now - self.last_publish < 10:
            return self.state
        managed = self.managed()
        sampled = diagnostics.snapshot(managed)
        log_sizes = {name: (self.journal.directory / name).stat().st_size
                     for name in diagnostics.LOG_NAMES if (self.journal.directory / name).is_file()}
        counters = self.journal.counters()
        activity = (tuple((p['pid'], p.get('cpu_ticks'), tuple(sorted(p.get('io', {}).items()))) for p in sampled['processes']),
                    tuple((p['pid'], p.get('cpu_ticks')) for p in sampled['children']),
                    tuple(sorted(counters.items())))
        changed = self.previous_activity is not None and activity != self.previous_activity
        if changed:
            self.last_change = now
        self.previous_activity = activity
        progress = {'changed_since_previous': changed, 'no_change_seconds': round(now - self.last_change, 3),
                    'processes': managed, 'snapshot': sampled, 'log_bytes_on_disk': log_sizes,
                    'log_bytes_seen': counters, 'last_probe': self.probe}
        record = {'stage': self.stage, 'phase': self.phase_name, 'detail': self.detail,
                  'elapsed_seconds': round(now - self.started, 3), 'startup_budget_seconds': self.TOTAL_SECONDS,
                  'startup_remaining_seconds': round(max(0.0, self.startup_deadline - now), 3),
                  'phase_elapsed_seconds': round(now - self.phase.started, 3) if self.phase else 0,
                  'phase_budget_seconds': self.phase.seconds if self.phase else None,
                  'phase_remaining_seconds': round(self.phase.remaining(), 3) if self.phase else None,
                  'progress': progress, 'supervisor': self.identity, 'log_errors': dict(self.log_errors), **self.extra}
        terminal = self.stage in ('error', 'cancelled', 'ready')
        try:
            self.state = self.journal.publish(record, durable=force or terminal)
        finally:
            console_record = {**record, 'boot_id': self.journal.boot_id, 'runtime_revision': diagnostics.REVISION,
                              'recorded_at_utc': diagnostics.utc()}
            emit(b'ANDROIDAGENT_ENGINE ' + json.dumps(console_record).encode() + b'\n')
        self.last_publish = now
        return self.state

    def launch(self, role, argv, log_name):
        self.phase.check()
        began = time.monotonic()
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        info = diagnostics.proc_info(process.pid)
        self.processes.append({'role': role, 'process': process, 'start_ticks': info.get('start_ticks'),
                               'spawn_after_seconds': round(began - self.started, 3),
                               'spawn_duration_seconds': round(time.monotonic() - began, 3)})
        def collect():
            try:
                while True:
                    block = os.read(process.stdout.fileno(), 4096)
                    if not block:
                        break
                    try:
                        self.journal.log(log_name, block)
                    except Exception as error:
                        self.log_errors[role] = {'error_type': type(error).__name__, 'error': str(error)[:512]}
                    finally:
                        emit(block)
            finally:
                process.stdout.close()
        collector = threading.Thread(target=collect, daemon=True, name='log-' + role)
        self.processes[-1]['collector'] = collector
        collector.start()
        self.publish(force=True)
        return process

    def ensure_alive(self, process, name):
        alive(process, name)
        self.phase.check()

    def record_probe(self, name, began, success, error=None):
        self.probe = {'name': name, 'success': success, 'duration_seconds': round(time.monotonic() - began, 3),
                      'at_phase_elapsed_seconds': round(time.monotonic() - self.phase.started, 3)}
        if error is not None:
            self.probe.update({'error_type': type(error).__name__, 'error': str(error)[:1024]})
        self.publish()

    def idle(self):
        self.phase.check()
        self.publish()
        # Short waits make cancellation observable without starting another CLI.
        time.sleep(min(2, self.phase.remaining()))

    def command(self, role, argv, log_name):
        process = self.launch(role, argv, log_name)
        while process.poll() is None:
            self.idle()
        self.phase.check()
        self.publish(force=True)
        if process.returncode:
            raise RuntimeError(f'{role} exited with code {process.returncode}; inspect {log_name}')
        return process

    def start_containerd(self):
        self.begin('containerd', 'Uruchamianie containerd; oczekiwanie na Version RPC (limit 180 s)')
        Path('/var/run/docker/containerd/daemon').mkdir(parents=True, exist_ok=True)
        Path('/var/lib/docker/containerd/daemon').mkdir(parents=True, exist_ok=True)
        config = self.run_root / 'androidagent-containerd.toml'
        config.write_text('''version = 3
root = "/var/lib/docker/containerd/daemon"
state = "/var/run/docker/containerd/daemon"
disabled_plugins = ["io.containerd.cri.v1.images", "io.containerd.cri.v1.runtime", "io.containerd.grpc.v1.cri"]
[grpc]
  address = "/var/run/docker/containerd/containerd.sock"
[debug]
  level = "info"
''')
        for attempt in (1, 2):
            self.publish(force=True, containerd_attempt=attempt)
            argv = ['containerd', '--config', str(config)]
            delay = self.flags.get('containerd', 0)
            if delay:
                argv = ['/bin/sh', '-c', 'sleep "$1"; shift; exec "$@"', 'containerd-test-delay', str(delay), *argv]
            process = self.launch('containerd', argv, 'containerd.log')
            try:
                while True:
                    self.ensure_alive(process, 'containerd')
                    began = time.monotonic()
                    try:
                        self.version = containerd_version(deadline=time.monotonic() + self.phase.timeout(8))
                        self.phase.check()
                        self.record_probe('containerd Version RPC', began, True)
                        self.stage = 'containerd_ready'
                        self.publish(force=True, containerd_version=self.version)
                        return process
                    except (OSError, ValueError) as error:
                        self.record_probe('containerd Version RPC', began, False, error)
                    self.idle()
            except RuntimeError:
                if attempt == 2:
                    raise
                # One exited process can be retried, within the SAME 180s cap.
                self.phase.check()
                self.idle()

    def start_docker(self, containerd):
        self.begin('docker', 'Uruchamianie Docker Engine i reguł sieci; ping/info (limit 360 s)')
        argv = ['dockerd', '--containerd', SOCKET, '--containerd-namespace', 'moby', '--containerd-plugins-namespace', 'plugins.moby']
        delay = self.flags.get('dockerd', 0)
        if delay:
            argv = ['/bin/sh', '-c', 'sleep "$1"; shift; exec "$@"', 'docker-test-delay', str(delay), *argv]
        engine = self.launch('dockerd', argv, 'docker.log')
        while True:
            self.ensure_alive(containerd, 'containerd')
            self.ensure_alive(engine, 'dockerd')
            began = time.monotonic()
            try:
                if docker_api('/_ping', deadline=time.monotonic() + self.phase.timeout(5)) != b'OK':
                    raise ValueError('Docker ping response is not OK')
                self.info = json.loads(docker_api('/info', deadline=time.monotonic() + self.phase.timeout(5)))
                if not self.info.get('ServerVersion'):
                    raise ValueError('Docker info did not return ServerVersion')
                self.phase.check()
                self.record_probe('Docker ping/info', began, True)
                self.publish(force=True, engine_version=self.info['ServerVersion'], storage_driver=self.info.get('Driver'))
                return engine
            except (OSError, ValueError, RuntimeError, http.client.HTTPException) as error:
                self.record_probe('Docker ping/info', began, False, error)
            self.idle()

    def finish_images(self):
        self.begin('image_probe', 'Sprawdzanie rzeczywistego obrazu Alpine')
        image_present = False
        began = time.monotonic()
        try:
            image_present = bool(json.loads(docker_api('/images/alpine:3.23/json', deadline=time.monotonic() + self.phase.timeout(15))).get('Id'))
            self.record_probe('Docker image inspect', began, image_present)
        except (OSError, ValueError, RuntimeError, http.client.HTTPException) as error:
            self.record_probe('Docker image inspect', began, False, error)
        self.phase.check()
        if not image_present:
            self.begin('load', 'Importowanie dołączonego Alpine przez Docker (limit 180 s)')
            self.command('docker-load', ['docker', 'load', '-i', str(self.image_root / 'alpine-base.tar')], 'base-images.log')
            self.begin('tag', 'Zapisywanie nazwy obrazu Alpine (limit 60 s)')
            identity = (self.image_root / 'alpine-base-id').read_text().strip()
            self.command('docker-tag', ['docker', 'tag', identity, 'alpine:3.23'], 'base-images.log')
        self.begin('sync', 'Utrwalanie obrazu i danych na dysku (limit 60 s)')
        self.command('sync', ['sync'], 'base-images.log')
        self.phase.check()
        marker = self.data_root / 'images-ready'
        marker.touch()
        with marker.open('rb') as stream:
            os.fsync(stream.fileno())
        fd = os.open(marker.parent, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        self.phase.check()
        self.stage = 'ready'
        self.phase_name, self.phase = 'running', None
        self.detail = 'Docker i utrwalony obraz Alpine są gotowe'
        self.publish(force=True, engine_version=self.info['ServerVersion'], storage_driver=self.info.get('Driver'),
                     containerd_version=self.version, test_delay_seconds=self.flags.get('containerd', 0),
                     test_dockerd_delay_seconds=self.flags.get('dockerd', 0))

    def cleanup(self):
        outcomes = []
        for item in reversed(self.processes):
            process = item['process']
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=3 if item['role'] == 'dockerd' else 1)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    try:
                        process.wait(timeout=1)
                    except subprocess.TimeoutExpired:
                        pass
            collector = item.get('collector')
            if collector is not None and process.poll() is not None:
                collector.join(timeout=0.25)
            outcomes.append({'role': item['role'], 'pid': process.pid, 'exit_code': process.poll()})
        try:
            self.publish(force=True, cleanup=outcomes)
        except Exception:
            pass
        return outcomes

    def run(self):
        try:
            for item in Path('/proc/cmdline').read_text().split():
                for name in ('containerd', 'dockerd'):
                    prefix = 'aa_' + name + '_test_delay='
                    if item.startswith(prefix):
                        self.flags[name] = max(0, min(240 if name == 'dockerd' else 60, int(item.split('=', 1)[1])))
            containerd = self.start_containerd()
            engine = self.start_docker(containerd)
            self.finish_images()
            while True:
                alive(containerd, 'containerd')
                alive(engine, 'dockerd')
                if self.cancel.exists():
                    raise Cancelled('Zatrzymano środowisko na żądanie użytkownika')
                if time.monotonic() - self.last_publish >= 30:
                    self.publish(force=True)
                time.sleep(2)
        except Exception as error:
            self.stage = 'cancelled' if isinstance(error, Cancelled) else 'error'
            self.detail = str(error)
            try:
                self.publish(force=True, error_type=type(error).__name__)
            except Exception:
                pass
            return 0 if isinstance(error, Cancelled) else 1
        finally:
            # ENOSPC, read failures or a broken diagnostic sink cannot leave
            # containerd/dockerd running after their supervisor has failed.
            self.cleanup()


if __name__ == '__main__':
    raise SystemExit(Supervisor().run())
