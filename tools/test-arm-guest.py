#!/usr/bin/env python3
"""Actual ARM Linux engine integration. Requires start-arm-guest-test.sh.

This does not assert that ARM Android JNI loading or a physical phone works.
"""
import json
import sys
import socket
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


def qmp(command, **args):
    with socket.create_connection(('127.0.0.1', 19782), timeout=10) as sock:
        stream = sock.makefile('rwb')
        json.loads(stream.readline())
        for execute in ['qmp_capabilities', command]:
            stream.write((json.dumps({'execute': execute, 'arguments': args if execute == command else {}, 'id': execute}) + '\n').encode())
            stream.flush()
            while True:
                reply = json.loads(stream.readline())
                if reply.get('id') == execute:
                    if 'error' in reply:
                        raise AssertionError(reply['error'])
                    break
        return reply


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
    qmp('device_add', driver='usb-kbd', id='usb-fixture', bus='aausb.0', port='1')
    grant = request('usb_attach', task=task, session='fixture-arm', slot=1, vid=0x0627, pid=1)
    node = grant['usb'][0]['node']
    check('ARM QMP virtual USB fixture enumerates in actual Linux guest', node.startswith('/dev/bus/usb/'))
    request('serial_attach', task=task, session='arm-uart-fixture', url='androidagent://10.0.2.2:18782/' + 'a' * 72)
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
    check('ARM non-root container can read UART library and receives scoped URL', 'ARM_UART_LIBRARY_OK' in job('exec', command='test -r /opt/androidagent/python/sitecustomize.py && test -r /opt/androidagent/python/androidagent_serial/protocol_androidagent.py && test -n "${ANDROIDAGENT_SERIAL_URL:-}" && echo ARM_UART_LIBRARY_OK')['output'])
    check('ARM container opens only selected USB bus node (virtual HID fixture)', 'ARM_SCOPED_USB_OK' in job('exec', command='test -c ' + node + ' && exec 3<>' + node + ' && exec 3>&- && test ! -e /dev/vdb && echo ARM_SCOPED_USB_OK')['output'])
    export = urllib.request.Request(f'http://127.0.0.1:19780/artifact?task={task}&path=out/built.txt', headers={'Authorization':'Bearer '+token})
    with http.open(export, timeout=30) as response:
        check('Actual exported ARM build output', response.read().strip() == b'ARM_REAL_BUILD_OK')
    check('ARM USB revoke removes the selected grant', request('usb_detach', task=task, session='fixture-arm')['usb'] == [])
    qmp('device_del', id='usb-fixture')
    check('Scoped ARM cleanup', request('cleanup', task=task)['cleaned'] == task)
    check('ARM shutdown', request('shutdown')['stopping'])
    report = {'architecture':'aarch64', 'environment':'Linux guest under native host QEMU; not Android ARM', 'tests':results,'failures':0}
except Exception as error:
    results.append({'name':'runtime', 'pass':False, 'detail':str(error)})
    report = {'tests':results,'failures':1}
finally:
    report['version'] = '0.4.0'
    report['clock_mode'] = clock_mode
    report['icount_shift'] = 3 if clock_mode == 'instruction_count' else None
    report['guest_manifest'] = json.loads((root / 'app/src/main/assets/docker/arm64-v8a/manifest.json').read_text())
    path = root / 'artifacts/docker' / ('arm-guest-host-clock-results.json' if clock_mode == 'host' else 'arm-guest-results.json')
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2)+'\n')
raise SystemExit(report['failures'])
