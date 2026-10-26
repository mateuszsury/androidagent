"""Reject the unmapped dynamic string table that crashed Android's linker."""
import importlib.util
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
from elftools.elf.elffile import ELFFile

SPEC = importlib.util.spec_from_file_location('qemu_elf_layout', Path(__file__).resolve().parents[1] / 'tools/prepare-qemu.py')
QEMU = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QEMU)

@unittest.skipUnless(shutil.which('cc'), 'A host C compiler is required')
class ElfLayoutTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        source = root / 'library.c'
        source.write_text('int answer(void) { return 42; }\n')
        self.library = root / 'library.so'
        subprocess.run(['cc', '-shared', '-fPIC', str(source), '-o', str(self.library)], check=True, capture_output=True)

    def test_compiled_shared_library_has_mapped_dynamic_strings(self):
        QEMU.validate_elf_layout(self.library)

    def test_dynamic_string_pointer_beyond_load_segments_is_rejected(self):
        with self.library.open('r+b') as stream:
            elf = ELFFile(stream)
            dynamic = next(s for s in elf.iter_segments() if s['p_type'] == 'PT_DYNAMIC')
            width = 8 if elf.elfclass == 64 else 4
            fmt = ('<' if elf.little_endian else '>') + ('Q' if width == 8 else 'I')
            for index, tag in enumerate(dynamic.iter_tags()):
                if tag.entry.d_tag == 'DT_STRTAB':
                    stream.seek(dynamic['p_offset'] + index * 2 * width + width)
                    stream.write(struct.pack(fmt, 0x7fff0000))
                    break
            else:
                self.fail('Compiler fixture has no dynamic string table')
        with self.assertRaisesRegex(RuntimeError, 'Unmapped ELF dynamic string table'):
            QEMU.validate_elf_layout(self.library)

if __name__ == '__main__':
    unittest.main()
