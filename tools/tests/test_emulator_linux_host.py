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
    def test_unified_session_uses_its_runtime_and_desktop_shell_environment(self):
        commands = []
        def output(command, **kwargs):
            if command[-1].endswith("ready.properties"):
                self.assertIn("/unified/", command[-1])
                return "ROOTFS=/data/user/0/dev.omamobile.host/files/linux/rootfs\nWAYLAND_DISPLAY=wayland-0\nHYPRLAND_INSTANCE_SIGNATURE=shared-test\n"
            if command[-1] == "ro.product.cpu.abi": return "x86_64\n"
            return "package:/data/app/host/base.apk\n"
        def run(command, **kwargs):
            commands.append(command)
            return subprocess.CompletedProcess(command, 0)
        with patch.object(sys, "argv", [str(SCRIPT), "--adb", "fake-adb", "session", "unified", "echo shared"]), \
                patch("subprocess.run", side_effect=run), patch("subprocess.check_output", side_effect=output):
            with self.assertRaises(SystemExit) as ended:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(ended.exception.code, 0)
        self.assertEqual(len(commands), 1)
        self.assertIn("XDG_RUNTIME_DIR=/run/omamobile/u", commands[0][-1])
        self.assertIn("OMAMOBILE_PROFILE=desktop", commands[0][-1])

    def test_stop_reveals_scrolled_control_before_pressing_enter(self):
        views = iter([
            b'<hierarchy><node text="console output" package="dev.omamobile.host" focused="true" /></hierarchy>',
            b'<hierarchy><node text="STOP GRAPHICAL SESSIONS" package="dev.omamobile.host" focused="true" /></hierarchy>',
        ])
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            return subprocess.CompletedProcess(command, 1 if "pidof" in command else 0, "", "")
        with patch.object(sys, "argv", [str(SCRIPT), "--adb", "fake-adb", "--serial", "emulator-5556", "stop"]), \
                patch("subprocess.run", side_effect=run), patch("subprocess.check_output", side_effect=lambda *a, **k: next(views)), \
                contextlib.redirect_stdout(io.TextIOWrapper(io.BytesIO(), encoding="utf-8")):
            with self.assertRaises(SystemExit) as stopped:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(stopped.exception.code, 0)
        self.assertEqual([c[-1] for c in commands if "keyevent" in c], ["KEYCODE_TAB", "KEYCODE_ENTER"])

    def test_obscuring_system_window_is_not_navigated(self):
        commands = []
        def run(command, **kwargs):
            commands.append(command)
            return subprocess.CompletedProcess(command, 0, "", "")
        with patch.object(sys, "argv", [str(SCRIPT), "--adb", "fake-adb", "start"]), \
                patch("subprocess.run", side_effect=run), \
                patch("subprocess.check_output", return_value=b'<hierarchy><node package="com.android.systemui" /></hierarchy>'), \
                contextlib.redirect_stdout(io.TextIOWrapper(io.BytesIO(), encoding="utf-8")):
            with self.assertRaisesRegex(RuntimeError, "obscured"):
                runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertFalse(any("keyevent" in c for c in commands))

    def test_help_runs_without_adb(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--help"],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("session", result.stdout)
        command_help = subprocess.run([sys.executable, str(SCRIPT), "command", "--help"],
                                      capture_output=True, text=True, timeout=10)
        self.assertEqual(command_help.returncode, 0, command_help.stderr)
        self.assertIn("--allow-app-restart", command_help.stdout)

    def test_command_requires_restart_acknowledgement_before_adb(self):
        calls = []
        with patch.object(sys, "argv", [str(SCRIPT), "--adb", "fake-adb", "command", "printf test"]), \
                patch("subprocess.run", side_effect=lambda *args, **kwargs: calls.append(args) or self.fail("subprocess.run called")), \
                patch("subprocess.check_output", side_effect=lambda *args, **kwargs: calls.append(args) or self.fail("subprocess.check_output called")), \
                contextlib.redirect_stderr(io.StringIO()) as stderr:
            with self.assertRaises(SystemExit) as failed:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(failed.exception.code, 2)
        self.assertEqual(calls, [])
        self.assertIn("--allow-app-restart", stderr.getvalue())
        self.assertIn("use session", stderr.getvalue())

    def test_command_acknowledgement_preserves_instrumentation_path(self):
        commands = []

        def run(command, **kwargs):
            commands.append(command)
            return subprocess.CompletedProcess(command, 0, "OK (1 test)\n", "")

        with patch.object(sys, "argv", [str(SCRIPT), "--adb", "fake-adb", "--serial", "emulator-5556",
                                         "command", "printf test", "--allow-app-restart"]), \
                patch("subprocess.run", side_effect=run), \
                patch("subprocess.check_output", return_value=b"command output\n"), \
                contextlib.redirect_stdout(io.TextIOWrapper(io.BytesIO(), encoding="utf-8")):
            runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(commands[0][3:6], ["shell", "am", "instrument"])

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
