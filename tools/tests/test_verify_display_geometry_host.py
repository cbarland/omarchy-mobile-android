import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("geometry", Path(__file__).parents[1] / "verify-display-geometry.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


class GeometryTests(unittest.TestCase):
    def test_console_rejection_is_failure_even_with_zero_exit_status(self):
        with patch.object(M.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "KO: rejected", "")):
            with self.assertRaises(RuntimeError):
                M.run(["adb", "emu", "multidisplay", "add"])

    def test_refuses_wrong_avd_before_display_mutation(self):
        with patch.object(M, "run", return_value="Prepared_AVD\nOK") as command:
            with self.assertRaises(RuntimeError):
                M.verify(["adb", "-s", "emulator-5554"], Path("."))
            self.assertEqual(command.call_count, 1)

    def exercise(self, *, fail_phase=False, fail_restore=False):
        commands = []
        original = (1440, 900, 240)
        owner = {"pid": "123", "signature": "live", "displayId": 2}
        def command(argv):
            commands.append(argv)
            return M.AVD if argv[-2:] == ["avd", "name"] else "dump"
        calls = 0
        def converged(*args):
            nonlocal calls
            calls += 1
            if (fail_phase and calls == 2) or (fail_restore and calls == 6):
                raise RuntimeError("Observed owner or geometry mismatch")
            return {"mode": args[2]}
        with patch.object(M, "run", side_effect=command), patch.object(M, "profile", return_value=owner), \
                patch.object(M, "android_mode", return_value=original), patch.object(M, "await_mode", side_effect=converged):
            result = M.verify(["adb", "-s", "emulator-5556"], Path("."))
        mutations = [c for c in commands if "multidisplay" in c]
        self.assertEqual(mutations[-1][-4:], ["1440", "900", "240", "0"])
        return result

    def test_restores_exact_original_density_after_phases(self):
        self.assertTrue(self.exercise()["passed"])

    def test_phase_failure_restores_and_reports_failure(self):
        result = self.exercise(fail_phase=True)
        self.assertFalse(result["passed"])
        self.assertIn("restored", result)

    def test_failed_restoration_never_reports_success(self):
        result = self.exercise(fail_restore=True)
        self.assertFalse(result["passed"])
        self.assertIn("cleanupError", result)

    def test_android_mode_uses_selected_display_and_density(self):
        dump = 'mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, real 1080 x 2400, density 420}\n' \
               'mBaseDisplayInfo=DisplayInfo{"Emulator 2D Display", displayId 12, real 720 x 1280, density 240}'
        self.assertEqual(M.android_mode(dump, 12), (720, 1280, 240))
        with self.assertRaises(RuntimeError):
            M.android_mode(dump, 0)


if __name__ == "__main__":
    unittest.main()
