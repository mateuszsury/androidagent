#!/usr/bin/env python3
"""Package the official Termux QEMU binaries as Android-executable JNI libraries.

No Termux installation is needed on the device. Downloads are pinned by SHA256
in docker/qemu-lock.json. --update-lock is an explicit maintainer operation.
Requires dpkg-deb, patchelf and readelf on the build host.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import struct
import urllib.request
from pathlib import Path
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://packages.termux.dev/apt/termux-main/"
CACHE = ROOT / "build-runtime/qemu"
LOCK = ROOT / "docker/qemu-lock.json"
ABIS = {"x86_64": ("x86_64", "x86-64"), "arm64-v8a": ("aarch64", "aarch64")}


def download(url, dest, digest=None):
    if not dest.exists() or (digest and hashlib.sha256(dest.read_bytes()).hexdigest() != digest):
        dest.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read()
        if digest and hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError("SHA256 mismatch: " + url)
        dest.write_bytes(data)
    return dest


def freeze(abi):
    arch, target = ABIS[abi]
    index = download(BASE + f"dists/stable/main/binary-{arch}/Packages.gz", CACHE / (arch + "-Packages.gz"))
    packages = {}
    for block in gzip.decompress(index.read_bytes()).decode().split("\n\n"):
        fields = dict(line.split(": ", 1) for line in block.splitlines() if ": " in line and not line.startswith(" "))
        if "Package" in fields:
            packages[fields["Package"]] = fields
    selected = {}
    pending = ["qemu-system-" + target + "-headless", "qemu-utils"]
    # CLI-only transitive dependencies are unnecessary for dynamic linking.
    skip = {"python", "python-pip", "perl", "termux-tools", "bash", "dash", "coreutils"}
    while pending:
        name = pending.pop()
        if name in selected or name in skip:
            continue
        p = packages[name]
        selected[name] = {k: p[k] for k in ["Version", "Filename", "SHA256"]}
        for dep in p.get("Depends", "").split(","):
            dep = dep.strip().split(" ")[0]
            if dep:
                pending.append(dep)
    return selected


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def fix_version_dependencies(path, aliases):
    # patchelf changes DT_NEEDED but leaves version records naming the old file.
    # Android's linker validates these records, unlike several desktop linkers.
    with path.open('r+b') as file:
        elf = ELFFile(file)
        versions = elf.get_section_by_name('.gnu.version_r')
        if versions is None:
            return
        strings = elf.get_section(versions['sh_link']).data()
        offset = versions['sh_offset']
        for unused in range(versions['sh_info']):
            file.seek(offset)
            record = file.read(16)
            version, count, name, auxiliary, following = struct.unpack('<HHIII', record)
            original = strings[name:strings.index(b'\0', name)].decode()
            replacement = aliases.get(original, original)
            if original != replacement:
                index = strings.find(replacement.encode() + b'\0')
                if index < 0:
                    raise RuntimeError('Missing renamed version dependency ' + replacement)
                file.seek(offset + 4)
                file.write(struct.pack('<I', index))
            if not following:
                break
            offset += following


def package(abi, packages):
    arch, target = ABIS[abi]
    extracted = CACHE / abi / "root"
    extracted.mkdir(parents=True, exist_ok=True)
    def fetch(item):
        name, p = item
        file = download(BASE + p["Filename"], CACHE / abi / (name + ".deb"), p["SHA256"])
        return file
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        files = list(pool.map(fetch, packages.items()))
    for file in files:
        subprocess.run(["dpkg-deb", "-x", str(file), str(extracted)], check=True)
    prefix = extracted / "data/data/com.termux/files/usr"
    sources = {"libqemu.so": prefix / "bin" / ("qemu-system-" + target.replace("-", "_")),
               "libqemu_img.so": prefix / "bin/qemu-img"}
    for f in (prefix / "lib").glob("*.so*"):
        if f.is_file() and f.read_bytes()[:4] == b"\x7fELF":
            # Android's package manager extracts only lib*.so entries.
            name = re.sub(r"\.so(?:\..*)?$", ".so", f.name)
            sources.setdefault(name, f.resolve())
    aliases = {}
    for f in (prefix / "lib").glob("*.so*"):
        aliases[f.name] = re.sub(r"\.so(?:\..*)?$", ".so", f.name)
    dest = ROOT / "app/src/main/jniLibs" / abi
    dest.mkdir(parents=True, exist_ok=True)
    patchelf = shutil.which("patchelf") or str(Path.home() / ".local/bin/patchelf")
    system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
    # Bundle only the ELF dependency closure, not unrelated package executables.
    wanted = ["libqemu.so", "libqemu_img.so", "libusbredirhost.so"]
    done = set()
    while wanted:
        name = wanted.pop()
        if name in done:
            continue
        if name not in sources:
            raise RuntimeError("Missing shared library " + abi + ": " + name)
        output = dest / name
        shutil.copyfile(sources[name], output)
        output.chmod(0o755)
        needed = run(patchelf, "--print-needed", str(output)).splitlines()
        for dep in needed:
            alias = aliases.get(dep, dep)
            if dep != alias:
                subprocess.run([patchelf, "--replace-needed", dep, alias, str(output)], check=True)
            if alias not in system:
                wanted.append(alias)
        subprocess.run([patchelf, "--page-size", "16384", "--set-rpath", "$ORIGIN", str(output)], check=True)
        if run(patchelf, "--print-soname", str(output)):
            subprocess.run([patchelf, "--set-soname", name, str(output)], check=True)
        fix_version_dependencies(output, aliases)
        done.add(name)
    for f in dest.glob("*.so"):
        if f.name not in done and f.name != "libaausb.so" and not f.name.startswith("libaaterm_"):
            f.unlink()
    notices = ROOT / 'docker/licenses/termux'
    for doc in (prefix / 'share/doc').glob('*/*'):
        if doc.is_file() and doc.name.lower().startswith(('copying', 'copyright', 'license', 'licence')):
            if doc.stat().st_size > 1024 * 1024 or doc.suffix == '.html':
                continue
            target = notices / doc.relative_to(prefix / 'share/doc')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(doc, target)
    inventory = ROOT / 'docker' / ('qemu-libraries-' + abi + '.json')
    inventory.write_text(json.dumps({name: {'source': str(sources[name].relative_to(prefix)),
        'sha256': hashlib.sha256((dest / name).read_bytes()).hexdigest()} for name in sorted(done)}, indent=2) + '\n')
    license_assets = ROOT / 'app/src/main/assets/docker/licenses'
    shutil.copytree(ROOT / 'docker/licenses', license_assets, dirs_exist_ok=True)
    shutil.copyfile(ROOT / 'docker/SOURCES.md', ROOT / 'app/src/main/assets/docker/SOURCES.txt')
    assets = ROOT / "app/src/main/assets/docker" / abi / "qemu"
    assets.mkdir(parents=True, exist_ok=True)
    firmware = {"bios.bin", "bios-256k.bin", "kvmvapic.bin", "linuxboot.bin", "linuxboot_dma.bin", "multiboot.bin", "vgabios.bin", "vgabios-stdvga.bin"}
    for stale in sorted(assets.rglob("*"), reverse=True):
        if stale.is_file() and stale.name not in firmware:
            stale.unlink()
        elif stale.is_dir() and not any(stale.iterdir()):
            stale.rmdir()
    for f in (prefix / "share/qemu").glob("*"):
        if f.is_file() and f.name in firmware:
            output = assets / f.relative_to(prefix / "share/qemu")
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(f, output)
    print(f"{abi}: {len(done)} ELF files, {sum(f.stat().st_size for f in dest.glob('*.so')) // 1048576} MiB")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-lock", action="store_true")
    parser.add_argument("--abi", choices=list(ABIS), action="append")
    args = parser.parse_args()
    abis = args.abi or list(ABIS)
    if args.update_lock:
        locked = {abi: freeze(abi) for abi in ABIS}
        LOCK.parent.mkdir(parents=True, exist_ok=True)
        LOCK.write_text(json.dumps(locked, indent=2) + "\n")
    locked = json.loads(LOCK.read_text())
    for abi in abis:
        package(abi, locked[abi])
