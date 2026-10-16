import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "verify_display_resume", Path(__file__).parents[1] / "verify-display-resume.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DisplayResumeAuditTest(unittest.TestCase):
    def test_power_state_is_explicit(self):
        self.assertEqual(MODULE.power_state("mWakefulness=Asleep\n"), "Asleep")
        with self.assertRaises(RuntimeError):
            MODULE.power_state("mInteractive=false\n")

    def test_keyguard_fields_are_observational(self):
        state = MODULE.keyguard_state("mShowingLockscreen=true mDreamingLockscreen=false isKeyguardShowing=false")
        self.assertEqual(state, {"mShowingLockscreen": True, "mDreamingLockscreen": False, "showing": False})

    def test_owner_and_geometry_must_match(self):
        before = {name: {"pid": str(index), "signature": name, "displayId": str(index)}
                  for index, name in enumerate(MODULE.PROFILES)}
        after = {name: dict(value) for name, value in before.items()}
        self.assertTrue(MODULE.owner_matches(before, after))
        after["desktop"]["pid"] = "restarted"
        self.assertFalse(MODULE.owner_matches(before, after))

    def test_baseline_rejection_is_before_mutation(self):
        self.assertNotEqual(MODULE.keyguard_state("mDreamingLockscreen=false").get("showing"), False)
        self.assertEqual(MODULE.power_state("mWakefulness=Asleep"), "Asleep")

    def test_baseline_health_gate_rejects_unhealthy_snapshot(self):
        snapshot = {"power": "Awake", "keyguard": {"showing": False},
                    "profiles": {name: {"pid": "1", "signature": name, "displayId": "0"}
                                 for name in MODULE.PROFILES},
                    "health": {name: {"healthy": False, "monitors": None}
                               for name in MODULE.PROFILES}}
        self.assertFalse(MODULE.baseline_ready(snapshot))

    def test_wrong_avd_is_rejected_before_any_cycle(self):
        with self.assertRaises(RuntimeError):
            MODULE.validate_avd("Other_AVD")

    def test_bad_session_output_is_unhealthy(self):
        class Result:
            returncode = 1
            stdout = ""
            stderr = "socket unavailable"

        original = MODULE.run
        try:
            MODULE.run = lambda *args, **kwargs: Result()
            health = MODULE.session_health(Path("."), ["adb", "-s", "emulator-5556"], "phone")
        finally:
            MODULE.run = original
        self.assertFalse(health["healthy"])
        self.assertIn("socket unavailable", health["stderr"])

    def test_postwake_geometry_or_restoration_failure_fails(self):
        profiles = {name: {"pid": str(index), "signature": name, "displayId": str(index)}
                    for index, name in enumerate(MODULE.PROFILES)}
        health = {name: {"healthy": True, "monitors": [{"width": 1920, "height": 1080}]}
                  for name in MODULE.PROFILES}
        baseline = {"profiles": profiles, "health": health}
        after = {"profiles": {name: dict(value) for name, value in profiles.items()},
                 "health": {name: {"healthy": True, "monitors": [dict(health[name]["monitors"][0])]}
                            for name in MODULE.PROFILES}}
        self.assertTrue(MODULE.lifecycle_passes(baseline, after, "Awake"))
        after["health"]["desktop"]["monitors"][0]["width"] = 1280
        self.assertFalse(MODULE.lifecycle_passes(baseline, after, "Awake"))
        self.assertFalse(MODULE.lifecycle_passes(baseline, baseline, "Asleep"))


if __name__ == "__main__":
    unittest.main()
