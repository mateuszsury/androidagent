#!/usr/bin/env python3
"""Verify pinned vendored sources and Android JNI runtime before APK packaging."""
import hashlib
import json
from pathlib import Path


def verify(repository):
    root = Path(repository)
    sources = json.loads((root / 'usb/source-manifest.json').read_text())
    if not sources:
        raise ValueError('USB source manifest is empty')
    for path, expected in sources.items():
        if hashlib.sha256((root / path).read_bytes()).hexdigest() != expected:
            raise ValueError(f'USB source changed: {path}; review and update source-manifest.json')
    native = json.loads((root / 'usb/native-manifest.json').read_text())
    for abi in ('arm64-v8a', 'x86_64'):
        p = root / 'app/src/main/jniLibs' / abi / 'libaausb.so'
        if not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest() != native.get(abi):
            raise ValueError(f'Rebuild USB JNI for {abi} with tools/build-usb-native.sh')


if __name__ == '__main__':
    try:
        verify(Path(__file__).resolve().parents[1])
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(str(error))
    print('Pinned USB sources and both JNI libraries verified')
