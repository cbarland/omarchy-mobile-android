#!/usr/bin/env python3
"""Audit prepared Omarchy display sessions across sleep, wake, and unlock."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

AVD = "Omamobile_Adapter_Test_API35"
APP = "dev.omamobile.host"
PROFILES = ("phone", "desktop")


def remaining(deadline, default=30):
    if deadline is None:
        return default
    budget = deadline - time.monotonic()
    if budget <= 0:
        raise TimeoutError("shared probe deadline exhausted")
    return min(default, budget)


def run(command, timeout=30, check=True, deadline=None):
    result = subprocess.run(command, capture_output=True, text=True,
                            timeout=remaining(deadline, timeout))
    if check and result.returncode:
        raise RuntimeError(f"Command failed: {command!r}: {result.stdout[-800:]}{result.stderr[-800:]}")
    return result


def power_state(text):
    match = re.search(r"\bmWakefulness=(\w+)", text)
    if not match:
        raise RuntimeError("Power state did not expose mWakefulness")
    return match.group(1)


def keyguard_state(text):
    fields = {}
    for name in ("mShowingLockscreen", "mDreamingLockscreen", "mKeyguardShown"):
        match = re.search(rf"\b{name}=(true|false)", text, re.IGNORECASE)
        if match:
            fields[name] = match.group(1).lower() == "true"
    match = re.search(r"\b(?:isKeyguardShowing|mIsShowing|showing)=(true|false)", text, re.IGNORECASE)
    if match:
        fields["showing"] = match.group(1).lower() == "true"
    return fields


def ready(adb, profile, deadline=None):
    result = run(adb + ["shell", "run-as", APP, "cat", f"files/graphics/{profile}/ready.properties"], deadline=deadline)
    values = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    pid = run(adb + ["shell", "pidof", f"{APP}:{profile}"], check=False, deadline=deadline)
    return {
        "pid": pid.stdout.strip(),
        "signature": values.get("HYPRLAND_INSTANCE_SIGNATURE"),
        "displayId": values.get("ANDROID_DISPLAY_ID"),
        "rootfs": values.get("ROOTFS"),
    }


def session_health(root, adb, profile, deadline=None):
    command = [sys.executable, str(root / "tools/emulator-linux.py"),
               "--adb", adb[0], "--serial", adb[2], "session", profile,
               "hyprctl -j monitors"]
    result = run(command, timeout=30, check=False, deadline=deadline)
    try:
        monitors = json.loads(result.stdout)
        healthy = (result.returncode == 0 and isinstance(monitors, list) and bool(monitors)
                   and all(isinstance(item, dict) and int(item.get("width", 0)) > 0
                           and int(item.get("height", 0)) > 0 for item in monitors))
    except (ValueError, TypeError):
        monitors = None
        healthy = False
    return {"healthy": healthy, "monitors": monitors,
            "stdout": result.stdout[-800:], "stderr": result.stderr[-800:]}


def owner_matches(before, after):
    return all(after[name][key] == before[name][key]
               for name in PROFILES for key in ("pid", "signature", "displayId"))


def geometry(snapshot_value):
    return {name: [(int(item["width"]), int(item["height"])) for item in value["monitors"]]
            for name, value in snapshot_value["health"].items()
            if value["healthy"]}


def lifecycle_passes(baseline, after, restored_power):
    return (all(item["healthy"] for item in after["health"].values())
            and after["power"] == "Awake"
            and after["keyguard"].get("showing") is False
            and owner_matches(baseline["profiles"], after["profiles"])
            and geometry(after) == geometry(baseline)
            and restored_power == "Awake")


def baseline_ready(snapshot_value):
    return (snapshot_value["power"] == "Awake"
            and snapshot_value["keyguard"].get("showing") is False
            and all(item["healthy"] for item in snapshot_value["health"].values())
            and all(profile["pid"] and profile["signature"] and profile["displayId"]
                    for profile in snapshot_value["profiles"].values()))


def snapshot(root, adb, deadline=None):
    power = run(adb + ["shell", "dumpsys", "power"], deadline=deadline).stdout
    window = run(adb + ["shell", "dumpsys", "window", "policy"], deadline=deadline).stdout
    return {
        "power": power_state(power),
        "keyguard": keyguard_state(window),
        "profiles": {profile: ready(adb, profile, deadline) for profile in PROFILES},
        "health": {profile: session_health(root, adb, profile, deadline) for profile in PROFILES},
    }


def validate_avd(name):
    if name != AVD:
        raise RuntimeError(f"Refusing AVD {name!r}; expected {AVD}")


def wait_power(adb, expected, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = power_state(run(adb + ["shell", "dumpsys", "power"], deadline=deadline).stdout)
        if state == expected:
            return state
        time.sleep(.25)
    raise RuntimeError(f"Power state did not reach {expected!r}")


def wait_healthy(root, adb, baseline, timeout=15):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = snapshot(root, adb, deadline)
        if (all(item["healthy"] for item in last["health"].values())
                and last["power"] == "Awake"
                and last["keyguard"].get("showing") is False
                and owner_matches(baseline["profiles"], last["profiles"])
                and geometry(last) == geometry(baseline)):
            return last
        time.sleep(.25)
    raise RuntimeError("Hyprland owners, signatures, display IDs, or geometry did not recover: "
                       + json.dumps(last, sort_keys=True)[:1200])


def verify(adb, root):
    avd = run(adb + ["emu", "avd", "name"]).stdout.splitlines()[0].strip()
    validate_avd(avd)
    baseline = snapshot(root, adb)
    initial_power = baseline["power"]
    result = {"serial": adb[2], "avd": avd, "passed": False,
              "initialPower": initial_power, "baseline": baseline}
    if not baseline_ready(baseline):
        result["error"] = "Baseline must be awake, known unlocked, healthy, and have complete owners"
        return result
    try:
        run(adb + ["shell", "input", "keyevent", "KEYCODE_SLEEP"])
        result["afterSleepPower"] = wait_power(adb, "Asleep")
        run(adb + ["shell", "input", "keyevent", "KEYCODE_WAKEUP"])
        wait_power(adb, "Awake")
        # This is a no-op on an unlocked prepared emulator and dismisses the
        # emulator keyguard when it is present; it does not alter credentials.
        run(adb + ["shell", "wm", "dismiss-keyguard"])
        result["afterWake"] = wait_healthy(root, adb, baseline)
        result["passed"] = lifecycle_passes(baseline, result["afterWake"], "Awake")
        if not result["passed"]:
            result["error"] = "Owners, signatures, display IDs, or geometry changed after sleep/wake"
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError, KeyError) as error:
        result["error"] = str(error)
    finally:
        try:
            # The sleep request may have reached Android even when ADB failed
            # to acknowledge it. Every attempted cycle must restore Awake.
            run(adb + ["shell", "input", "keyevent", "KEYCODE_WAKEUP"])
            result["restoredPower"] = wait_power(adb, "Awake")
        except (OSError, subprocess.SubprocessError, RuntimeError, ValueError) as error:
            result["cleanupError"] = str(error)
    result["passed"] = result["passed"] and result.get("restoredPower") == initial_power and "cleanupError" not in result
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--output", type=Path, default=Path("app/build/verification/display-resume.json"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only named emulator serials are supported")
    adb = [args.adb, "-s", args.serial]
    try:
        result = verify(adb, Path(__file__).resolve().parents[1])
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError, KeyError, IndexError) as error:
        result = {"serial": args.serial, "passed": False, "error": str(error)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "output": str(args.output),
                      "error": result.get("error"), "cleanupError": result.get("cleanupError")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
