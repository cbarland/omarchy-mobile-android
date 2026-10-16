from pathlib import Path
import json
import unittest


ROOT = Path(__file__).resolve().parents[2]
HARNESS = (ROOT / "tools/linux/verify-access-portal").read_text(encoding="utf-8")
PREPARE = (ROOT / "tools/prepare-linux-shell.py").read_text(encoding="utf-8")


class AccessPortalHarnessTest(unittest.TestCase):
    def test_snapshot_fixture_owns_only_matching_request(self):
        owned = {"opened": True, "pending": 1, "current": {"id": "a1b2c3d4"}}
        unrelated = {"opened": True, "pending": 1, "current": {"id": "deadbeef"}}
        empty = {"opened": False, "pending": 0, "current": None}

        def owned_current(snapshot, request_id):
            current = snapshot.get("current") if isinstance(snapshot, dict) else None
            return current.get("id") == request_id if isinstance(current, dict) else False

        self.assertTrue(owned_current(json.loads(json.dumps(owned)), "a1b2c3d4"))
        self.assertFalse(owned_current(json.loads(json.dumps(unrelated)), "a1b2c3d4"))
        self.assertFalse(owned_current(json.loads(json.dumps(empty)), "a1b2c3d4"))

    def test_harness_requires_real_reply_and_cleans_owned_id_only(self):
        self.assertIn("AccessDialog", HARNESS)
        self.assertIn("tap the real phone sheet now", HARNESS)
        self.assertIn("dismiss_owned_request", HARNESS)
        self.assertIn("require_empty_snapshot", HARNESS)
        self.assertIn('[[ "$current_id" == "$request_id" ]]', HARNESS)
        self.assertNotIn('--method "$iface.Answer"', HARNESS)

    def test_payload_selects_access_only(self):
        self.assertIn("org.freedesktop.impl.portal.Access=omamobile;gtk", PREPARE)
        self.assertNotIn("org.freedesktop.impl.portal.FileChooser=gnome", PREPARE)
        self.assertNotIn("usr/lib/systemd/user/omamobile-portal-access.service", PREPARE)
        self.assertIn("Exec=/usr/bin/omamobile-portal-access", PREPARE)


if __name__ == "__main__":
    unittest.main()
