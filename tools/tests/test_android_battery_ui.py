#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import unittest

MODEL = Path(__file__).resolve().parents[2] / "tools/linux/shell-adapter/AndroidBatteryModel.js"


def project(packet):
    script = """
const M = require(process.argv[1]);
const p = M.project(JSON.parse(process.argv[2]));
console.log(JSON.stringify({p, fraction: M.fraction(p), percentage: M.displayPercentage(p), icon: M.icon(p), label: M.label(p)}));
"""
    return json.loads(subprocess.check_output(["node", "-e", script, str(MODEL), json.dumps(packet)], text=True, encoding="utf-8"))


class BatteryUiTest(unittest.TestCase):
    def test_unknown_absent_and_valid_states(self):
        self.assertEqual(project({})["p"], {"present": None, "percentage": None, "plugged": None, "state": "unknown"})
        absent = project({"present": False, "percentage": 88, "plugged": False, "state": "discharging"})
        self.assertEqual((absent["fraction"], absent["percentage"], absent["icon"]), (0, "—", "?"))
        charging = project({"present": True, "percentage": 42.4, "plugged": True, "state": "charging"})
        self.assertEqual((charging["fraction"], charging["percentage"], charging["label"]), (0.424, "42%", "Charging"))
        full = project({"present": True, "percentage": 100, "plugged": True, "state": "full"})
        self.assertEqual(full["label"], "Fully charged")


if __name__ == "__main__":
    unittest.main()
