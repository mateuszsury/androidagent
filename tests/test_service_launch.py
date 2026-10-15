"""Run the production background-service launcher with a host bash, isolated from the real HOME."""
import os
import pathlib
import subprocess
import tempfile
import unittest


SCRIPT = (pathlib.Path(__file__).resolve().parents[1] / "termux/service-launch.sh").read_text()


class ServiceLaunchTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="androidagent-service-")
        self.home = pathlib.Path(self.temporary.name).resolve()
        self.env = dict(os.environ, HOME=str(self.home))

    def tearDown(self):
        self.temporary.cleanup()

    def launch(self, directory, command, name="web", timeout=10, **kwargs):
        # Mirrors EmbeddedRuntime.serviceCommand: the script is bash -c's program, the
        # values are positional arguments.
        return subprocess.run(["bash", "--noprofile", "--norc", "-c", SCRIPT, "androidagent-service",
                               name, directory, command], env=self.env, capture_output=True,
                              text=True, timeout=timeout, **kwargs)

    def test_runs_in_the_requested_directory(self):
        site = self.home / "site"
        site.mkdir()
        result = self.launch(str(site), "pwd")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(site))

    def test_expands_home_shorthand(self):
        (self.home / ".buddy/work/app").mkdir(parents=True)
        self.assertEqual(self.launch("~/.buddy/work/app", "pwd").stdout.strip(),
                         str(self.home / ".buddy/work/app"))
        self.assertEqual(self.launch("~", "pwd").stdout.strip(), str(self.home))
        self.assertEqual(self.launch("", "pwd").stdout.strip(), str(self.home))

    def test_missing_directory_fails_clearly(self):
        result = self.launch(str(self.home / "missing"), "echo should-not-run")
        self.assertEqual(result.returncode, 126)
        self.assertNotIn("should-not-run", result.stdout)
        self.assertIn("Brak katalogu roboczego", result.stderr)

    def test_exit_status_and_service_name_reach_the_supervisor(self):
        result = self.launch(str(self.home), 'echo "$ANDROIDAGENT_SERVICE"; exit 3', name="bot-1")
        self.assertEqual(result.returncode, 3)
        self.assertEqual(result.stdout.strip(), "bot-1")

    def test_arguments_are_never_interpolated(self):
        hostile = self.home / "a'$(touch pwned)\"`touch pwned2`"
        hostile.mkdir()
        result = self.launch(str(hostile), "pwd")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), str(hostile))
        self.assertFalse(list(self.home.rglob("pwned*")))

    def test_command_keeps_shell_syntax_and_reads_no_stdin(self):
        result = self.launch(str(self.home), "printf 'a\\n' | tr a b && cat && echo done",
                             input="ignored\n")
        self.assertEqual(result.stdout.split(), ["b", "done"])

    def test_command_replaces_the_launcher_process(self):
        # The supervisor signals the PID it started; exec must keep that PID for the command.
        process = subprocess.Popen(["bash", "--noprofile", "--norc", "-c", SCRIPT, "androidagent-service",
                                    "web", str(self.home), "echo $$; exec sleep 30"],
                                   env=self.env, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(int(process.stdout.readline()), process.pid)
            process.terminate()
            self.assertEqual(process.wait(timeout=5), -15)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()


if __name__ == "__main__":
    unittest.main()
