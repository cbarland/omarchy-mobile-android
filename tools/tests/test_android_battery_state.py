import importlib.machinery
import importlib.util
import io
import json
import math
import pathlib
import unittest
from contextlib import redirect_stdout

MODULE = pathlib.Path(__file__).parents[1] / "linux" / "omamobile-android"
loader = importlib.machinery.SourceFileLoader("omamobile_android", str(MODULE))
spec = importlib.util.spec_from_loader("omamobile_android", loader)
cli = importlib.util.module_from_spec(spec)
loader.exec_module(cli)


class FakePortal:
    def __init__(self, reply):
        self.reply = reply
        self.closed = False

    def request(self, op, **fields):
        self.op = op
        return self.reply, b""

    def close(self):
        self.closed = True


class BatteryStateTest(unittest.TestCase):
    def test_valid_and_nullable_values(self):
        valid = {"present": True, "percentage": 12.5, "plugged": False, "state": "charging"}
        self.assertEqual(cli.battery_state(valid), valid)
        unknown = {"present": None, "percentage": None, "plugged": None, "state": "unknown"}
        self.assertEqual(cli.battery_state(unknown), unknown)
        self.assertEqual(cli.battery_state(dict(valid, ok=True)), valid)

    def test_rejects_bad_fields(self):
        base = {"present": True, "percentage": 12.5, "plugged": False, "state": "charging"}
        cases = [("present", 1), ("plugged", 1), ("percentage", True),
                 ("percentage", -1), ("percentage", 101), ("percentage", math.nan),
                 ("state", "broken")]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                candidate = dict(base)
                candidate[key] = value
                with self.assertRaises(RuntimeError):
                    cli.battery_state(candidate)
        missing = dict(base)
        del missing["percentage"]
        with self.assertRaises(RuntimeError):
            cli.battery_state(missing)
        extra = dict(base, extra=False)
        with self.assertRaises(RuntimeError):
            cli.battery_state(extra)

    def test_main_path_outputs_valid_payload_and_closes_portal(self):
        payload = {"ok": True, "present": True, "percentage": 12.5,
                   "plugged": False, "state": "charging"}
        portal = FakePortal(payload)
        old = cli.Portal
        cli.Portal = lambda: portal
        old_argv = __import__("sys").argv
        output = io.StringIO()
        try:
            __import__("sys").argv = [str(MODULE), "battery-state"]
            with redirect_stdout(output):
                cli.main()
        finally:
            __import__("sys").argv = old_argv
            cli.Portal = old
        self.assertEqual(json.loads(output.getvalue()), {key: value for key, value in payload.items() if key != "ok"})
        self.assertTrue(portal.closed)

    def test_main_path_closes_portal_on_validation_failure(self):
        portal = FakePortal({"ok": True, "present": True, "percentage": True,
                             "plugged": False, "state": "charging"})
        old = cli.Portal
        cli.Portal = lambda: portal
        old_argv = __import__("sys").argv
        try:
            __import__("sys").argv = [str(MODULE), "battery-state"]
            with self.assertRaises(RuntimeError):
                cli.main()
        finally:
            __import__("sys").argv = old_argv
            cli.Portal = old
        self.assertTrue(portal.closed)


if __name__ == "__main__":
    unittest.main()
