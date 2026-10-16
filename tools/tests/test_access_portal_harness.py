from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
HARNESS = (ROOT / "tools/linux/verify-access-portal").read_text(encoding="utf-8")
PREPARE = (ROOT / "tools/prepare-linux-shell.py").read_text(encoding="utf-8")


class AccessPortalHarnessTest(unittest.TestCase):
    def test_harness_uses_unique_snapshot_and_waits_for_real_answer(self):
        self.assertIn("AccessDialog", HARNESS)
        self.assertIn("omamobile.access snapshot", HARNESS)
        self.assertIn('current.get("id")', HARNESS)
        self.assertIn("tap the real phone sheet now", HARNESS)
        self.assertNotIn('--method "$iface.Answer"', HARNESS)
        self.assertNotIn("shell call omamobile.access dismiss", HARNESS)

    def test_payload_selects_access_only(self):
        self.assertIn("org.freedesktop.impl.portal.Access=omamobile;gtk", PREPARE)
        self.assertNotIn("org.freedesktop.impl.portal.FileChooser=gnome", PREPARE)


if __name__ == "__main__":
    unittest.main()
