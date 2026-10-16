from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest
from importlib.machinery import SourceFileLoader


ROOT = Path(__file__).resolve().parents[2]
MODULE = SourceFileLoader("verify_theme_download_persistence",
                          str(ROOT / "tools/linux/verify-theme-download-persistence")).load_module()


@unittest.skipUnless(sys.platform == "linux", "the production harness requires Linux /proc")
class HackerboyFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hackerboy-fixture-")
        self.root = Path(self.temp.name)
        self.checkout = self.root / "hackerboy"
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null",
                        GIT_TERMINAL_PROMPT="0")
        subprocess.run(["git", "clone", "-q", "--no-checkout", MODULE.HACKERBOY_URL,
                        str(self.checkout)], env=self.env, check=True, timeout=90)
        subprocess.run(["git", "-C", str(self.checkout), "checkout", "-q", "--detach",
                        MODULE.HACKERBOY_COMMIT], env=self.env, check=True, timeout=30)

    def tearDown(self):
        self.temp.cleanup()

    def verify(self):
        MODULE.verify_hackerboy_checkout(self.checkout, self.env, self.root)

    def test_actual_pinned_checkout_matches_manifest(self):
        self.verify()

    def test_modified_checkout_is_rejected(self):
        cases = ("missing", "extra", "hash", "mode", "symlink")
        for case in cases:
            with self.subTest(case=case):
                target = self.checkout / "colors.toml"
                if case == "missing":
                    target.unlink()
                elif case == "extra":
                    (self.checkout / "unexpected.txt").write_text("unexpected", encoding="utf-8")
                elif case == "hash":
                    target.write_text("accent = \"#000000\"\n", encoding="utf-8")
                elif case == "mode":
                    subprocess.run(["git", "-C", str(self.checkout), "update-index", "--chmod=+x",
                                    "colors.toml"], env=self.env, check=True)
                    subprocess.run(["git", "-C", str(self.checkout), "-c", "user.name=fixture",
                                    "-c", "user.email=fixture@example.invalid", "commit", "-qm", "mode"],
                                   env=self.env, check=True)
                else:
                    link = self.checkout / "colors-link"
                    try:
                        link.symlink_to(target)
                    except (OSError, NotImplementedError) as error:
                        self.skipTest("symlinks unavailable: " + str(error))
                with self.assertRaises(RuntimeError):
                    self.verify()
                subprocess.run(["git", "-C", str(self.checkout), "restore", "--worktree", "."],
                               env=self.env, check=True)
                if case == "mode":
                    subprocess.run(["git", "-C", str(self.checkout), "reset", "-q", "--hard",
                                    MODULE.HACKERBOY_COMMIT], env=self.env, check=True)
                if case == "extra":
                    (self.checkout / "unexpected.txt").unlink()
                elif case == "symlink":
                    (self.checkout / "colors-link").unlink()


if __name__ == "__main__":
    unittest.main()
