import importlib.util
import subprocess
import unittest
from unittest.mock import patch
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "verify-display-reconnect.py"
SPEC = importlib.util.spec_from_file_location("verify_display_reconnect", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class FakeProcess:
    def __init__(self, kill_reaps):
        self.alive = True
        self.kill_reaps = kill_reaps
        self.terminated = False
        self.killed = False

    @property
    def returncode(self):
        return 0 if not self.alive else None

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True

    def kill(self):
        self.killed = True
        if self.kill_reaps:
            self.alive = False

    def communicate(self, timeout):
        if self.alive:
            raise subprocess.TimeoutExpired("fake-instrumentation", timeout, output=b"partial")
        return ("complete", "")


class ReapTests(unittest.TestCase):
    def test_adb_timeout_partial_bytes_are_json_safe(self):
        log = []
        adb = MODULE.Adb("adb", "emulator-5554", log)
        timeout = subprocess.TimeoutExpired(["adb"], 1, output=b"partial", stderr=b"diagnostic")
        with patch.object(MODULE.subprocess, "run", side_effect=timeout):
            with self.assertRaises(MODULE.AuditError):
                adb.run(["shell", "true"], timeout=1)
        import json
        json.dumps(log)
        self.assertEqual(log[0]["stdout"], "partial")
        self.assertEqual(log[0]["stderr"], "diagnostic")

    def test_terminate_timeout_then_kill_reaps_and_records_output(self):
        process = FakeProcess(kill_reaps=True)
        instrumentation = {}
        MODULE.reap_local_instrumentation(process, instrumentation)
        self.assertTrue(process.terminated)
        self.assertTrue(process.killed)
        self.assertTrue(instrumentation["forcedLocalKill"])
        self.assertTrue(instrumentation["localKillReaped"])
        self.assertEqual(instrumentation["stdout"], "complete")
        self.assertEqual(instrumentation["remoteStatus"], "unknown")

    def test_kill_timeout_returns_without_raising_or_losing_report(self):
        process = FakeProcess(kill_reaps=False)
        instrumentation = {}
        MODULE.reap_local_instrumentation(process, instrumentation)
        self.assertTrue(instrumentation["forcedLocalKill"])
        self.assertFalse(instrumentation["localKillReaped"])
        self.assertIn("kill communication timed out", instrumentation["cleanupErrors"])
        self.assertEqual(instrumentation["stdout"], "partial")

if __name__ == "__main__":
    unittest.main()
