"""Guest preview-port allocation and operation timeouts with real processes.

A fake `docker` CLI on PATH records every call. Its `exec` starts the command in
a separate session, like containerd does, so killing the CLI client alone
leaves the command running exactly as in the VM.
"""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'docker/guest'))

FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, subprocess, sys, time
args = sys.argv[1:]
with open(os.environ['FAKE_DOCKER_LOG'], 'a') as log:
    log.write(json.dumps(args) + '\n')
if args[0] == 'exec':
    env, i = dict(os.environ), 1
    while args[i].startswith('-'):
        if args[i] == '-e':
            key, value = args[i + 1].split('=', 1)
            env[key] = value
        i += 2
    child = subprocess.Popen(args[i + 1:], env=env, start_new_session=True)
    sys.exit(child.wait())
if args[0] == 'ps':
    print(os.environ.get('FAKE_DOCKER_PS', ''))
if args[0] == 'sh-sleep':
    time.sleep(60)
sys.exit(0)
'''


def alive(pid):
    try:
        with open(f'/proc/{pid}/stat') as stat:
            return stat.read().split(') ')[1][0] != 'Z'
    except OSError:
        return False


class GuestNetworkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        root = Path(cls.temp.name)
        token = root / 'token'
        token.write_bytes(b'1' * 64)
        bin_dir = root / 'bin'
        bin_dir.mkdir()
        docker = bin_dir / 'docker'
        docker.write_text(FAKE_DOCKER)
        docker.chmod(0o755)
        cls.log = root / 'docker.log'
        cls.saved_env = {key: os.environ.get(key) for key in ('AA_TASK_ROOT', 'AA_TOKEN_DEVICE', 'PATH', 'FAKE_DOCKER_LOG')}
        os.environ.update({'AA_TASK_ROOT': str(root / 'tasks'), 'AA_TOKEN_DEVICE': str(token),
                           'PATH': str(bin_dir) + os.pathsep + os.environ.get('PATH', ''),
                           'FAKE_DOCKER_LOG': str(cls.log)})
        spec = importlib.util.spec_from_file_location('docker_guest_network', ROOT / 'docker/guest/agent.py')
        cls.agent = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.agent)

    @classmethod
    def tearDownClass(cls):
        for key, value in cls.saved_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        cls.temp.cleanup()

    def setUp(self):
        self.log.write_text('')
        # Every test starts with no published ports.
        for path in self.agent.ROOT.iterdir():
            self.agent.release_preview(path)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def task(self, name='app'):
        return self.agent.dispatch({'op': 'create', 'name': name})['id']

    def run_task(self, task, port, **extra):
        with patch.object(self.agent, 'start_job', return_value={'id': 'job', 'state': 'queued'}) as start:
            result = self.agent.dispatch({'op': 'run', 'task': task, 'image': 'alpine', 'port': port, **extra})
        return result, start.call_args

    def wait(self, job_id, timeout=15):
        until = time.monotonic() + timeout
        while self.agent.JOBS[job_id]['state'] in ('queued', 'running') and time.monotonic() < until:
            time.sleep(.02)
        until = time.monotonic() + timeout
        while 'finished_at' not in self.agent.JOBS[job_id] and time.monotonic() < until:
            time.sleep(.02)
        return self.agent.JOBS[job_id]

    def test_each_task_gets_a_unique_persisted_guest_port(self):
        first, second, third = self.task('a'), self.task('b'), self.task('c')
        result, call = self.run_task(first, 3000)
        self.assertEqual({'guest_port': 8080, 'container_port': 3000}, result['preview'])
        argv = call.args[2]
        self.assertEqual('8080:3000', argv[argv.index('-p') + 1])
        result, call = self.run_task(second, 3000)
        self.assertEqual(8081, result['preview']['guest_port'])
        self.assertEqual('8081:3000', call.args[2][call.args[2].index('-p') + 1])
        self.assertEqual(8082, self.run_task(third, 5173)[0]['preview']['guest_port'])
        # Persisted with the task (survives an agent restart) and in status.
        self.assertEqual({'guest_port': 8081, 'container_port': 3000},
                         json.loads((self.agent.ROOT / second / 'task.json').read_text())['preview'])
        info = self.agent.task_info(self.agent.task_dir(second))
        self.assertEqual(8081, info['preview']['guest_port'])
        # A rerun keeps its port even with a new container port.
        result, call = self.run_task(second, 8000)
        self.assertEqual({'guest_port': 8081, 'container_port': 8000}, result['preview'])
        self.assertEqual('8081:8000', call.args[2][call.args[2].index('-p') + 1])

    def test_only_one_publish_flag_and_no_legacy_fixed_mapping_for_second_task(self):
        self.run_task(self.task(), 80)
        _, call = self.run_task(self.task(), 80)
        argv = call.args[2]
        self.assertEqual(1, argv.count('-p'))
        self.assertNotIn('8080:80', argv)

    def test_released_ports_are_reused_and_reported(self):
        first, second = self.task(), self.task()
        self.assertEqual(8080, self.run_task(first, 80)[0]['preview']['guest_port'])
        self.assertEqual(8081, self.run_task(second, 80)[0]['preview']['guest_port'])
        cancelled = self.agent.dispatch({'op': 'cancel', 'task': first})
        self.assertEqual([8080], cancelled['released_guest_ports'])
        self.assertNotIn('preview', cancelled)
        self.assertEqual([], self.agent.dispatch({'op': 'cancel', 'task': first})['released_guest_ports'])
        third = self.task()
        self.assertEqual(8080, self.run_task(third, 80)[0]['preview']['guest_port'])
        # A run without a port unpublishes the task.
        result, call = self.run_task(second, None)
        self.assertIsNone(result['preview'])
        self.assertEqual([8081], result['released_guest_ports'])
        self.assertNotIn('-p', call.args[2])
        self.run_task(second, 80)
        cleaned = self.agent.dispatch({'op': 'cleanup', 'task': second})
        self.assertEqual([8081], cleaned['released_guest_ports'])

    def test_port_range_exhaustion_and_corrupt_duplicate(self):
        with patch.object(self.agent, 'PREVIEW_GUEST_PORTS', range(8080, 8082)):
            self.run_task(self.task(), 80)
            self.run_task(self.task(), 80)
            with self.assertRaisesRegex(ValueError, 'preview port is in use'):
                self.run_task(self.task(), 80)
        first, second = self.task(), self.task()
        self.run_task(first, 80)
        info = json.loads((self.agent.ROOT / second / 'task.json').read_text())
        info['preview'] = self.agent.read_task(self.agent.ROOT / first)['preview']
        (self.agent.ROOT / second / 'task.json').write_text(json.dumps(info))
        self.assertNotEqual(self.agent.read_task(self.agent.ROOT / first)['preview']['guest_port'],
                            self.run_task(second, 80)[0]['preview']['guest_port'])

    def test_invalid_container_ports_are_rejected_before_allocation(self):
        task = self.task()
        for port in (0, 65536, -1, 'eighty', 80.5, True, [80]):
            with self.assertRaises(ValueError):
                self.run_task(task, port)
        self.assertNotIn('preview', self.agent.read_task(self.agent.ROOT / task))
        self.assertEqual(8080, self.run_task(task, '8080')[0]['preview']['container_port'])

    def test_exec_timeout_stops_only_that_exec_not_the_container(self):
        task = self.task()
        # The container's long-running server: started like `docker run -d`.
        server = subprocess.Popen(['sleep', '60'], start_new_session=True)
        try:
            with patch.object(self.agent, 'JOB_TIMEOUT', 0.6), patch.dict(os.environ, {'FAKE_DOCKER_PS': 'container-id'}):
                job = self.agent.dispatch({'op': 'exec', 'task': task, 'command': 'sleep 60'})
                until = time.monotonic() + 5
                while not self.agent.marked_processes(job['id']) and time.monotonic() < until:
                    time.sleep(.02)
                marked = self.agent.marked_processes(job['id'])
                self.assertTrue(marked, 'exec command did not start with its marker')
                self.assertIn(self.agent.JOB_MARKER + '=' + job['id'], self.calls()[0])
                # Busy until the timeout cleanup completes.
                self.assertTrue(self.agent.active(task))
                result = self.wait(job['id'])
            self.assertEqual('timed_out', result['state'])
            self.assertIn('The container keeps running', result['output'])
            until = time.monotonic() + 5
            while any(alive(pid) for pid in marked) and time.monotonic() < until:
                time.sleep(.05)
            self.assertFalse(any(alive(pid) for pid in marked), 'timed-out exec kept running')
            self.assertTrue(alive(server.pid), 'server process was stopped by an exec timeout')
            commands = [call[0] for call in self.calls()]
            self.assertNotIn('stop', commands)
            self.assertNotIn('ps', commands)
            # The task accepts the next operation immediately.
            self.assertFalse(self.agent.active(task))
        finally:
            server.kill()
            server.wait()

    def test_finished_exec_does_not_touch_other_processes(self):
        task = self.task()
        job = self.agent.dispatch({'op': 'exec', 'task': task, 'command': 'echo ready'})
        result = self.wait(job['id'])
        self.assertEqual('succeeded', result['state'])
        self.assertIn('ready', result['output'])
        self.assertEqual([], self.agent.marked_processes(job['id']))

    def test_stop_marked_ignores_unmarked_and_other_jobs(self):
        other = subprocess.Popen(['sleep', '60'], env={**os.environ, self.agent.JOB_MARKER: 'other'})
        target = subprocess.Popen(['sleep', '60'], env={**os.environ, self.agent.JOB_MARKER: 'target'})
        plain = subprocess.Popen(['sleep', '60'])
        try:
            self.assertEqual([target.pid], self.agent.marked_processes('target'))
            self.assertTrue(self.agent.stop_marked('target', grace=1))
            self.assertEqual(-signal.SIGTERM, target.wait(timeout=5))
            self.assertIsNone(other.poll())
            self.assertIsNone(plain.poll())
        finally:
            for process in (other, target, plain):
                process.kill()
                process.wait()

    def test_run_timeout_still_stops_only_its_task_containers(self):
        task = self.task()
        with patch.object(self.agent, 'JOB_TIMEOUT', 0.4), patch.dict(os.environ, {'FAKE_DOCKER_PS': 'abc123'}):
            job = self.agent.start_job(task, 'run', ['docker', 'sh-sleep'], on_timeout=lambda: self.agent.stop_containers(task))
            result = self.wait(job['id'])
        self.assertEqual('timed_out', result['state'])
        calls = self.calls()
        self.assertIn(['ps', '-q', '--filter', 'label=' + self.agent.LABEL + '=' + task], calls)
        self.assertIn(['stop', '-t', '2', 'abc123'], calls)

    def test_build_timeout_does_not_stop_running_containers(self):
        task = self.task()
        with patch.object(self.agent, 'JOB_TIMEOUT', 0.4), patch.dict(os.environ, {'FAKE_DOCKER_PS': 'abc123'}):
            job = self.agent.start_job(task, 'build', ['docker', 'sh-sleep'])
            result = self.wait(job['id'])
        self.assertEqual('timed_out', result['state'])
        self.assertNotIn('stop', [call[0] for call in self.calls()])

    def test_explicit_cancel_still_stops_the_server(self):
        task = self.task()
        with patch.dict(os.environ, {'FAKE_DOCKER_PS': 'abc123'}):
            self.agent.dispatch({'op': 'cancel', 'task': task})
        self.assertIn(['stop', '-t', '2', 'abc123'], self.calls())


if __name__ == '__main__':
    unittest.main()
