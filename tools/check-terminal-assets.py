#!/usr/bin/env python3
"""Validate hashes, ABI, ELF dependency closure and the terminal extraction plan."""
import hashlib
import io
import json
import posixpath
import sys
import zipfile
from pathlib import Path
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[1]
MACHINES = {"arm64-v8a": "EM_AARCH64", "x86_64": "EM_X86_64"}
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
REQUIRED = ["usr/bin/bash", "usr/bin/node", "usr/bin/npm", "usr/bin/git", "usr/bin/python",
            "usr/bin/jq", "usr/bin/curl", "usr/bin/proot", "usr/bin/tar", "usr/bin/zip",
            "usr/etc/tls/cert.pem", "usr/etc/resolv.conf"]


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def safe(path):
    return not path.startswith("/") and ".." not in path.split("/") and path.startswith("usr/")


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def validate(abi):
    root = ROOT / "app/src/main/assets/terminal" / abi
    manifest = json.loads((root / "manifest.json").read_text())
    check(manifest["version"] == 1 and manifest["abi"] == abi, "Manifest version/ABI")
    archive_path = root / manifest["archive"]
    check(archive_path.stat().st_size == manifest["bytes"], abi + " archive length")
    check(sha256(archive_path) == manifest["sha256"], abi + " archive SHA256")
    with zipfile.ZipFile(archive_path) as archive:
        entries = archive.namelist()
        check(len(entries) == len(set(entries)), "Duplicate archive entries")
        check(all(safe(path) for path in entries), "Unsafe archive entry")
        links = {link["path"]: link["target"] for link in manifest["symlinks"]}
        check(len(links) == len(manifest["symlinks"]), "Duplicate symlinks")
        check(not set(entries).intersection(links), "Regular file/symlink collision")
        check(all(safe(path) for path in links), "Unsafe symlink name")
        available = set(entries) | set(links)
        for required in REQUIRED:
            check(required in available, "Missing runtime tool " + required)
        for executable in manifest["executable_paths"]:
            check(executable in entries, "Missing executable path " + executable)
            check((archive.getinfo(executable).external_attr >> 16) & 0o111, "Missing X mode " + executable)
        for entry in entries:
            with archive.open(entry) as source:
                header = source.read(4)
                if header != b"\x7fELF":
                    continue
                elf = ELFFile(io.BytesIO(header + source.read()))
                check(elf["e_machine"] == MACHINES[abi], "Wrong userspace ABI " + entry)
                # Linux musl libraries are intentionally isolated from Android ELF.
                if "/opt/androidagent/musl/" in entry:
                    continue
                section = elf.get_section_by_name(".dynamic")
                if section:
                    for tag in section.iter_tags():
                        if tag.entry.d_tag == "DT_NEEDED" and tag.needed not in SYSTEM:
                            check("usr/lib/" + tag.needed in available,
                                  "Missing runtime dependency " + entry + ":" + tag.needed)
    native_root = ROOT / "app/src/main/jniLibs" / abi
    native = manifest["native"]
    for name, metadata in native.items():
        check(name.startswith("libaaterm_") and name.endswith(".so"), "Unsafe JNI name")
        source = native_root / name
        check(source.stat().st_size == metadata["bytes"] and sha256(source) == metadata["sha256"],
              "Native hash/length " + name)
        with source.open("rb") as stream:
            elf = ELFFile(stream)
            check(elf["e_machine"] == MACHINES[abi], "Wrong JNI ABI " + name)
            section = elf.get_section_by_name(".dynamic")
            if section:
                for tag in section.iter_tags():
                    if tag.entry.d_tag == "DT_NEEDED":
                        check(tag.needed in SYSTEM or tag.needed in native, "JNI dependency " + tag.needed)
                    elif tag.entry.d_tag in {"DT_RUNPATH", "DT_RPATH"}:
                        value = tag.runpath if tag.entry.d_tag == "DT_RUNPATH" else tag.rpath
                        check(value == "$ORIGIN", "JNI RPATH " + value)
    for value in manifest["executables"].values():
        check(value in native, "Missing JNI executable " + value)
    check(manifest["provider_clients_bundled"] is False, "Unexpected provider client bundle")
    print(f"{abi}: archive SHA256/length, {len(entries)} files, {len(links)} links, ELF/JNI closure OK")


if __name__ == "__main__":
    try:
        assets = ROOT / "app/src/main/assets/terminal"
        for name in ["runtime-lock.json", "clients-lock.json"]:
            check((ROOT / "terminal" / name).read_bytes() == (assets / name).read_bytes(),
                  "Bundled lock differs: " + name)
        for abi in sys.argv[1:] or MACHINES:
            validate(abi)
    except Exception as error:
        raise SystemExit(str(error))
