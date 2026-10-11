#!/usr/bin/env python3
"""Fail a full APK build rather than silently shipping a nonfunctional Docker UI."""
import hashlib
import json
from pathlib import Path

ABIS = ('arm64-v8a', 'x86_64')
GUEST_FILES = ('base.qcow2.gz', 'vmlinuz-virt', 'initramfs-virt')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def verify(repository):
    root = Path(repository) / 'app/src/main'
    usb_manifest = json.loads((Path(repository) / 'usb/native-manifest.json').read_text())
    for abi in ABIS:
        assets = root / 'assets/docker' / abi
        native = root / 'jniLibs' / abi
        manifest = json.loads((assets / 'manifest.json').read_text())
        if not all(name in manifest['files'] for name in GUEST_FILES):
            raise ValueError(f'{abi}: guest manifest must include disk, kernel and initramfs')
        if manifest.get('runtime_revision') != 2:
            raise ValueError(f'{abi}: prepare current guest runtime in initramfs first')
        runtime = manifest.get('runtime', {}).get('files', {})
        if not all(name in runtime for name in ('init.sh', 'agent.py', 'engine.py')):
            raise ValueError(f'{abi}: guest runtime manifest must include init, agent and engine supervisor')
        for name, expected in runtime.items():
            if digest(Path(repository) / 'docker/guest' / name) != expected:
                raise ValueError(f'{abi}: stale packaged guest runtime {name}')
        for name, expected in manifest['files'].items():
            if digest(assets / name) != expected:
                raise ValueError(f'{abi}/{name}: SHA256 mismatch')
        libraries = json.loads((Path(repository) / 'docker' / ('qemu-libraries-' + abi + '.json')).read_text())
        for name in ('libqemu.so', 'libqemu_img.so', 'libusbredirhost.so'):
            if name not in libraries:
                raise ValueError(f'Missing pinned runtime {abi}/{name}')
        for name, metadata in libraries.items():
            if digest(native / name) != metadata['sha256']:
                raise ValueError(f'Invalid {abi}/{name}')
        if digest(native / 'libaausb.so') != usb_manifest[abi]:
            raise ValueError(f'Invalid USB JNI for {abi}')
        if not (assets / 'qemu').is_dir() or not any((assets / 'qemu').iterdir()):
            raise ValueError(f'Missing {abi} firmware')
        if abi == 'x86_64':
            for name in ('bios-256k.bin', 'kvmvapic.bin', 'linuxboot_dma.bin'):
                if not (assets / 'qemu' / name).is_file() or not (assets / 'qemu' / name).stat().st_size:
                    raise ValueError(f'Missing {abi} firmware {name}')


if __name__ == '__main__':
    try:
        verify(Path(__file__).resolve().parents[1])
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(f'{error}\nPrepare QEMU and both Docker guests first; see docs/DOCKER.md.')
    print('Docker guest images and both native runtimes verified')
