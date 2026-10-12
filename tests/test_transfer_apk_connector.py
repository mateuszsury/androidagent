"""Offline connector staging checks with synthetic bytes and an in-memory Git API.

No test executes gh, invokes a connector, uploads an APK, or uses an account.
"""
import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


HELPER_PATH = Path(__file__).resolve().parents[1] / "tools/transfer-apk-connector.py"
SPEC = importlib.util.spec_from_file_location("transfer_apk_connector_under_test", HELPER_PATH)
connector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(connector)
driver = connector.TRANSFER


class MemoryConnectorTransfer(driver.Transfer):
    def __init__(self, args, remote):
        self.remote = remote
        super().__init__(args)

    def gh(self, method, suffix, input_file=None):
        self.remote["calls"].append((method, suffix))
        if method == "GET":
            sha = suffix.removeprefix("/")
            if sha not in self.remote["blobs"]:
                raise driver.TransferError("Offline fixture GET HTTP 404", 1)
            content = self.remote["blobs"][sha]
            return {"sha": sha, "size": len(content), "encoding": "base64",
                    "content": base64.b64encode(content).decode("ascii")}
        if method != "POST":
            raise AssertionError("Unexpected local transport method")
        durable = json.loads(self.path.read_text())
        pending = durable["pending"]
        if pending["kind"] == "part":
            raise AssertionError("Connector helper must never POST APK parts")
        request = json.loads(input_file.read_text())
        if request["encoding"] != "utf-8":
            raise AssertionError("Only small UTF-8 report/manifest POSTs are permitted")
        content = request["content"].encode("utf-8")
        sha = driver.git_sha(content)
        if not durable["parts_complete"] or pending["entry"]["shaGit"] != sha:
            raise AssertionError("POST preceded durable verified-parts/pending state")
        self.remote["post_kinds"].append(pending["kind"])
        self.remote["blobs"][sha] = content
        return {"sha": sha}


class ConnectorTransferTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="androidagent-connector-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        # Small boundaries exercise staging/resume. A separate CLI check uses
        # the actual production 8 MiB boundary and base64 request expansion.
        self.chunk = 1024
        self.chunk_patch = patch.object(driver, "PART_BYTES", self.chunk)
        self.chunk_patch.start()
        self.addCleanup(self.chunk_patch.stop)
        self.apk = self.root / "synthetic-fixture.apk"
        self.apk.write_bytes(bytes(range(256)) * 8 + b"last-17-byte-tail")
        self.journal_dir = self.root / "journal"
        self.remote = {"calls": [], "post_kinds": [], "blobs": {}}
        # Fail immediately if a future implementation reaches a real subprocess.
        self.transport_guard = patch.object(driver.subprocess, "run",
                                            side_effect=AssertionError("Real CLI/network transport forbidden"))
        self.transport_guard.start()
        self.addCleanup(self.transport_guard.stop)

    def args(self, **changes):
        values = dict(apk=self.apk, expected_bytes=self.apk.stat().st_size,
                      expected_sha256=driver.file_sha256(self.apk), repo="mateuszsury/androidagent",
                      journal_dir=self.journal_dir, report=None, parts_only=True, attach_report=False)
        values.update(changes)
        return SimpleNamespace(**values)

    def transfer(self, **changes):
        return MemoryConnectorTransfer(self.args(**changes), self.remote)

    def stage(self):
        with contextlib.redirect_stdout(io.StringIO()):
            return connector.stage(self.transfer())

    def journal(self):
        return json.loads((self.journal_dir / "journal.json").read_text())

    def external_accept(self, staged):
        """Model the caller's already accepted external POST, without invoking one."""
        durable = self.journal()
        entry = staged["entry"]
        self.assertEqual({"kind": "part", "entry": entry}, durable["pending"])
        self.assertEqual(len(durable["parts"]), entry["part"])
        with self.apk.open("rb") as source:
            source.seek(staged["offset"])
            content = source.read(staged["chunk_bytes"])
        self.assertEqual(driver.describe(content, part=entry["part"]), entry)
        self.remote["blobs"][entry["shaGit"]] = content

    def finish_parts(self):
        for _ in range(10):
            staged = self.stage()
            if staged["complete"]:
                return staged
            self.external_accept(staged)
        self.fail("Staging did not reach the synthetic fixture's end")

    def test_first_stage_is_durable_before_external_transport_and_never_posts(self):
        staged = self.stage()
        journal = self.journal()
        self.assertFalse(staged["complete"])
        self.assertEqual(0, staged["offset"])
        self.assertEqual(self.chunk, staged["chunk_bytes"])
        self.assertEqual("mateuszsury/androidagent", staged["repository"])
        self.assertEqual([], journal["parts"])
        self.assertFalse(journal["first_part_verified"])
        self.assertFalse(journal["parts_complete"])
        self.assertEqual({"kind": "part", "entry": staged["entry"]}, journal["pending"])
        self.assertEqual([], self.remote["calls"])
        self.assertEqual({}, self.remote["blobs"])
        self.assertEqual([], list(self.journal_dir.glob("request-*.json")))
        self.external_accept(staged)

    def test_accepted_external_part_resumes_by_exact_get_without_reposting(self):
        first = self.stage()
        self.external_accept(first)
        second = self.stage()
        journal = self.journal()
        self.assertEqual([("GET", "/" + first["entry"]["shaGit"])], self.remote["calls"])
        self.assertEqual([first["entry"]], journal["parts"])
        self.assertTrue(journal["first_part_verified"])
        self.assertEqual(1, second["entry"]["part"])
        self.assertEqual(self.chunk, second["offset"])
        self.assertEqual(second["entry"], journal["pending"]["entry"])
        self.assertEqual([], self.remote["post_kinds"])

    def test_bad_get_bytes_leave_original_pending_without_staging_next_part(self):
        staged = self.stage()
        entry = staged["entry"]
        # Metadata still claims the expected SHA and size; exact bytes must win.
        self.remote["blobs"][entry["shaGit"]] = b"X" * entry["bytes"]
        before = (self.journal_dir / "journal.json").read_bytes()
        with self.assertRaisesRegex(driver.TransferError, "exact local bytes"):
            self.stage()
        self.assertEqual(before, (self.journal_dir / "journal.json").read_bytes())
        self.assertEqual([], self.journal()["parts"])
        self.assertEqual(entry, self.journal()["pending"]["entry"])
        self.assertEqual([("GET", "/" + entry["shaGit"])], self.remote["calls"])

    def test_unavailable_pending_blob_stops_with_get_only_and_preserves_journal(self):
        staged = self.stage()
        before = (self.journal_dir / "journal.json").read_bytes()
        with self.assertRaisesRegex(driver.TransferError, "GET HTTP 404"):
            self.stage()
        self.assertEqual(before, (self.journal_dir / "journal.json").read_bytes())
        self.assertEqual([("GET", "/" + staged["entry"]["shaGit"])], self.remote["calls"])
        self.assertEqual([], self.remote["post_kinds"])

    def test_completed_parts_and_manifest_describe_every_chunk_without_part_posts(self):
        completed = self.finish_parts()
        journal = self.journal()
        self.assertTrue(completed["complete"])
        self.assertEqual(3, completed["parts"])
        self.assertEqual(self.args().expected_bytes, completed["apk"]["bytes"])
        self.assertEqual([self.chunk, self.chunk, 17], [part["bytes"] for part in journal["parts"]])
        self.assertEqual([0, 1, 2], [part["part"] for part in journal["parts"]])
        self.assertTrue(journal["parts_complete"])
        self.assertIsNone(journal["pending"])
        self.assertFalse((self.journal_dir / "manifest.json").exists())
        previous_calls = list(self.remote["calls"])
        self.assertTrue(self.stage()["complete"])
        self.assertEqual(previous_calls, self.remote["calls"])

        report = self.root / "report.json"
        report.write_bytes(driver.json_bytes({"fixture": "offline synthetic bytes; no Android/device test"}))
        transfer = self.transfer(report=report, parts_only=False, attach_report=True)
        with contextlib.redirect_stdout(io.StringIO()):
            transfer.run()
        manifest = json.loads((self.journal_dir / "manifest.json").read_text())
        self.assertEqual(self.chunk, manifest["chunk_bytes"])
        self.assertEqual(journal["parts"], manifest["parts"])
        self.assertEqual(["report", "manifest"], self.remote["post_kinds"])
        self.assertEqual(json.loads(report.read_text()), manifest["report"]["metadata"])
        rebuilt = b"".join(self.remote["blobs"][part["shaGit"]] for part in manifest["parts"])
        self.assertEqual(self.apk.read_bytes(), rebuilt)
        self.assertEqual(len(rebuilt), manifest["bytes"])
        self.assertEqual(driver.sha256(rebuilt), manifest["sha256"])
        self.assertEqual([], list(self.journal_dir.glob("request-*.json")))

    def test_different_chunk_journal_is_rejected_before_any_get_or_post(self):
        self.stage()
        before = (self.journal_dir / "journal.json").read_bytes()
        with patch.object(driver, "PART_BYTES", 16 * 1024 * 1024):
            with self.assertRaisesRegex(driver.TransferError, "part_bytes"):
                self.transfer()
        self.assertEqual(before, (self.journal_dir / "journal.json").read_bytes())
        self.assertEqual([], self.remote["calls"])

    def test_cli_default_eight_mib_fits_connector_json_cap_without_post(self):
        part_bytes = 8 * 1024 * 1024
        self.apk.write_bytes(b"A" * part_bytes + b"short final chunk")
        argv = [str(HELPER_PATH), "--apk", str(self.apk), "--expected-bytes", str(self.apk.stat().st_size),
                "--expected-sha256", driver.file_sha256(self.apk), "--journal-dir", str(self.journal_dir)]
        stdout = io.StringIO()
        with patch.object(connector.sys, "argv", argv), contextlib.redirect_stdout(stdout):
            self.assertEqual(0, connector.main())
        staged = json.loads(stdout.getvalue().removeprefix("CONNECTOR_STAGE "))
        self.assertEqual(part_bytes, staged["chunk_bytes"])
        self.assertEqual(part_bytes, staged["entry"]["bytes"])
        self.assertEqual(part_bytes, self.journal()["part_bytes"])
        self.assertEqual(staged["entry"], self.journal()["pending"]["entry"])
        # Include the externally created request's base64 plus repository fields.
        request = {"owner": "mateuszsury", "repo": "androidagent", "encoding": "base64",
                   "content": base64.b64encode(b"A" * part_bytes).decode("ascii")}
        self.assertLess(len(json.dumps(request).encode()), 16 * 1024 * 1024)
        self.assertEqual([], self.remote["calls"])
        self.assertEqual([], list(self.journal_dir.glob("request-*.json")))

    def test_cli_report_attachment_cannot_upload_unfinished_apk_parts(self):
        with patch.object(driver, "PART_BYTES", 1024 * 1024):
            self.stage()
        report = self.root / "report.json"
        report.write_text('{"offline":true}\n')
        before = (self.journal_dir / "journal.json").read_bytes()
        argv = [str(HELPER_PATH), "--apk", str(self.apk), "--expected-bytes", str(self.apk.stat().st_size),
                "--expected-sha256", driver.file_sha256(self.apk), "--journal-dir", str(self.journal_dir),
                "--chunk-mib", "1", "--attach-report", str(report)]
        stderr = io.StringIO()
        with patch.object(connector.sys, "argv", argv), contextlib.redirect_stderr(stderr):
            self.assertEqual(1, connector.main())
        self.assertIn("requires completed parts", stderr.getvalue())
        self.assertEqual(before, (self.journal_dir / "journal.json").read_bytes())
        self.assertEqual([], self.remote["calls"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
