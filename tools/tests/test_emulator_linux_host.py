import contextlib
import io
from pathlib import Path
import runpy
import subprocess
import sys
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "emulator-linux.py"


class EmulatorHelperTests(unittest.TestCase):
    def test_help_runs_without_adb(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--help"],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("session", result.stdout)

    def test_start_retries_direct_bounded_adb_probe(self):
        # Exercise the actual CLI path without launching any process/device.
        # A timed-out probe must retry directly, without an intermediate Python
        # process whose surviving adb child could retain the output pipes.
        probes = []

        def output(command, *, timeout, **kwargs):
            self.assertGreater(timeout, 0)
            if command[3] == "exec-out" and command[-1].endswith(".xml"):
                return b'<hierarchy><node text="START OMARCHY SCREENS" package="dev.omamobile.host" focused="true" /></hierarchy>'
            if command[-1].endswith("ready.properties"):
                return "ROOTFS=/data/user/0/dev.omamobile.host/files/linux/rootfs\nWAYLAND_DISPLAY=wayland-7\nHYPRLAND_INSTANCE_SIGNATURE=session-test\n"
            if command[3:6] == ["shell", "pm", "path"]:
                return "package:/data/app/host/base.apk\n"
            if command[-1] == "ro.product.cpu.abi":
                return "x86_64\n"
            self.fail(f"Unexpected metadata operation: {command}")

        def run(command, *, timeout, **kwargs):
            self.assertEqual(command[:3], ["fake-adb", "-s", "emulator-5556"])
            self.assertGreater(timeout, 0)
            if len(command) == 5 and command[3] == "shell":
                probes.append(command)
                self.assertLessEqual(timeout, 5)
                self.assertIn("WAYLAND_DISPLAY=wayland-7", command[4])
                if len(probes) == 1:
                    raise subprocess.TimeoutExpired(command, timeout)
                return subprocess.CompletedProcess(command, 0, '[{"width":1920}]', "")
            return subprocess.CompletedProcess(command, 0, "", "")

        # Give the CLI its own reconfigurable stdout without changing test I/O.
        with patch.object(sys, "argv", [str(SCRIPT), "--adb", "fake-adb", "--serial", "emulator-5556", "start"]), \
                patch("subprocess.run", side_effect=run), patch("subprocess.check_output", side_effect=output), \
                patch("time.sleep"), contextlib.redirect_stdout(io.TextIOWrapper(io.BytesIO(), encoding="utf-8")):
            runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(len(probes), 3)  # One retry, then both profiles ready.


if __name__ == "__main__":
    unittest.main()
