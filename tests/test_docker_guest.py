"""Real filesystem and subprocess checks for the guest's task boundary."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'docker/guest'))


class GuestBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        root = Path(cls.temp.name)
        token = root / 'token'
        token.write_bytes(b'0' * 64)
        os.environ['AA_TASK_ROOT'] = str(root / 'tasks')
        os.environ['AA_TOKEN_DEVICE'] = str(token)
        spec = importlib.util.spec_from_file_location('docker_guest', Path(__file__).resolve().parents[1] / 'docker/guest/agent.py')
        cls.agent = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.agent)
        cls.root = root

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()
        os.environ.pop('AA_TASK_ROOT', None)
        os.environ.pop('AA_TOKEN_DEVICE', None)

    def setUp(self):
        self.task = self.agent.dispatch({'op': 'create', 'name': 'Źródła'})['id']
        self.path = self.agent.task_dir(self.task)

    def test_traversal_cannot_overwrite_neighbor(self):
        neighbor = self.root / 'private'
        neighbor.write_text('keep')
        for path in ['../../private', '/etc/passwd', '']:
            with self.assertRaises(ValueError):
                self.agent.dispatch({'op': 'write', 'task': self.task, 'path': path, 'content': 'changed'})
        self.assertEqual('keep', neighbor.read_text())

    def test_symlink_cannot_read_or_replace_other_files(self):
        private = self.root / 'private2'
        private.write_text('account remains outside workspace')
        (self.path / 'work/link').symlink_to(private)
        for write in [False, True]:
            with self.assertRaises(ValueError):
                self.agent.open_work(self.path, 'link', write)
        self.assertEqual('account remains outside workspace', private.read_text())

    def test_parent_symlink_cannot_escape(self):
        (self.path / 'work/out').symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.agent.dispatch({'op': 'write', 'task': self.task, 'path': 'out/private', 'content': 'changed'})

    def test_real_unicode_file_round_trip(self):
        text = 'Zażółć gęślą jaźń — działają pliki'
        self.agent.dispatch({'op': 'write', 'task': self.task, 'path': 'src/hello.txt', 'content': text})
        with self.agent.open_work(self.path, 'src/hello.txt') as file:
            self.assertEqual(text, file.read().decode())
        self.assertEqual(text, self.agent.dispatch({'op': 'read', 'task': self.task, 'path': 'src/hello.txt'})['content'])

    def test_fifo_and_directory_are_not_artifacts(self):
        os.mkfifo(self.path / 'work/fifo')
        with self.assertRaises(ValueError):
            self.agent.open_work(self.path, 'fifo')
        (self.path / 'work/dir').mkdir()
        with self.assertRaises((ValueError, IsADirectoryError)):
            self.agent.open_work(self.path, 'dir')

    def test_real_build_archive_keeps_sources_and_rejects_external_links(self):
        import tarfile
        self.agent.dispatch({'op': 'write', 'task': self.task, 'path': 'src/hello.txt', 'content': 'Zażółć'})
        archive = self.path / 'build.tar'
        self.agent.snapshot_context(self.path, archive)
        with tarfile.open(archive) as source:
            self.assertEqual('Zażółć', source.extractfile('src/hello.txt').read().decode())
        outside = self.root / 'outside-ignore'
        outside.write_text('private data')
        (self.path / 'work/.dockerignore').symlink_to(outside)
        with self.assertRaises(ValueError):
            self.agent.snapshot_context(self.path, archive)

    def test_unknown_task_and_job_are_rejected(self):
        with self.assertRaises(ValueError):
            self.agent.task_dir('../account')
        with self.assertRaises(ValueError):
            self.agent.task_dir('f' * 32)
        with self.assertRaises(ValueError):
            self.agent.dispatch({'op': 'logs', 'task': self.task, 'job': 'other'})

    def test_startup_status_does_not_start_go_info_probes(self):
        import json
        from unittest.mock import patch
        for stage in ('containerd', 'docker', 'load', 'sync', 'error', 'cancelled'):
            with patch.object(Path, 'read_text', return_value=json.dumps({'stage': stage})), \
                 patch.object(self.agent, 'engine_info') as info:
                status = self.agent.dispatch({'op': 'status'})
                self.assertFalse(status['ready'])
                self.assertEqual(stage, status['startup']['stage'])
                info.assert_not_called()

    def test_readonly_diagnostics_is_available_without_task_lock_or_ready(self):
        from unittest.mock import patch
        class Locked:
            def __enter__(self):
                raise AssertionError('Diagnostic request must not wait for a task lock')
            def __exit__(self, *args):
                pass
        request = {'op': 'diagnostics', 'max_bytes': 262144, 'tail_bytes': 8192}
        with patch.object(self.agent, 'LOCK', Locked()), \
             patch.object(self.agent.diagnostics, 'diagnostics', return_value={'startup': {'stage': 'error'}}) as call:
            self.assertEqual('error', self.agent.dispatch(request)['startup']['stage'])
            call.assert_called_once_with(request, token=self.agent.TOKEN)

    def test_stale_ready_rejected_after_real_supervisor_process_exit(self):
        import json
        import subprocess
        from unittest.mock import patch
        process = subprocess.Popen(['python3', '-c', 'import time;time.sleep(30)'])
        identity = self.agent.diagnostics.proc_info(process.pid)
        state = {'stage': 'ready', 'supervisor': {'pid': process.pid, 'start_ticks': identity['start_ticks']}}
        process.terminate()
        process.wait(timeout=5)
        real_read = Path.read_text
        def read(path, *args, **kwargs):
            return json.dumps(state) if str(path) == '/run/androidagent-engine.json' else real_read(path, *args, **kwargs)
        with patch.object(Path, 'read_text', read), \
             patch.object(self.agent, 'engine_info') as info:
            status = self.agent.dispatch({'op': 'status'})
        self.assertFalse(status['ready'])
        self.assertEqual('error', status['startup']['stage'])
        self.assertEqual('ready', status['startup']['last_persisted_stage'])
        self.assertEqual('process_unavailable', status['startup']['supervisor_liveness']['reason'])
        info.assert_not_called()

    def test_stale_ready_rejected_for_pid_identity_reuse_and_missing_identity(self):
        import json
        from unittest.mock import patch
        current = self.agent.diagnostics.proc_info(os.getpid())
        for identity in ({}, {'pid': os.getpid(), 'start_ticks': current['start_ticks'] + 1}):
            real_read = Path.read_text
            def read(path, *args, **kwargs):
                return json.dumps({'stage': 'ready', 'supervisor': identity}) if str(path) == '/run/androidagent-engine.json' else real_read(path, *args, **kwargs)
            with patch.object(Path, 'read_text', read), \
                 patch.object(self.agent, 'engine_info') as info:
                status = self.agent.dispatch({'op': 'status'})
            self.assertFalse(status['ready'])
            self.assertEqual('error', status['startup']['stage'])
            info.assert_not_called()

    def test_live_matching_supervisor_allows_actual_engine_ready_check(self):
        import json
        from unittest.mock import patch
        current = self.agent.diagnostics.proc_info(os.getpid())
        state = {'stage': 'ready', 'supervisor': {'pid': os.getpid(), 'start_ticks': current['start_ticks']}}
        real_read, real_exists = Path.read_text, Path.exists
        def read(path, *args, **kwargs):
            return json.dumps(state) if str(path) == '/run/androidagent-engine.json' else real_read(path, *args, **kwargs)
        def exists(path):
            return True if str(path) == '/var/lib/androidagent/images-ready' else real_exists(path)
        with patch.object(Path, 'read_text', read), patch.object(Path, 'exists', exists), \
             patch.object(self.agent, 'engine_info', return_value={'ServerVersion': 'unit-fixture'}) as info:
            status = self.agent.dispatch({'op': 'status'})
        self.assertTrue(status['ready'])
        self.assertEqual('verified', status['startup']['supervisor_liveness']['reason'])
        info.assert_called_once_with()

    def test_text_upload_limit_is_enforced(self):
        with self.assertRaises(ValueError):
            self.agent.dispatch({'op': 'write', 'task': self.task, 'path': 'large', 'content': 'x' * (256 * 1024 + 1)})
        self.assertFalse((self.path / 'work/large').exists())

    def test_job_output_is_bounded_with_real_process(self):
        import time
        job = self.agent.start_job(self.task, 'test', ['python3', '-c', 'print("x"*100000)'])
        until = time.monotonic() + 10
        while self.agent.JOBS[job['id']]['state'] in ('queued', 'running') and time.monotonic() < until:
            time.sleep(.02)
        result = self.agent.JOBS[job['id']]
        self.assertEqual('succeeded', result['state'])
        self.assertLessEqual(len(result['output']), 65536)


    def test_serial_grant_is_private_and_generation_scoped(self):
        from unittest.mock import patch
        url = 'androidagent://10.0.2.2:18782/' + 'a' * 72
        self.agent.dispatch({'op': 'serial_attach', 'task': self.task, 'session': 'first', 'url': url})
        public = self.agent.task_info(self.path)
        self.assertEqual(['first'], public['serial_sessions'])
        self.assertNotIn(url, str(public))
        with patch.object(self.agent, 'cancel') as cancel:
            self.agent.dispatch({'op': 'usb_detach', 'task': self.task, 'session': 'stale'})
            cancel.assert_not_called()
            self.assertIn('first', self.agent.SERIAL[self.task])
            self.agent.dispatch({'op': 'usb_detach', 'task': self.task, 'session': 'first'})
            cancel.assert_called_once_with(self.task)
        self.assertFalse(self.agent.SERIAL[self.task])

    def test_serial_url_cannot_point_to_another_host_or_credential(self):
        for url in ['http://127.0.0.1/token', 'androidagent://10.0.2.2:8766/root-token',
                    'androidagent://remote:18782/' + 'x' * 72]:
            with self.assertRaises(ValueError):
                self.agent.dispatch({'op': 'serial_attach', 'task': self.task, 'session': 'first', 'url': url})

    def test_usb_grant_only_maps_selected_char_device(self):
        from unittest.mock import patch
        device = {'slot': 1, 'vid': 1575, 'pid': 1, 'node': '/dev/bus/usb/001/002'}
        with patch.object(self.agent, 'usb_device', return_value=device.copy()):
            grant = self.agent.dispatch({'op': 'usb_attach', 'task': self.task, 'session': 'first', **{k: device[k] for k in ('slot', 'vid', 'pid')}})
            self.assertEqual(device['node'], grant['usb'][0]['node'])
            other = self.agent.dispatch({'op': 'create'})['id']
            with self.assertRaises(ValueError):
                self.agent.dispatch({'op': 'usb_attach', 'task': other, 'session': 'second', 'slot': 1, 'vid': 1575, 'pid': 1})
            with patch.object(self.agent, 'start_job', return_value={}) as start:
                self.agent.dispatch({'op': 'run', 'task': self.task, 'image': 'alpine'})
                argv = start.call_args.args[2]
                self.assertIn('/dev/bus/usb/001/002:/dev/bus/usb/001/002:rwm', str(argv))
                self.assertNotIn('--privileged', str(argv))
                self.assertNotIn('src=/dev', str(argv))
        with patch.object(self.agent, 'usb_device', return_value=None):
            with self.assertRaises(ValueError):
                self.agent.dispatch({'op': 'run', 'task': self.task, 'image': 'alpine'})


if __name__ == '__main__':
    unittest.main()
