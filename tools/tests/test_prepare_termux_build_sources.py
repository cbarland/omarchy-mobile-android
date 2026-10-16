import gzip
import hashlib
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
        (self.evidence / "termux-full").joinpath("actual-input-blob-verification.json").write_text("[]\n")
        (self.evidence / "termux-full").joinpath("input-blob-verification.json").write_text("[]\n")
        (self.evidence / "termux-recipes").mkdir()
        (self.evidence / "termux-recipes/README").write_text("recipe snapshot\n")
        (self.evidence / "sol/official-checksums").mkdir(parents=True)
        (self.evidence / "sol/official-checksums/run.json").write_text("{}\n")
        self.repo = self.root / "repo"
        (self.repo / "tools/native").mkdir(parents=True)
        (self.repo / "tools/native/source-inputs.lock.json").write_text("{}\n")
        (self.repo / "tools/native/prepare_source_inputs.py").write_text("# evidence only\n")
        entries = []
        for index, package in enumerate(sorted(MODULE.EXPECTED_PACKAGES)):
            name = f"actual-{index}.tar.gz"
            data = f"source-{package}\n".encode()
            archive = self.evidence / "termux-full/archives" / name
            archive.write_bytes(data)
            entries.append({"package": package, "commit": f"{index:040x}", "tree": f"{index:040x}",
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
        self.assertIn("build-trees/freetype/actual-0.tar.gz", names)
        self.assertNotIn("build-trees/libgraphite", "".join(names))

    def test_archive_hash_mismatch_is_rejected(self):
        archive = next((self.evidence / "termux-full/archives").iterdir())
        archive.write_bytes(b"tampered\n")
        with self.assertRaises(ValueError):
            MODULE.prepare(self.evidence, self.repo, self.root / "bundle")


if __name__ == "__main__":
    unittest.main()
