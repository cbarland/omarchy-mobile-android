import importlib.machinery
import importlib.util
import io
import json
import pathlib
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout

MODULE = pathlib.Path(__file__).parents[1] / "linux" / "omamobile-android"
loader = importlib.machinery.SourceFileLoader("omamobile_android_radio", str(MODULE))
spec = importlib.util.spec_from_loader("omamobile_android_radio", loader)
cli = importlib.util.module_from_spec(spec)
loader.exec_module(cli)


def connected_reply():
    return {
        "ok": True,
        "network": {"state": "connected", "transports": ["wifi", "vpn"],
                    "internetCapable": True, "validated": False},
        "wifi": {"supported": True, "state": "enabled"},
        "bluetooth": {"supported": None, "state": "unknown"},
    }


class FakePortal:
    def __init__(self, reply):
        self.reply = reply
        self.operation = None
        self.closed = False

    def request(self, operation):
        self.operation = operation
        if not self.reply.get("ok"):
            raise RuntimeError(self.reply.get("error", "Android app operation failed"))
        return self.reply, b""

    def close(self):
        self.closed = True


class RadioStateTest(unittest.TestCase):
    def test_accepts_connected_disconnected_and_unknown_observations(self):
        connected = connected_reply()
        self.assertEqual(cli.radio_state(connected), {key: value for key, value in connected.items() if key != "ok"})
        disconnected = {
            "network": {"state": "disconnected", "transports": [],
                        "internetCapable": None, "validated": None},
            "wifi": {"supported": False, "state": "unsupported"},
            "bluetooth": {"supported": True, "state": "off"},
        }
        self.assertEqual(cli.radio_state({"ok": True, **disconnected}), disconnected)
        unknown = {
            "network": {"state": "unknown", "transports": None,
                        "internetCapable": None, "validated": None},
            "wifi": {"supported": True, "state": "unknown"},
            "bluetooth": {"supported": None, "state": "unknown"},
        }
        self.assertEqual(cli.radio_state({"ok": True, **unknown}), unknown)

    def test_rejects_malformed_or_inconsistent_state_at_boundary(self):
        base = connected_reply()
        cases = []
        cases.append({key: value for key, value in base.items() if key != "ok"})
        cases.append(dict(base, extra=False))
        cases.append({key: value for key, value in base.items() if key != "wifi"})
        for key, value in (("state", "invalid"), ("transports", []), ("transports", ["wifi", "wifi"]),
                           ("transports", ["shell"]), ("internetCapable", 1),
                           ("validated", "no")):
            changed = json.loads(json.dumps(base))
            changed["network"][key] = value
            cases.append(changed)
        unsupported_mismatch = json.loads(json.dumps(base))
        unsupported_mismatch["wifi"] = {"supported": False, "state": "enabled"}
        cases.append(unsupported_mismatch)
        supported_mismatch = json.loads(json.dumps(base))
        supported_mismatch["bluetooth"] = {"supported": True, "state": "unsupported"}
        cases.append(supported_mismatch)
        for candidate in cases:
            with self.subTest(candidate=candidate), self.assertRaises(RuntimeError):
                cli.radio_state(candidate)

    def test_real_cli_path_uses_fixed_operation_and_closes_socket(self):
        reply = connected_reply()
        portal = FakePortal(reply)
        original_portal, original_argv = cli.Portal, sys.argv
        output, errors = io.StringIO(), io.StringIO()
        try:
            cli.Portal = lambda: portal
            sys.argv = [str(MODULE), "radio-state"]
            with redirect_stdout(output), redirect_stderr(errors):
                cli.main()
        finally:
            cli.Portal, sys.argv = original_portal, original_argv
        self.assertEqual("radio-state", portal.operation)
        self.assertTrue(portal.closed)
        self.assertEqual({key: value for key, value in reply.items() if key != "ok"},
                         json.loads(output.getvalue()))
        self.assertEqual("", errors.getvalue())

    def test_unknown_action_target_is_rejected_before_portal_connect(self):
        portal_created = False

        def make_portal():
            nonlocal portal_created
            portal_created = True
            return FakePortal(connected_reply())

        original_portal, original_argv = cli.Portal, sys.argv
        try:
            cli.Portal = make_portal
            sys.argv = [str(MODULE), "radio-state", "wifi"]
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    cli.main()
        finally:
            cli.Portal, sys.argv = original_portal, original_argv
        self.assertEqual(2, error.exception.code)
        self.assertFalse(portal_created)

    def test_error_ack_fails_and_still_closes_socket(self):
        portal = FakePortal({"ok": False, "error": "Radio service unavailable"})
        original_portal, original_argv = cli.Portal, sys.argv
        try:
            cli.Portal = lambda: portal
            sys.argv = [str(MODULE), "radio-state"]
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, "Radio service unavailable"):
                    cli.main()
        finally:
            cli.Portal, sys.argv = original_portal, original_argv
        self.assertTrue(portal.closed)


if __name__ == "__main__":
    unittest.main()
