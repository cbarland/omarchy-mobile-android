import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import runpy
import sys
import tarfile
import tempfile
import unittest
import zipfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/native"))
import build_graphite as graphite
import verify_release


class OwnedGraphiteTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.spec = graphite.specification()
        self.spec["upstream"]["sha256"] = hashlib.sha256(b"archive").hexdigest()
        self.output = self.root / "cache/x86_64/owned/graphite2"
        self.output.mkdir(parents=True)
        for name in ("recipes", "patches", "notices", "logs"):
            (self.output / name).mkdir()
        for name in graphite.RECIPE_FILES:
            (self.output / "recipes" / name).write_bytes((ROOT / "tools/native" / name).read_bytes())
        # Source bytes are a test fixture; the real source hash is independently
        # checked by the source-input preparer and actual cross-build proof.
        self.spec["referenceRecipe"]["sha256"] = hashlib.sha256(b"reference").hexdigest()
        self.spec["patches"][0]["sha256"] = hashlib.sha256(b"patch").hexdigest()
        (self.output / "recipes/termux-reference-build.sh").write_bytes(b"reference")
        (self.output / "patches/src-CMakeLists.txt.patch").write_bytes(b"patch")
        (self.output / "notices/LICENSE").write_bytes(b"notice")
        elf = bytearray(20); elf[:6] = b"\x7fELF\x02\x01"; elf[18:20] = (62).to_bytes(2, "little")
        (self.output / "libgraphite2.so").write_bytes(elf)
        (self.output / "upstream.tar.gz").write_bytes(b"archive")
        self.compiler = {"files": {"compiler": "hash"}}
        self.record = {"format": 1, "kind": "project-built", "abi": "x86_64", "specification": self.spec,
                       "toolchain": self.compiler, "librarySha256": graphite.sha256(self.output / "libgraphite2.so"),
                       "materials": {"notices/LICENSE": graphite.sha256(self.output / "notices/LICENSE")}}
        self.write_record()
        self.addCleanup(patch.stopall)
        patch.object(graphite, "specification", return_value=self.spec).start()
        patch.object(graphite, "toolchain", return_value=self.compiler).start()

    def write_record(self):
        (self.output / "build.json").write_text(json.dumps(self.record))

    def validate(self):
        return graphite.validate_output(self.output, "x86_64", self.root, "cmake")

    def test_valid_cache_is_reused_without_download_or_build(self):
        with patch.object(graphite, "fetch_checked") as fetch, patch.object(graphite.subprocess, "run") as run:
            self.assertEqual(graphite.build(self.output, "x86_64", self.root), self.output)
        fetch.assert_not_called(); run.assert_not_called()

    def test_stale_recipe_is_rejected_without_discarding_existing_library(self):
        library = (self.output / "libgraphite2.so").read_bytes()
        (self.output / "recipes/build_graphite.py").write_bytes(b"stale recipe")
        with self.assertRaises(ValueError):
            graphite.build(self.output, "x86_64", self.root)
        self.assertEqual(library, (self.output / "libgraphite2.so").read_bytes())

    def test_wrong_toolchain_abi_library_and_archive_are_rejected(self):
        self.validate()
        for field, change in (("abi", "arm64-v8a"), ("toolchain", {"files": {"other": "hash"}}),
                              ("librarySha256", "0" * 64)):
            saved = copy.deepcopy(self.record)
            self.record[field] = change; self.write_record()
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate()
            self.record = saved; self.write_record()
        (self.output / "upstream.tar.gz").write_bytes(b"changed source")
        with self.assertRaises(ValueError): self.validate()

    def test_material_traversal_is_rejected(self):
        self.record["materials"]["notices/../../outside"] = "0" * 64
        self.write_record()
        with self.assertRaises(ValueError): self.validate()

    def test_wrong_elf_machine_is_rejected_even_with_matching_library_hash(self):
        path = self.output / "libgraphite2.so"
        data = bytearray(path.read_bytes()); data[18:20] = (183).to_bytes(2, "little")
        path.write_bytes(data)
        self.record["librarySha256"] = graphite.sha256(path); self.write_record()
        with self.assertRaises(ValueError): self.validate()

    def test_archive_escape_and_symlinks_are_rejected_before_extraction(self):
        for name, kind in (("../escape", tarfile.REGTYPE), ("graphite-1.3.15/link", tarfile.SYMTYPE)):
            archive = self.root / "bad.tar"
            with tarfile.open(archive, "w") as tar:
                entry = tarfile.TarInfo(name); entry.type = kind; entry.linkname = "/outside"
                tar.addfile(entry)
            with self.subTest(name=name), self.assertRaises(ValueError):
                graphite.extract(archive, self.root / "source")
        self.assertFalse((self.root / "escape").exists())

    def test_failed_fresh_download_does_not_publish_output(self):
        fresh = self.root / "new/owned/graphite2"
        with patch.object(graphite, "fetch_checked", side_effect=ValueError("checksum mismatch")):
            with self.assertRaises(ValueError): graphite.build(fresh, "x86_64", self.root)
        self.assertFalse(fresh.exists())
        self.assertEqual(list(fresh.parent.iterdir()), [])

    def test_owned_source_gate_cannot_be_cleared_with_termux_strings(self):
        project = self.root / "project"
        for abi in verify_release.ABIS:
            notices = project / "app/src/main/assets/licenses/native" / abi
            notices.mkdir(parents=True)
            (notices / "termux-packages.json").write_text("[]")
            (notices / "upstream-deps.lock.json").write_text("{}")
            item = {"name_in_apk": "libgraphite2.so", "needed": [], "source": "owned/graphite2/libgraphite2.so"}
            (notices / "libraries.json").write_text(json.dumps({"abi": abi, "libraries": {"libgraphite2.so": item}}))
        source = project / "app/src/main/assets/licenses/native/corresponding-sources.json"
        source.write_text(json.dumps({"packages": [{"package": "libgraphite", "version": "1.3.15",
            "upstreamSourceUrl": "https://example.invalid/source", "upstreamSourceSha256": "1" * 64,
            "termuxRecipeCommit": "2" * 40, "termuxRecipePath": "packages/libgraphite/build.sh",
            "sourceBundleUrl": "https://example.invalid/bundle", "sourceBundleSha256": "3" * 64}]}))
        result = verify_release.audit(project)
        gap = next(g for g in result["sourceMaterialGaps"] if g["package"] == "libgraphite")
        self.assertEqual(gap["missingFields"], ["ownedSourceBundleDelivery", "ownedRelinkingMaterials"])
        self.assertFalse(result["readyForPublicBinaryRelease"])

    def test_stage_refuses_missing_owned_output_before_destination_writes(self):
        destination = self.root / "untouched-project"
        argv = ["stage_port.py", "--abi", "x86_64", "--cache", str(self.root / "empty"),
                "--project", str(destination), "--ndk", str(self.root / "ndk")]
        with patch.object(sys, "argv", argv), self.assertRaises(FileNotFoundError):
            runpy.run_path(str(ROOT / "tools/native/stage_port.py"), run_name="__main__")
        self.assertFalse(destination.exists())

    def test_stage_selects_owned_library_even_when_termux_library_exists(self):
        base = self.root / "cache/x86_64"
        source = base / "anhyprland"
        prefix = source / "build/android-prefix"
        for directory in (source / "android", prefix / "share/misc", prefix / "lib", base / "ndk-prefix/lib"):
            directory.mkdir(parents=True, exist_ok=True)
        (source / "build/android-hyprland").mkdir()
        (source / "build/android-hyprland/libanhyprland.so").write_bytes(b"compositor")
        (source / "LICENSE").write_text("notice")
        (source / "android/deps.lock.json").write_text("{}")
        (prefix / "share/misc/magic.mgc").write_bytes(b"magic")
        (base / "termux-packages.json").write_text("[]")
        (base / "ndk-prefix/lib/libgraphite2.so").write_bytes(b"old official library")
        project = self.root / "stage"
        (project / "app/src/main/cpp").mkdir(parents=True)
        argv = ["stage_port.py", "--abi", "x86_64", "--cache", str(self.root / "cache"),
                "--project", str(project), "--ndk", str(self.root / "ndk")]
        def output(command, **kwargs):
            if command[0] == "git": return "pinned-port\n"
            if str(command[-1]).endswith("libanhyprland.so"):
                return "(NEEDED) Shared library: [libgraphite2.so]\n"
            return ""
        with patch.object(sys, "argv", argv), patch.object(graphite, "validate_output", return_value=self.record), \
                patch("subprocess.check_output", side_effect=output), patch("subprocess.run"), \
                contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(ROOT / "tools/native/stage_port.py"), run_name="__main__")
        copied = project / "app/src/main/jniLibs/x86_64/libgraphite2.so"
        self.assertEqual(copied.read_bytes(), (self.output / "libgraphite2.so").read_bytes())
        records = json.loads((project / "app/src/main/assets/licenses/native/x86_64/libraries.json").read_text())
        self.assertEqual(records["libraries"]["libgraphite2.so"]["origin"]["kind"], "project-built")

    def test_apk_guard_rejects_missing_wrapper_and_wrong_abi(self):
        apk = self.root / "fixture.apk"
        required = ("libomamobile_hyprland.so", "libanhyprland.so", "libtawcroot.so", "libando.so")
        def write(missing=None, wrong=None):
            with zipfile.ZipFile(apk, "w") as archive:
                for abi, machine in (("x86_64", 62), ("arm64-v8a", 183)):
                    for name in required:
                        entry = "lib/" + abi + "/" + name
                        if entry == missing: continue
                        elf = bytearray(20); elf[:6] = b"\x7fELF\x02\x01"; elf[16:18] = (3).to_bytes(2, "little")
                        elf[18:20] = (62 if entry == wrong else machine).to_bytes(2, "little")
                        archive.writestr(entry, elf)
        write(); self.assertEqual(verify_release.apk_native_errors(apk), ([], 8))
        write(missing="lib/x86_64/libomamobile_hyprland.so")
        errors, checked = verify_release.apk_native_errors(apk)
        self.assertEqual(checked, 7)
        self.assertEqual(errors[0]["kind"], "missing_or_duplicate_apk_native")
        write(wrong="lib/arm64-v8a/libanhyprland.so")
        self.assertEqual(verify_release.apk_native_errors(apk)[0][0]["kind"], "invalid_apk_native_elf")


if __name__ == "__main__":
    unittest.main()
