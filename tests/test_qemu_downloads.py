"""Pinned builds reject changed bytes; explicit lock refreshes bypass old indexes."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    'qemu_downloads', Path(__file__).resolve().parents[1] / 'tools/prepare-qemu.py')
QEMU = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QEMU)


class DownloadTests(unittest.TestCase):
    def test_explicit_index_refresh_replaces_an_existing_cached_index(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'Packages.gz'
            destination.write_bytes(b'old package index')
            with patch.object(QEMU.urllib.request, 'urlopen', return_value=io.BytesIO(b'current index')) as request:
                QEMU.download('https://example.invalid/Packages.gz', destination, force=True)
            self.assertEqual(b'current index', destination.read_bytes())
            request.assert_called_once()

    def test_digest_failure_does_not_replace_the_cached_file(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'package.deb'
            destination.write_bytes(b'previous download')
            expected = hashlib.sha256(b'pinned package').hexdigest()
            with patch.object(QEMU.urllib.request, 'urlopen', return_value=io.BytesIO(b'changed package')):
                with self.assertRaisesRegex(RuntimeError, 'SHA256 mismatch'):
                    QEMU.download('https://example.invalid/package.deb', destination, expected)
            self.assertEqual(b'previous download', destination.read_bytes())


if __name__ == '__main__':
    unittest.main()
