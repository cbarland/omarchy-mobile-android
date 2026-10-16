from pathlib import Path
import unittest
from importlib.machinery import SourceFileLoader


ROOT = Path(__file__).resolve().parents[2]
MODULE = SourceFileLoader("verify_theme_download_persistence",
                          str(ROOT / "tools/linux/verify-theme-download-persistence")).load_module()


class HackerboyFixtureTest(unittest.TestCase):
    def test_manifest_is_fixed_data_only_tree(self):
        self.assertEqual(MODULE.HACKERBOY_URL, "https://github.com/ggregoro/hackerboy")
        self.assertEqual(MODULE.HACKERBOY_COMMIT,
                         "add711288b2549ad0b017618cb6256053391d8fa")
        self.assertEqual(len(MODULE.HACKERBOY_TREE), 14)
        self.assertTrue(all(mode == "100644" and len(digest) == 64
                            for mode, digest in MODULE.HACKERBOY_TREE.values()))
        self.assertFalse(any(path.endswith((".lua", ".sh", ".bash", ".py", ".desktop", ".service"))
                           for path in MODULE.HACKERBOY_TREE))
        self.assertEqual(len(MODULE.HACKERBOY_BACKGROUND_HASHES), 4)


if __name__ == "__main__":
    unittest.main()
