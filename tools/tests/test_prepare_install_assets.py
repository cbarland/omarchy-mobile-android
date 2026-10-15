import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).parents[2]
SCRIPT = ROOT / "tools/prepare-install-assets.py"
ASSETS = ROOT / "app/src/main/assets/install"


class InstallAssetTests(unittest.TestCase):
    def test_cli_help(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--help"], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0)
        self.assertIn("--cache", result.stdout)
        self.assertIn("--widgets", result.stdout)

    def test_generated_manifest_matches_payload(self):
        manifest_path = ASSETS / "install-manifest.json"
        if not manifest_path.exists():
            self.skipTest("generated install assets are not present")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for item in manifest["files"]:
            path = ASSETS / item["path"]
            self.assertTrue(path.is_file(), item["path"])
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), item["sha256"])
        members = manifest["payloadMembers"]
        self.assertTrue(members)
        self.assertFalse(any("squeekboard" in item["path"].lower() for item in members))
        self.assertEqual(manifest["gzipMtimeControlled"], False)


if __name__ == "__main__":
    unittest.main()
