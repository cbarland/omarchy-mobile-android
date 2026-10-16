import importlib.machinery
import importlib.util
import tempfile
import unittest
from unittest import mock
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "tools" / "linux" / "omamobile-android"
LOADER = importlib.machinery.SourceFileLoader("omamobile_android", str(SOURCE))
SPEC = importlib.util.spec_from_loader("omamobile_android", LOADER)
MODULE = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(MODULE)


class AndroidLauncherSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=ROOT.parent)
        self.applications = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def old_write_only_sync(self, apps):
        for app in apps:
            package = app["package"]
            (self.applications / (MODULE.LAUNCHER_PREFIX + package + ".desktop")).write_bytes(
                MODULE.launcher_content(package, app["name"]))

    def test_old_stale_entry_reproduction_then_reconciliation(self):
        stale = self.applications / "omamobile-android-com.example.old.desktop"
        self.old_write_only_sync([{"package": "com.example.old", "name": "Old"}])
        self.assertTrue(stale.exists())
        MODULE.sync_launchers([], self.applications)
        self.assertFalse(stale.exists())

    def test_install_reinstall_and_label_update_are_idempotent(self):
        app = {"package": "com.example.app", "name": "Example"}
        MODULE.sync_launchers([app], self.applications)
        path = self.applications / "omamobile-android-com.example.app.desktop"
        self.assertIn(b"Name=Example (Android)", path.read_bytes())
        MODULE.sync_launchers([{**app, "name": "Renamed"}], self.applications)
        self.assertIn(b"Name=Renamed (Android)", path.read_bytes())
        MODULE.sync_launchers([{**app, "name": "Renamed"}], self.applications)
        self.assertIn(b"Name=Renamed (Android)", path.read_bytes())
        MODULE.sync_launchers([], self.applications)
        self.assertFalse(path.exists())
        MODULE.sync_launchers([app], self.applications)
        self.assertTrue(path.exists())

    def test_malformed_response_does_not_prune(self):
        path = self.applications / "omamobile-android-com.example.old.desktop"
        path.write_bytes(MODULE.launcher_content("com.example.old", "Old"))
        with self.assertRaises(RuntimeError):
            MODULE.sync_launchers([{"package": "bad package", "name": "Bad"}], self.applications)
        self.assertTrue(path.exists())

    def test_user_modified_entry_and_collision_are_preserved(self):
        path = self.applications / "omamobile-android-com.example.app.desktop"
        path.write_bytes(MODULE.launcher_content("com.example.app", "Example") + b"X")
        MODULE.sync_launchers([{"package": "com.example.app", "name": "New label"}], self.applications)
        self.assertTrue(path.exists())
        self.assertTrue(path.read_bytes().endswith(b"X"))

        label_edit = self.applications / "omamobile-android-com.example.label.desktop"
        label_edit.write_bytes(MODULE.launcher_content("com.example.label", "Original").replace(
            b"Name=Original", b"Name=User edit"))
        MODULE.sync_launchers([{"package": "com.example.label", "name": "Current"}], self.applications)
        self.assertIn(b"Name=User edit", label_edit.read_bytes())

        extra = self.applications / "omamobile-android-com.example.extra.desktop"
        marked = MODULE.launcher_content("com.example.extra", "Extra")
        extra.write_bytes(marked.replace(b"X-Omamobile-Launcher-SHA256=", b"Exec=extra\nX-Omamobile-Launcher-SHA256="))
        MODULE.sync_launchers([], self.applications)
        self.assertTrue(extra.exists())

        bad_hash = self.applications / "omamobile-android-com.example.bad.desktop"
        bad_hash.write_bytes(MODULE.launcher_content("com.example.bad", "Bad").replace(b"a", b"b", 1))
        MODULE.sync_launchers([], self.applications)
        self.assertTrue(bad_hash.exists())

    def test_legacy_exact_current_entry_is_adopted_but_unmatched_is_preserved(self):
        adopt = self.applications / "omamobile-android-com.example.adopt.desktop"
        adopt.write_bytes(MODULE.launcher_body("com.example.adopt", "Adopt"))
        MODULE.sync_launchers([{"package": "com.example.adopt", "name": "Adopt"}], self.applications)
        self.assertTrue(MODULE.generated_launcher("com.example.adopt", adopt.read_bytes()))

        preserve = self.applications / "omamobile-android-com.example.legacy.desktop"
        preserve.write_bytes(MODULE.launcher_body("com.example.legacy", "Old label"))
        MODULE.sync_launchers([], self.applications)
        self.assertTrue(preserve.exists())

        collision = self.applications / "omamobile-android-com.example.other.desktop"
        collision.mkdir()
        MODULE.sync_launchers([], self.applications)
        self.assertTrue(collision.is_dir())

    def test_prune_revalidates_after_inspection(self):
        path = self.applications / "omamobile-android-com.example.race.desktop"
        path.write_bytes(MODULE.launcher_content("com.example.race", "Race"))
        original = MODULE.unchanged_snapshot
        edited = False

        def edit_before_revalidation(candidate, snapshot):
            nonlocal edited
            if not edited:
                edited = True
                candidate.write_bytes(b"[Desktop Entry]\nName=User edit\n")
            return original(candidate, snapshot)

        with mock.patch.object(MODULE, "unchanged_snapshot", edit_before_revalidation):
            MODULE.sync_launchers([], self.applications)
        self.assertEqual(path.read_bytes(), b"[Desktop Entry]\nName=User edit\n")

    def test_update_revalidates_after_inspection(self):
        path = self.applications / "omamobile-android-com.example.race.desktop"
        path.write_bytes(MODULE.launcher_content("com.example.race", "Old"))
        original = MODULE.unchanged_snapshot
        edited = False

        def edit_before_revalidation(candidate, snapshot):
            nonlocal edited
            if not edited:
                edited = True
                candidate.write_bytes(b"[Desktop Entry]\nName=User edit\n")
            return original(candidate, snapshot)

        with mock.patch.object(MODULE, "unchanged_snapshot", edit_before_revalidation):
            MODULE.sync_launchers([{"package": "com.example.race", "name": "New"}], self.applications)
        self.assertEqual(path.read_bytes(), b"[Desktop Entry]\nName=User edit\n")

    @unittest.skipUnless(hasattr(Path, "symlink_to"), "symlink support unavailable")
    def test_symlink_is_preserved(self):
        target = self.applications / "target.desktop"
        target.write_bytes(MODULE.launcher_content("com.example.link", "Link"))
        link = self.applications / "omamobile-android-com.example.link.desktop"
        try:
            link.symlink_to(target)
        except (OSError, NotImplementedError):
            self.skipTest("symlink creation unavailable")
        MODULE.sync_launchers([], self.applications)
        self.assertTrue(link.is_symlink())
        self.assertTrue(target.exists())


if __name__ == "__main__":
    unittest.main()
