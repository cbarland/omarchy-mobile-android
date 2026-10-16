from contextlib import redirect_stderr, redirect_stdout
from importlib.machinery import SourceFileLoader
import io
import json
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "linux" / "omamobile-android"
fixture = SourceFileLoader("omamobile_android_fixture", str(SCRIPT)).load_module()


class FakePortal:
    def __init__(self, reply):
        self.reply = reply
        self.closed = False

    def request(self, operation):
        self.operation = operation
        return self.reply, b""

    def close(self):
        self.closed = True


class DeviceStateCliTest(unittest.TestCase):
    def run_cli(self, reply):
        fake = FakePortal(reply)
        old_argv = sys.argv
        output = io.StringIO()
        try:
            fixture.Portal = lambda: fake
            sys.argv = [str(SCRIPT), "device-state"]
            with redirect_stdout(output), redirect_stderr(io.StringIO()):
                fixture.main()
        finally:
            sys.argv = old_argv
        return fake, output.getvalue()

    def test_valid_state_is_json_and_closes_portal(self):
        fake, output = self.run_cli({
            "ok": True, "deviceLocked": False, "interactive": True, "keyguardLocked": False
        })
        self.assertTrue(fake.closed)
        self.assertEqual({"deviceLocked": False, "interactive": True, "keyguardLocked": False},
                         json.loads(output))

    def test_display_settings_opens_fixed_host_panel_and_closes(self):
        fake = FakePortal({"ok": True, "requested": True})
        old_argv = sys.argv
        output = io.StringIO()
        try:
            fixture.Portal = lambda: fake
            sys.argv = [str(SCRIPT), "display-settings"]
            with redirect_stdout(output), redirect_stderr(io.StringIO()):
                fixture.main()
        finally:
            sys.argv = old_argv
        self.assertTrue(fake.closed)
        self.assertEqual("display-settings", fake.operation)
        self.assertEqual("Android display settings requested\n", output.getvalue())

    def test_display_settings_rejects_missing_launch_ack(self):
        fake = FakePortal({"ok": True})
        old_argv = sys.argv
        try:
            fixture.Portal = lambda: fake
            sys.argv = [str(SCRIPT), "display-settings"]
            with self.assertRaises(RuntimeError):
                fixture.main()
        finally:
            sys.argv = old_argv
        self.assertTrue(fake.closed)
        self.assertEqual("display-settings", fake.operation)

    def test_invalid_state_fields_fail_and_close_portal(self):
        for value in (None, "false", 0):
            with self.subTest(value=value):
                fake = FakePortal({
                    "ok": True, "deviceLocked": value, "interactive": True, "keyguardLocked": False
                })
                old_argv = sys.argv
                try:
                    fixture.Portal = lambda: fake
                    sys.argv = [str(SCRIPT), "device-state"]
                    with self.assertRaises(RuntimeError):
                        fixture.main()
                finally:
                    sys.argv = old_argv
                self.assertTrue(fake.closed)

        fake = FakePortal({"ok": True, "interactive": True, "keyguardLocked": False})
        old_argv = sys.argv
        try:
            fixture.Portal = lambda: fake
            sys.argv = [str(SCRIPT), "device-state"]
            with self.assertRaises(RuntimeError):
                fixture.main()
        finally:
            sys.argv = old_argv
        self.assertTrue(fake.closed)


if __name__ == "__main__":
    unittest.main()
