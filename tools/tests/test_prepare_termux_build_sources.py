import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from importlib.machinery import SourceFileLoader

ROOT = Path(__file__).resolve().parents[2]
MODULE = SourceFileLoader("prepare_termux_build_sources",
                          str(ROOT / "tools/native/prepare_termux_build_sources.py")).load_module()


class SourceBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="termux-source-bundle-")
        self.root = Path(self.temp.name)
        self.evidence = self.root / "evidence"
        (self.evidence / "termux-full/archives").mkdir(parents=True)
        (self.evidence / "archives").mkdir()
        (self.evidence / "termux-full").joinpath("actual-input-blob-verification.json").write_text("[]\n")
        (self.evidence / "termux-full").joinpath("input-blob-verification.json").write_text("[]\n")
        (self.evidence / "termux-recipes").mkdir()
        (self.evidence / "termux-recipes/README").write_text("recipe snapshot\n")
        (self.evidence / "sol/official-checksums").mkdir(parents=True)
        (self.evidence / "sol/official-checksums/run.json").write_text("{}\n")
        self.repo = self.root / "repo"
        (self.repo / "tools/native").mkdir(parents=True)
        source = self.evidence / "archives/freetype.tar.xz"
        source.write_bytes(b"upstream\n")
        lock = {"format": 1, "snapshotKind": "source-input-snapshot",
                "repository": "https://github.com/termux/termux-packages",
                "termuxLicense": {"license": "per-package", "url": "https://example.invalid/LICENSE",
                                  "sha256": "a" * 64}, "packages": [{
            "name": "freetype", "version": "1", "license": "MIT", "homepage": "https://example.invalid",
            "upstream": {"url": "https://example.invalid/freetype.tar.xz",
                         "sha256": hashlib.sha256(source.read_bytes()).hexdigest()},
            "recipe": {"commit": "0" * 40, "path": "packages/freetype/build.sh",
                       "url": "https://example.invalid/build.sh", "sha256": "b" * 64}, "patches": []}]}
        (self.repo / "tools/native/source-inputs.lock.json").write_text(json.dumps(lock) + "\n")
        (self.repo / "tools/native/prepare_source_inputs.py").write_text("def validate(lock):\n    return None\n")
        for commit in ["0" * 40] + [f"{index + 1:040x}" for index in range(6)]:
            (self.evidence / "sol/official-checksums" / f"commit-{commit}.json").write_text(
                json.dumps({"sha": commit}) + "\n")
        entries = []
        for index, package in enumerate(sorted(MODULE.EXPECTED_PACKAGES)):
            commit = f"{index + 1:040x}"
            name = f"actual-{commit}.tar.gz"
            data = f"source-{package}\n".encode()
            archive = self.evidence / "termux-full/archives" / name
            archive.write_bytes(data)
            entries.append({"package": package, "commit": commit, "tree": commit,
                            "archive": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        (self.evidence / "termux-full/actual-tree-archive-manifest.json").write_text(
            json.dumps({"archives": entries}) + "\n")

    def tearDown(self):
        self.temp.cleanup()

    def test_bundle_is_portable_and_reproducible(self):
        first = self.root / "bundle-one"
        second = self.root / "bundle-two"
        MODULE.prepare(self.evidence, self.repo, first)
        MODULE.prepare(self.evidence, self.repo, second)
        self.assertEqual((first.with_suffix(".tar.gz")).read_bytes(), (second.with_suffix(".tar.gz")).read_bytes())
        manifest = json.loads((first / "manifest.json").read_text())
        self.assertEqual(set(manifest["packages"]), MODULE.EXPECTED_PACKAGES)
        with tarfile.open(first.with_suffix(".tar.gz"), "r:gz") as archive:
            names = {member.name for member in archive.getmembers()}
        self.assertIn("build-trees/freetype/actual-0000000000000000000000000000000000000001.tar.gz", names)
        self.assertNotIn("build-trees/libgraphite", "".join(names))

    def test_archive_hash_mismatch_is_rejected(self):
        archive = next((self.evidence / "termux-full/archives").iterdir())
        archive.write_bytes(b"tampered\n")
        with self.assertRaises(ValueError):
            MODULE.prepare(self.evidence, self.repo, self.root / "bundle")

    def test_archive_path_traversal_and_existing_outputs_are_rejected(self):
        manifest_path = self.evidence / "termux-full/actual-tree-archive-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["archives"][0]["archive"] = "actual-../escape.tar.gz"
        manifest_path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            MODULE.prepare(self.evidence, self.repo, self.root / "bundle")
        manifest["archives"][0]["archive"] = "actual-0000000000000000000000000000000000000001.tar.gz"
        manifest_path.write_text(json.dumps(manifest))
        output = self.root / "existing"
        output.mkdir()
        (self.root / "existing.tar.gz").write_bytes(b"owned")
        with self.assertRaises(ValueError):
            MODULE.prepare(self.evidence, self.repo, output)

    def test_nested_symlink_in_reviewed_recipe_snapshot_is_rejected(self):
        link = self.evidence / "termux-recipes/nested-link"
        try:
            link.symlink_to(self.evidence / "termux-full")
        except (OSError, NotImplementedError) as error:
            self.skipTest("symlinks unavailable: " + str(error))
        with self.assertRaises(ValueError):
            MODULE.prepare(self.evidence, self.repo, self.root / "bundle")

    def test_tar_duplicate_member_is_rejected(self):
        target = self.root / "bad.tar.gz"
        with tarfile.open(target, "w:gz") as archive:
            info = tarfile.TarInfo("manifest.json")
            data = b"{}\n"
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
            archive.addfile(info, io.BytesIO(data))
        with self.assertRaises(ValueError):
            MODULE.verify_tar(target, [], b"{}\n")


if __name__ == "__main__":
    unittest.main()
