import struct
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1] / 'native'))
from elf_dependencies import read_elf64_dependencies


EH = struct.Struct('<16sHHIQQQIHHHHHH')
PH = struct.Struct('<IIQQQQQQ')
DY = struct.Struct('<qQ')


def fixture(*, needed=True, dynamic=True, strsz=True, bad_offset=False,
            unterminated=False, bss_string=False, overlap=False,
            version=1, elf_class=2, endian=1, machine=62, elf_type=3,
            duplicate_dynamic=False, missing_strtab=False,
            conflicting_strsz=False, strsz_value=0x40, no_load=False,
            oversized_phdr=False, truncated_dynamic=False):
    data = bytearray(0x800)
    ident = b'\x7fELF' + bytes([2, 1, 1]) + bytes(9)
    phnum = (3 if duplicate_dynamic else 2) if dynamic else 1
    if oversized_phdr:
        phnum = 20
    ident = b'\x7fELF' + bytes([elf_class, endian, 1]) + bytes(9)
    data[:EH.size] = EH.pack(ident, elf_type, machine, version, 0, EH.size, 0, 0,
                             EH.size, PH.size, phnum, 0, 0, 0)
    load_filesz = 0x500
    data[EH.size:EH.size + PH.size] = PH.pack(4 if no_load else 1, 5, 0, 0x400000,
                                               0, load_filesz, 0x700, 0x1000)
    if not dynamic:
        return data
    dyn_off = 0x100
    dynamic_size = 0x81 if truncated_dynamic else 0x80
    data[EH.size + PH.size:EH.size + 2 * PH.size] = PH.pack(
        2, 6, dyn_off, 0x400100, 0, dynamic_size, dynamic_size, 8)
    if duplicate_dynamic:
        data[EH.size + 2 * PH.size:EH.size + 3 * PH.size] = PH.pack(
            2, 6, dyn_off, 0x400100, 0, dynamic_size, dynamic_size, 8)
    entries = []
    if needed:
        entries.append((1, (10 if unterminated else 1) if not bad_offset else 0x1000))
    if not missing_strtab:
        entries.append((5, 0x400400))
    if strsz:
        entries.append((10, strsz_value))
        if conflicting_strsz:
            entries.append((10, strsz_value + 1))
    entries.append((0, 0))
    for index, entry in enumerate(entries):
        data[dyn_off + index * DY.size:dyn_off + (index + 1) * DY.size] = DY.pack(*entry)
    strings = b'\0libc.so\0libm.so\0'
    if unterminated:
        strings = b'\0libc.so\0libm.so'
    if bss_string:
        data[0x400:0x400 + len(strings)] = strings
        data[0x400 + len(strings):0x400 + len(strings) + 1] = b'X'
        # Point the string table into the PT_LOAD BSS, outside p_filesz.
        data[dyn_off + (1 if needed else 0) * DY.size:dyn_off + (2 if needed else 1) * DY.size] = DY.pack(5, 0x400600)
    else:
        data[0x400:0x400 + len(strings)] = strings
    if unterminated:
        data[0x400 + len(strings):0x440] = b'X' * (0x40 - len(strings))
    if overlap:
        # A second PT_LOAD covering the same virtual string address makes the mapping ambiguous.
        data[EH.size + 2 * PH.size:EH.size + 3 * PH.size] = PH.pack(1, 4, 0x200, 0x400300, 0, 0x300, 0x300, 0x1000)
        data[:EH.size] = EH.pack(ident, 3, 62, 1, 0, EH.size, 0, 0,
                                 EH.size, PH.size, 3, 0, 0, 0)
    return data


class ELFDependenciesTest(unittest.TestCase):
    def check(self, data, expected=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'fixture.elf'
            path.write_bytes(data)
            if expected is None:
                with self.assertRaises(ValueError):
                    read_elf64_dependencies(path)
            else:
                self.assertEqual(read_elf64_dependencies(path).needed, expected)

    def test_sectionless_positive_then_single_mutations_fail(self):
        self.check(fixture(), ('libc.so',))
        for kwargs in ({'bad_offset': True}, {'unterminated': True},
                       {'strsz': False}, {'bss_string': True}, {'overlap': True}):
            with self.subTest(**kwargs):
                self.check(fixture(**kwargs))

    def test_static_program_load_is_valid(self):
        self.check(fixture(dynamic=False), ())

    def test_header_only_and_truncated_tables_fail(self):
        self.check(bytes(20))
        data = fixture()
        self.check(data[:EH.size + PH.size])

    def test_duplicate_dynamic_and_missing_null_fail(self):
        self.check(fixture(duplicate_dynamic=True))
        data = fixture()
        dyn_off = 0x100
        for index in range(0x80 // DY.size):
            data[dyn_off + index * DY.size:dyn_off + (index + 1) * DY.size] = DY.pack(1, 1)
        self.check(data)

    def test_header_and_mapping_mutations_fail(self):
        for kwargs in ({'version': 0}, {'elf_class': 1}, {'endian': 2},
                       {'machine': 40}, {'elf_type': 1}, {'no_load': True},
                       {'oversized_phdr': True}, {'truncated_dynamic': True},
                       {'missing_strtab': True}, {'conflicting_strsz': True},
                       {'strsz_value': 0x300}):
            with self.subTest(**kwargs):
                self.check(fixture(**kwargs))

    def test_dynamic_segment_must_be_file_backed_by_load(self):
        data = fixture()
        data[EH.size + PH.size:EH.size + 2 * PH.size] = PH.pack(
            2, 6, 0x700, 0x400700, 0, 0x80, 0x80, 8)
        self.check(data)

    def test_partial_overlapping_load_is_rejected(self):
        data = fixture()
        data[EH.size + 2 * PH.size:EH.size + 3 * PH.size] = PH.pack(
            1, 4, 0x430, 0x400430, 0, 0x20, 0x20, 0x1000)
        data[:EH.size] = EH.pack(b'\x7fELF' + bytes([2, 1, 1]) + bytes(9),
                                 3, 62, 1, 0, EH.size, 0, 0,
                                 EH.size, PH.size, 3, 0, 0, 0)
        self.check(data)


if __name__ == '__main__':
    unittest.main()
