"""Execute the production helper with local transport/CLI fixtures, never a model account."""
import json
import os
import pathlib
import platform
import shutil
import subprocess
import tempfile
import unittest


SOURCE = (pathlib.Path(__file__).resolve().parents[1] / "termux/buddy-run").read_text()


class EmbeddedHelperTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="androidagent-helper-status-")
        self.root = pathlib.Path(self.temporary.name)
        self.binary = self.root / "bin"
        self.binary.mkdir()
        self.helper = self.root / "buddy-run"
        # Keep the real helper logic; isolate all HOME references without changing
        # the execution workspace's HOME or reading any developer authentication.
        self.helper.write_text(SOURCE.replace("$HOME", "$ANDROIDAGENT_TEST_HOME"))
        self.env = dict(os.environ, ANDROIDAGENT_TEST_HOME=str(self.root),
                        ANDROIDAGENT_EMBEDDED="1", PATH=str(self.binary) + os.pathsep + os.environ["PATH"])
        self.processes = []
        self.write_binary("curl", r'''#!/usr/bin/python3
import os,pathlib,sys
with pathlib.Path(os.environ['ANDROIDAGENT_TEST_HOME'],'events').open('a') as output:
    output.write(sys.stdin.read().strip()+'\n')
''')
        self.write_binary("claude", r'''#!/usr/bin/python3
import json,os,sys
if sys.argv[1:] != ['auth','status']: sys.exit(17)
print(json.dumps({'loggedIn':os.environ.get('ANDROIDAGENT_TEST_CLAUDE_SIGNED') == '1'}))
''')
        self.write_binary("codex", r'''#!/usr/bin/python3
import os,sys
if sys.argv[1:] != ['login','status']: sys.exit(17)
sys.exit(0 if os.environ.get('ANDROIDAGENT_TEST_CODEX_SIGNED') == '1' else 1)
''')

    def tearDown(self):
        for process in self.processes:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
        self.temporary.cleanup()

    def write_binary(self, name, content):
        file = self.binary / name
        file.write_text(content)
        file.chmod(0o700)

    def installed(self, agent, versioned=False):
        folder = self.root / ".androidagent/clients" / ("claude-2.1.296" if versioned else agent)
        folder.mkdir(parents=True, exist_ok=True)
        (folder / ".verified").write_text("fixture archive digest, not an account credential\n")
        if agent == "claude":
            executable = folder / "package/claude"
        else:
            triple = "aarch64-unknown-linux-musl" if platform.machine() in ("aarch64", "arm64") else "x86_64-unknown-linux-musl"
            executable = folder / "package/vendor" / triple / "bin/codex"
        executable.parent.mkdir(parents=True, exist_ok=True)
        # A real host ELF permits checking /proc process identity and SIGKILL;
        # its contents are a sleep fixture, not an official or authenticated CLI.
        compiler = shutil.which("cc")
        if not compiler:
            self.skipTest("Native process fixtures require a C compiler")
        # Multicall coreutils dispatch by argv[0] and cannot reliably be copied
        # under the name claude. Build a genuine standalone fixture instead.
        subprocess.run([compiler, "-x", "c", "-", "-o", str(executable)],
                       input="#include <unistd.h>\nint main(void) { sleep(60); return 0; }\n",
                       text=True, check=True, capture_output=True)
        executable.chmod(0o700)
        return executable

    def invoke(self, *args):
        result = subprocess.run(["bash", str(self.helper), "test-only", *args],
                                env=self.env, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr.decode())

    def event(self):
        return json.loads((self.root / "events").read_text().splitlines()[-1])

    def test_launchers_without_installed_native_clients_are_not_advertised(self):
        self.env.update(ANDROIDAGENT_TEST_CODEX_SIGNED="1", ANDROIDAGENT_TEST_CLAUDE_SIGNED="1")
        self.invoke("ping")
        self.assertEqual((self.event()["text"], self.event()["signed"]), ("none", "none"))

    def test_verified_marker_alone_and_executable_directory_do_not_count(self):
        executable = self.installed("claude")
        executable.unlink()
        executable.mkdir()
        self.invoke("ping")
        self.assertEqual(self.event()["text"], "none")

    def test_stale_codex_auth_file_does_not_imply_signed_in_account(self):
        self.installed("codex")
        stale = self.root / ".codex/auth.json"
        stale.parent.mkdir()
        stale.write_text("malformed test fixture, not an account credential")
        self.invoke("ping")
        self.assertEqual((self.event()["text"], self.event()["signed"]), ("codex", "none"))

    def test_signed_in_state_comes_from_clients_account_queries(self):
        self.installed("codex")
        self.installed("claude")
        self.env.update(ANDROIDAGENT_TEST_CODEX_SIGNED="1", ANDROIDAGENT_TEST_CLAUDE_SIGNED="1")
        self.invoke("ping")
        self.assertEqual((self.event()["text"], self.event()["signed"]), ("claude,codex", "claude,codex"))
        self.assertFalse((self.root / ".codex/auth.json").exists())

    def test_legacy_runtime_still_uses_clients_on_path(self):
        self.env["ANDROIDAGENT_EMBEDDED"] = "0"
        self.env["ANDROIDAGENT_TEST_CODEX_SIGNED"] = "1"
        self.invoke("ping")
        self.assertEqual((self.event()["text"], self.event()["signed"]), ("claude,codex", "codex"))

    def test_monitor_and_kill_accept_embedded_claude_stable_path(self):
        self.check_process_path(False)

    def test_monitor_and_kill_accept_embedded_claude_versioned_path(self):
        self.check_process_path(True)

    def check_process_path(self, versioned):
        executable = self.installed("claude", versioned)
        process = subprocess.Popen([str(executable), "60"], env=self.env)
        self.processes.append(process)
        self.invoke("procs")
        matches = [entry for entry in self.event()["procs"] if entry["pid"] == process.pid]
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0]["agent"], "claude")
        self.invoke("kill", str(process.pid))
        self.assertEqual(process.wait(timeout=5), -9)


if __name__ == "__main__":
    unittest.main(verbosity=2)
