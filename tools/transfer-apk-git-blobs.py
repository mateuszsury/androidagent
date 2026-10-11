#!/usr/bin/env python3
r"""Transfer an existing, exactly verified APK using unreferenced GitHub Git blobs.

No APK rebuild, source/ref/commit change, release, or authentication change occurs.
The receiver can fetch the printed manifest SHA through the repository Git API.
All APK parts are 16 MiB except the last, and requests execute sequentially.

Example:
  python3 tools/transfer-apk-git-blobs.py \
    --apk artifacts/androidagent-0.4.3.apk --expected-bytes EXACT_BYTES \
    --expected-sha256 EXACT_SHA256 --repo mateuszsury/androidagent \
    --journal-dir /workspace/tools/usb-runtime/apk-git-blobs-v0.4.3 \
    --report artifacts/release-0.4.3.json

Upload APK parts while final tests are running, then attach their final report:
  ... --parts-only
  ... --attach-report --report artifacts/release-0.4.3.json
Use the same verified APK identity and journal directory in both commands.
Parts-only never creates a manifest. Attaching a report is allowed only after
all parts are complete and before any report or manifest upload is pending.

An interrupted or failed POST leaves a pending deterministic blob SHA. A resumed
run only GETs that pending SHA; if it is unavailable, the script stops without
reposting. Large temporary request JSON files are always deleted. The local APK,
small journal and manifest are retained. No encoded content is logged.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path


PART_BYTES = 16 * 1024 * 1024
REPORT_LIMIT = 1024 * 1024
REQUEST_SECONDS = 180


class TransferError(Exception):
    def __init__(self, message, exit_code=1):
        super().__init__(message)
        self.exit_code = exit_code


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def git_sha(data):
    identity = hashlib.sha1(usedforsecurity=False)
    identity.update(b"blob " + str(len(data)).encode("ascii") + b"\0")
    identity.update(data)
    return identity.hexdigest()


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while True:
            part = source.read(PART_BYTES)
            if not part:
                return digest.hexdigest()
            digest.update(part)


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", suffix=".part", delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        temporary = None
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def describe(data, **fields):
    return dict(fields, bytes=len(data), shaGit=git_sha(data), sha256=sha256(data))


class Transfer:
    def __init__(self, args):
        self.args = args
        self.parts_only = bool(getattr(args, "parts_only", False))
        self.attach_report = bool(getattr(args, "attach_report", False))
        if self.parts_only and (args.report is not None or self.attach_report):
            raise TransferError("--parts-only cannot include --report or --attach-report; no GitHub request made.")
        if self.attach_report and args.report is None:
            raise TransferError("--attach-report requires --report; no GitHub request made.")
        self.apk = args.apk.resolve(strict=True)
        if not self.apk.is_file() or self.apk.stat().st_size != args.expected_bytes:
            raise TransferError("APK length does not match --expected-bytes; no GitHub request made.")
        actual_hash = file_sha256(self.apk)
        if actual_hash != args.expected_sha256:
            raise TransferError("APK SHA256 does not match --expected-sha256; no GitHub request made.")
        self.apk_fields = {"name": self.apk.name, "bytes": args.expected_bytes, "sha256": actual_hash}
        self.report_data = None
        self.report_metadata = None
        report_identity = None
        if args.report is not None:
            report = args.report.resolve(strict=True)
            if not report.is_file() or report.stat().st_size > REPORT_LIMIT:
                raise TransferError("Report must be a JSON file of at most 1 MiB; no GitHub request made.")
            self.report_data = report.read_bytes()
            try:
                self.report_metadata = json.loads(self.report_data.decode("utf-8"))
            except (UnicodeError, ValueError) as error:
                raise TransferError("Report is not UTF-8 JSON; no GitHub request made.") from error
            if not isinstance(self.report_metadata, dict):
                raise TransferError("Report must contain a JSON object; no GitHub request made.")
            report_identity = {"name": report.name, "bytes": len(self.report_data), "sha256": sha256(self.report_data)}
        self.directory = args.journal_dir.resolve()
        self.path = self.directory / "journal.json"
        self.manifest_path = self.directory / "manifest.json"
        identity = {"format": 1, "repo": args.repo, "apk": self.apk_fields,
                    "part_bytes": PART_BYTES, "report_source": report_identity}
        if self.path.exists():
            self.journal = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(self.journal, dict):
                raise TransferError("Malformed journal object; no GitHub request made.")
            for name, value in identity.items():
                if name == "report_source":
                    continue
                if self.journal.get(name) != value:
                    raise TransferError("Journal identity differs for " + name + "; no GitHub request made.")
        else:
            if self.attach_report:
                raise TransferError("--attach-report requires an existing completed parts journal; no GitHub request made.")
            self.journal = dict(identity, parts=[], pending=None, first_part_verified=False, parts_complete=False)
            self.save()
        if not isinstance(self.journal.get("parts"), list):
            raise TransferError("Malformed journal parts; no GitHub request made.")
        pending = self.journal.get("pending")
        if pending is not None and not isinstance(pending, dict):
            raise TransferError("Malformed pending state; no GitHub request made.")
        if self.attach_report and self.journal.get("parts_complete") is not True:
            raise TransferError("--attach-report requires completed parts; no GitHub request made.")
        if self.journal.get("parts_complete") is True:
            self.validate_completed_parts()
        if self.parts_only and (self.journal.get("report_source") is not None
                               or "report_blob" in self.journal or "manifest_blob" in self.journal
                               or (self.journal.get("pending") is not None
                                   and self.journal["pending"].get("kind") != "part")):
            raise TransferError("--parts-only requires a journal without report or manifest state; no GitHub request made.")
        saved_report = self.journal.get("report_source")
        if saved_report != report_identity:
            if not self.attach_report or saved_report is not None:
                raise TransferError("Journal identity differs for report_source; no GitHub request made.")
            if (self.journal.get("parts_complete") is not True or self.journal.get("pending") is not None
                    or "report_blob" in self.journal or "manifest_blob" in self.journal):
                raise TransferError("Report attachment requires completed parts and no pending/report/manifest blob; no GitHub request made.")
            self.journal["report_source"] = report_identity
            self.save()  # Bind the exact report bytes durably before any POST.

    def validate_completed_parts(self):
        expected_count = (self.args.expected_bytes + PART_BYTES - 1) // PART_BYTES
        if (len(self.journal["parts"]) != expected_count
                or self.journal.get("first_part_verified") is not True):
            raise TransferError("Completed-parts journal is incomplete or unverified; no GitHub request made.")
        with self.apk.open("rb") as source:
            for number, entry in enumerate(self.journal["parts"]):
                if entry != describe(source.read(PART_BYTES), part=number):
                    raise TransferError("Completed part differs from the verified APK; no GitHub request made.")

    def save(self):
        atomic_write(self.path, json_bytes(self.journal))

    def gh(self, method, suffix, input_file=None):
        command = ["gh", "api", "--method", method,
                   "repos/" + self.args.repo + "/git/blobs" + suffix]
        if input_file is not None:
            command.extend(["--input", str(input_file)])
        # Preserve authentication, proxy and CA settings. API debug logging can
        # otherwise contain huge request bodies, so disable it only in this child.
        environment = dict(os.environ)
        environment.pop("GH_DEBUG", None)
        try:
            response = subprocess.run(command, capture_output=True, env=environment, timeout=REQUEST_SECONDS)
        except FileNotFoundError as error:
            raise TransferError("gh executable is unavailable; no upload made.") from error
        except subprocess.TimeoutExpired as error:
            raise TransferError("GitHub " + method + " timed out after " + str(REQUEST_SECONDS)
                                + " seconds; pending state retained, no automatic POST retry.", 124) from error
        if response.returncode != 0:
            # Do not dump CLI debug output, authentication headers or payloads.
            status = re.search(rb"HTTP[ :]+([0-9]{3})", response.stderr + response.stdout)
            http = " HTTP " + status.group(1).decode("ascii") if status else ""
            raise TransferError("GitHub " + method + " failed: gh exit " + str(response.returncode)
                                + http + "; pending state retained, no automatic POST retry.", response.returncode)
        try:
            result = json.loads(response.stdout)
        except (UnicodeError, ValueError) as error:
            raise TransferError("GitHub " + method + " returned invalid JSON; pending state retained.") from error
        if not isinstance(result, dict):
            raise TransferError("GitHub " + method + " returned an invalid object; pending state retained.")
        return result

    def verify_remote(self, entry, expected):
        result = self.gh("GET", "/" + entry["shaGit"])
        if result.get("sha") != entry["shaGit"] or result.get("size") != len(expected) or result.get("encoding") != "base64":
            raise TransferError("GitHub GET blob metadata differs from expected bytes; pending state retained.")
        try:
            encoded = result["content"]
            if not isinstance(encoded, str):
                raise ValueError("Blob content is not a string")
            content = base64.b64decode("".join(encoded.split()), validate=True)
        except (KeyError, TypeError, ValueError) as error:
            raise TransferError("GitHub GET blob has invalid base64; pending state retained.") from error
        if content != expected or git_sha(content) != entry["shaGit"] or sha256(content) != entry["sha256"]:
            raise TransferError("GitHub GET blob does not match the exact local bytes; pending state retained.")

    def accept(self, kind, entry):
        if kind == "part":
            if entry["part"] != len(self.journal["parts"]):
                raise TransferError("Pending part is out of sequence; no POST repeated.")
            self.journal["parts"].append(entry)
            if entry["part"] == 0:
                self.journal["first_part_verified"] = True
        else:
            self.journal[kind + "_blob"] = entry
        self.journal["pending"] = None
        self.save()

    def upload(self, kind, data, entry, utf8=False):
        if self.journal.get("pending") is not None:
            raise TransferError("Unresolved pending upload; resume its GET before creating another blob.")
        temporary = None
        try:
            # Large JSON is temporary and never enters repository history.
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.directory,
                                             prefix="request-", suffix=".json", delete=False) as output:
                temporary = Path(output.name)
                json.dump({"content": data.decode("utf-8") if utf8 else base64.b64encode(data).decode("ascii"),
                           "encoding": "utf-8" if utf8 else "base64"}, output, ensure_ascii=False)
            self.journal["pending"] = {"kind": kind, "entry": entry}
            self.save()  # Durable before POST, even if the response is lost.
            result = self.gh("POST", "", temporary)
            if result.get("sha") != entry["shaGit"]:
                raise TransferError("GitHub POST returned a different Git SHA; pending state retained.")
            if kind != "part" or entry["part"] == 0:
                self.verify_remote(entry, data)
            self.accept(kind, entry)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)

    def manifest(self):
        # Preserve the receiver schema used for the successful 0.4.2 transfer.
        value = dict(self.apk_fields, version=1, repository=self.args.repo,
                     chunk_bytes=PART_BYTES, parts=self.journal["parts"])
        if self.report_data is not None:
            if "report_blob" not in self.journal:
                raise TransferError("Report blob is missing; manifest cannot be created.")
            value["report"] = dict(self.journal["report_blob"], metadata=self.report_metadata)
        return json_bytes(value)

    def resume_pending(self):
        pending = self.journal.get("pending")
        if pending is None:
            return
        kind, entry = pending.get("kind"), pending.get("entry")
        if not isinstance(entry, dict):
            raise TransferError("Malformed pending state; no POST repeated.")
        if kind == "part":
            number = entry.get("part")
            if not isinstance(number, int) or number != len(self.journal["parts"]):
                raise TransferError("Pending part number is inconsistent; no POST repeated.")
            with self.apk.open("rb") as source:
                source.seek(number * PART_BYTES)
                data = source.read(PART_BYTES)
            expected = describe(data, part=number)
        elif kind == "report" and self.report_data is not None:
            data = self.report_data
            expected = describe(data, name=self.journal["report_source"]["name"])
        elif kind == "manifest":
            data = self.manifest()
            expected = describe(data, name="manifest.json")
        else:
            raise TransferError("Unknown pending upload kind; no POST repeated.")
        if not data or entry != expected:
            raise TransferError("Pending content no longer matches local input; no POST repeated.")
        # This is deliberately GET-only, including after a known failed POST.
        self.verify_remote(entry, data)
        self.accept(kind, entry)
        print("resumed", kind, "bytes", entry["bytes"], "shaGit", entry["shaGit"], flush=True)

    def run(self):
        self.resume_pending()
        transferred = hashlib.sha256()
        with self.apk.open("rb") as source:
            number = 0
            while True:
                data = source.read(PART_BYTES)
                if not data:
                    break
                transferred.update(data)
                entry = describe(data, part=number)
                if number < len(self.journal["parts"]):
                    if self.journal["parts"][number] != entry:
                        raise TransferError("Accepted part differs from local APK; no upload continued.")
                    if number == 0 and not self.journal.get("first_part_verified"):
                        self.verify_remote(entry, data)
                        self.journal["first_part_verified"] = True
                        self.save()
                else:
                    self.upload("part", data, entry)
                    print("part", number, "bytes", entry["bytes"], "shaGit", entry["shaGit"],
                          "sha256", entry["sha256"], flush=True)
                number += 1
        if len(self.journal["parts"]) != number or sum(part["bytes"] for part in self.journal["parts"]) != self.args.expected_bytes:
            raise TransferError("Journal does not cover the exact APK length; manifest not uploaded.")
        if transferred.hexdigest() != self.args.expected_sha256:
            raise TransferError("APK changed while reading transfer parts; manifest not uploaded.")
        if self.journal.get("parts_complete") is not True:
            self.journal["parts_complete"] = True
            self.save()
        if self.parts_only:
            print("PARTS_COMPLETE apk_bytes", self.args.expected_bytes, "apk_sha256", self.args.expected_sha256,
                  "parts", number, flush=True)
            return
        if self.report_data is not None:
            entry = describe(self.report_data, name=self.journal["report_source"]["name"])
            existing = self.journal.get("report_blob")
            if existing is None:
                self.upload("report", self.report_data, entry, utf8=True)
            elif existing != entry:
                raise TransferError("Accepted report differs from local report; no upload continued.")
        manifest = self.manifest()
        atomic_write(self.manifest_path, manifest)
        entry = describe(manifest, name="manifest.json")
        existing = self.journal.get("manifest_blob")
        if existing is None:
            self.upload("manifest", manifest, entry, utf8=True)
        elif existing != entry:
            raise TransferError("Accepted manifest differs from local manifest; no upload continued.")
        else:
            self.verify_remote(entry, manifest)
        print("SUCCESS manifest_shaGit", entry["shaGit"], "apk_bytes", self.args.expected_bytes,
              "apk_sha256", self.args.expected_sha256, "parts", number, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--expected-bytes", type=int, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--repo", default="mateuszsury/androidagent")
    parser.add_argument("--journal-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    phases = parser.add_mutually_exclusive_group()
    phases.add_argument("--parts-only", action="store_true", help="Transfer only APK parts; retain journal, create no report or manifest blob.")
    phases.add_argument("--attach-report", action="store_true", help="Bind a final --report to an existing completed parts journal, then create its manifest.")
    args = parser.parse_args()
    if args.parts_only and args.report is not None:
        parser.error("--parts-only cannot include --report.")
    if args.attach_report and args.report is None:
        parser.error("--attach-report requires --report.")
    if args.expected_bytes <= 0 or not re.fullmatch(r"[0-9a-f]{64}", args.expected_sha256):
        parser.error("Expected bytes must be positive and SHA256 must be 64 lowercase hex characters.")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repo):
        parser.error("Repository must have owner/name format.")
    try:
        Transfer(args).run()
    except TransferError as error:
        print("ERROR:", str(error), file=sys.stderr, flush=True)
        return error.exit_code
    except (OSError, ValueError, KeyError, TypeError) as error:
        # Never print request bodies or inherited process environment.
        print("ERROR:", type(error).__name__, "while handling local files or metadata; transfer stopped.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
