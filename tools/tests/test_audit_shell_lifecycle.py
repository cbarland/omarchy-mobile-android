import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / "tools/linux/audit-shell-lifecycle"


class AuditShellLifecycleTest(unittest.TestCase):
    def run_audit(self, *, ping="ok", timeout=3, missing=None, sleep=False, profile="phone"):
        if os.name != "posix":
            self.skipTest("requires POSIX shell commands")
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory)
            (fake / "quickshell").write_text("#!/bin/sh\nprintf '%s' '[{\"id\":\"shell\"}]'\n")
            if missing != "omarchy-shell":
                (fake / "omarchy-shell").write_text(
                    "#!/bin/sh\n"
                    f"case \"$2\" in ping|reloadConfig) printf '%s' '{ping}' ;; *) printf '%s' 'ok' ;; esac\n")
            if sleep:
                (fake / "quickshell").write_text("#!/bin/sh\nsleep 1\n")
            for command in fake.iterdir():
                command.chmod(0o755)
            environment = os.environ.copy()
            environment["PATH"] = str(fake)
            environment["OMAMOBILE_PROFILE"] = profile
            return subprocess.run([sys.executable, str(AUDIT), "--profile", profile,
                                   "--timeout", str(timeout)],
                                  env=environment, capture_output=True, text=True)

    def test_records_successful_existing_commands(self):
        result = self.run_audit()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertTrue(report["passed"])
        self.assertEqual(report["checks"]["discovery"]["instances"], 1)
        self.assertTrue(report["checks"]["ping"]["ack"])
        self.assertTrue(report["checks"]["reload"]["ack"])

    def test_rejects_non_acknowledgement(self):
        result = self.run_audit(ping="not ready")
        self.assertEqual(result.returncode, 1)
        self.assertFalse(json.loads(result.stdout)["passed"])

    def test_missing_command_is_reported(self):
        result = self.run_audit(missing="omarchy-shell")
        self.assertEqual(result.returncode, 1)
        report = json.loads(result.stdout)
        self.assertIn("error", report["checks"]["ping"])

    def test_timeout_is_reported_without_json_crash(self):
        result = self.run_audit(timeout=0.01, sleep=True)
        self.assertEqual(result.returncode, 1)
        report = json.loads(result.stdout)
        self.assertIsNone(report["checks"]["discovery"]["status"])

    def test_profile_mismatch_is_rejected(self):
        if os.name != "posix":
            self.skipTest("requires POSIX shell commands")
        environment = os.environ.copy()
        environment["OMAMOBILE_PROFILE"] = "phone"
        result = subprocess.run([sys.executable, str(AUDIT), "--profile", "desktop"],
                                env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("does not match", json.loads(result.stdout)["error"])


if __name__ == "__main__":
    unittest.main()
