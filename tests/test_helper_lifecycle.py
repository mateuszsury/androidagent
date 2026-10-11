"""Run the real helper against controlled local CLI/HTTP transports, without model login."""
import json
import os
import pathlib
import subprocess
import tempfile
import time
import unittest


SOURCE = (pathlib.Path(__file__).resolve().parents[1] / "termux/buddy-run").read_text()


class HelperLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="androidagent-lifecycle-")
        self.root = pathlib.Path(self.temporary.name)
        self.binary = self.root / "bin"
        self.binary.mkdir()
        self.helper = self.root / "buddy-run"
        # Isolate only the state directory; execute the actual complete helper logic.
        self.helper.write_text(SOURCE.replace("D=$HOME/.buddy", "D=$ANDROIDAGENT_TEST_ROOT/.buddy"))
        self.env = dict(os.environ, ANDROIDAGENT_TEST_ROOT=str(self.root),
                        PATH=str(self.binary) + os.pathsep + os.environ["PATH"])
        self.write_binary("curl", r'''#!/usr/bin/python3
import os,pathlib,sys,time
root=pathlib.Path(os.environ['ANDROIDAGENT_TEST_ROOT'])
if '--data-binary' in sys.argv:
    with (root/'events').open('a') as stream: stream.write(sys.stdin.read().strip()+'\n')
else:
    (root/'downloading').write_text(str(os.getpid()))
    time.sleep(120)
''')
        self.write_binary("codex", r'''#!/usr/bin/python3
import json,os,pathlib,time
root=pathlib.Path(os.environ['ANDROIDAGENT_TEST_ROOT'])
(root/'invoked').write_text(str(os.getpid()))
if os.environ.get('ANDROIDAGENT_TEST_WAIT') == '1': time.sleep(120)
print(json.dumps({'type':'item.completed','item':{'type':'agent_message','text':'controlled CLI output'}}))
''')
        self.processes = []

    def write_binary(self, name, content):
        target = self.binary / name
        target.write_text(content)
        target.chmod(0o700)

    def invoke(self, *args, wait=True):
        process = subprocess.Popen(["bash", str(self.helper), "test-only", *args], env=self.env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.processes.append(process)
        if wait:
            _, err = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 0, err.decode())
        return process

    def ask(self, run="run-one", files="", wait=True):
        return self.invoke("ask", "chat-one", "codex", "0", "test", "", files,
                           "test-run-credential", run, wait=wait)

    def wait_file(self, name):
        path = self.root / name
        until = time.monotonic() + 5
        while not path.exists() and time.monotonic() < until:
            time.sleep(.01)
        self.assertTrue(path.exists(), f"Missing {name}")
        return path

    def events(self):
        return [json.loads(line) for line in (self.root / "events").read_text().splitlines()]

    def tearDown(self):
        # Tests deliberately create process trees; stop through the same public command.
        try:
            self.invoke("stop", "chat-one")
        finally:
            for process in self.processes:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=5)
            self.temporary.cleanup()

    def test_every_callback_is_correlated_and_state_is_cleaned(self):
        self.ask()
        events = self.events()
        self.assertEqual([event["type"] for event in events],
                         ["buddy_started", "assistant", "buddy_done"])
        self.assertTrue(all(event["buddy_run"] == "run-one" for event in events))
        self.assertFalse((self.root / ".buddy/sessions/chat-one/pid").exists())
        self.assertFalse((self.root / ".buddy/sessions/chat-one/run-lock").exists())

    def test_stop_cancels_attachment_download_before_cli_starts(self):
        process = self.ask(files="fixture.txt", wait=False)
        download = int(self.wait_file("downloading").read_text())
        self.invoke("stop", "chat-one", "run-one")
        self.assertEqual(process.wait(timeout=5), -9)
        self.assertFalse((self.root / "invoked").exists())
        stat = pathlib.Path(f"/proc/{download}/stat")
        if stat.exists():
            self.assertEqual(stat.read_text().split(")", 1)[1].split()[0], "Z")
        self.assertEqual(self.events()[-1]["type"], "buddy_stopped")

    def test_stop_delivered_before_ask_prevents_launch(self):
        self.invoke("stop", "chat-one", "run-one")
        self.ask()
        self.assertFalse((self.root / "invoked").exists())
        self.assertNotIn("buddy_started", [event["type"] for event in self.events()])

    def test_late_stop_for_old_run_cannot_kill_new_run(self):
        self.env["ANDROIDAGENT_TEST_WAIT"] = "1"
        process = self.ask(run="run-two", wait=False)
        self.wait_file("invoked")
        self.invoke("stop", "chat-one", "run-one")
        self.assertIsNone(process.poll())
        self.assertEqual((self.root / ".buddy/sessions/chat-one/run").read_text(), "run-two")
        self.invoke("stop", "chat-one", "run-two")
        self.assertEqual(process.wait(timeout=5), -9)

    def test_duplicate_run_finishes_with_error_without_starting_second_cli(self):
        self.env["ANDROIDAGENT_TEST_WAIT"] = "1"
        process = self.ask(wait=False)
        original = self.wait_file("invoked").read_text()
        self.ask(run="run-two")
        self.assertIsNone(process.poll())
        self.assertEqual((self.root / "invoked").read_text(), original)
        duplicate = [event for event in self.events() if event["buddy_run"] == "run-two"]
        self.assertEqual([event["type"] for event in duplicate], ["buddy_error", "buddy_done"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
