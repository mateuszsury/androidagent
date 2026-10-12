#!/usr/bin/env python3
"""Real ARM Linux/QEMU migration and supervised startup; never a phone test."""
import datetime
import argparse
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
PARSER = argparse.ArgumentParser(description=__doc__)
PARSER.add_argument('--baseline-initramfs', type=Path, default=ROOT.parent / 'tools/usb-runtime/044-baseline-guest/arm64-v8a/initramfs-virt')
PARSER.add_argument('--baseline-version', choices=('0.4.2', '0.4.3'), default='0.4.3')
PARSER.add_argument('--report-dir', type=Path, default=ROOT / 'artifacts/0.4.4/docker-arm')
PARSER.add_argument('--vcpus', type=int, choices=(1, 2), default=2, help='vCPUs for the clean cold boot; baseline/upgrade use one')
PARSER.add_argument('--dockerd-delay', type=int, default=160)
PARSER.add_argument('--containerd-delay', type=int, default=20)
PARSER.add_argument('--cpu-quota', type=float, default=.25, help='host CPU cap during upgraded guest startup')
ARGS = PARSER.parse_args()
if not 121 <= ARGS.dockerd_delay <= 240 or not 0 <= ARGS.containerd_delay <= 60 or not .05 <= ARGS.cpu_quota <= 2:
    PARSER.error('dockerd-delay must exceed the old 120s cap and be <=240; containerd-delay0..60; quota0.05..2')
OUT = ROOT / 'build-runtime/arm-guest-test-044'
REPORT_DIR = ARGS.report_dir.resolve()
REPORT = REPORT_DIR / 'arm-startup-migration-results.json'
BASELINE_SHA = {'0.4.2': '947384afeab8c7622d144e1b400d81b3a18b5353252ab38f45dd982fefd45d27',
                '0.4.3': 'a2dffdd3132d8677bec23098e2a74af59522855f3952d14348208391fca31388'}[ARGS.baseline_version]
PRESERVED = 'PRESERVE_' + ARGS.baseline_version + '_PROJECT'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
DOCKER = ['env', '-u', 'DOCKER_HOST', '-u', 'DOCKER_CONTEXT', '-u', 'DOCKER_TLS', '-u', 'DOCKER_TLS_VERIFY', '-u', 'DOCKER_CERT_PATH', 'docker', '--host=unix:///var/run/docker.sock']
checks, phases = [], []
process = None
phase = None
started = datetime.datetime.now(datetime.timezone.utc).isoformat()
run_id = str(uuid.uuid4())
report = {'failures': 1, 'error': 'Run interrupted before completion'}


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


def relative(path):
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def save_diagnostics(name):
    result = request('diagnostics', timeout=15, tail_bytes=8192, max_bytes=262144)
    encoded = json.dumps(result).encode()
    if len(encoded) > 262144:
        raise AssertionError('Diagnostics exceeded exact 256KiB encoded limit')
    path = REPORT_DIR / ('arm-' + name + '-diagnostics.json')
    path.write_bytes(encoded + b'\n')
    return result, path


def start(name, reuse=False, legacy=False, delay=0, dockerd_delay=0, quota=None, vcpus=1):
    global process, phase
    phase = name
    log = REPORT_DIR / ('arm-' + name + '.log')
    log.parent.mkdir(parents=True, exist_ok=True)
    quota = ARGS.cpu_quota if quota is None else quota
    args = ['bash', str(ROOT / 'tools/start-arm-guest-test.sh'), '--host-clock', '--memory', '1024', '--cpu-quota', str(quota),
            '--vcpus', str(vcpus), '--baseline-initramfs', str(ARGS.baseline_initramfs.resolve()), '--report-dir', str(REPORT_DIR)]
    if reuse:
        args.append('--reuse-disk')
    if legacy:
        args.append('--legacy-runtime')
    args += ['--containerd-delay', str(delay), '--dockerd-delay', str(dockerd_delay)]
    with log.open('wb') as stream:
        process = subprocess.Popen(args, stdout=stream, stderr=subprocess.STDOUT, cwd=ROOT)
    began = time.monotonic()
    observations = []
    until = began + 1200
    incomplete_was_never_ready = True
    saw_pre_ready_diagnostics = False
    while time.monotonic() < until:
        if process.poll() is not None:
            raise AssertionError(f'{name}: VM exited with {process.returncode}; see {log}')
        try:
            status = request('status', timeout=15)
            startup = status.get('startup', {})
            if not observations or observations[-1].get('stage') != startup.get('stage'):
                observations.append({**startup, 'observed_after_seconds': round(time.monotonic() - began, 3)})
                print('STAGE ' + name + ' ' + startup.get('stage', 'legacy'), flush=True)
            if not legacy and startup.get('stage') != 'ready':
                incomplete_was_never_ready = incomplete_was_never_ready and not status['ready']
                if not saw_pre_ready_diagnostics:
                    diagnostic, unused = save_diagnostics(name + '-pre-ready')
                    saw_pre_ready_diagnostics = diagnostic['startup'].get('stage') != 'ready'
            if startup.get('stage') in ('error', 'cancelled'):
                if not legacy:
                    save_diagnostics(name + '-failure')
                raise AssertionError(f'{name}: {startup}')
            if status['ready']:
                elapsed = round(time.monotonic() - began, 3)
                diagnostic, diagnostic_path = (None, None) if legacy else save_diagnostics(name + '-ready')
                phases.append({'name': name, 'ram_mb': 1024, 'vcpus': vcpus, 'host_cpu_quota': quota,
                               'clock_mode': 'host', 'icount': None, 'legacy_runtime': legacy,
                               'baseline_version': ARGS.baseline_version if legacy else None,
                               'injected_containerd_delay_seconds': delay, 'injected_dockerd_delay_seconds': dockerd_delay,
                               'ready_after_seconds': elapsed, 'incomplete_was_never_ready': incomplete_was_never_ready,
                               'diagnostics_available_before_ready': saw_pre_ready_diagnostics,
                               'startup': startup, 'observations': observations, 'log': relative(log),
                               'ready_diagnostics': relative(diagnostic_path) if diagnostic_path else None})
                if not legacy:
                    check('Incomplete phases never reported ready: ' + name, incomplete_was_never_ready)
                    check('Read-only bounded diagnostics available before real ready: ' + name, saw_pre_ready_diagnostics)
                    events = diagnostic['events']
                    check('Ready follows verified image persistence: ' + name,
                          startup['runtime_revision'] == 3 and diagnostic['startup']['stage'] == 'ready'
                          and any(event.get('phase') == 'sync' for event in events))
                return status
        except (OSError, ValueError):
            pass
        time.sleep(2)
    if not legacy:
        try:
            save_diagnostics(name + '-outer-timeout')
        except (OSError, ValueError):
            pass
    raise AssertionError(f'{name}: startup exceeded 1200 host seconds')


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
    check('Baseline initramfs is exact ' + ARGS.baseline_version, sha(ARGS.baseline_initramfs) == BASELINE_SHA)
    status = start('baseline-' + ARGS.baseline_version.replace('.', '') + '-seed', legacy=True, quota=1)
    check('Legacy actual ARM engine ready', status['architecture'] == 'aarch64' and status['engine_version'] == '29.5.2')
    legacy = request('create', name=ARGS.baseline_version + ' preserved project')['id']
    request('write', task=legacy, path='keep.txt', content=PRESERVED)
    request('write', task=legacy, path='Dockerfile', content='FROM alpine:3.23\nCMD ["sh", "-c", "echo LEGACY_CONTAINER_MARKER; sleep 3600"]\n')
    check('Legacy image built before migration', job(legacy, 'build')['state'] == 'succeeded')
    check('Legacy container started before migration', job(legacy, 'run', memory_mb=128)['state'] == 'succeeded')
    inode = (OUT / 'user.qcow2').stat().st_ino
    base_sha = sha(OUT / 'base.qcow2')
    stop()
    status = start('upgrade-dockerd-delayed', reuse=True, delay=ARGS.containerd_delay, dockerd_delay=ARGS.dockerd_delay)
    check('New supervisor actual ARM engine and overlay2', status['architecture'] == 'aarch64' and status['engine_version'] == '29.5.2' and status['storage_driver'] == 'overlay2')
    check('Containerd delay accepted only after real RPC', status['startup']['test_delay_seconds'] == ARGS.containerd_delay and status['startup']['elapsed_seconds'] >= ARGS.containerd_delay and status['startup']['containerd_version']['version'] == 'v2.2.0')
    check('Dockerd delay beyond old 120s cap reaches actual ping/info', status['startup']['test_dockerd_delay_seconds'] == ARGS.dockerd_delay
          and status['startup']['engine_version'] == '29.5.2' and status['startup']['elapsed_seconds'] >= ARGS.dockerd_delay
          and any(row.get('phase') == 'docker' and row.get('phase_budget_seconds') == 360 for row in phases[-1]['observations']))
    check('Migration reuses original overlay inode and backing bytes', (OUT / 'user.qcow2').stat().st_ino == inode and sha(OUT / 'base.qcow2') == base_sha)
    check(ARGS.baseline_version + ' project remains after guest code update', request('read', task=legacy, path='keep.txt')['content'] == PRESERVED)
    # Restart of Docker restores the existing container object, but its live
    # process was deliberately stopped by shutdown; start is a normal task run.
    check(ARGS.baseline_version + ' container object and its logs survive migration', 'LEGACY_CONTAINER_MARKER' in request('logs', task=legacy)['output'])
    check(ARGS.baseline_version + ' built image and container run after migration', job(legacy, 'run', memory_mb=128)['state'] == 'succeeded')
    check('Migrated container exec sees persisted workspace', PRESERVED in job(legacy, 'exec', command='cat /workspace/keep.txt')['output'])
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
    retained = REPORT_DIR / 'arm-retained-build.txt'
    retained.write_bytes(exported)
    check('Actual build output exported', exported.strip() == b'ARM_REAL_BUILD_OK')
    pending = request('exec', task=task, command='sleep 60; echo SHOULD_NOT_FINISH')
    time.sleep(2)
    request('cancel', task=task)
    check('Cancellation stops running operation', request('logs', task=task, job=pending['id'])['state'] == 'cancelled')
    check('Cleanup targets only selected project', request('cleanup', task=task)['cleaned'] == task and request('read', task=legacy, path='keep.txt')['content'] == PRESERVED)
    stop()
    previous_boot = status['startup']['boot_id']
    status = start('restart-persistent', reuse=True, quota=.5)
    check('Restart keeps migrated disk inode and project', (OUT / 'user.qcow2').stat().st_ino == inode and request('read', task=legacy, path='keep.txt')['content'] == PRESERVED)
    diagnostic, unused = save_diagnostics('restart-persistent-retention')
    prior = request('diagnostics', boot_id=previous_boot, max_bytes=262144, tail_bytes=1024)
    check('Prior boot logs remain readable under a distinct boot UUID', status['startup']['boot_id'] != previous_boot
          and previous_boot in diagnostic['available_boots'] and prior['historical_snapshot']
          and bool(prior['logs'].get('docker.log', {}).get('tail')))
    check('Host export survives cleanup and VM restart', retained.read_bytes() == exported)
    check('Migrated image reusable after second reboot', job(legacy, 'run', memory_mb=128)['state'] == 'succeeded')
    request('cleanup', task=legacy)
    stop()
    status = start('cold-two-vcpu', delay=ARGS.containerd_delay, quota=.5, vcpus=ARGS.vcpus)
    check('Fresh disk ready after actual base image import and sync', status['ready'] and status['startup']['stage'] == 'ready' and status['startup']['test_delay_seconds'] == ARGS.containerd_delay)
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
    report['baseline_initramfs'] = {'path': str(ARGS.baseline_initramfs.resolve()), 'version': ARGS.baseline_version, 'sha256': BASELINE_SHA}
    for log in REPORT_DIR.glob('arm-*.log'):
        match = re.search(r'QEMU emulator version ([^\r\n]+)', log.read_text(errors='replace'))
        if match:
            versions.append({'log': relative(log), 'qemu_version': match[1]})
    report['qemu_versions'] = versions
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, indent=2) + '\n')
raise SystemExit(report['failures'])
