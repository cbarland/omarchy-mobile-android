from contextlib import redirect_stderr, redirect_stdout
from importlib.machinery import SourceFileLoader
import io
from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "linux" / "omamobile-android"
cli = SourceFileLoader("omamobile_android_settings", str(SCRIPT)).load_module()


class FakePortal:
    def __init__(self, reply):
        self.reply = reply
        self.operation = None
        self.closed = False

    def request(self, operation):
        self.operation = operation
        return self.reply, b""

    def close(self):
        self.closed = True


class AndroidSettingsCliTest(unittest.TestCase):
    def run_cli(self, action, reply, target=None):
        portal = FakePortal(reply)
        old_argv = sys.argv
        output, errors = io.StringIO(), io.StringIO()
        try:
            cli.Portal = lambda: portal
            sys.argv = [str(SCRIPT), action] + ([target] if target else [])
            with redirect_stdout(output), redirect_stderr(errors):
                cli.main()
        finally:
            sys.argv = old_argv
        return portal, output.getvalue(), errors.getvalue()

    def test_fixed_actions_are_acknowledged_and_close_connection(self):
        for action, message in (("network-settings", "network"), ("bluetooth-settings", "Bluetooth")):
            with self.subTest(action=action):
                portal, output, _ = self.run_cli(action, {"ok": True, "requested": True})
                self.assertEqual(action, portal.operation)
                self.assertTrue(portal.closed)
                self.assertEqual(f"Android {message} settings requested\n", output)

    def test_unaccepted_launch_reply_fails(self):
        for reply in ({"ok": False, "error": "No activity"}, {"ok": True}):
            for action in ("network-settings", "bluetooth-settings"):
                with self.subTest(action=action, reply=reply):
                    portal = FakePortal(reply)
                    old_argv = sys.argv
                    try:
                        cli.Portal = lambda: portal
                        sys.argv = [str(SCRIPT), action]
                        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                            with self.assertRaises(RuntimeError):
                                cli.main()
                    finally:
                        sys.argv = old_argv
                    self.assertTrue(portal.closed)

    def test_arbitrary_target_is_rejected_before_portal_connection(self):
        old_argv = sys.argv
        portal_created = False

        def make_portal():
            nonlocal portal_created
            portal_created = True
            return FakePortal({"ok": True, "requested": True})

        try:
            cli.Portal = make_portal
            sys.argv = [str(SCRIPT), "network-settings", "com.example.anything"]
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    cli.main()
        finally:
            sys.argv = old_argv
        self.assertEqual(2, error.exception.code)
        self.assertFalse(portal_created)


if __name__ == "__main__":
    unittest.main()
