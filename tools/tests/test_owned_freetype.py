import hashlib
import json
import tempfile
import unittest
import sys
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/native"))
import build_freetype as freetype


class OwnedFreeTypeValidationTest(unittest.TestCase):
    def test_elf_check_accepts_android_system_libm_and_libdl(self):
        elf = """Machine: Advanced Micro Devices X86-64
 (SONAME) Library soname: [libfreetype.so]
 (NEEDED) Shared library: [libz.so.1]
 (NEEDED) Shared library: [libbz2.so.1.0]
 (NEEDED) Shared library: [libpng16.so]
 (NEEDED) Shared library: [libbrotlidec.so]
 (NEEDED) Shared library: [libc.so]
 (NEEDED) Shared library: [libm.so]
 (NEEDED) Shared library: [libdl.so]
"""
        freetype.check_elf(elf, "x86_64")

    def test_elf_check_rejects_host_dependency(self):
        elf = """Machine: Advanced Micro Devices X86-64
 (SONAME) Library soname: [libfreetype.so]
 (NEEDED) Shared library: [libz.so.1]
 (NEEDED) Shared library: [libbz2.so.1.0]
 (NEEDED) Shared library: [libpng16.so]
 (NEEDED) Shared library: [libbrotlidec.so]
 (NEEDED) Shared library: [libstdc++.so]
"""
        with self.assertRaises(ValueError):
            freetype.check_elf(elf, "x86_64")

    def test_stale_recipe_is_rejected_without_replacing_library(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            recipe = directory / "recipes/build_freetype.py"
            recipe.parent.mkdir()
            recipe.write_bytes(b"recipe")
            prep = directory / "recipes/prepare_source_inputs.py"
            prep.write_bytes(b"prepare")
            notice = directory / "notices/copyright"
            notice.parent.mkdir(); notice.write_bytes(b"notice")
            library = directory / "libfreetype.so"
            library.write_bytes(b"\x7fELF\x02\x01" + b"\0" * 14 + (62).to_bytes(2, "little"))
            archive = directory / "upstream.tar.xz"
            archive.write_bytes(b"archive")
            spec = {"recipes": {"build_freetype.py": hashlib.sha256(b"recipe").hexdigest(),
                                "prepare_source_inputs.py": hashlib.sha256(b"prepare").hexdigest()},
                    "patches": []}
            record = {"format": 1, "kind": "project-built", "abi": "x86_64", "specification": spec,
                      "toolchain": {"files": {"tool": "hash"}}, "dependencies": list(freetype.DEPENDENCIES),
                      "materials": {"notices/copyright": hashlib.sha256(b"notice").hexdigest()},
                      "dependencyHashes": {}, "librarySha256": hashlib.sha256(library.read_bytes()).hexdigest()}
            (directory / "build.json").write_text(json.dumps(record))
            with patch.object(freetype, "specification", return_value=spec):
                freetype.validate_materials(directory, "x86_64")
                original = library.read_bytes()
                recipe.write_bytes(b"stale")
                with self.assertRaises(ValueError):
                    freetype.validate_materials(directory, "x86_64")
                self.assertEqual(original, library.read_bytes())


if __name__ == "__main__":
    unittest.main()
