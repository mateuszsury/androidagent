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
import subprocess
import time
from pathlib import Path

STATE = Path('/run/androidagent-engine.json')
STARTED = time.monotonic()
SOCKET = '/var/run/docker/containerd/containerd.sock'
PROCESSES = []


def publish(stage, detail, **values):
    info = {'stage': stage, 'detail': detail, 'elapsed_seconds': round(time.monotonic() - STARTED, 3),
            'runtime_revision': 2, **values}
    temporary = STATE.with_suffix('.part')
    temporary.write_text(json.dumps(info))
    temporary.replace(STATE)
    print('ANDROIDAGENT_ENGINE ' + json.dumps(info), flush=True)


def spawn(argv, log):
    stream = open(log, 'ab', buffering=0)
    try:
        process = subprocess.Popen(argv, stdout=stream, stderr=subprocess.STDOUT)
        PROCESSES.append(process)
        return process
    finally:
        stream.close()


def alive(process, name):
    if process.poll() is not None:
        raise RuntimeError(f'{name} exited with code {process.returncode}; inspect its startup log')


def containerd_version():
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
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(8)
        sock.connect(SOCKET)
        sock.sendall(b'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n' + frame(4, 0, 0) + frame(1, 4, 1, headers) + frame(0, 1, 1, b'\0' * 5))
        data, ended = bytearray(), False
        until = time.monotonic() + 8
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


def wait_containerd(process, deadline):
    # The socket alone does not prove that the Version gRPC service is ready.
    # Avoid concurrent Go CLI probes while TCG is still loading containerd.
    while time.monotonic() < deadline:
        alive(process, 'containerd')
        if Path(SOCKET).exists():
            try:
                return containerd_version()
            except (OSError, ValueError):
                pass
        time.sleep(2)
    raise TimeoutError('containerd Version RPC did not become ready within 150 seconds')


def docker_api(path, timeout=5):
    connection = http.client.HTTPConnection('localhost', timeout=timeout)
    connection.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.sock.settimeout(timeout)
    try:
        connection.sock.connect('/var/run/docker.sock')
        connection.request('GET', path)
        response = connection.getresponse()
        body = response.read(1024 * 1024)
        if response.status != 200:
            raise RuntimeError(f'Docker {path} returned {response.status}')
        return body
    finally:
        connection.close()


def start():
    Path('/var/run/docker/containerd/daemon').mkdir(parents=True, exist_ok=True)
    Path('/var/lib/docker/containerd/daemon').mkdir(parents=True, exist_ok=True)
    config = Path('/run/androidagent-containerd.toml')
    # These are the same persistent directories/namespaces Docker uses when it
    # owns the child process. CRI/CNI are irrelevant to Docker's moby namespace.
    config.write_text('''version = 3
root = "/var/lib/docker/containerd/daemon"
state = "/var/run/docker/containerd/daemon"
disabled_plugins = ["io.containerd.cri.v1.images", "io.containerd.cri.v1.runtime", "io.containerd.grpc.v1.cri"]
[grpc]
  address = "/var/run/docker/containerd/containerd.sock"
[debug]
  level = "info"
''')
    delay = 0
    # Dedicated integration test fault injection, not a phone UI preference.
    for item in Path('/proc/cmdline').read_text().split():
        if item.startswith('aa_containerd_test_delay='):
            delay = max(0, min(60, int(item.split('=', 1)[1])))
    last_error = None
    for attempt in (1, 2):
        publish('containerd', 'Uruchamianie containerd; sprawdzanie rzeczywistego RPC', attempt=attempt)
        argv = ['containerd', '--config', str(config)]
        if delay:
            argv = ['/bin/sh', '-c', 'sleep "$1"; shift; exec "$@"', 'containerd-test-delay', str(delay), *argv]
        process = spawn(argv, '/var/log/containerd.log')
        try:
            version = wait_containerd(process, time.monotonic() + 150)
            publish('containerd_ready', 'containerd odpowiada na Version RPC', attempt=attempt, version=version)
            break
        except RuntimeError as error:
            # Retry a process which really exited once. Never duplicate a live
            # containerd or remove its persistent data after a timeout.
            last_error = error
            if attempt == 2:
                raise
            time.sleep(2)
    else:
        raise last_error
    publish('docker', 'Uruchamianie Docker Engine z gotowym containerd')
    engine = spawn(['dockerd', '--containerd', SOCKET, '--containerd-namespace', 'moby',
                    '--containerd-plugins-namespace', 'plugins.moby'], '/var/log/docker.log')
    until = time.monotonic() + 120
    while time.monotonic() < until:
        alive(process, 'containerd')
        alive(engine, 'dockerd')
        try:
            if docker_api('/_ping') == b'OK':
                info = json.loads(docker_api('/info'))
                if info.get('ServerVersion'):
                    break
        except (OSError, ValueError, RuntimeError, http.client.HTTPException):
            pass
        time.sleep(2)
    else:
        raise TimeoutError('Docker ping/info did not become ready within 120 seconds')
    publish('images', 'Sprawdzanie i import dołączonego obrazu Alpine', engine_version=info['ServerVersion'])
    # Check the actual image even if an old ready marker exists. An interrupted
    # import or incomplete disk write must never produce a false ready state.
    try:
        image_present = bool(json.loads(docker_api('/images/alpine:3.23/json', timeout=15)).get('Id'))
    except (OSError, ValueError, RuntimeError, http.client.HTTPException):
        image_present = False
    if not image_present:
        with open('/var/log/base-images.log', 'ab', buffering=0) as stream:
            for argv in (['docker', 'load', '-i', '/opt/androidagent/alpine-base.tar'],
                         ['docker', 'tag', Path('/opt/androidagent/alpine-base-id').read_text().strip(), 'alpine:3.23']):
                completed = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT, timeout=150)
                if completed.returncode:
                    raise RuntimeError(f'Base image import failed with code {completed.returncode}')
    os.sync()
    Path('/var/lib/androidagent/images-ready').touch()
    os.sync()
    publish('ready', 'Docker i obraz Alpine są gotowe', engine_version=info['ServerVersion'],
            containerd_version=version, test_delay_seconds=delay)
    while True:
        alive(process, 'containerd')
        alive(engine, 'dockerd')
        time.sleep(3)


if __name__ == '__main__':
    try:
        start()
    except Exception as error:
        publish('error', str(error), error_type=type(error).__name__)
        for process in reversed(PROCESSES):
            if process.poll() is None:
                process.terminate()
        raise SystemExit(1)
