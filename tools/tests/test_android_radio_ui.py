#!/usr/bin/env python3
"""Pure checks for the Android radio presentation mapping."""
import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "tools/linux/shell-adapter/AndroidRadioModel.js"


def run_model(packet):
    script = """
const M = require(process.argv[1]);
const packet = JSON.parse(process.argv[2]);
const p = M.project(packet);
console.log(JSON.stringify({
  networkKind: M.networkKind(p.network),
  wifiAvailable: M.radioAvailable(p.wifi),
  bluetoothAvailable: M.radioAvailable(p.bluetooth),
  wifiState: p.wifi.state,
  bluetoothState: p.bluetooth.state
}));
"""
    output = subprocess.check_output(["node", "-e", script, str(MODEL), json.dumps(packet)], text=True)
    return json.loads(output)


def main():
    assert run_model({
        "network": {"state": "available", "transports": ["wifi"], "validated": False},
        "wifi": {"supported": True, "state": "enabled"},
        "bluetooth": {"supported": True, "state": "off"},
    }) == {"networkKind": "wifi", "wifiAvailable": True, "bluetoothAvailable": True,
            "wifiState": "enabled", "bluetoothState": "off"}
    assert run_model({
        "network": {"state": "unknown", "transports": None},
        "wifi": {"supported": None, "state": "unknown"},
        "bluetooth": {"supported": False, "state": "unsupported"},
    }) == {"networkKind": "disconnected", "wifiAvailable": True, "bluetoothAvailable": False,
            "wifiState": "unknown", "bluetoothState": "unsupported"}
    assert run_model({}) == {"networkKind": "disconnected", "wifiAvailable": True,
                             "bluetoothAvailable": True, "wifiState": "unknown",
                             "bluetoothState": "unknown"}
    print("android radio presentation mapping passed")


if __name__ == "__main__":
    main()
