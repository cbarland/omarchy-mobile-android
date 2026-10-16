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
    @unittest.skipUnless(os.name == "posix", "requires POSIX shell commands")
    def run_audit(self, *, ping="ok"):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory)
            (fake / "quickshell").write_text("#!/bin/sh\nprintf '%s' '[{\"id\":\"shell\"}]'\n")
            (fake / "omarchy-shell").write_text(
                "#!/bin/sh\n"
                f"case \"$2\" in ping|reloadConfig) printf '%s' '{ping}' ;; *) printf '%s' 'ok' ;; esac\n"
            )
            for command in fake.iterdir():
                command.chmod(0o755)
            environment = os.environ.copy()
            environment["PATH"] = str(fake)
            return subprocess.run([sys.executable, str(AUDIT), "--profile", "phone"],
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


if __name__ == "__main__":
    unittest.main()
