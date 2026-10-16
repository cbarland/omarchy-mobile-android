import hashlib
import json
import tempfile
import unittest
import zipfile
import struct
from pathlib import Path

import sys
ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT / "tools/native"))
import verify_release


def valid_elf(machine, elf_type=3, needed=()):
    eh = struct.Struct('<16sHHIQQQIHHHHHH')
    ph = struct.Struct('<IIQQQQQQ')
    dy = struct.Struct('<qQ')
    data = bytearray(0x800)
    ident = b'\x7fELF' + bytes([2, 1, 1]) + bytes(9)
    phnum = 2 if needed else 1
    data[:eh.size] = eh.pack(ident, elf_type, machine, 1, 0, eh.size, 0, 0,
                             eh.size, ph.size, phnum, 0, 0, 0)
    data[eh.size:eh.size + ph.size] = ph.pack(1, 5, 0, 0x400000, 0, 0x600, 0x600, 0x1000)
    if not needed:
        return bytes(data)
    dyn = 0x100
    data[eh.size + ph.size:eh.size + 2 * ph.size] = ph.pack(2, 6, dyn, 0x400100, 0, 0x80, 0x80, 8)
    strings = b'\0' + b'\0'.join(name.encode() for name in needed) + b'\0'
    entries = [(1, 1), (5, 0x400400), (10, len(strings)), (0, 0)]
    for i, item in enumerate(entries):
        data[dyn + i * dy.size:dyn + (i + 1) * dy.size] = dy.pack(*item)
    data[0x400:0x400 + len(strings)] = strings
    return bytes(data)


class ApkPayloadBindingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.project = Path(self.temp.name) / "project"
        self.main = self.project / "app/src/main"
        self.apk = Path(self.temp.name) / "fixture.apk"
        self.native = {}
        for abi in verify_release.ABIS:
            directory = self.main / "assets/licenses/native" / abi
            directory.mkdir(parents=True)
            libraries = {}
            machine = 62 if abi == "x86_64" else 183
            for name in ("libomamobile_hyprland.so", "libanhyprland.so", "libtawcroot.so", "libando.so"):
                data = valid_elf(machine, 3)
                self.native["lib/" + abi + "/" + name] = data
                libraries[name] = {"name_in_apk": name, "sha256_apk": hashlib.sha256(data).hexdigest()}
            (directory / "libraries.json").write_text(json.dumps({"abi": abi, "libraries": libraries}))
        install = self.main / "assets/install"
        install.mkdir(parents=True)
        files = []
        for name, data in (("linux-shell.tgz", b"shell"), ("prepare-arch-graphics", b"graphics"),
                           ("configure-omarchy-repository", b"repo")):
            files.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        (install / "install-manifest.json").write_text(json.dumps({"format": 1, "pins": verify_release.INSTALL_PINS,
                                                                      "files": files}))
        self.install = {"assets/install/" + item["path"]: data for item, data in
                        zip(files, (b"shell", b"graphics", b"repo"))}

    def tearDown(self):
        self.temp.cleanup()

    def write_apk(self, mutate=None, duplicate=None, omit=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            payload = {**self.native, **self.install}
            if mutate in payload:
                changed = bytearray(payload[mutate])
                changed[10] ^= 1
                payload[mutate] = bytes(changed)
            for name, data in payload.items():
                if name != omit:
                    archive.writestr(name, data)
                    if name == duplicate:
                        archive.writestr(name, data)

    def test_valid_payload_binds_all_native_and_installer_bytes(self):
        self.write_apk()
        errors, native_checked, payload_checked = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertEqual(errors, [])
        self.assertEqual(native_checked, 8)
        self.assertEqual(payload_checked, 11)

    def test_changed_staged_library_is_rejected(self):
        self.write_apk(mutate="lib/x86_64/libando.so")
        errors, _, _ = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertEqual(errors[0]["kind"], "apk_payload_hash_mismatch")

    def test_missing_installer_payload_is_rejected(self):
        self.write_apk(omit="assets/install/prepare-arch-graphics")
        errors, _, _ = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertTrue(any(e["kind"] == "missing_or_duplicate_apk_payload" for e in errors))

    def test_installer_manifest_size_is_bound(self):
        manifest = self.main / "assets/install/install-manifest.json"
        data = json.loads(manifest.read_text())
        data["files"][0]["bytes"] += 1
        manifest.write_text(json.dumps(data))
        self.write_apk()
        errors, _, _ = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertTrue(any(e["kind"] == "apk_payload_size_mismatch" for e in errors))

    def test_duplicate_required_native_entry_is_rejected(self):
        self.write_apk(duplicate="lib/arm64-v8a/libando.so")
        errors, _, _ = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertTrue(any(e["kind"] == "missing_or_duplicate_apk_native" for e in errors))

    def test_malformed_library_manifest_fails_closed(self):
        path = self.main / "assets/licenses/native/x86_64/libraries.json"
        path.write_text(json.dumps({"abi": "x86_64", "libraries": []}))
        self.write_apk()
        errors, native_checked, payload_checked = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertEqual((native_checked, payload_checked), (8, 0))
        self.assertEqual(errors[0]["kind"], "invalid_apk_payload_structure")

    def test_unrewritten_pangocairo_dependency_is_rejected_after_hash_update(self):
        path = self.main / "assets/licenses/native/x86_64/libraries.json"
        manifest = json.loads(path.read_text())
        data = valid_elf(62, needed=("libpangocairo-1.0.so",))
        entry = "lib/x86_64/libando.so"
        self.native[entry] = data
        manifest["libraries"]["libando.so"]["sha256_apk"] = hashlib.sha256(data).hexdigest()
        path.write_text(json.dumps(manifest))
        self.write_apk()
        errors, _, payload_checked = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertEqual(payload_checked, 11)
        self.assertTrue(any(e["kind"] == "missing_apk_native_dependency" and
                            e["dependency"] == "libpangocairo-1.0.so" for e in errors))

    def test_dependency_must_be_same_abi(self):
        self.native["lib/x86_64/libfoo.so"] = valid_elf(62, needed=("libbar.so",))
        self.native["lib/arm64-v8a/libbar.so"] = valid_elf(183)
        self.write_apk()
        errors, _, _ = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertTrue(any(e["kind"] == "missing_apk_native_dependency" and
                            e["abi"] == "x86_64" and e["dependency"] == "libbar.so" for e in errors))

    def test_missing_cpp_dependency_is_rejected(self):
        self.native["lib/x86_64/libfoo.so"] = valid_elf(62, needed=("libc++_shared.so",))
        self.write_apk()
        errors, _, _ = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertTrue(any(e["kind"] == "missing_apk_native_dependency" and
                            e["dependency"] == "libc++_shared.so" for e in errors))

    def test_malformed_secondary_and_duplicate_other_native_are_rejected(self):
        self.native["lib/x86_64/libbad.so"] = b"not-an-elf"
        self.native["lib/x86_64/libfoo.so"] = valid_elf(62)
        self.write_apk(duplicate="lib/x86_64/libfoo.so")
        errors, _, _ = verify_release.apk_payload_errors(self.project, self.apk)
        self.assertTrue(any(e["kind"] == "invalid_apk_native_elf" and e.get("entry", "").endswith("libbad.so") for e in errors))
        self.assertTrue(any(e["kind"] == "duplicate_apk_native_entry" and e.get("entry", "").endswith("libfoo.so") for e in errors))

    def test_noncanonical_native_provider_paths_are_rejected(self):
        self.write_apk()
        self.assertEqual(verify_release.apk_native_errors(self.apk), ([], 8))
        for entry in ("lib/x86_64/nested/libbar.so", "lib/x86_64/../libbar.so"):
            with self.subTest(entry=entry):
                self.native[entry] = valid_elf(62)
                self.write_apk()
                errors, checked = verify_release.apk_native_errors(self.apk)
                self.assertEqual(checked, 8)
                self.assertTrue(any(e["kind"] == "invalid_apk_native_path" and
                                    e["entry"] == entry for e in errors))
                del self.native[entry]


if __name__ == "__main__":
    unittest.main()
