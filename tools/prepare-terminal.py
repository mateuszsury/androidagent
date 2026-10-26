#!/usr/bin/env python3
"""Pin and package an embedded, unmodified official Termux userspace.

The bootstrap and Debian package closure remain at Termux's canonical prefix
inside PRoot. Only PRoot, its static exec loader and their ELF dependency closure
are installed as executable JNI files. No provider client or account is bundled.
Requires dpkg-deb, patchelf and pyelftools. --update-lock is a maintainer action.
"""
import argparse
import concurrent.futures
import gzip
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "build-runtime/terminal"
LOCK = ROOT / "terminal/runtime-lock.json"
BASE = "https://packages.termux.dev/apt/termux-main/"
ABIS = {"arm64-v8a": "aarch64", "x86_64": "x86_64"}
REQUESTED = ["bash", "coreutils", "jq", "curl", "nodejs-lts", "npm", "proot",
             "git", "python", "tar", "procps", "openssl", "ca-certificates",
             "resolv-conf", "unzip", "zip", "findutils", "grep", "sed", "libmount",
             "python-pip"]
ALPINE = "https://dl-cdn.alpinelinux.org/alpine/v3.23/main/"
PREFIX = "data/data/com.termux/files/usr"
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fetch(url, dest, sha256=None, force=False):
    if not force and dest.is_file() and (not sha256 or digest(dest) == sha256):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    temporary = dest.with_suffix(dest.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=120) as source, temporary.open("wb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
        if sha256 and digest(temporary) != sha256:
            raise RuntimeError("SHA256 mismatch for " + dest.name)
        temporary.replace(dest)
    finally:
        temporary.unlink(missing_ok=True)
    return dest


def github(path):
    return json.loads(subprocess.check_output(["gh", "api", path]))


def deb_index(data):
    result = {}
    for block in gzip.decompress(data).decode().split("\n\n"):
        fields = dict(line.split(": ", 1) for line in block.splitlines()
                      if ": " in line and not line.startswith(" "))
        if "Package" in fields:
            result[fields["Package"]] = fields
    return result


def installed_versions(prefix):
    status = prefix / "var/lib/dpkg/status"
    result = {}
    for block in status.read_text().split("\n\n"):
        fields = dict(line.split(": ", 1) for line in block.splitlines()
                      if ": " in line and not line.startswith(" "))
        if fields.get("Status") == "install ok installed":
            result[fields["Package"]] = fields["Version"]
    return dict(sorted(result.items()))


def overlay_packages(packages, staging, prefix):
    """Replace complete package payloads, not only files present in the new DEB.

    Bootstrap package splitting changes over time. Leaving an obsolete binary
    behind can retain a dependency that its replacement package no longer ships.
    Keep dpkg's installed inventory accurate without executing maintainer scripts.
    """
    all_paths, package_paths = set(), {}
    for package in packages:
        process = subprocess.Popen(["dpkg-deb", "--fsys-tarfile", str(package)], stdout=subprocess.PIPE)
        paths = []
        with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
            for item in archive:
                name = item.name.removeprefix("./").rstrip("/")
                if name:
                    paths.append("/" + name)
        process.stdout.close()
        if process.wait() != 0:
            raise RuntimeError("Cannot inventory " + package.name)
        package_paths[package.stem] = paths
        all_paths.update(paths)
    info = prefix / "var/lib/dpkg/info"
    for package in packages:
        old_list = info / (package.stem + ".list")
        if not old_list.exists():
            continue
        for path in old_list.read_text().splitlines():
            if not path.startswith("/" + PREFIX + "/") or path in all_paths:
                continue
            obsolete = staging / safe_relative(path.removeprefix("/"))
            if obsolete.is_symlink() or obsolete.is_file():
                obsolete.unlink()
    status = prefix / "var/lib/dpkg/status"
    blocks = {}
    for block in status.read_text().split("\n\n"):
        match = re.search(r"^Package: (.+)$", block, re.MULTILINE)
        if match:
            blocks[match.group(1)] = block
    for package in packages:
        subprocess.run(["dpkg-deb", "-x", str(package), str(staging)], check=True)
        control = subprocess.check_output(["dpkg-deb", "-f", str(package)], text=True).rstrip()
        name = re.search(r"^Package: (.+)$", control, re.MULTILINE).group(1)
        blocks[name] = control + "\nStatus: install ok installed"
        (info / (name + ".list")).write_text("\n".join(package_paths[package.stem]) + "\n")
        controls = staging.parent / "control" / name
        if controls.exists():
            shutil.rmtree(controls)
        controls.mkdir(parents=True, exist_ok=True)
        subprocess.run(["dpkg-deb", "-e", str(package), str(controls)], check=True)
        for item in controls.iterdir():
            if item.is_file() and item.name != "control":
                shutil.copyfile(item, info / (name + "." + item.name))
    status.write_text("\n\n".join(blocks[name] for name in sorted(blocks)) + "\n\n")


def deb_dependencies(fields, packages):
    for dependency in fields.get("Depends", "").split(","):
        choices = [re.split(r"[ (]", item.strip(), maxsplit=1)[0]
                   for item in dependency.split("|") if item.strip()]
        if not choices:
            continue
        # npm permits either Node release; the LTS is intentionally selected.
        if "nodejs-lts" in choices:
            yield "nodejs-lts"
            continue
        for choice in choices:
            if choice in packages:
                yield choice
                break
        else:
            raise RuntimeError("Unresolved dependency " + dependency)


def alpine_index(path):
    with tarfile.open(path, "r:gz") as archive:
        data = archive.extractfile("APKINDEX").read().decode()
    packages = {}
    for block in data.split("\n\n"):
        fields = dict(line.split(":", 1) for line in block.splitlines() if ":" in line)
        if "P" in fields:
            packages[fields["P"]] = fields
    return packages


def freeze():
    release = github("repos/termux/termux-packages/releases/latest")
    if not release["tag_name"].startswith("bootstrap-"):
        raise RuntimeError("Latest Termux release is not a bootstrap")
    recipes = github("repos/termux/termux-packages/commits/master")["sha"]
    result = {"version": 1, "prefix": "/" + PREFIX,
              "bootstrap_release": release["tag_name"], "recipes_commit": recipes,
              "requested_packages": REQUESTED, "abis": {}}
    assets = {asset["name"]: asset for asset in release["assets"]}
    for abi, arch in ABIS.items():
        asset = assets["bootstrap-" + arch + ".zip"]
        expected = asset.get("digest", "").removeprefix("sha256:")
        if not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise RuntimeError("Bootstrap asset has no published SHA256")
        bootstrap = {"url": asset["browser_download_url"], "sha256": expected,
                     "bytes": asset["size"], "asset_id": asset["id"]}
        index = fetch(BASE + f"dists/stable/main/binary-{arch}/Packages.gz",
                      CACHE / (arch + "-Packages.gz"), force=True)
        packages = deb_index(index.read_bytes())
        selected, pending = {}, list(REQUESTED)
        while pending:
            name = pending.pop()
            if name in selected:
                continue
            package = packages[name]
            selected[name] = {"version": package["Version"], "filename": package["Filename"],
                              "sha256": package["SHA256"],
                              "depends": list(deb_dependencies(package, packages))}
            pending.extend(selected[name]["depends"])
        alpine_arch = "aarch64" if arch == "aarch64" else "x86_64"
        alpine_base = ALPINE + alpine_arch + "/"
        alpine_packages = alpine_index(fetch(alpine_base + "APKINDEX.tar.gz",
                                             CACHE / (alpine_arch + "-APKINDEX.tar.gz"), force=True))
        musl = {}
        for name in ["musl", "libgcc", "libstdc++"]:
            package = alpine_packages[name]
            filename = name + "-" + package["V"] + ".apk"
            archive = fetch(alpine_base + filename, CACHE / abi / filename)
            musl[name] = {"version": package["V"], "url": alpine_base + filename,
                          "sha256": digest(archive), "bytes": archive.stat().st_size,
                          "license": package.get("L", ""), "origin": package.get("o", name),
                          "aports_commit": package.get("c", "")}
        result["abis"][abi] = {"bootstrap": bootstrap, "packages": dict(sorted(selected.items())),
                                "package_index_sha256": digest(index), "musl": musl}
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    LOCK.write_text(json.dumps(result, indent=2) + "\n")
    return result


def safe_relative(path):
    path = Path(path)
    if path.is_absolute() or ".." in path.parts:
        raise RuntimeError("Unsafe archive path " + str(path))
    return path


def extract_bootstrap(archive, prefix):
    with zipfile.ZipFile(archive) as source:
        links = source.read("SYMLINKS.txt").decode()
        for item in source.infolist():
            if item.filename == "SYMLINKS.txt":
                continue
            output = prefix / safe_relative(item.filename)
            if item.is_dir():
                output.mkdir(parents=True, exist_ok=True)
            else:
                output.parent.mkdir(parents=True, exist_ok=True)
                with source.open(item) as data, output.open("wb") as target:
                    shutil.copyfileobj(data, target)
                output.chmod((item.external_attr >> 16) & 0o777 or 0o644)
        for entry in links.splitlines():
            target, name = entry.split("←", 1)
            output = prefix / safe_relative(name)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.unlink(missing_ok=True)
            output.symlink_to(target)


def package_native(abi, prefix):
    module_spec = importlib.util.spec_from_file_location("prepare_qemu", ROOT / "tools/prepare-qemu.py")
    qemu = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(qemu)
    patchelf = shutil.which("patchelf") or str(Path.home() / ".local/bin/patchelf")
    sources = {"libaaterm_proot.so": prefix / "bin/proot",
               "libaaterm_loader.so": prefix / "libexec/proot/loader"}
    aliases = {}
    for path in (prefix / "lib").glob("*.so*"):
        if not path.is_file() or path.read_bytes()[:4] != b"\x7fELF":
            continue
        canonical = "libaaterm_" + re.sub(r"\.so(?:\..*)?$", ".so", path.name.removeprefix("lib"))
        aliases[path.name] = canonical
        sources.setdefault(canonical, path.resolve())
    destination = ROOT / "app/src/main/jniLibs" / abi
    destination.mkdir(parents=True, exist_ok=True)
    pending, inventory = ["libaaterm_proot.so", "libaaterm_loader.so"], {}
    while pending:
        name = pending.pop()
        if name in inventory:
            continue
        if name not in sources:
            raise RuntimeError("Missing native PRoot dependency " + name)
        source = sources[name]
        output = destination / name
        shutil.copyfile(source, output)
        output.chmod(0o755)
        # The PRoot loader is a static executable without a dynamic section.
        dynamic = subprocess.run([patchelf, "--print-needed", str(output)],
                                 text=True, capture_output=True)
        if dynamic.returncode == 0:
            for dependency in dynamic.stdout.splitlines():
                alias = aliases.get(dependency, dependency)
                if alias != dependency:
                    subprocess.run([patchelf, "--replace-needed", dependency, alias, str(output)], check=True)
                if alias not in SYSTEM_LIBS:
                    pending.append(alias)
            subprocess.run([patchelf, "--page-size", "16384", "--set-rpath", "$ORIGIN", str(output)], check=True)
            if subprocess.check_output([patchelf, "--print-soname", str(output)], text=True).strip():
                subprocess.run([patchelf, "--set-soname", name, str(output)], check=True)
            qemu.fix_version_dependencies(output, aliases)
        qemu.validate_elf_layout(output)
        inventory[name] = {"source": str(source.relative_to(prefix)), "sha256": digest(output),
                           "source_sha256": digest(source), "bytes": output.stat().st_size}
    for stale in destination.glob("libaaterm_*.so"):
        if stale.name not in inventory:
            stale.unlink()
    return dict(sorted(inventory.items()))


def extract_musl(archive, destination):
    # APKs concatenate signature/control/data gzip members. Read all tar blocks.
    payload = gzip.decompress(archive.read_bytes())
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:", ignore_zeros=True) as source:
        for item in source:
            if item.name.startswith("."):
                continue
            relative = safe_relative(item.name)
            if not relative.parts or relative.parts[0] not in {"lib", "usr"}:
                continue
            output = destination / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            if item.isdir():
                output.mkdir(parents=True, exist_ok=True)
            elif item.issym():
                output.unlink(missing_ok=True)
                output.symlink_to(item.linkname)
            elif item.isfile():
                with source.extractfile(item) as data, output.open("wb") as target:
                    shutil.copyfileobj(data, target)
                output.chmod(item.mode & 0o777)


def make_archive(prefix, output):
    links, executable_paths = [], []
    output.parent.mkdir(parents=True, exist_ok=True)
    # Keep incomplete output outside assets, even if an independent APK build
    # reads that directory concurrently. Publish only the complete ZIP atomically.
    temporary = CACHE / (output.parent.name + "-runtime.zip.part")
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for source in sorted(prefix.rglob("*")):
            relative = "usr/" + source.relative_to(prefix).as_posix()
            if source.is_symlink():
                links.append({"path": relative, "target": os.readlink(source)})
            elif source.is_dir():
                info = zipfile.ZipInfo(relative + "/", (2026, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = ((source.stat().st_mode & 0o777) | 0o040000) << 16
                archive.writestr(info, b"")
            elif source.is_file():
                info = zipfile.ZipInfo(relative, (2026, 1, 1, 0, 0, 0))
                info.create_system = 3
                info.external_attr = (source.stat().st_mode & 0o777) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with source.open("rb") as data, archive.open(info, "w", force_zip64=True) as target:
                    shutil.copyfileobj(data, target, length=1024 * 1024)
                if source.stat().st_mode & 0o111:
                    executable_paths.append(relative)
    temporary.replace(output)
    return links, executable_paths


def copy_notices(abi, prefix):
    output = ROOT / "terminal/licenses" / abi
    output.mkdir(parents=True, exist_ok=True)
    for folder in [prefix / "share/LICENSES", prefix / "share/doc"]:
        if not folder.exists():
            continue
        for source in folder.rglob("*"):
            if not source.is_file() or source.stat().st_size > 1024 * 1024:
                continue
            if folder.name == "doc" and not source.name.lower().startswith(("license", "licence", "copying", "copyright", "notice")):
                continue
            target = output / source.relative_to(prefix / "share")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)


def package(abi, lock):
    definition = lock["abis"][abi]
    work = CACHE / abi
    bootstrap_definition = definition["bootstrap"]
    bootstrap = fetch(bootstrap_definition["url"], work / "bootstrap.zip", bootstrap_definition["sha256"])
    if bootstrap.stat().st_size != bootstrap_definition["bytes"]:
        raise RuntimeError("Bootstrap length mismatch")
    staging = work / "root"
    if staging.exists():
        shutil.rmtree(staging)
    prefix = staging / PREFIX
    prefix.mkdir(parents=True)
    extract_bootstrap(bootstrap, prefix)
    bootstrap_versions = installed_versions(prefix)

    def download_package(item):
        name, metadata = item
        return fetch(BASE + metadata["filename"], work / (name + ".deb"), metadata["sha256"])

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        packages = list(pool.map(download_package, definition["packages"].items()))
    overlay_packages(packages, staging, prefix)
    # These upstream development tests need the separate compiler sanitizer
    # runtime, are not terminal tools, and would add a toolchain to the APK.
    installed_tests = prefix / "libexec/installed-tests"
    if installed_tests.exists():
        shutil.rmtree(installed_tests)
    # This APK supports only 64-bit terminal processes; the package also ships
    # a 32-bit companion loader for separate ARM/i686 installations.
    (prefix / "libexec/proot/loader32").unlink(missing_ok=True)
    musl_root = prefix / "opt/androidagent/musl"
    for name, metadata in definition["musl"].items():
        archive = fetch(metadata["url"], work / (name + "-musl.apk"), metadata["sha256"])
        extract_musl(archive, musl_root)
    # Keep bootstrap/package metadata and the canonical symlinks; do not run
    # maintainer scripts on the host. Nothing loads termux-exec automatically.
    native = package_native(abi, prefix)
    copy_notices(abi, prefix)
    assets = ROOT / "app/src/main/assets/terminal" / abi
    archive = assets / "runtime.zip"
    links, executable_paths = make_archive(prefix, archive)
    versions = {name: package["version"] for name, package in definition["packages"].items()}
    manifest = {"version": 1, "archive": "runtime.zip", "sha256": digest(archive),
                "bytes": archive.stat().st_size, "abi": abi, "prefix": "/" + PREFIX,
                "bootstrap_release": lock["bootstrap_release"], "symlinks": links,
                "executable_paths": executable_paths,
                "executables": {"proot": "libaaterm_proot.so", "loader": "libaaterm_loader.so"},
                "native": native, "packages": versions,
                "bootstrap_packages": bootstrap_versions,
                "musl": {name: metadata["version"] for name, metadata in definition["musl"].items()},
                "provider_clients_bundled": False}
    manifest["omitted_paths"] = ["usr/libexec/installed-tests", "usr/libexec/proot/loader32"]
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (ROOT / "terminal" / ("inventory-" + abi + ".json")).write_text(json.dumps({
        key: manifest[key] for key in ["version", "abi", "prefix", "bootstrap_release", "sha256", "bytes", "native", "packages", "bootstrap_packages", "musl", "provider_clients_bundled"]}, indent=2) + "\n")
    print(f"{abi}: {len(versions)} pinned packages, {len(native)} JNI files, {archive.stat().st_size} archive bytes", flush=True)


def prepare_sources(lock):
    """Retain entry recipes and a Git inventory of their complete directories."""
    commit = lock["recipes_commit"]
    cache = CACHE / ("recipes-tree-" + commit + ".json")
    if not cache.exists():
        cache.write_text(json.dumps(github("repos/termux/termux-packages/git/trees/" + commit + "?recursive=1")))
    tree = json.loads(cache.read_text())
    if tree.get("truncated"):
        raise RuntimeError("Truncated Termux source tree")
    files = {item["path"]: item for item in tree["tree"] if item["type"] == "blob"}
    names = set()
    for abi, definition in lock["abis"].items():
        names.update(definition["packages"])
        inventory = ROOT / "terminal" / ("inventory-" + abi + ".json")
        if inventory.exists():
            names.update(json.loads(inventory.read_text()).get("bootstrap_packages", {}))
    result, targets = {}, {}
    for name in sorted(names):
        candidate = "packages/" + name + "/build.sh"
        if candidate not in files:
            alternatives = [path for path in files if path.endswith("/" + name + ".subpackage.sh")]
            if len(alternatives) != 1:
                raise RuntimeError("Cannot locate pinned source recipe " + name)
            candidate = alternatives[0]
        directory = Path(candidate).parent.as_posix()
        targets[candidate] = name
        result[name] = {"recipe": candidate,
                        "url": "https://github.com/termux/termux-packages/tree/" + commit + "/" + directory,
                        "recipe_files": [{"path": path, "git_blob_sha": files[path]["sha"]}
                                         for path in sorted(files) if path.startswith(directory + "/")]}

    def read_recipe(item):
        path, name = item
        url = "https://raw.githubusercontent.com/termux/termux-packages/" + commit + "/" + path
        output = ROOT / "terminal/recipes" / safe_relative(path)
        expected = files[path]["sha"]
        data = output.read_bytes() if output.exists() else b""
        git_hash = lambda payload: hashlib.sha1(b"blob " + str(len(payload)).encode() + b"\0" + payload).hexdigest()
        if git_hash(data) != expected:
            fetch(url, output, force=True)
            data = output.read_bytes()
        if git_hash(data) != expected:
            raise RuntimeError("Recipe Git hash mismatch " + path)
        fields = {}
        for field in ["VERSION", "LICENSE", "SRCURL", "SHA256", "HOMEPAGE", "REVISION"]:
            match = re.search(r"^TERMUX_PKG_" + field + r"=(.*)$", data.decode(), re.MULTILINE)
            if match:
                fields[field.lower()] = match.group(1)
        return name, {"recipe_sha256": hashlib.sha256(data).hexdigest(),
                      "declared_fields": fields, "source_url": url}

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for name, metadata in pool.map(read_recipe, targets.items()):
            result[name].update(metadata)
    (ROOT / "terminal/recipes-metadata.json").write_text(json.dumps(
        {"recipes_commit": commit, "packages": result}, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-lock", action="store_true")
    parser.add_argument("--abi", action="append", choices=list(ABIS))
    args = parser.parse_args()
    lock = freeze() if args.update_lock else json.loads(LOCK.read_text())
    license_sources = ROOT / "terminal/license-sources.json"
    if license_sources.exists():
        for name, metadata in json.loads(license_sources.read_text()).items():
            output = ROOT / "terminal/licenses/shared" / name
            if metadata.get("member"):
                archive = fetch(metadata["url"], CACHE / Path(metadata["url"]).name,
                                metadata["archive_sha256"])
                with tarfile.open(archive, "r:gz") as source:
                    data = source.extractfile(metadata["member"]).read()
                if hashlib.sha256(data).hexdigest() != metadata["sha256"]:
                    raise RuntimeError("License SHA256 mismatch " + name)
                output.parent.mkdir(parents=True, exist_ok=True)
                output.write_bytes(data)
            else:
                fetch(metadata["url"], output, metadata["sha256"])
    for abi in args.abi or list(ABIS):
        package(abi, lock)
    if args.update_lock or not (ROOT / "terminal/recipes-metadata.json").exists():
        prepare_sources(lock)
    licenses = ROOT / "app/src/main/assets/terminal/licenses"
    shutil.copytree(ROOT / "terminal/licenses", licenses, dirs_exist_ok=True)
    sources = ROOT / "terminal/SOURCES.md"
    if sources.exists():
        shutil.copyfile(sources, ROOT / "app/src/main/assets/terminal/SOURCES.txt")
    shutil.copyfile(LOCK, ROOT / "app/src/main/assets/terminal/runtime-lock.json")
    shutil.copyfile(ROOT / "terminal/clients-lock.json", ROOT / "app/src/main/assets/terminal/clients-lock.json")


if __name__ == "__main__":
    main()
