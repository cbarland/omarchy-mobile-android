import hashlib
import json
import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parents[1] / "native"))
import prepare_source_inputs


class PrepareSourceInputsTest(unittest.TestCase):
    def make_lock(self, root, archive=b"archive", recipe=b"recipe", patch=b"patch"):
        sha = lambda data: hashlib.sha256(data).hexdigest()
        lock = {"format": 1, "snapshotKind": "source-input-snapshot",
                "termuxLicense": {"license": "per-package", "url": "https://example.test/LICENSE.md", "sha256": sha(b"license")},
                "packages": [{"name": "demo", "version": "1.0", "license": "MIT",
                    "upstream": {"url": "https://example.test/demo.tar", "sha256": sha(archive)},
                    "recipe": {"commit": "a" * 40, "path": "packages/demo/build.sh", "url": "https://example.test/build.sh", "sha256": sha(recipe)},
                    "patches": [{"path": "packages/demo/fix.patch", "url": "https://example.test/fix.patch", "sha256": sha(patch)}]}]}
        path = root / "lock.json"
        path.write_text(json.dumps(lock), encoding="utf-8")
        return path, {"https://example.test/demo.tar": archive, "https://example.test/build.sh": recipe,
                      "https://example.test/fix.patch": patch, "https://example.test/LICENSE.md": b"license"}

    def fetcher(self, data):
        def fetch(url, destination, expected):
            destination.write_bytes(data[url])
            if hashlib.sha256(data[url]).hexdigest() != expected:
                raise ValueError("SHA-256 mismatch")
        return fetch

    def temp_root(self):
        return tempfile.TemporaryDirectory(dir=Path.cwd())

    def test_snapshot_is_verified_and_deterministic(self):
        with self.temp_root() as directory:
            root = Path(directory)
            lock, data = self.make_lock(root)
            first, second = root / "first", root / "second"
            prepare_source_inputs.prepare(lock, first, fetcher=self.fetcher(data))
            prepare_source_inputs.prepare(lock, second, fetcher=self.fetcher(data))
            files = lambda base: sorted((path.relative_to(base), path.read_bytes()) for path in base.rglob("*") if path.is_file())
            self.assertEqual(files(first), files(second))
            self.assertEqual((first / "notices/TERMUX-LICENSE.md").read_bytes(), b"license")

    def test_existing_output_is_refused_before_fetch(self):
        with self.temp_root() as directory:
            root = Path(directory)
            lock, _ = self.make_lock(root)
            output = root / "snapshot"
            output.mkdir()
            marker = output / "existing-marker"
            marker.write_text("keep")
            with self.assertRaises(FileExistsError):
                prepare_source_inputs.prepare(lock, output, fetcher=lambda *_: self.fail("fetch before refusal"))
            self.assertEqual(marker.read_text(), "keep")

    def test_unsafe_lock_is_rejected_before_io(self):
        with self.temp_root() as directory:
            root = Path(directory)
            lock, _ = self.make_lock(root)
            value = json.loads(lock.read_text())
            value["packages"][0]["patches"][0]["path"] = "packages/demo/C:/outside.patch"
            lock.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                prepare_source_inputs.prepare(lock, root / "snapshot", fetcher=lambda *_: self.fail("unsafe lock reached IO"))

    def test_conflicting_recipe_and_patch_rejected_before_io(self):
        with self.temp_root() as directory:
            root = Path(directory)
            lock, _ = self.make_lock(root)
            value = json.loads(lock.read_text())
            value["packages"][0]["patches"][0]["path"] = "packages/demo/build.sh"
            lock.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                prepare_source_inputs.prepare(lock, root / "snapshot", fetcher=lambda *_: self.fail("conflict reached IO"))

    def test_invalid_metadata_rejected_before_io(self):
        with self.temp_root() as directory:
            root = Path(directory)
            lock, _ = self.make_lock(root)
            original = lock.read_text()
            for field in ("missing-version", "empty-version", "bad-commit"):
                with self.subTest(field=field):
                    value = json.loads(original)
                    if field == "missing-version": del value["packages"][0]["version"]
                    elif field == "empty-version": value["packages"][0]["version"] = ""
                    else: value["packages"][0]["recipe"]["commit"] = "A" * 40
                    lock.write_text(json.dumps(value))
                    with self.assertRaises(ValueError):
                        prepare_source_inputs.prepare(lock, root / "snapshot", fetcher=lambda *_: self.fail("metadata reached IO"))

    def test_corrupt_cache_fails_without_publication(self):
        with self.temp_root() as directory:
            root = Path(directory)
            lock, data = self.make_lock(root)
            cache = root / "cache"
            (cache / "demo").mkdir(parents=True)
            (cache / "demo.tar").write_bytes(data["https://example.test/demo.tar"])
            (cache / "demo" / "build.sh").write_bytes(b"corrupt")
            (cache / "demo" / "fix.patch").write_bytes(data["https://example.test/fix.patch"])
            (cache / "LICENSE.md").write_bytes(data["https://example.test/LICENSE.md"])
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                prepare_source_inputs.prepare(lock, root / "snapshot", cache=cache)
            self.assertFalse((root / "snapshot").exists())


if __name__ == "__main__":
    unittest.main()
