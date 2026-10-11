#!/usr/bin/env python3
"""Real ARM Linux/QEMU migration and supervised startup; never a phone test."""
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import uuid
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build-runtime/arm-guest-test'
REPORT = ROOT / 'artifacts/docker/arm-startup-migration-results.json'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
DOCKER = ['env', '-u', 'DOCKER_HOST', '-u', 'DOCKER_CONTEXT', '-u', 'DOCKER_TLS', '-u', 'DOCKER_TLS_VERIFY', '-u', 'DOCKER_CERT_PATH', 'docker', '--host=unix:///var/run/docker.sock']
checks, phases = [], []
process = None
phase = None
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
run_id = str(uuid.uuid4())


def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    checks.append({'name': name, 'pass': True, 'phase': phase})
    print('PASS ' + name, flush=True)


def request(op, timeout=60, **args):
    token = (OUT / 'token').read_bytes()[:64].decode()
    call = urllib.request.Request('http://127.0.0.1:19780/request', data=json.dumps({'op': op, **args}).encode(),
                                 headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with OPENER.open(call, timeout=timeout) as response:
        return json.load(response)


def start(name, reuse=False, legacy=False, delay=0, quota=0.5):
    global process, phase
    phase = name
    log = ROOT / 'artifacts/docker' / ('arm-' + name + '.log')
    log.parent.mkdir(parents=True, exist_ok=True)
    args = ['bash', str(ROOT / 'tools/start-arm-guest-test.sh'), '--host-clock', '--memory', '1024', '--cpu-quota', str(quota)]
    if reuse:
        args.append('--reuse-disk')
    if legacy:
        args.append('--legacy-runtime')
    args += ['--containerd-delay', str(delay)]
    with log.open('wb') as stream:
        process = subprocess.Popen(args, stdout=stream, stderr=subprocess.STDOUT, cwd=ROOT)
    began = time.monotonic()
    observations = []
    until = began + 600
    while time.monotonic() < until:
        if process.poll() is not None:
            raise AssertionError(f'{name}: VM exited with {process.returncode}; see {log}')
        try:
            status = request('status', timeout=15)
            startup = status.get('startup', {})
            if not observations or observations[-1].get('stage') != startup.get('stage'):
                observations.append({**startup, 'observed_after_seconds': round(time.monotonic() - began, 3)})
                print('STAGE ' + name + ' ' + startup.get('stage', 'legacy'), flush=True)
            if startup.get('stage') == 'error':
                raise AssertionError(f'{name}: {startup}')
            if status['ready']:
                elapsed = round(time.monotonic() - began, 3)
                phases.append({'name': name, 'ram_mb': 1024, 'vcpus': 1, 'host_cpu_quota': quota,
                               'clock_mode': 'host', 'icount': None, 'legacy_runtime': legacy,
                               'injected_containerd_delay_seconds': delay, 'ready_after_seconds': elapsed,
                               'startup': startup, 'observations': observations, 'log': str(log.relative_to(ROOT))})
                return status
        except (OSError, ValueError):
            pass
        time.sleep(2)
    raise AssertionError(f'{name}: startup exceeded 600 host seconds')


def stop():
    global process
    check('Clean guest shutdown accepted', request('shutdown')['stopping'])
    if process is not None:
        code = process.wait(timeout=90)
        check('QEMU ended after synchronized shutdown', code == 0)
        process = None


def job(task, op, **args):
    result = request(op, task=task, **args)
    until = time.monotonic() + 300
    while time.monotonic() < until:
        result = request('logs', task=task, job=result['id'])
        if result['state'] not in ('queued', 'running'):
            if result['state'] != 'succeeded':
                raise AssertionError(result)
            return result
        time.sleep(1)
    raise AssertionError('Operation timed out: ' + op)


def artifact(task, name):
    token = (OUT / 'token').read_bytes()[:64].decode()
    call = urllib.request.Request(f'http://127.0.0.1:19780/artifact?task={task}&path={name}', headers={'Authorization': 'Bearer ' + token})
    with OPENER.open(call, timeout=30) as response:
        return response.read()


try:
    manifest = json.loads((ROOT / 'app/src/main/assets/docker/arm64-v8a/manifest.json').read_text())
    check('Exact 0.4.2 base disk remains unchanged', manifest['files']['base.qcow2.gz'] == '7399ae1c32e046f67bc5bc1de6ff2b71af4ed1329eefe3600396ab6bc67821d4')
    check('Exact 0.4.2 kernel remains unchanged', manifest['files']['vmlinuz-virt'] == '0431ad6e5e0296080add279573c773287b55427bf3ec94fbc2c9f985d0b79966')
    check('Legacy test initramfs is exact 0.4.2', sha(ROOT / 'build-runtime/guest-arm64-v8a/initramfs-virt') == '947384afeab8c7622d144e1b400d81b3a18b5353252ab38f45dd982fefd45d27')
    status = start('legacy-seed', legacy=True, quota=1)
    check('Legacy actual ARM engine ready', status['architecture'] == 'aarch64' and status['engine_version'] == '29.5.2')
    legacy = request('create', name='0.4.2 preserved project')['id']
    request('write', task=legacy, path='keep.txt', content='PRESERVE_0.4.2_PROJECT')
    request('write', task=legacy, path='Dockerfile', content='FROM alpine:3.23\nCMD ["sh", "-c", "echo LEGACY_CONTAINER_MARKER; sleep 3600"]\n')
    check('Legacy image built before migration', job(legacy, 'build')['state'] == 'succeeded')
    check('Legacy container started before migration', job(legacy, 'run', memory_mb=128)['state'] == 'succeeded')
    inode = (OUT / 'user.qcow2').stat().st_ino
    base_sha = sha(OUT / 'base.qcow2')
    stop()
    status = start('upgrade-delayed', reuse=True, delay=20)
    check('New supervisor actual ARM engine and overlay2', status['architecture'] == 'aarch64' and status['engine_version'] == '29.5.2' and status['storage_driver'] == 'overlay2')
    check('Containerd delay beyond managed 15 seconds accepted only after real RPC', status['startup']['test_delay_seconds'] == 20 and status['startup']['elapsed_seconds'] >= 20 and status['startup']['containerd_version']['version'] == 'v2.2.0')
    check('Migration reuses original overlay inode and backing bytes', (OUT / 'user.qcow2').stat().st_ino == inode and sha(OUT / 'base.qcow2') == base_sha)
    check('0.4.2 project remains after guest code update', request('read', task=legacy, path='keep.txt')['content'] == 'PRESERVE_0.4.2_PROJECT')
    # Restart of Docker restores the existing container object, but its live
    # process was deliberately stopped by shutdown; start is a normal task run.
    check('0.4.2 container object and its logs survive migration', 'LEGACY_CONTAINER_MARKER' in request('logs', task=legacy)['output'])
    check('0.4.2 built image and container run after migration', job(legacy, 'run', memory_mb=128)['state'] == 'succeeded')
    check('Migrated container exec sees persisted workspace', 'PRESERVE_0.4.2_PROJECT' in job(legacy, 'exec', command='cat /workspace/keep.txt')['output'])
    task = request('create', name='ARM full lifecycle')['id']
    demo = ROOT / 'app/src/main/assets/docker/demo'
    request('write', task=task, path='index.html', content='<html><body>REAL_ARM_DOCKER_PREVIEW<button onclick="this.textContent=\'works\'">Tap</button></body></html>')
    request('write', task=task, path='serve.sh', content=(demo / 'serve.sh').read_text())
    request('write', task=task, path='Dockerfile', content=(demo / 'Dockerfile').read_text().replace('USER 1000:1000', 'RUN echo ARM_REAL_BUILD_OK > /built.txt\nUSER 1000:1000'))
    check('Docker build executes ARM RUN and COPY', job(task, 'build')['state'] == 'succeeded')
    check('Docker run actual non-root web application', job(task, 'run', memory_mb=128, port=8080)['state'] == 'succeeded')
    with OPENER.open('http://127.0.0.1:19781', timeout=30) as response:
        html = response.read().decode()
    check('Actual forwarded HTML with interactive control (JS executed separately in Android WebView tests)', 'REAL_ARM_DOCKER_PREVIEW' in html and 'onclick=' in html)
    result = job(task, 'exec', command='uname -m; test "$(id -u)" = 1000 && test "$(readlink /proc/1/cwd)" = /app && test "$(cat /sys/fs/cgroup/memory.max)" = 134217728 && mkdir -p /workspace/out && cp /built.txt /workspace/out/built.txt')
    check('ARM exec verifies UID, WORKDIR and actual 128 MiB cgroup', 'aarch64' in result['output'])
    check('Container logs accessible', isinstance(request('logs', task=task)['output'], str))
    exported = artifact(task, 'out/built.txt')
    retained = ROOT / 'artifacts/docker/arm-retained-build.txt'
    retained.write_bytes(exported)
    check('Actual build output exported', exported.strip() == b'ARM_REAL_BUILD_OK')
    pending = request('exec', task=task, command='sleep 60; echo SHOULD_NOT_FINISH')
    time.sleep(2)
    request('cancel', task=task)
    check('Cancellation stops running operation', request('logs', task=task, job=pending['id'])['state'] == 'cancelled')
    check('Cleanup targets only selected project', request('cleanup', task=task)['cleaned'] == task and request('read', task=legacy, path='keep.txt')['content'] == 'PRESERVE_0.4.2_PROJECT')
    stop()
    status = start('restart-persistent', reuse=True)
    check('Restart keeps migrated disk inode and project', (OUT / 'user.qcow2').stat().st_ino == inode and request('read', task=legacy, path='keep.txt')['content'] == 'PRESERVE_0.4.2_PROJECT')
    check('Host export survives cleanup and VM restart', retained.read_bytes() == exported)
    check('Migrated image reusable after second reboot', job(legacy, 'run', memory_mb=128)['state'] == 'succeeded')
    request('cleanup', task=legacy)
    stop()
    status = start('clean-delayed', delay=20)
    check('Fresh disk ready after actual base image import and sync', status['ready'] and status['startup']['stage'] == 'ready' and status['startup']['test_delay_seconds'] == 20)
    fresh = request('create', name='Clean first install')['id']
    request('write', task=fresh, path='Dockerfile', content='FROM alpine:3.23\nRUN echo FRESH_IMAGE_OK >/built\nCMD ["sh", "-c", "sleep 3600"]\n')
    job(fresh, 'build')
    job(fresh, 'run', memory_mb=128)
    check('Fresh imported Alpine builds and runs real container', 'FRESH_IMAGE_OK' in job(fresh, 'exec', command='cat /built')['output'])
    request('cleanup', task=fresh)
    stop()
    report = {'failures': 0}
except Exception as error:
    checks.append({'name': 'runtime', 'pass': False, 'phase': phase, 'detail': str(error)})
    report = {'failures': 1, 'error': str(error)}
    print('FAIL ' + str(error), flush=True)
finally:
    if process is not None:
        subprocess.run(DOCKER + ['stop', '-t', '10', 'androidagent-arm-guest-test'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.terminate()
    report.update({'started_at': started, 'completed_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   'run_id': run_id,
                   'environment': 'aarch64 Linux guest under native x86_64 host QEMU TCG; not physical Android',
                   'version': re.search(r'VERSION = "([^"]+)"', (ROOT / 'app/src/main/java/dev/mateuszsury/androidagent/BuildInfo.java').read_text())[1],
                   'host_architecture': os.uname().machine, 'tests': checks, 'phases': phases,
                   'guest_manifest': json.loads((ROOT / 'app/src/main/assets/docker/arm64-v8a/manifest.json').read_text()),
                   'limits': ['No physical Android ARM host or tablet validation', 'HTTP serves real interactive HTML; JavaScript requires separate Android WebView suite', 'CPU quota limits x86 host, not a calibrated Lenovo benchmark']})
    versions = []
    for log in (ROOT / 'artifacts/docker').glob('arm-*.log'):
        match = re.search(r'QEMU emulator version ([^\r\n]+)', log.read_text(errors='replace'))
        if match:
            versions.append({'log': str(log.relative_to(ROOT)), 'qemu_version': match[1]})
    report['qemu_versions'] = versions
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2) + '\n')
raise SystemExit(report['failures'])
