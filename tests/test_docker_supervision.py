"""Guest supervisor units with real Unix sockets/processes; not a Docker VM test."""
import json
import errno
import os
import socket
import struct
import sys
import tempfile
import threading
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'docker/guest'))
import diagnostics
import engine


class SupervisorTests(unittest.TestCase):
    def setUp(self):
        self.actual_emit = engine.emit
        self.console = patch.object(engine, 'emit')
        self.console.start()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.identity = str(uuid.uuid4())
        self.journal = diagnostics.Journal(self.root / 'logs', self.identity, self.root / 'state.json')
        self.supervisor = engine.Supervisor(self.journal, self.root, self.root, self.root)

    def tearDown(self):
        self.supervisor.cleanup()
        self.console.stop()
        self.temp.cleanup()

    def test_phase_global_deadline_and_cancellation(self):
        clock = [10.0]
        cancel = self.root / 'cancel'
        budget = engine.PhaseBudget('docker', 360, 15, cancel, clock=lambda: clock[0])
        self.assertEqual(5, budget.timeout(8))
        clock[0] = 15
        with self.assertRaises(TimeoutError):
            budget.check()
        clock[0] = 11
        cancel.touch()
        with self.assertRaises(engine.Cancelled):
            budget.timeout(8)

    def test_containerd_retry_uses_same_budget_and_only_exited_process(self):
        clock = [time.monotonic()]
        attempts = []
        class Exited:
            returncode = 1
            def poll(self):
                return self.returncode
        def launch(*args):
            attempts.append(self.supervisor.phase)
            clock[0] += 100
            return Exited()
        real_path = Path
        real_budget = engine.PhaseBudget
        def path(value):
            if str(value).startswith('/var/'):
                return self.root / str(value).lstrip('/')
            return real_path(value)
        with patch.object(engine, 'Path', side_effect=path), patch.object(engine.time, 'monotonic', side_effect=lambda: clock[0]), \
             patch.object(engine, 'PhaseBudget', side_effect=lambda *args: real_budget(*args, clock=lambda: clock[0])), \
             patch.object(self.supervisor, 'launch', side_effect=launch), patch.object(self.supervisor, 'idle'), \
             patch.object(engine, 'containerd_version') as probe:
            with self.assertRaises(RuntimeError):
                self.supervisor.start_containerd()
        self.assertEqual(2, len(attempts))
        self.assertIs(attempts[0], attempts[1])
        self.assertEqual(180, attempts[0].seconds)
        self.assertEqual(0, attempts[0].remaining())
        probe.assert_not_called()

    def test_observable_progress_and_stall_are_separate_from_readiness(self):
        self.supervisor.begin('docker', 'fixture')
        sample = {'processes': [], 'children': []}
        with patch.object(diagnostics, 'snapshot', return_value=sample):
            self.supervisor.publish(force=True)
            self.supervisor.last_change -= 20
            stalled = self.supervisor.publish(force=True)
            self.assertFalse(stalled['progress']['changed_since_previous'])
            self.assertGreaterEqual(stalled['progress']['no_change_seconds'], 20)
            self.journal.log('docker.log', b'actual child output\n')
            changed = self.supervisor.publish(force=True)
        self.assertTrue(changed['progress']['changed_since_previous'])
        self.assertEqual('docker', changed['stage'])
        self.assertLess(changed['progress']['no_change_seconds'], 1)

    def test_cancel_stops_real_process_and_records_exit(self):
        self.supervisor.begin('sync', 'fixture cancellable process')
        timer = threading.Timer(.1, self.supervisor.cancel.touch)
        timer.start()
        try:
            with self.assertRaises(engine.Cancelled):
                self.supervisor.command('fixture-sync', [sys.executable, '-c', 'import time; print("started",flush=True);time.sleep(30)'], 'base-images.log')
            self.supervisor.stage = 'cancelled'
            self.supervisor.cleanup()
            result = self.supervisor.state['cleanup'][0]
            self.assertIsNotNone(result['exit_code'])
            self.assertNotEqual(0, result['exit_code'])
            self.assertIn('started', (self.journal.directory / 'base-images.log').read_text())
            self.assertFalse((self.root / 'images-ready').exists())
        finally:
            timer.cancel()

    def test_ready_requires_load_tag_and_successful_real_sync(self):
        self.supervisor.info = {'ServerVersion': 'unit-fixture', 'Driver': 'fixture'}
        self.supervisor.version = {'version': 'unit-fixture'}
        (self.root / 'alpine-base-id').write_text('sha256:fixture')
        observed = []
        actual_command = self.supervisor.command
        def command(role, argv, name):
            observed.append((role, self.supervisor.state['stage'], (self.root / 'images-ready').exists()))
            self.assertNotEqual('ready', self.supervisor.state['stage'])
            return actual_command(role, ['/bin/true'], name)
        with patch.object(engine, 'docker_api', return_value=b'{}'), patch.object(self.supervisor, 'command', side_effect=command):
            self.supervisor.finish_images()
        self.assertEqual(['docker-load', 'docker-tag', 'sync'], [item[0] for item in observed])
        self.assertTrue(all(not item[2] for item in observed))
        self.assertEqual('ready', self.supervisor.state['stage'])
        self.assertTrue((self.root / 'images-ready').exists())

    def test_sync_failure_cannot_publish_ready(self):
        self.supervisor.info = {'ServerVersion': 'unit-fixture', 'Driver': 'fixture'}
        actual_command = self.supervisor.command
        with patch.object(engine, 'docker_api', return_value=b'{"Id":"fixture"}'), \
             patch.object(self.supervisor, 'command', side_effect=lambda role, argv, name: actual_command(role, ['/bin/false'], name)):
            with self.assertRaises(RuntimeError):
                self.supervisor.finish_images()
        self.assertEqual('sync', self.supervisor.state['stage'])
        self.assertFalse((self.root / 'images-ready').exists())

    def test_enospc_and_io_error_cannot_skip_real_child_cleanup(self):
        for code in (errno.ENOSPC, errno.EIO):
            with self.subTest(errno=code):
                self.supervisor.begin('docker', 'fault injection')
                process = self.supervisor.launch('dockerd', [sys.executable, '-c', 'import time;time.sleep(30)'], 'docker.log')
                # Keep a stale ready file, as if its subsequent write failed.
                self.journal.publish({**self.supervisor.state, 'stage': 'ready'}, durable=True)
                with patch.object(self.journal, 'publish', side_effect=OSError(code, os.strerror(code))) as broken, \
                     patch.object(self.supervisor, 'start_containerd', side_effect=RuntimeError('injected process failure')):
                    self.assertEqual(1, self.supervisor.run())
                self.assertGreaterEqual(broken.call_count, 2, 'error and cleanup publication were both attempted')
                self.assertIsNotNone(process.poll(), 'running child must be terminated despite both failed writes')
                self.assertNotEqual(0, process.returncode)

    def test_managed_process_identity_is_not_overwritten_by_current_proc(self):
        self.supervisor.begin('docker', 'PID reuse identity fixture')
        process = self.supervisor.launch('fixture', [sys.executable, '-c', 'import time;time.sleep(30)'], 'docker.log')
        original = self.supervisor.processes[-1]['start_ticks'] + 1
        self.supervisor.processes[-1]['start_ticks'] = original
        managed = self.supervisor.managed()[-1]
        self.assertEqual(original, managed['start_ticks'])
        self.assertTrue(managed['pid_reused'])
        self.assertTrue(managed['unavailable'])
        self.assertNotIn('cpu_ticks', managed)

    def test_console_receives_new_child_output_after_file_compaction(self):
        self.supervisor.begin('docker', 'bounded log stream fixture')
        stream = self.root / 'console.bin'
        with stream.open('wb') as output, patch.object(engine, 'emit', side_effect=output.write):
            process = self.supervisor.launch('fixture', [sys.executable, '-c',
                'import sys;sys.stdout.buffer.write(b"A"*600000+b"\\nLATEST_CONSOLE_MARKER\\n");sys.stdout.flush()'], 'docker.log')
            process.wait(timeout=10)
            self.supervisor.processes[-1]['collector'].join(timeout=10)
        self.assertLessEqual((self.journal.directory / 'docker.log').stat().st_size, diagnostics.LOG_LIMIT)
        self.assertTrue((self.journal.directory / 'docker.log').read_bytes().endswith(b'LATEST_CONSOLE_MARKER\n'))
        self.assertGreater(stream.stat().st_size, diagnostics.LOG_LIMIT)
        self.assertIn(b'LATEST_CONSOLE_MARKER', stream.read_bytes())

    def test_child_pipe_drains_to_console_even_if_persistent_log_fails(self):
        self.supervisor.begin('docker', 'broken persistent log fixture')
        seen = []
        with patch.object(self.journal, 'log', side_effect=OSError(errno.ENOSPC, 'disk full')), \
             patch.object(engine, 'emit', side_effect=seen.append):
            process = self.supervisor.launch('fixture', [sys.executable, '-c',
                'import sys;sys.stdout.buffer.write(b"A"*600000+b"\\nPIPE_DRAINED\\n");sys.stdout.flush()'], 'docker.log')
            process.wait(timeout=10)
            self.supervisor.processes[-1]['collector'].join(timeout=10)
        self.assertEqual(0, process.returncode)
        self.assertIn(b'PIPE_DRAINED', b''.join(seen))
        self.assertEqual('OSError', self.supervisor.log_errors['fixture']['error_type'])

    def test_full_real_console_pipe_cannot_block_supervisor(self):
        read, write = os.pipe()
        os.set_blocking(write, False)
        try:
            began = time.monotonic()
            with patch.object(engine, 'CONSOLE_FD', write):
                self.actual_emit(b'A' * 1000000)
            self.assertLess(time.monotonic() - began, .5)
            self.assertTrue(os.read(read, 1024))
        finally:
            os.close(read)
            os.close(write)


class AbsoluteProbeTests(unittest.TestCase):
    def dribble(self, data, callback):
        with tempfile.TemporaryDirectory() as temporary:
            path = str(Path(temporary) / 'socket')
            listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            listener.bind(path)
            listener.listen(1)
            def server():
                try:
                    connection, unused = listener.accept()
                    with connection:
                        connection.recv(4096)
                        for item in data:
                            connection.sendall(bytes([item]))
                            time.sleep(.025)
                except (OSError, BrokenPipeError):
                    pass
                finally:
                    listener.close()
            thread = threading.Thread(target=server, daemon=True)
            thread.start()
            began = time.monotonic()
            try:
                callback(path, began + .18)
            finally:
                thread.join(timeout=1)
            self.assertLess(time.monotonic() - began, .7, 'continuous partial input cannot renew the absolute deadline')

    def test_real_http_header_dribble_has_absolute_deadline(self):
        def probe(path, deadline):
            with patch.object(engine, 'DOCKER_SOCKET', path):
                with self.assertRaises((TimeoutError, OSError)):
                    engine.docker_api('/_ping', deadline=deadline)
        self.dribble(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK', probe)

    def test_real_http2_frame_dribble_has_absolute_deadline(self):
        data = b'\0\0\0\4\0' + struct.pack('!I', 0)
        def probe(path, deadline):
            with patch.object(engine, 'SOCKET', path):
                with self.assertRaises((TimeoutError, OSError)):
                    engine.containerd_version(deadline)
        self.dribble(data, probe)


class DiagnosticTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.identity = str(uuid.uuid4())
        self.journal = diagnostics.Journal(self.root, self.identity, self.root / 'runtime.json')

    def tearDown(self):
        self.temp.cleanup()

    def test_per_boot_retention_preserves_foreign_and_previous_logs(self):
        foreign = self.root / 'legacy-log'
        foreign.mkdir()
        (foreign / 'docker.log').write_text('0.4.3 must remain')
        ids = []
        os.utime(self.journal.directory, (0, 0))
        for index in range(7):
            identity = str(uuid.uuid4())
            journal = diagnostics.Journal(self.root, identity, self.root / 'runtime.json')
            journal.log('docker.log', ('boot ' + str(index)).encode())
            os.utime(journal.directory, (100 + index, 100 + index))
            ids.append(identity)
        directories = {item.name for item in self.root.iterdir() if diagnostics.BOOT_PATTERN.fullmatch(item.name)}
        self.assertEqual(set(ids[-5:]), directories)
        self.assertEqual('boot 2', (self.root / ids[2] / 'docker.log').read_text())
        self.assertEqual('0.4.3 must remain', (foreign / 'docker.log').read_text())

    def test_bounded_compaction_preserves_inode_and_latest_bytes(self):
        self.journal.log('docker.log', b'A' * diagnostics.LOG_LIMIT)
        path = self.journal.directory / 'docker.log'
        inode = path.stat().st_ino
        self.journal.log('docker.log', b'B' * 1024)
        self.assertEqual(inode, path.stat().st_ino)
        self.assertEqual(diagnostics.LOG_LIMIT, path.stat().st_size)
        self.assertTrue(path.read_bytes().endswith(b'B' * 1024))
        self.assertEqual(diagnostics.LOG_LIMIT + 1024, self.journal.counters()['docker.log'])

    def test_traversal_symlink_and_unknown_files_cannot_be_exported(self):
        with self.assertRaises(ValueError):
            diagnostics.diagnostics({'boot_id': '../private'}, root=self.root)
        with self.assertRaises(ValueError):
            self.journal.log('../../private', b'data')
        private = self.root / 'private'
        private.write_text('SECRET_FILE')
        (self.journal.directory / 'docker.log').symlink_to(private)
        with patch.object(diagnostics, 'boot_id', return_value=self.identity):
            with self.assertRaises(OSError):
                diagnostics.diagnostics({}, root=self.root)
        self.assertEqual('SECRET_FILE', private.read_text())

    def test_unicode_exact_encoded_bound_and_token_redaction_at_error(self):
        token = 'sensitive-vm-token'
        self.journal.publish({'stage': 'error', 'detail': token, 'progress': {}}, durable=True)
        for name in diagnostics.LOG_NAMES:
            self.journal.log(name, (token + '😀' * 50000).encode())
        with patch.object(diagnostics, 'boot_id', return_value=self.identity):
            response = diagnostics.diagnostics({'tail_bytes': 65536, 'max_bytes': 65536}, token=token, root=self.root)
        encoded = json.dumps(response).encode()
        self.assertLessEqual(len(encoded), 65536)
        self.assertNotIn(token.encode(), encoded)
        self.assertEqual('error', response['startup']['stage'])
        self.assertEqual(self.identity, response['boot_id'])
        self.assertIn('snapshot', response)
        self.assertTrue(any(log['truncated'] for log in response['logs'].values()))


if __name__ == '__main__':
    unittest.main()
