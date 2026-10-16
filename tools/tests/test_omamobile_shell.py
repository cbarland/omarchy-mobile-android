import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "tools/linux/omamobile-shell"

class OmamobileShellTest(unittest.TestCase):
    def run_helper(self, action, *, list_status=0, list_output='[]', ping_status=0, ping_output='ok'):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory)
            log = fake / "calls"
            (fake / "quickshell").write_text(
                "#!/bin/bash\n"
                f"printf '%s\\n' '{list_output}'\n"
                f"exit {list_status}\n")
            (fake / "omarchy-shell").write_text(
                "#!/bin/bash\n"
                f"printf '%s\\n' '{ping_output}'\n"
                f"exit {ping_status}\n")
            for command in (fake / "quickshell", fake / "omarchy-shell"):
                command.chmod(0o755)
            environment = os.environ.copy()
            environment.update(PATH=str(fake), OMAMOBILE_SESSION_HOST="android")
            return subprocess.run(["/bin/bash", str(HELPER), action], env=environment,
                                  text=True, capture_output=True)

    def test_status_uses_ipc_when_discovery_is_missing(self):
        result = self.run_helper("status", list_status=1, list_output='not found')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ipc=ready", result.stdout)
        self.assertIn("discovery=unavailable", result.stdout)

    def test_status_reports_empty_successful_discovery(self):
        result = self.run_helper("status", list_output='[]')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("discovery=empty", result.stdout)

    def test_status_fails_when_shell_ipc_is_unavailable(self):
        result = self.run_helper("status", ping_output='not ready')
        self.assertEqual(result.returncode, 1)
        self.assertIn("ipc=unavailable", result.stderr)

    def test_reload_preserves_cli_failure(self):
        result = self.run_helper("reload", ping_status=23, ping_output='failed')
        self.assertEqual(result.returncode, 23)

    def test_requires_android_session(self):
        environment = os.environ.copy()
        environment["OMAMOBILE_SESSION_HOST"] = "desktop"
        result = subprocess.run(["/bin/bash", str(HELPER), "status"], env=environment,
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 69)

if __name__ == "__main__":
    unittest.main()
