"""Real helper functions, controlled CLI events; no model account or simulated conversation."""
import json
import pathlib
import subprocess
import tempfile
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] / "termux/buddy-run").read_text()
LAUNCH = "run_claude() {" + SOURCE.split("run_claude() {", 1)[1].split("\nstream_claude() {", 1)[0]
STREAM = "stream_claude() {" + SOURCE.split("stream_claude() {", 1)[1].split("\n# ---------------------------------------------------------------------- Codex", 1)[0]
KILL = "kill_tree() {" + SOURCE.split("kill_tree() {", 1)[1].split("\ndescendants() {", 1)[0]
HANDOFF = SOURCE.split("  handoff)\n", 1)[1].split("\n  adopt)", 1)[0]


class ClaudeLaunchTests(unittest.TestCase):
    def args(self, dev, resume=False, model="", credential=""):
        with tempfile.TemporaryDirectory(prefix="androidagent-claude-") as state:
            if resume:
                pathlib.Path(state, "claude-session").write_text("existing-session")
            script = LAUNCH + r'''
claude() { printf '%s\0' "$@"; }
S=$1; W=$1; DEV=$2; MODEL=$3; MSG='Zażółć'; SYSTEM='phone instructions'
URL=http://127.0.0.1:8766; TOKEN=test-only; MCP_TOKEN=$4
run_claude
'''
            out = subprocess.run(["bash", "-c", script, "test", state, str(dev), model, credential],
                                 check=True, capture_output=True)
            return out.stdout.decode().rstrip("\0").split("\0")

    def test_normal_mode_removes_shell_and_mutation_tools(self):
        args = self.args(0)
        self.assertEqual(args[args.index("--tools") + 1:], ["WebSearch", "WebFetch", "Read"])
        self.assertNotIn("Bash", args)
        self.assertNotIn("Edit", args)
        self.assertNotIn("--dangerously-skip-permissions", args)

    def test_developer_mode_enables_code_tools_without_disabling_permissions(self):
        args = self.args(1)
        for name in ("Bash", "Read", "Write", "Edit", "Glob", "Grep", "Skill"):
            self.assertIn(name, args)
        self.assertNotIn("--tools", args)
        self.assertNotIn("--dangerously-skip-permissions", args)

    def test_real_streaming_and_noninteractive_permission_policy(self):
        args = self.args(0)
        self.assertIn("--include-partial-messages", args)
        self.assertEqual(args[args.index("--output-format") + 1], "stream-json")
        self.assertEqual(args[args.index("--permission-prompts") + 1], "none")

    def test_mcp_is_authenticated_and_isolated_from_user_servers(self):
        args = self.args(0)
        cfg = json.loads(args[args.index("--mcp-config") + 1])
        self.assertEqual(cfg["mcpServers"]["phone"]["url"], "http://127.0.0.1:8766/mcp")
        self.assertEqual(cfg["mcpServers"]["phone"]["headers"]["Authorization"], "Bearer test-only")
        self.assertIn("--strict-mcp-config", args)

    def test_run_credential_replaces_installation_token(self):
        args = self.args(0, credential="scoped-run-credential")
        cfg = json.loads(args[args.index("--mcp-config") + 1])
        self.assertEqual("Bearer scoped-run-credential", cfg["mcpServers"]["phone"]["headers"]["Authorization"])

    def test_resume_model_and_unicode_prompt(self):
        args = self.args(1, True, "sonnet")
        self.assertEqual(args[args.index("--resume") + 1], "existing-session")
        self.assertEqual(args[args.index("--model") + 1], "sonnet")
        self.assertEqual(args[args.index("-p") + 1], "Zażółć")

    def handoff(self, dev):
        with tempfile.TemporaryDirectory(prefix="androidagent-handoff-") as state:
            directory = pathlib.Path(state)
            (directory / "work").mkdir()
            (directory / "sessions" / "phone-session").mkdir(parents=True)
            (directory / "sessions" / "phone-session" / "claude-session").write_text("existing-session")
            (directory / "system-prompt.md").write_text("phone instructions")
            script = r'''
D=$1; W=$1/work; URL=http://127.0.0.1:8766; TOKEN=test-only
valid_sid() { [[ "$1" =~ ^[A-Za-z0-9_-]{1,64}$ ]]; }
claude() { printf '%s\0' "$@" > "$D/captured"; }
case handoff in
handoff)
''' + HANDOFF + "\nesac\n"
            subprocess.run(["bash", "-c", script, "test", state, "handoff", "phone-session", str(dev), "sonnet"],
                           check=True, capture_output=True)
            return (directory / "captured").read_text().rstrip("\0").split("\0")

    def test_remote_control_keeps_authenticated_phone_tools_model_and_resume(self):
        args = self.handoff(0)
        self.assertEqual(args[args.index("--resume") + 1], "existing-session")
        self.assertEqual(args[args.index("--model") + 1], "sonnet")
        self.assertIn("--remote-control", args)
        self.assertIn("--strict-mcp-config", args)
        config = json.loads(args[args.index("--mcp-config") + 1])
        self.assertEqual(config["mcpServers"]["phone"]["headers"]["Authorization"], "Bearer test-only")
        self.assertEqual(args[args.index("--tools") + 1:args.index("--model")], ["WebSearch", "WebFetch", "Read"])

    def test_remote_control_developer_policy_matches_code_mode(self):
        args = self.handoff(1)
        self.assertIn("Bash", args)
        self.assertIn("Edit", args)
        self.assertNotIn("--tools", args)
        self.assertNotIn("--dangerously-skip-permissions", args)

    def stream(self, events):
        with tempfile.TemporaryDirectory(prefix="androidagent-claude-stream-") as state:
            script = STREAM + r'''
S=$1; SID=phone-session
tag() { jq -c --arg s "$SID" '. + {buddy_session:$s}'; }
post() { cat; }
stream_claude
'''
            out = subprocess.run(["bash", "-c", script, "test", state],
                                 input="\n".join(json.dumps(e) for e in events),
                                 capture_output=True, text=True, check=True)
            saved = pathlib.Path(state, "claude-session")
            return [json.loads(line) for line in out.stdout.splitlines()], saved.read_text() if saved.exists() else None

    def test_stream_preserves_deltas_tools_and_session_identity(self):
        events = [
            {"type": "system", "subtype": "init", "session_id": "claude-session-123"},
            {"type": "stream_event", "event": {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "Zażółć"}}},
            {"type": "assistant", "message": {"content": [{"type": "tool_use", "name": "mcp__phone__device_status", "input": {}}]}},
        ]
        out, sid = self.stream(events)
        self.assertEqual(sid, "claude-session-123")
        self.assertEqual(out, [dict(e, buddy_session="phone-session") for e in events])

    def test_failed_result_is_preserved_without_fabricated_success(self):
        event = {"type": "result", "is_error": True, "subtype": "error_during_execution", "errors": ["Not logged in"]}
        out, sid = self.stream([event])
        self.assertEqual(out, [dict(event, buddy_session="phone-session")])
        self.assertIsNone(sid)

    def test_invalid_session_identifier_cannot_replace_resume_state(self):
        _, sid = self.stream([{"type": "system", "session_id": "../../invalid"}])
        self.assertIsNone(sid)

    def test_stop_kills_shell_and_children_even_when_term_is_ignored(self):
        with tempfile.TemporaryDirectory(prefix="androidagent-stop-") as state:
            child_file = pathlib.Path(state, "child")
            unwanted = pathlib.Path(state, "continued")
            proc = subprocess.Popen(["bash", "-c", 'trap "" TERM; sleep 300 & echo $! > "$1"; wait; echo unsafe > "$2"',
                                     "test", str(child_file), str(unwanted)])
            try:
                import time
                for _ in range(100):
                    if child_file.exists():
                        break
                    time.sleep(.01)
                child = int(child_file.read_text())
                subprocess.run(["bash", "-c", KILL + '\nkill_tree "$1"', "test", str(proc.pid)],
                               check=True, timeout=5)
                self.assertEqual(proc.wait(timeout=3), -9)
                self.assertFalse(unwanted.exists())
                stat = pathlib.Path(f"/proc/{child}/stat")
                if stat.exists():
                    self.assertEqual(stat.read_text().split(")", 1)[1].split()[0], "Z")
            finally:
                if proc.poll() is None:
                    proc.kill()
                    proc.wait()


if __name__ == "__main__":
    unittest.main(verbosity=2)
