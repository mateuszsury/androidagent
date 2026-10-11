#!/usr/bin/env python3
"""Create an isolated, guarded fix for PHADDD source/destination aliasing in TCG.

Only the exact Linux x86_64 headless engine from Android Emulator 37.2.12 is
supported. The installed SDK stays unchanged. No Android image or APK is patched.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import tempfile

ORIGINAL_SHA256 = "23b788a3b87b3228558924de4cb5477e2818b3b9415d6ce27d6438cf3e462b98"
ENGINE = Path("qemu/linux-x86_64/qemu-system-x86_64-headless")
OFFSET = 0x908630
# helper_phaddd_xmm, followed by its alignment padding. Both operands must be
# read before any write: Chromium emits PHADDD with the same source and target.
ORIGINAL = bytes.fromhex("f30f6f06660f70c8ed660f70c0e8660ffec1660fd6068b420403028946088b420c03420889460cc30f1f840000000000")
# tools/emulator/phaddd-fixed.S: position-independent, baseline SSE2, same ABI.
REPLACEMENT = bytes.fromhex("f30f6f06f30f6f0a660f6fd0660f70c0b1660ffec2660f70d1b1660ffeca0fc6c188f30f7f06c3")
REPLACEMENT += b"\x90" * (len(ORIGINAL) - len(REPLACEMENT))

def digest(data):
    return hashlib.sha256(data).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "AMD64"):
        parser.error("This fix supports only Linux x86_64 hosts")
    source = (args.sdk_root / "emulator").resolve()
    target = args.output.resolve()
    if target == source or source in target.parents or target in source.parents:
        parser.error("Output must be separate from the installed SDK emulator")
    original = (source / ENGINE).read_bytes()
    if digest(original) != ORIGINAL_SHA256:
        parser.error("Unsupported emulator engine: refusing to patch an unknown build")
    if original[OFFSET:OFFSET + len(ORIGINAL)] != ORIGINAL:
        parser.error("Unexpected helper bytes: refusing to patch")
    patched = original[:OFFSET] + REPLACEMENT + original[OFFSET + len(ORIGINAL):]
    manifest = {
        "emulator": "37.2.12-16428233", "host": "Linux x86_64",
        "scope": "headless software TCG; helper_phaddd_xmm only",
        "original_sha256": ORIGINAL_SHA256, "patched_sha256": digest(patched),
        "offset": hex(OFFSET), "original_hex": ORIGINAL.hex(), "replacement_hex": REPLACEMENT.hex(),
        "source": "https://android.googlesource.com/platform/external/qemu/+/ae9d18d2b6261179fbd57fffec720a04f7bfb053/target/i386/ops_sse.h",
        "sdk_changed": False, "android_image_changed": False, "apk_changed": False,
    }
    if target.exists():
        record = target / "androidagent-tcg-fix.json"
        if not record.is_file() or json.loads(record.read_text()) != manifest or digest((target / ENGINE).read_bytes()) != manifest["patched_sha256"]:
            parser.error("Output already exists and is not this verified patched emulator")
    else:
        # Hard-link immutable SDK resources to avoid copying a gigabyte. Replacing
        # the engine atomically below breaks its link, preserving the SDK file.
        def copy(src, dst):
            try:
                os.link(src, dst)
            except OSError:
                shutil.copy2(src, dst)
            return dst
        target.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix="androidagent-emulator-", dir=target.parent))
        try:
            shutil.copytree(source, staging, dirs_exist_ok=True, copy_function=copy, symlinks=True)
            replacement = staging / "androidagent-engine.tmp"
            replacement.write_bytes(patched)
            replacement.chmod((source / ENGINE).stat().st_mode & 0o777)
            os.replace(replacement, staging / ENGINE)
            (staging / "androidagent-tcg-fix.json").write_text(json.dumps(manifest, indent=2) + "\n")
            os.rename(staging, target)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    if digest((source / ENGINE).read_bytes()) != ORIGINAL_SHA256:
        raise RuntimeError("Installed SDK engine unexpectedly changed")
    print(target)
    print(json.dumps(manifest, indent=2))

if __name__ == "__main__":
    main()
