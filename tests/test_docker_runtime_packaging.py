"""Validate migration packaging and readiness when a previous marker exists."""
import importlib.util
import json
from pathlib import Path
import stat
import socket
import struct
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('docker_runtime_package', ROOT / 'tools/prepare-docker-runtime.py')
PACKAGE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PACKAGE)
engine_spec = importlib.util.spec_from_file_location('docker_engine_startup', ROOT / 'docker/guest/engine.py')
ENGINE = importlib.util.module_from_spec(engine_spec)
engine_spec.loader.exec_module(ENGINE)


class RuntimePackagingTests(unittest.TestCase):
    def test_cpio_preserves_symlink_permissions_and_file_bytes(self):
        entries = [('root', [1, stat.S_IFDIR | 0o755, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0], b''),
                   ('root/link', [2, stat.S_IFLNK | 0o777, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0], b'../bin/sh'),
                   ('root/init', [3, stat.S_IFREG | 0o755, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0], b'#!/bin/sh\nexit 0\n')]
        restored = PACKAGE.unpack(PACKAGE.pack(entries))
        self.assertEqual([value[0] for value in entries], [value[0] for value in restored])
        self.assertEqual([value[1][1] for value in entries], [value[1][1] for value in restored])
        self.assertEqual([value[2] for value in entries], [value[2] for value in restored])

    def test_truncated_archive_is_rejected(self):
        with self.assertRaises(ValueError):
            PACKAGE.unpack(b'070701' + b'0' * 104)

    def test_runtime_payload_cannot_replace_persistent_data(self):
        files, manifest = PACKAGE.payload()
        self.assertEqual(2, manifest['revision'])
        self.assertTrue({'init.sh', 'engine.py', 'agent.py'} <= files.keys())
        self.assertTrue(all(name in {'init.sh', 'agent.py', 'engine.py', 'runtime.json', 'install.sh'} or name.startswith('python/') for name in files))
        installer = files['install.sh'].decode()
        self.assertNotIn('/var/lib/docker', installer)
        self.assertNotIn('/var/lib/androidagent', installer)
        self.assertNotIn('base.qcow2', installer)
        self.assertIn('.aa-new', installer)

    def test_ready_marker_does_not_override_supervisor_error(self):
        from tests.test_docker_guest import GuestBoundaryTests
        GuestBoundaryTests.setUpClass()
        try:
            agent = GuestBoundaryTests.agent
            with patch.object(agent, 'engine_info', return_value={'ServerVersion': '29.5.2'}), patch.object(agent, 'Path') as path:
                path.return_value.read_text.return_value = json.dumps({'stage': 'error', 'detail': 'containerd exited'})
                path.return_value.exists.return_value = True
                result = agent.dispatch({'op': 'status'})
                self.assertFalse(result['ready'])
                self.assertEqual('containerd exited', result['startup']['detail'])
        finally:
            GuestBoundaryTests.tearDownClass()

    def rpc_fixture(self, message):
        """Actual Unix socket frames; a listening socket alone is insufficient."""
        def frame(kind, flags, stream, body=b''):
            return len(body).to_bytes(3, 'big') + bytes([kind, flags]) + struct.pack('!I', stream) + body
        with tempfile.TemporaryDirectory() as temporary:
            path = str(Path(temporary) / 'rpc.sock')
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
                server.bind(path)
                server.listen(1)
                def serve():
                    with server.accept()[0] as peer:
                        request = bytearray()
                        while b'application/grpc' not in request:
                            request.extend(peer.recv(4096))
                        self.assertIn(b'/containerd.services.version.v1.Version/Version', request)
                        body = b'\0' + len(message).to_bytes(4, 'big') + message
                        peer.sendall(frame(4, 0, 0) + frame(1, 4, 1, b'\x88') + frame(0, 0, 1, body) + frame(1, 5, 1))
                        # Keep the peer live until the probe acknowledges SETTINGS.
                        peer.recv(4096)
                worker = threading.Thread(target=serve)
                worker.start()
                try:
                    with patch.object(ENGINE, 'SOCKET', path):
                        return ENGINE.containerd_version()
                finally:
                    worker.join(timeout=3)

    def test_version_probe_parses_actual_unix_http2_rpc_bytes(self):
        result = self.rpc_fixture(b'\x0a\x06v2.2.0\x12\x03abc')
        self.assertEqual({'version': 'v2.2.0', 'revision': 'abc'}, result)

    def test_listening_rpc_socket_without_version_cannot_be_ready(self):
        with self.assertRaisesRegex(ValueError, 'server version'):
            self.rpc_fixture(b'')

    def test_real_containerd_process_exit_is_a_startup_error(self):
        with subprocess.Popen(['/bin/sh', '-c', 'exit 17']) as process:
            process.wait(timeout=3)
            with self.assertRaisesRegex(RuntimeError, 'containerd exited with code 17'):
                ENGINE.alive(process, 'containerd')


if __name__ == '__main__':
    unittest.main()
