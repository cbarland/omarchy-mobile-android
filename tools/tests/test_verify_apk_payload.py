import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import sys
ROOT = Path(__file__).parents[2]
sys.path.insert(0, str(ROOT / "tools/native"))
import verify_release


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
                data = bytearray(20)
                data[:6] = b"\x7fELF\x02\x01"
                data[16:18] = (3).to_bytes(2, "little")
                data[18:20] = machine.to_bytes(2, "little")
                self.native["lib/" + abi + "/" + name] = bytes(data)
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


if __name__ == "__main__":
    unittest.main()
