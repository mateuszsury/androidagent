#!/usr/bin/env python3
"""Fail a full APK build rather than silently shipping a nonfunctional Docker UI."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'app/src/main'
for abi in ['arm64-v8a', 'x86_64']:
    assets = root / 'assets/docker' / abi
    native = root / 'jniLibs' / abi
    try:
        manifest = json.loads((assets / 'manifest.json').read_text())
        for name, expected in manifest['files'].items():
            with (assets / name).open('rb') as file:
                digest = hashlib.sha256()
                for block in iter(lambda: file.read(1024 * 1024), b''):
                    digest.update(block)
                actual = digest.hexdigest()
            assert actual == expected, f'{abi}/{name}: SHA256 mismatch'
        for name in ['libqemu.so', 'libqemu_img.so']:
            assert (native / name).is_file(), f'Missing {abi}/{name}'
        libraries = json.loads((root.parents[2] / 'docker' / ('qemu-libraries-' + abi + '.json')).read_text())
        for name, metadata in libraries.items():
            assert hashlib.sha256((native / name).read_bytes()).hexdigest() == metadata['sha256'], f'Invalid {abi}/{name}'
        usb_manifest = json.loads((root.parents[2] / 'usb/native-manifest.json').read_text())
        assert hashlib.sha256((native / 'libaausb.so').read_bytes()).hexdigest() == usb_manifest[abi], f'Invalid USB JNI for {abi}'
        assert (assets / 'qemu').is_dir(), f'Missing {abi} firmware'
    except (OSError, AssertionError) as error:
        raise SystemExit(f'{error}\nPrepare QEMU and both Docker guests first; see docs/DOCKER.md.')
print('Docker guest images and both native runtimes verified')
