#!/usr/bin/env python3
"""Verify pinned vendored sources and Android JNI runtime before APK packaging."""
import hashlib
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
for path, expected in json.loads((root/'usb/source-manifest.json').read_text()).items():
    assert hashlib.sha256((root/path).read_bytes()).hexdigest() == expected, f'USB source changed: {path}; review and update source-manifest.json'
for abi, expected in json.loads((root/'usb/native-manifest.json').read_text()).items():
    p = root/'app/src/main/jniLibs'/abi/'libaausb.so'
    assert p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == expected, f'Rebuild USB JNI for {abi} with tools/build-usb-native.sh'
print('Pinned USB sources and both JNI libraries verified')
