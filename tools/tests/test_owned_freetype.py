import hashlib
import json
import os
import tempfile
import unittest
import sys
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/native"))
import build_freetype as freetype


class OwnedFreeTypeValidationTest(unittest.TestCase):
    def make_prefix(self, root):
        prefix = root / "prefix"
        (prefix / "include").mkdir(parents=True)
        (prefix / "lib/pkgconfig").mkdir(parents=True)
        (prefix / "include/freetype.h").write_bytes(b"header")
        (prefix / "lib/pkgconfig/freetype2.pc").write_bytes(b"pc")
        aliases = {"libz.so": b"z", "libbz2.so": b"bz2", "libpng16.so": b"png", "libbrotlidec.so": b"brotli"}
        for name, content in aliases.items():
            (prefix / "lib" / name).write_bytes(content)
        return prefix

    def pkg_config(self, prefix):
        path = prefix.parent / "pkg-config"
        path.write_bytes(b"pkg-config")
        return path

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
                    "referenceRecipe": {"sha256": hashlib.sha256(b"reference").hexdigest()},
                    "patches": []}
            reference = directory / "recipes/termux-reference-build.sh"
            reference.write_bytes(b"reference")
            record = {"format": 1, "kind": "project-built", "abi": "x86_64", "specification": spec,
                      "toolchain": {"files": {"tool": "hash"}}, "dependencies": list(freetype.DEPENDENCIES),
                      "materials": {"notices/copyright": hashlib.sha256(b"notice").hexdigest()},
                      "dependencyHashes": {name: {} for name in freetype.DEPENDENCIES},
                      "dependencyInputs": {"sha256": "fingerprint"},
                      "librarySha256": hashlib.sha256(library.read_bytes()).hexdigest()}
            (directory / "build.json").write_text(json.dumps(record))
            with patch.object(freetype, "specification", return_value=spec):
                freetype.validate_materials(directory, "x86_64")
                original = library.read_bytes()
                recipe.write_bytes(b"stale")
                with self.assertRaises(ValueError):
                    freetype.validate_materials(directory, "x86_64")
                self.assertEqual(original, library.read_bytes())

    def test_dependency_header_change_changes_fingerprint(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = self.make_prefix(Path(root))
            before = freetype.dependency_inputs(prefix, self.pkg_config(prefix))
            (prefix / "include/freetype.h").write_bytes(b"changed")
            self.assertNotEqual(before, freetype.dependency_inputs(prefix, self.pkg_config(prefix)))

    def test_dependency_alias_change_changes_fingerprint(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = self.make_prefix(Path(root))
            before = freetype.dependency_inputs(prefix, self.pkg_config(prefix))
            (prefix / "lib/libz.so").write_bytes(b"different-target")
            self.assertNotEqual(before, freetype.dependency_inputs(prefix, self.pkg_config(prefix)))

    def test_actual_png_link_alias_change_changes_fingerprint(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = self.make_prefix(Path(root))
            (prefix / "lib/libpng.so").write_bytes(b"png-link")
            (prefix / "lib/libpng16.so").write_bytes(b"png-soname")
            before = freetype.dependency_inputs(prefix, self.pkg_config(prefix))
            (prefix / "lib/libpng.so").write_bytes(b"changed-link")
            self.assertNotEqual(before, freetype.dependency_inputs(prefix, self.pkg_config(prefix)))

    def test_complete_cache_reuse_then_stale_inputs_preserve_output(self):
        # Only the source specification/toolchain are synthetic. Dependency
        # enumeration, material validation and build's existing-output path run.
        for mutation in ("dependencyHashes", "header", "pngAlias", "pkgConfig"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                root = Path(root)
                prefix = self.make_prefix(root)
                for name in freetype.DEPENDENCIES:
                    (prefix / "lib" / name).write_bytes(name.encode())
                (prefix / "lib/libpng.so").write_bytes(b"actual-png-link")
                pkg_config = self.pkg_config(prefix)
                directory = root / "output"
                (directory / "recipes").mkdir(parents=True)
                for name, content in (("build_freetype.py", b"recipe"),
                                      ("prepare_source_inputs.py", b"prepare"),
                                      ("termux-reference-build.sh", b"reference")):
                    (directory / "recipes" / name).write_bytes(content)
                (directory / "notices").mkdir()
                (directory / "notices/copyright").write_bytes(b"notice")
                (directory / "upstream.tar.xz").write_bytes(b"archive")
                header = bytearray(64)
                header[:6] = b"\x7fELF\x02\x01"
                header[18:20] = (62).to_bytes(2, "little")
                (directory / "libfreetype.so").write_bytes(header)
                spec = {
                    "recipes": {name: freetype.sha256(directory / "recipes" / name)
                                for name in freetype.RECIPE_FILES},
                    "referenceRecipe": {"sha256": freetype.sha256(directory / "recipes/termux-reference-build.sh")},
                    "upstream": {"sha256": freetype.sha256(directory / "upstream.tar.xz")},
                }
                compiler = {"files": {"tool": "hash"}}
                inputs = freetype.dependency_inputs(prefix, pkg_config)
                record = {
                    "format": 1, "kind": "project-built", "abi": "x86_64",
                    "specification": spec, "toolchain": compiler,
                    "dependencyPrefix": str(prefix.resolve()),
                    "dependencies": list(freetype.DEPENDENCIES),
                    "dependencyHashes": {name: {"libraryAliasesSha256": inputs["sha256"]}
                                         for name in freetype.DEPENDENCIES},
                    "dependencyInputs": inputs,
                    "materials": {"notices/copyright": freetype.sha256(directory / "notices/copyright")},
                    "librarySha256": freetype.sha256(directory / "libfreetype.so"),
                }
                manifest = directory / "build.json"
                manifest.write_text(json.dumps(record))
                with patch.object(freetype, "specification", return_value=spec), \
                     patch.object(freetype, "toolchain", return_value=compiler), \
                     patch.object(freetype.shutil, "which", return_value=str(pkg_config)), \
                     patch.object(freetype, "fetch_checked", side_effect=AssertionError("cache reuse fetched source")):
                    self.assertEqual(record, freetype.validate_output(directory, "x86_64", root / "ndk", "cmake", prefix))
                    self.assertEqual(directory, freetype.build(directory, "x86_64", root / "ndk", dependencies=prefix))
                    if mutation == "dependencyHashes":
                        del record["dependencyHashes"][freetype.DEPENDENCIES[0]]
                        manifest.write_text(json.dumps(record))
                        error = "dependency closure"
                    else:
                        target = {"header": prefix / "include/freetype.h",
                                  "pngAlias": prefix / "lib/libpng.so",
                                  "pkgConfig": pkg_config}[mutation]
                        target.write_bytes(b"changed-input")
                        error = "Dependency headers, pkg-config files, or linker aliases changed"
                    before = {str(p.relative_to(directory)): p.read_bytes()
                              for p in directory.rglob("*") if p.is_file()}
                    with self.assertRaisesRegex(ValueError, error):
                        freetype.build(directory, "x86_64", root / "ndk", dependencies=prefix)
                    after = {str(p.relative_to(directory)): p.read_bytes()
                             for p in directory.rglob("*") if p.is_file()}
                    self.assertEqual(before, after)

    def test_reference_recipe_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            (directory / "recipes").mkdir()
            path = directory / "recipes/termux-reference-build.sh"
            path.write_bytes(b"reference")
            spec = {"recipes": {}, "referenceRecipe": {"sha256": hashlib.sha256(b"changed").hexdigest()}}
            record = {"format": 1, "kind": "project-built", "abi": "x86_64", "specification": spec,
                      "dependencies": list(freetype.DEPENDENCIES), "dependencyHashes": {name: {} for name in freetype.DEPENDENCIES},
                      "dependencyInputs": {"sha256": "fingerprint"}, "materials": {}, "toolchain": {"files": {}}}
            (directory / "build.json").write_text(json.dumps(record))
            with patch.object(freetype, "specification", return_value=spec):
                with self.assertRaises(ValueError):
                    freetype.validate_materials(directory, "x86_64")

    def test_inherited_build_flags_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = self.make_prefix(Path(root))
            pkg_config = Path(root) / "pkgconf"
            pkg_config.write_bytes(b"pkgconf")
            with patch.dict(freetype.os.environ, {"C_INCLUDE_PATH": "/tmp/escape", "PKG_CONFIG": "/tmp/override"}, clear=False):
                env = freetype.sanitized_environment(prefix, pkg_config)
                self.assertNotIn("C_INCLUDE_PATH", env)
                self.assertNotIn("CPLUS_INCLUDE_PATH", env)
                self.assertNotIn("PKG_CONFIG_PATH_OVERRIDE", env)
                self.assertEqual(env["PKG_CONFIG"], str(pkg_config))

    @unittest.skipUnless(os.name == "posix", "symlink adversarial tests require POSIX test host")
    def test_external_file_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = self.make_prefix(Path(root))
            outside = Path(root) / "outside.h"
            outside.write_bytes(b"outside")
            os.symlink(outside, prefix / "include/external.h")
            with self.assertRaises(ValueError):
                freetype.dependency_inputs(prefix)

    @unittest.skipUnless(os.name == "posix", "symlink adversarial tests require POSIX test host")
    def test_directory_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            prefix = self.make_prefix(Path(root))
            outside = Path(root) / "outside"
            outside.mkdir()
            os.symlink(outside, prefix / "include/external-dir")
            with self.assertRaises(ValueError):
                freetype.dependency_inputs(prefix)


if __name__ == "__main__":
    unittest.main()
