"""Real local transfer/journal checks with an in-memory Git API; never call gh or a network."""
import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest


DRIVER_PATH = Path(__file__).resolve().parents[1] / "tools/transfer-apk-git-blobs.py"
SPEC = importlib.util.spec_from_file_location("transfer_apk_git_blobs_under_test", DRIVER_PATH)
driver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(driver)


class MemoryTransfer(driver.Transfer):
    def __init__(self, args, remote):
        self.remote = remote
        super().__init__(args)

    def gh(self, method, suffix, input_file=None):
        if method == "POST":
            durable = json.loads(self.path.read_text())
            pending = durable["pending"]
            request = json.loads(input_file.read_text())
            content = request["content"].encode("utf-8") if request["encoding"] == "utf-8" else base64.b64decode(request["content"], validate=True)
            sha = driver.git_sha(content)
            assert pending["entry"]["shaGit"] == sha, "POST preceded its durable pending journal"
            if pending["kind"] == "report":
                assert durable["report_source"] == {
                    "name": self.args.report.name, "bytes": len(content), "sha256": driver.sha256(content)
                }, "POST preceded the durable report identity"
                assert durable["parts_complete"] is True
            self.remote["calls"].append((method, pending["kind"], sha))
            self.remote["blobs"][sha] = content
            if self.remote.get("lose") == pending["kind"]:
                self.remote["lose"] = None
                raise driver.TransferError("Simulated response lost after an accepted POST", 124)
            return {"sha": sha}
        assert method == "GET"
        sha = suffix.removeprefix("/")
        self.remote["calls"].append((method, None, sha))
        if sha not in self.remote["blobs"]:
            raise driver.TransferError("Simulated GET 404", 1)
        content = self.remote["blobs"][sha]
        return {"sha": sha, "size": len(content), "encoding": "base64", "content": base64.b64encode(content).decode("ascii")}


class TransferPhaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="androidagent-transfer-phase-tests-")
        self.root = Path(self.temporary.name)
        self.apk = self.root / "synthetic-test.apk"
        self.apk.write_bytes(b"synthetic apk fixture, not an Android installable\n")
        self.report = self.root / "final-report.json"
        self.report.write_bytes(driver.json_bytes({"fixture": "no model account", "passed": True}))
        self.journal_dir = self.root / "journal"
        self.remote = {"blobs": {}, "calls": []}

    def tearDown(self):
        self.temporary.cleanup()

    def args(self, **changes):
        fields = dict(apk=self.apk, expected_bytes=self.apk.stat().st_size,
                      expected_sha256=driver.file_sha256(self.apk), repo="mateuszsury/androidagent",
                      journal_dir=self.journal_dir, report=None, parts_only=True, attach_report=False)
        fields.update(changes)
        return SimpleNamespace(**fields)

    def run_transfer(self, **changes):
        transfer = MemoryTransfer(self.args(**changes), self.remote)
        with contextlib.redirect_stdout(io.StringIO()):
            transfer.run()
        self.assertEqual([], list(self.journal_dir.glob("request-*.json")))
        return transfer

    def journal(self):
        return json.loads((self.journal_dir / "journal.json").read_text())

    def overwrite_journal(self, **changes):
        value = self.journal()
        value.update(changes)
        driver.atomic_write(self.journal_dir / "journal.json", driver.json_bytes(value))

    def posts(self):
        return [kind for method, kind, _ in self.remote["calls"] if method == "POST"]

    def assert_rejected_without_requests(self, args):
        before = list(self.remote["calls"])
        original = (self.journal_dir / "journal.json").read_bytes() if (self.journal_dir / "journal.json").exists() else None
        with self.assertRaises(driver.TransferError):
            MemoryTransfer(args, self.remote)
        self.assertEqual(before, self.remote["calls"])
        if original is not None:
            self.assertEqual(original, (self.journal_dir / "journal.json").read_bytes())

    def test_parts_only_exact_boundary_creates_no_manifest_or_report(self):
        self.apk.write_bytes(b"p" * driver.PART_BYTES + b"last part")
        self.run_transfer()
        journal = self.journal()
        self.assertEqual([driver.PART_BYTES, 9], [part["bytes"] for part in journal["parts"]])
        self.assertTrue(journal["parts_complete"])
        self.assertTrue(journal["first_part_verified"])
        self.assertIsNone(journal["report_source"])
        self.assertNotIn("report_blob", journal)
        self.assertNotIn("manifest_blob", journal)
        self.assertFalse((self.journal_dir / "manifest.json").exists())
        self.assertEqual(["part", "part"], self.posts())

    def test_completed_parts_resume_has_no_additional_post(self):
        self.run_transfer()
        before = list(self.remote["calls"])
        self.run_transfer()
        self.assertEqual(before, self.remote["calls"])

    def test_attach_persists_identity_before_report_post_and_creates_compatible_manifest(self):
        self.run_transfer()
        self.run_transfer(parts_only=False, attach_report=True, report=self.report)
        journal = self.journal()
        self.assertEqual(driver.sha256(self.report.read_bytes()), journal["report_source"]["sha256"])
        self.assertEqual(["part", "report", "manifest"], self.posts())
        manifest = json.loads((self.journal_dir / "manifest.json").read_text())
        self.assertEqual(journal["parts"], manifest["parts"])
        self.assertEqual(self.apk.stat().st_size, manifest["bytes"])
        self.assertEqual(json.loads(self.report.read_bytes()), manifest["report"]["metadata"])

    def test_attachment_requires_existing_journal(self):
        self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))
        self.assertFalse(self.journal_dir.exists())

    def test_attachment_requires_completed_parts(self):
        self.run_transfer()
        self.overwrite_journal(parts_complete=False)
        self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))

    def test_attachment_rejects_pending_or_blob_state(self):
        self.run_transfer()
        original = self.journal()
        for changes in ({"pending": {"kind": "part", "entry": {}}}, {"report_blob": None}, {"manifest_blob": None}):
            with self.subTest(changes=changes):
                driver.atomic_write(self.journal_dir / "journal.json", driver.json_bytes(dict(original, **changes)))
                self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))

    def test_claimed_complete_parts_are_verified_before_attachment(self):
        self.run_transfer()
        self.overwrite_journal(parts=[])
        self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))

    def test_corrupted_part_hash_cannot_bind_report(self):
        self.run_transfer()
        parts = self.journal()["parts"]
        parts[0]["sha256"] = "0" * 64
        self.overwrite_journal(parts=parts)
        self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))

    def test_report_identity_never_changes_after_attachment(self):
        self.run_transfer()
        self.run_transfer(parts_only=False, attach_report=True, report=self.report)
        self.report.write_bytes(driver.json_bytes({"passed": False, "changed": True}))
        self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))
        self.assert_rejected_without_requests(self.args(parts_only=False, report=self.report))

    def test_attachment_rejects_invalid_report_before_binding(self):
        self.run_transfer()
        for value in (b"not JSON", b"[]", b"\xff"):
            with self.subTest(value=value):
                self.report.write_bytes(value)
                self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))

    def test_changed_apk_is_rejected_even_with_its_new_correct_hash(self):
        self.run_transfer()
        self.apk.write_bytes(b"a different build must use a different journal\n")
        self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True, report=self.report))

    def test_missing_report_and_conflicting_parts_flags_are_rejected(self):
        self.assert_rejected_without_requests(self.args(parts_only=False, attach_report=True))
        self.assert_rejected_without_requests(self.args(report=self.report))
        self.assert_rejected_without_requests(self.args(attach_report=True, report=self.report))

    def test_lost_part_response_resumes_with_get_and_never_reposts(self):
        self.remote["lose"] = "part"
        with self.assertRaises(driver.TransferError):
            self.run_transfer()
        self.assertEqual([], list(self.journal_dir.glob("request-*.json")))
        self.assertEqual("part", self.journal()["pending"]["kind"])
        self.run_transfer()
        self.assertEqual(["part"], self.posts())
        self.assertTrue(self.journal()["parts_complete"])

    def test_lost_report_response_keeps_bound_identity_and_resumes_without_repost(self):
        self.run_transfer()
        self.remote["lose"] = "report"
        with self.assertRaises(driver.TransferError):
            self.run_transfer(parts_only=False, attach_report=True, report=self.report)
        journal = self.journal()
        self.assertEqual("report", journal["pending"]["kind"])
        self.assertEqual(driver.sha256(self.report.read_bytes()), journal["report_source"]["sha256"])
        self.assertEqual([], list(self.journal_dir.glob("request-*.json")))
        self.run_transfer(parts_only=False, attach_report=True, report=self.report)
        self.assertEqual(["part", "report", "manifest"], self.posts())
        self.assertIsNone(self.journal()["pending"])

    def test_parts_only_cannot_drop_an_attached_report(self):
        self.run_transfer()
        self.run_transfer(parts_only=False, attach_report=True, report=self.report)
        self.assert_rejected_without_requests(self.args())

    def test_legacy_one_phase_report_transfer_and_resume_remain_compatible(self):
        self.run_transfer(parts_only=False, report=self.report)
        before = self.posts()
        self.run_transfer(parts_only=False, report=self.report)
        self.assertEqual(before, self.posts())
        self.assertEqual(["part", "report", "manifest"], before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
