"""Exercise the Android session environment exported by start-shell."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
START = ROOT / "tools" / "linux" / "start-shell"


class StartShellTest(unittest.TestCase):
    def run_start(self, overrides=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_bin = root / "bin"
            fake_bin.mkdir()
            capture = root / "environment.json"
            quickshell = fake_bin / "quickshell"
            quickshell.write_text(
                "#!/bin/sh\n"
                f"env | python3 -c 'import json,os,sys; json.dump(dict(os.environ), open({str(capture)!r}, \"w\"))'\n",
                encoding="utf-8",
            )
            quickshell.chmod(0o755)
            environment = os.environ.copy()
            environment.update({
                "HOME": str(root / "home"),
                "PATH": str(fake_bin) + os.pathsep + environment.get("PATH", ""),
                "OMARCHY_PATH": str(root / "omarchy"),
            })
            environment.update(overrides or {})
            result = subprocess.run(
                ["bash", str(START), "desktop"],
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            return json.loads(capture.read_text(encoding="utf-8"))

    def test_android_defaults_supply_upstream_desktop_environment(self):
        environment = self.run_start()
        self.assertEqual(environment["OMAMOBILE_SESSION_HOST"], "android")
        self.assertEqual(environment["XDG_CURRENT_DESKTOP"], "Hyprland")
        self.assertEqual(environment["XDG_SESSION_DESKTOP"], "hyprland")
        self.assertEqual(environment["DESKTOP_SESSION"], "hyprland")
        self.assertEqual(environment["XDG_SESSION_TYPE"], "wayland")
        self.assertEqual(environment["TERMINAL"], "xdg-terminal-exec")

    def test_existing_session_values_are_preserved(self):
        environment = self.run_start({
            "XDG_CURRENT_DESKTOP": "CustomWM",
            "XDG_SESSION_DESKTOP": "custom",
            "DESKTOP_SESSION": "custom-session",
            "XDG_SESSION_TYPE": "custom-wayland",
            "TERMINAL": "foot",
        })
        self.assertEqual(environment["XDG_CURRENT_DESKTOP"], "CustomWM")
        self.assertEqual(environment["XDG_SESSION_DESKTOP"], "custom")
        self.assertEqual(environment["DESKTOP_SESSION"], "custom-session")
        self.assertEqual(environment["XDG_SESSION_TYPE"], "custom-wayland")
        self.assertEqual(environment["TERMINAL"], "foot")


if __name__ == "__main__":
    unittest.main()
