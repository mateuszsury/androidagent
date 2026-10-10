#!/usr/bin/env python3
"""Actual ARM Linux engine integration. Requires start-arm-guest-test.sh.

This does not assert that ARM Android JNI loading or a physical phone works.
"""
import json
import sys
import time
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
token = (root / 'build-runtime/arm-guest-test/token').read_bytes()[:64].decode()
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
results = []
task = None
clock_mode = 'host' if '--host-clock' in sys.argv else 'instruction_count'


def request(op, **args):
    r = urllib.request.Request('http://127.0.0.1:19780/request',
        data=json.dumps({'op': op, **args}).encode(), headers={
            'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    with http.open(r, timeout=60) as response:
        return json.load(response)


def check(name, condition):
    if not condition:
        raise AssertionError(name)
    results.append({'name': name, 'pass': True})
    print('PASS ' + name, flush=True)


def job(op, **args):
    info = request(op, task=task, **args)
    until = time.monotonic() + 240
    while time.monotonic() < until:
        info = request('logs', task=task, job=info['id'])
        if info['state'] not in ('queued', 'running'):
            if info['state'] != 'succeeded':
                raise AssertionError(info)
            return info
        time.sleep(1)
    raise AssertionError('Timed out: ' + op)


try:
    until = time.monotonic() + 240
    while time.monotonic() < until:
        try:
            status = request('status')
            if status['ready']:
                break
        except (OSError, ValueError):
            pass
        time.sleep(2)
    else:
        raise AssertionError('ARM engine not ready')
    check('Actual aarch64 Linux guest and Docker 29', status['architecture'] == 'aarch64' and status['engine_version'].startswith('29.'))
    check('Actual overlay2 storage driver', status['storage_driver'] == 'overlay2')
    task = request('create', name='ARM guest integration')['id']
    demo = root / 'app/src/main/assets/docker/demo'
    request('write', task=task, path='index.html', content='ARM_REAL_WEB_APP')
    request('write', task=task, path='serve.sh', content=(demo / 'serve.sh').read_text())
    dockerfile = (demo / 'Dockerfile').read_text().replace('USER 1000:1000', 'RUN echo ARM_REAL_BUILD_OK > /built.txt\nUSER 1000:1000')
    request('write', task=task, path='Dockerfile', content=dockerfile)
    check('Real ARM Dockerfile build', job('build')['state'] == 'succeeded')
    check('Real ARM container', job('run', memory_mb=128, port=8080)['state'] == 'succeeded')
    until = time.monotonic() + 30
    while True:
        try:
            with http.open('http://127.0.0.1:19781', timeout=3) as response:
                check('Actual forwarded non-root ARM web application', response.read() == b'ARM_REAL_WEB_APP')
            break
        except OSError:
            if time.monotonic() > until:
                raise
            time.sleep(.5)
    check('ARM non-root execution, image WORKDIR, memory cap and build artifact', 'aarch64' in job('exec', command='uname -m; test "$(id -u)" = 1000 && test "$(readlink /proc/1/cwd)" = /app && test "$(cat /sys/fs/cgroup/memory.max)" = 134217728 && mkdir -p out && cp /built.txt out/built.txt')['output'])
    export = urllib.request.Request(f'http://127.0.0.1:19780/artifact?task={task}&path=out/built.txt', headers={'Authorization':'Bearer '+token})
    with http.open(export, timeout=30) as response:
        check('Actual exported ARM build output', response.read().strip() == b'ARM_REAL_BUILD_OK')
    check('Scoped ARM cleanup', request('cleanup', task=task)['cleaned'] == task)
    check('ARM shutdown', request('shutdown')['stopping'])
    report = {'architecture':'aarch64', 'environment':'Linux guest under native host QEMU; not Android ARM', 'tests':results,'failures':0}
except Exception as error:
    results.append({'name':'runtime', 'pass':False, 'detail':str(error)})
    report = {'tests':results,'failures':1}
finally:
    report['clock_mode'] = clock_mode
    report['icount_shift'] = 3 if clock_mode == 'instruction_count' else None
    report['guest_manifest'] = json.loads((root / 'app/src/main/assets/docker/arm64-v8a/manifest.json').read_text())
    path = root / 'artifacts/docker' / ('arm-guest-host-clock-results.json' if clock_mode == 'host' else 'arm-guest-results.json')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2)+'\n')
raise SystemExit(report['failures'])
