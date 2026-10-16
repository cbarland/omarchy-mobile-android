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
        state = MODULE.keyguard_state("mShowingLockscreen=true mDreamingLockscreen=false")
        self.assertEqual(state, {"mShowingLockscreen": True, "mDreamingLockscreen": False})

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


if __name__ == "__main__":
    unittest.main()
