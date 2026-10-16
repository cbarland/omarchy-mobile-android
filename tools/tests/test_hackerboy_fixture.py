import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
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
        subprocess.run(["git", "init", "-q", str(self.checkout)], env=self.env, check=True)
        (self.checkout / "colors.toml").write_text('accent = "#12abef"\n', encoding="utf-8")
        (self.checkout / "backgrounds").mkdir()
        (self.checkout / "backgrounds/fixture.bin").write_bytes(b"reviewed fixture image\n")
        subprocess.run(["git", "-C", str(self.checkout), "add", "."], env=self.env, check=True)
        subprocess.run(["git", "-C", str(self.checkout), "-c", "user.name=fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"],
                       env=self.env, check=True)
        self.commit = subprocess.run(["git", "-C", str(self.checkout), "rev-parse", "HEAD"],
                                     env=self.env, check=True, capture_output=True, text=True).stdout.strip()
        self.old_commit = MODULE.HACKERBOY_COMMIT
        self.old_tree = MODULE.HACKERBOY_TREE
        self.old_home = MODULE.HOME
        MODULE.HACKERBOY_COMMIT = self.commit
        MODULE.HACKERBOY_TREE = {
            "colors.toml": ("100644", hashlib.sha256((self.checkout / "colors.toml").read_bytes()).hexdigest()),
            "backgrounds/fixture.bin": ("100644", hashlib.sha256((self.checkout / "backgrounds/fixture.bin").read_bytes()).hexdigest()),
        }
        MODULE.HACKERBOY_BACKGROUND_HASHES = {MODULE.HACKERBOY_TREE["backgrounds/fixture.bin"][1]}

    def tearDown(self):
        MODULE.HACKERBOY_COMMIT = self.old_commit
        MODULE.HACKERBOY_TREE = self.old_tree
        MODULE.HACKERBOY_BACKGROUND_HASHES = {digest for path, (_, digest) in self.old_tree.items()
                                              if path.startswith("backgrounds/")}
        MODULE.HOME = self.old_home
        self.temp.cleanup()

    def verify(self):
        MODULE.verify_hackerboy_checkout(self.checkout, self.env, self.root)

    def test_local_checkout_matches_manifest(self):
        self.verify()

    def test_modified_checkout_is_rejected(self):
        target = self.checkout / "colors.toml"
        for case in ("missing", "extra", "hash", "mode", "symlink"):
            with self.subTest(case=case):
                if case == "missing":
                    target.unlink()
                elif case == "extra":
                    (self.checkout / "unexpected.txt").write_text("unexpected", encoding="utf-8")
                elif case == "hash":
                    target.write_text('accent = "#000000"\n', encoding="utf-8")
                elif case == "mode":
                    target.chmod(0o755)
                else:
                    (self.checkout / "colors-link").symlink_to(target)
                with self.assertRaises(RuntimeError):
                    self.verify()
                subprocess.run(["git", "-C", str(self.checkout), "restore", "--worktree", "."],
                               env=self.env, check=True)
                target.chmod(0o644)
                if case == "extra":
                    (self.checkout / "unexpected.txt").unlink()
                elif case == "symlink":
                    (self.checkout / "colors-link").unlink()

    def test_legacy_state_defaults_to_owned_target(self):
        work = self.root / "legacy-work"
        work.mkdir()
        (work / "state.json").write_text(json.dumps({
            "version": 1, "home": str(MODULE.HOME), "fixture": work.name,
            "snapshots": {}, "signatures": {}, "transport": "loopback-http-git",
        }), encoding="utf-8")
        state = MODULE.load(work)
        self.assertFalse(state["publicHackerboy"])
        self.assertEqual(state["themeName"], work.name)
        self.assertEqual(MODULE.theme_target(state), MODULE.HOME / ".config/omarchy/themes" / work.name)
        state["themeName"] = "unrelated-user-theme"
        with self.assertRaises(RuntimeError):
            MODULE.theme_target(state)

    def test_public_verdict_requires_phase_and_both_reopens(self):
        state = {"publicHackerboy": True, "phase": "installed",
                 "signatures": {"phone": "p", "desktop": "d"},
                 "reopened": {"phone": "p2", "desktop": "d2"}}
        self.assertTrue(MODULE.public_install_complete(state))
        for change in ({"phase": "installing"}, {"reopened": {"phone": "p2"}},
                       {"signatures": {"phone": "p"}}, {"publicHackerboy": False}):
            partial = dict(state)
            partial.update(change)
            self.assertFalse(MODULE.public_install_complete(partial))

    def test_public_prepare_records_public_transport_and_owned_name(self):
        home = self.root / "home"
        omarchy = home / ".local/share/omarchy"
        (home / ".local/state/omarchy/current").mkdir(parents=True)
        (home / ".local/state/omarchy/current/theme.name").write_text("tokyo-night\n", encoding="utf-8")
        (omarchy / "themes/tokyo-night").mkdir(parents=True)
        work = self.root / "prepare-work"
        with mock.patch.object(MODULE, "HOME", home), mock.patch.object(MODULE, "OMARCHY", omarchy), \
                mock.patch.object(MODULE, "SNAPSHOTS", (".local/state/omarchy",)), \
                mock.patch.object(MODULE, "preconditions"):
            MODULE.prepare(work, public_hackerboy=True)
        state = json.loads((work / "state.json").read_text())
        self.assertEqual(state["transport"], "public-https-git")
        self.assertEqual(state["themeName"], "hackerboy")

    def test_restore_records_public_success_before_phase_transition(self):
        home = self.root / "home"
        work = self.root / "omamobile-theme-download-restore"
        work.mkdir()
        target = home / ".config/omarchy/themes/hackerboy"
        target.mkdir(parents=True)
        (target / "colors.toml").write_text("fixture\n", encoding="utf-8")
        current = home / ".local/state/omarchy/current/theme"
        current.mkdir(parents=True)
        (current / "colors.toml").write_text("fixture\n", encoding="utf-8")
        (current / "shell.toml").write_text("fixture\n", encoding="utf-8")
        (home / ".local/state/omamobile").mkdir(parents=True)
        sync_log = home / ".local/state/omamobile/theme-sync.log"
        sync_log.write_text("", encoding="utf-8")
        state = {"version": 1, "home": str(home), "fixture": work.name,
                 "originalTheme": "tokyo-night", "snapshots": {"snapshot": None},
                 "signatures": {"phone": "p", "desktop": "d"},
                 "reopened": {"phone": "p2", "desktop": "d2"},
                 "transport": "public-https-git", "themeName": "hackerboy",
                 "publicHackerboy": True, "colorsSha256": "unused",
                 "backgroundSha256": "unused", "phase": "installed"}
        (work / "state.json").write_text(json.dumps(state), encoding="utf-8")

        def fake_run(argv, *args, **kwargs):
            if argv[:2] == ["omarchy-shell", "shell"]:
                return "ok"
            if argv[:2] == ["omarchy-shell", "background"]:
                return ""
            if argv and argv[0].endswith("sync-session-theme"):
                sync_log.write_text("desktop ping acknowledged\ndesktop applyTheme acknowledged\n"
                                    "desktop background setInstant acknowledged\n", encoding="utf-8")
            return ""

        with mock.patch.object(MODULE, "HOME", home), mock.patch.object(MODULE, "SNAPSHOTS", ("snapshot",)), \
                mock.patch.object(MODULE, "SYNC_LOG", sync_log), mock.patch.object(MODULE, "profile", return_value="phone"), \
                mock.patch.object(MODULE, "run", side_effect=fake_run), \
                mock.patch.object(MODULE, "verify_hackerboy_checkout"):
            MODULE.restore(work, state)
        result = json.loads((work / "result.json").read_text())
        self.assertTrue(result["publicHttpsVerified"])
        self.assertEqual(json.loads((work / "state.json").read_text())["phase"], "restored")


if __name__ == "__main__":
    unittest.main()
