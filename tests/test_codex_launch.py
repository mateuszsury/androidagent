"""Exercise the real launcher's argument contract for the Termux runtime."""
import pathlib
import subprocess
import tempfile
import unittest


SOURCE = (pathlib.Path(__file__).resolve().parents[1] / "termux/buddy-run").read_text()
FUNCTION = "run_codex() {" + SOURCE.split("run_codex() {", 1)[1].split(
    "\n# Translate Codex's JSONL events", 1
)[0]
SCRIPT = FUNCTION + r'''
codex() { printf '%s\0' "$@"; }
S=$1; W=$1; DEV=$2; MSG='Zażółć'; SYSTEM='phone instructions'
URL=http://127.0.0.1:8766; TOKEN=test-only; MODEL=''; IMGS=()
run_codex
'''


class CodexLaunchTests(unittest.TestCase):
    def args(self, dev, resume=False):
        with tempfile.TemporaryDirectory(prefix="androidagent-launch-") as state:
            if resume:
                pathlib.Path(state, "codex-thread").write_text("existing-thread")
            result = subprocess.run(
                ["bash", "-c", SCRIPT, "launcher-test", state, str(dev)],
                capture_output=True, check=True,
            )
            return result.stdout.decode().rstrip("\0").split("\0")

    def test_normal_mode_disables_shell_and_retains_read_only_policy(self):
        args = self.args(0)
        self.assertEqual(args[args.index("--sandbox") + 1], "read-only")
        self.assertIn("features.shell_tool=false", args)
        self.assertIn('mcp_servers.phone.bearer_token_env_var="BUDDY_TOKEN"', args)
        self.assertNotIn("danger-full-access", args)

    def test_developer_mode_uses_android_uid_instead_of_linux_sandbox(self):
        args = self.args(1)
        self.assertEqual(args[args.index("--sandbox") + 1], "danger-full-access")
        self.assertIn('approval_policy="never"', args)
        self.assertNotIn("features.shell_tool=false", args)
        self.assertNotIn("workspace-write", args)

    def test_run_credential_reaches_the_codex_environment(self):
        with tempfile.TemporaryDirectory() as state:
            script = SCRIPT.replace("codex() { printf '%s\\0' \"$@\"; }", "codex() { printf '%s' \"$BUDDY_TOKEN\"; }")
            script = script.replace("run_codex\n", "MCP_TOKEN=scoped-run-credential\nrun_codex\n")
            result = subprocess.run(["bash", "-c", script, "test", state, "0"], capture_output=True, check=True)
            self.assertEqual("scoped-run-credential", result.stdout.decode())

    def test_resume_passes_saved_thread_and_unicode_prompt(self):
        args = self.args(0, resume=True)
        self.assertEqual(args[-4:], ["resume", "--", "existing-thread", "Zażółć"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
