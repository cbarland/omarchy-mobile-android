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
    }) == {"networkKind": "unknown", "wifiAvailable": True, "bluetoothAvailable": False,
            "wifiState": "unknown", "bluetoothState": "unsupported"}
    assert run_model({"network": {"state": "available", "transports": ["cellular"]}})["networkKind"] == "cellular"
    assert run_model({"network": {"state": "available", "transports": ["ethernet"]}})["networkKind"] == "ethernet"
    assert run_model({"network": {"state": "available", "transports": ["vpn"]}})["networkKind"] == "connected"
    assert run_model({"network": {"state": "unavailable", "transports": []}})["networkKind"] == "unavailable"
    assert run_model({}) == {"networkKind": "unknown", "wifiAvailable": True,
                             "bluetoothAvailable": True, "wifiState": "unknown",
                             "bluetoothState": "unknown"}
    script = """
const M = require(process.argv[1]);
const a = {}, b = {};
let owners = [];
owners = M.requestBarOwners(owners, a, true);
owners = M.requestBarOwners(owners, a, true);
owners = M.requestBarOwners(owners, b, true);
const two = owners.length;
owners = M.requestBarOwners(owners, a, false);
const one = owners.length;
owners = M.requestBarOwners(owners, b, false);
console.log(JSON.stringify({two, one, zero: owners.length,
  horizontal: M.radioGridColumns(false, 2), vertical: M.radioGridColumns(true, 2),
  labels: ["unknown", "unavailable", "wifi", "cellular", "connected"].map(M.networkDisplay),
  tooltips: ["unknown", "unavailable", "wifi", "cellular", "connected"].map(M.networkTooltip)}));
"""
    output = subprocess.check_output(["node", "-e", script, str(MODEL)], text=True, encoding="utf-8")
    assert json.loads(output) == {
        "two": 2, "one": 1, "zero": 0, "horizontal": 2, "vertical": 1,
        "labels": ["?", "disconnected", "wifi", "CELL", "NET"],
        "tooltips": ["Network unknown", "No default network · Open Android settings",
                     "Wi-Fi connected · Open Android settings", "Cellular connected · Open Android settings",
                     "Network connected · Open Android settings"]}
    print("android radio presentation mapping passed")


if __name__ == "__main__":
    main()
