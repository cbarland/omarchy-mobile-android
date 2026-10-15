import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SHIM = ROOT / "tools" / "linux" / "session-exec"


class SessionExecTest(unittest.TestCase):
    def run_shim(self, *args, env=None):
        command = ["bash", str(SHIM), *args]
        merged = os.environ.copy()
        merged.update(env or {})
        return subprocess.run(command, env=merged, text=True, capture_output=True)

    def test_bare_invocation_preserves_exact_arguments_and_environment(self):
        result = self.run_shim(
            "bash", "-c", "printf '%s\\n' \"$@\"; printf 'token=%s host=%s wayland=%s dbus=%s sig=%s\\n' \"$HL_EXEC_RULE_TOKEN\" \"$OMAMOBILE_SESSION_HOST\" \"$WAYLAND_DISPLAY\" \"$DBUS_SESSION_BUS_ADDRESS\" \"$HYPRLAND_INSTANCE_SIGNATURE\"", "child",
            "literal space", "semi;colon", "dollar$(echo nope)",
            env={"OMAMOBILE_SESSION_HOST": "android", "HL_EXEC_RULE_TOKEN": "rule-token",
                 "WAYLAND_DISPLAY": "wayland-7", "DBUS_SESSION_BUS_ADDRESS": "unix:path=/run/bus",
                 "HYPRLAND_INSTANCE_SIGNATURE": "sig-7"},
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "literal space\nsemi;colon\ndollar$(echo nope)\n"
                                      "token=rule-token host=android wayland=wayland-7 "
                                      "dbus=unix:path=/run/bus sig=sig-7\n")

    def test_standalone_separator_form(self):
        result = self.run_shim(
            "--", "bash", "-c", "printf '%s\\n' \"$1\"", "child", "a b",
            env={"OMAMOBILE_SESSION_HOST": "android"},
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "a b\n")

    def test_separator_only_and_empty_executable_are_rejected(self):
        for args in (("--",), ("--", "")):
            with self.subTest(args=args):
                result = self.run_shim(*args, env={"OMAMOBILE_SESSION_HOST": "android"})
                self.assertEqual(result.returncode, 64)

    def test_child_nonzero_exit_is_preserved(self):
        result = self.run_shim("--", "bash", "-c", "exit 37", env={"OMAMOBILE_SESSION_HOST": "android"})
        self.assertEqual(result.returncode, 37)

    def test_missing_executable_returns_127(self):
        result = self.run_shim("definitely-missing-session-executable", env={"OMAMOBILE_SESSION_HOST": "android"})
        self.assertEqual(result.returncode, 127)

    def test_non_executable_file_returns_126(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "not-executable"
            path.write_text("#!/bin/sh\nexit 0\n")
            path.chmod(0o644)
            result = self.run_shim(str(path), env={"OMAMOBILE_SESSION_HOST": "android"})
            self.assertEqual(result.returncode, 126)

    def test_empty_and_launcher_options_are_rejected(self):
        for args in ((), ("-s", "app"), ("--bad", "app")):
            with self.subTest(args=args):
                result = self.run_shim(*args, env={"OMAMOBILE_SESSION_HOST": "android"})
                self.assertEqual(result.returncode, 64)

    def test_outside_android_host_is_rejected_without_running_child(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "ran"
            result = self.run_shim(
                "--", "bash", "-c", f"touch {marker}",
                env={"OMAMOBILE_SESSION_HOST": "desktop"},
            )
            self.assertEqual(result.returncode, 69)
            self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()
