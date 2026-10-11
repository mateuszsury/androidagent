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
    if os.environ.get('ANDROIDAGENT_TEST_DOWNLOAD_FAILURE') == '1':
        pathlib.Path(sys.argv[sys.argv.index('-o')+1]).write_text('partial download')
        sys.exit(18)
    if os.environ.get('ANDROIDAGENT_TEST_DOWNLOAD_SUCCESS') == '1':
        pathlib.Path(sys.argv[sys.argv.index('-o')+1]).write_text('complete download')
        sys.exit(0)
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
        self.assertEqual(list((self.root / ".buddy/sessions/chat-one").glob(".attachment.*")), [])
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
        self.assertEqual(duplicate[0]["active_run"], "run-one")

    def test_nonzero_cli_exit_without_stderr_is_reported(self):
        self.write_binary("codex", "#!/bin/sh\nexit 23\n")
        self.ask()
        errors = [event for event in self.events() if event["type"] == "buddy_error"]
        self.assertTrue(errors, "CLI crash was silently treated as completion")
        self.assertIn("23", errors[-1]["text"])

    def test_model_not_found_does_not_delete_resume_state_or_retry(self):
        state = self.root / ".buddy/sessions/chat-one"
        state.mkdir(parents=True)
        (state / "codex-thread").write_text("saved-thread")
        self.write_binary("codex", r'''#!/usr/bin/python3
import os,pathlib,sys
root=pathlib.Path(os.environ['ANDROIDAGENT_TEST_ROOT'])
with (root/'invocations').open('a') as stream: stream.write('called\n')
print('requested model not found', file=sys.stderr)
sys.exit(1)
''')
        self.ask()
        self.assertTrue((state / "codex-thread").exists(), "Unrelated CLI error destroyed the saved conversation")
        self.assertEqual((state / "codex-thread").read_text(), "saved-thread")
        self.assertEqual((self.root / "invocations").read_text().splitlines(), ["called"])

    def test_failed_attachment_does_not_start_agent_or_expose_partial_file(self):
        self.env["ANDROIDAGENT_TEST_DOWNLOAD_FAILURE"] = "1"
        self.ask(files="fixture.txt")
        self.assertFalse((self.root / "invoked").exists(), "Agent started without the requested file")
        self.assertFalse((self.root / ".buddy/sessions/chat-one/files/fixture.txt").exists(), "Partial download became an attachment")
        self.assertTrue(any(event["type"] == "buddy_error" for event in self.events()))

    def test_invalid_attachment_identifier_is_reported_without_launch(self):
        self.ask(files="../outside.txt")
        self.assertFalse((self.root / "invoked").exists(), "Invalid attachment was silently ignored")
        self.assertTrue(any(event["type"] == "buddy_error" for event in self.events()))

    def test_failed_attachment_preserves_previous_completed_copy(self):
        target = self.root / ".buddy/sessions/chat-one/files/fixture.txt"
        target.parent.mkdir(parents=True)
        target.write_text("previous complete bytes")
        self.env["ANDROIDAGENT_TEST_DOWNLOAD_FAILURE"] = "1"
        self.ask(files="fixture.txt")
        self.assertEqual(target.read_text(), "previous complete bytes")
        self.assertEqual(list(target.parent.parent.glob(".attachment.*")), [])

    def test_leading_dash_filename_is_downloaded_as_a_literal_name(self):
        self.env["ANDROIDAGENT_TEST_DOWNLOAD_SUCCESS"] = "1"
        self.ask(files="-literal.txt")
        self.assertEqual((self.root / ".buddy/sessions/chat-one/files/-literal.txt").read_text(), "complete download")
        self.assertTrue((self.root / "invoked").exists())

    def test_attachment_target_directory_is_rejected_without_starting_cli(self):
        target = self.root / ".buddy/sessions/chat-one/files/fixture.txt"
        target.mkdir(parents=True)
        (target / "existing").write_text("keep this file")
        self.env["ANDROIDAGENT_TEST_DOWNLOAD_SUCCESS"] = "1"
        self.ask(files="fixture.txt")
        self.assertFalse((self.root / "invoked").exists(), "Directory was passed to the agent as an attachment")
        self.assertEqual([entry.name for entry in target.iterdir()], ["existing"])
        self.assertEqual((target / "existing").read_text(), "keep this file")
        self.assertEqual(list(target.parent.parent.glob(".attachment.*")), [])
        self.assertTrue(any(event["type"] == "buddy_error" for event in self.events()))

    def test_attachment_replaces_directory_symlink_without_writing_through_it(self):
        target = self.root / ".buddy/sessions/chat-one/files/fixture.txt"
        target.parent.mkdir(parents=True)
        outside = self.root / "other-directory"
        outside.mkdir()
        (outside / "existing").write_text("keep this file")
        target.symlink_to(outside, target_is_directory=True)
        self.env["ANDROIDAGENT_TEST_DOWNLOAD_SUCCESS"] = "1"
        self.ask(files="fixture.txt")
        self.assertFalse(target.is_symlink(), "Attachment followed a pre-existing directory symlink")
        self.assertEqual(target.read_text(), "complete download")
        self.assertEqual([entry.name for entry in outside.iterdir()], ["existing"])
        self.assertEqual((outside / "existing").read_text(), "keep this file")
        self.assertTrue((self.root / "invoked").exists())

    def test_failed_claude_resume_preserves_conversation_identity(self):
        state = self.root / ".buddy/sessions/chat-one"
        state.mkdir(parents=True)
        (state / "claude-session").write_text("saved-session")
        self.write_binary("claude", r'''#!/usr/bin/python3
import os,pathlib,sys
root=pathlib.Path(os.environ['ANDROIDAGENT_TEST_ROOT'])
with (root/'invocations').open('a') as stream: stream.write('called\n')
print('No conversation found with session ID saved-session', file=sys.stderr)
sys.exit(1)
''')
        self.invoke("ask", "chat-one", "claude", "0", "test", "", "", "test-run-credential", "run-one")
        self.assertEqual((state / "claude-session").read_text(), "saved-session")
        self.assertEqual((self.root / "invocations").read_text().splitlines(), ["called"])
        self.assertTrue(any(event["type"] == "buddy_error" for event in self.events()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
