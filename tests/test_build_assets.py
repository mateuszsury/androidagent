"""Build gates must reject incomplete/corrupt offline runtimes, including with -O."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


DOCKER = load('check-docker-assets')
USB = load('check-usb-assets')


class BuildAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'usb/source.c'
        self.write(self.source, b'pinned source')
        self.json('usb/source-manifest.json', {'usb/source.c': self.sha(self.source)})
        native = {}
        runtime = {}
        for name in ('init.sh', 'agent.py', 'engine.py'):
            source = self.root / 'docker/guest' / name
            self.write(source, ('owned runtime ' + name).encode())
            runtime[name] = self.sha(source)
        for abi in ('arm64-v8a', 'x86_64'):
            assets = self.root / 'app/src/main/assets/docker' / abi
            files = {}
            for name in DOCKER.GUEST_FILES:
                self.write(assets / name, name.encode())
                files[name] = self.sha(assets / name)
            self.json(assets / 'manifest.json', {'files': files, 'runtime_revision': 2, 'runtime': {'files': runtime}})
            for name in ('bios-256k.bin', 'kvmvapic.bin', 'linuxboot_dma.bin'):
                self.write(assets / 'qemu' / name, b'firmware')
            libraries = {}
            for name in ('libqemu.so', 'libqemu_img.so', 'libusbredirhost.so', 'libaausb.so'):
                path = self.root / 'app/src/main/jniLibs' / abi / name
                self.write(path, (abi + name).encode())
                if name == 'libaausb.so':
                    native[abi] = self.sha(path)
                else:
                    libraries[name] = {'sha256': self.sha(path)}
            self.json('docker/qemu-libraries-' + abi + '.json', libraries)
        self.json('usb/native-manifest.json', native)

    @staticmethod
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def json(self, path, value):
        self.write(self.root / path, json.dumps(value).encode())

    def test_complete_runtimes_pass(self):
        DOCKER.verify(self.root)
        USB.verify(self.root)

    def test_corrupt_guest_is_rejected(self):
        self.write(self.root / 'app/src/main/assets/docker/x86_64/base.qcow2.gz', b'corrupt')
        with self.assertRaisesRegex(ValueError, 'SHA256 mismatch'):
            DOCKER.verify(self.root)

    def test_manifest_cannot_omit_guest_files(self):
        self.json('app/src/main/assets/docker/arm64-v8a/manifest.json', {'files': {}, 'runtime_revision': 2})
        with self.assertRaisesRegex(ValueError, 'disk, kernel and initramfs'):
            DOCKER.verify(self.root)

    def test_missing_boot_firmware_is_rejected(self):
        (self.root / 'app/src/main/assets/docker/x86_64/qemu/bios-256k.bin').unlink()
        with self.assertRaisesRegex(ValueError, 'firmware bios-256k.bin'):
            DOCKER.verify(self.root)

    def test_runtime_must_be_pinned(self):
        self.json('docker/qemu-libraries-x86_64.json', {})
        with self.assertRaisesRegex(ValueError, 'Missing pinned runtime'):
            DOCKER.verify(self.root)

    def test_guest_supervisor_source_change_requires_repack(self):
        self.write(self.root / 'docker/guest/engine.py', b'changed supervisor')
        with self.assertRaisesRegex(ValueError, 'stale packaged guest runtime engine.py'):
            DOCKER.verify(self.root)

    def test_native_manifest_cannot_omit_supported_abi(self):
        self.json('usb/native-manifest.json', {})
        with self.assertRaisesRegex(ValueError, 'Rebuild USB JNI'):
            USB.verify(self.root)

    def test_corrupt_usb_source_is_rejected(self):
        self.source.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'USB source changed'):
            USB.verify(self.root)

    def test_missing_jni_is_rejected(self):
        (self.root / 'app/src/main/jniLibs/x86_64/libaausb.so').unlink()
        with self.assertRaisesRegex(ValueError, 'Rebuild USB JNI'):
            USB.verify(self.root)

    def test_optimized_python_does_not_disable_integrity_checks(self):
        self.source.write_bytes(b'changed')
        self.write(self.root / 'app/src/main/assets/docker/x86_64/base.qcow2.gz', b'corrupt')
        for script in ('check-docker-assets.py', 'check-usb-assets.py'):
            with self.subTest(script=script):
                result = subprocess.run([
                    sys.executable, '-O', '-c',
                    'import runpy,sys; runpy.run_path(sys.argv[1])["verify"](sys.argv[2])',
                    str(ROOT / 'tools' / script), str(self.root),
                ], capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('ValueError', result.stderr)


if __name__ == '__main__':
    unittest.main()
