#!/usr/bin/env python3
"""Resize existing emulator slot 1, check live owners and restore its mode."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

AVD = "Omamobile_Adapter_Test_API35"
INSTALL_AVD = "Omamobile_Install_Trust_API35"
APP = "dev.omamobile.host"
PHASES = ((1920, 1080, 160), (1280, 720, 160), (720, 1280, 240), (1920, 1080, 160))


def run(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=30)
    if result.returncode or ("emu" in command and re.search(r"\bKO\b", result.stdout)):
        raise RuntimeError(f"Command failed: {command!r}: {result.stdout[-1000:]}{result.stderr[-1000:]}")
    return result.stdout.strip()


def profile(adb, name):
    raw = run(adb + ["shell", "run-as", APP, "cat", f"files/graphics/{name}/ready.properties"])
    ready = dict(line.split("=", 1) for line in raw.splitlines() if "=" in line)
    return {"pid": run(adb + ["shell", "pidof", APP + ":" + name]),
            "signature": ready["HYPRLAND_INSTANCE_SIGNATURE"],
            "displayId": int(ready["ANDROID_DISPLAY_ID"])}


def monitor(adb, root):
    output = run([sys.executable, str(root / "tools/emulator-linux.py"),
                  "--adb", adb[0], "--serial", adb[2], "session", "desktop", "hyprctl -j monitors"])
    monitors = json.loads(output)
    if len(monitors) != 1:
        raise RuntimeError("Expected one native desktop monitor")
    return monitors[0]


def android_mode(dump, display_id):
    for line in dump.splitlines():
        if "mBaseDisplayInfo=" not in line or not re.search(rf"\bdisplayId\s*[=:]?\s*{display_id}\s*[,}}]", line):
            continue
        if '"Emulator 2D Display"' not in line:
            raise RuntimeError("Desktop target is not an emulator display")
        geometry = re.search(r"\breal (\d+) x (\d+)", line)
        density = re.search(r"\bdensity (\d+)\b", line)
        if geometry and density:
            return int(geometry[1]), int(geometry[2]), int(density[1])
    raise RuntimeError("Existing desktop Android display geometry is unavailable")


def await_mode(adb, root, expected, owners):
    deadline = time.monotonic() + 30
    while True:
        native = monitor(adb, root)
        mode = android_mode(run(adb + ["shell", "dumpsys", "display"]), owners["desktop"]["displayId"])
        current = {name: profile(adb, name) for name in owners}
        if current != owners:
            raise RuntimeError(f"Output owner changed: {owners!r} -> {current!r}")
        if (native["width"], native["height"]) == expected[:2] and mode == expected:
            return {"mode": mode, "monitor": native, "owners": current}
        if time.monotonic() >= deadline:
            raise RuntimeError(f"Geometry did not converge: Android {mode}, native {native}")
        time.sleep(.2)


def verify(adb, root, phase_probe=None):
    avd = run(adb + ["emu", "avd", "name"]).splitlines()[0].strip()
    if avd not in (AVD, INSTALL_AVD):
        raise RuntimeError(f"Refusing AVD {avd!r}; expected one of {(AVD, INSTALL_AVD)!r}")
    owners = {name: profile(adb, name) for name in ("phone", "desktop")}
    original = android_mode(run(adb + ["shell", "dumpsys", "display"]), owners["desktop"]["displayId"])
    baseline = await_mode(adb, root, original, owners)
    result = {"passed": False, "baseline": baseline, "phases": []}
    try:
        for mode in PHASES:
            run(adb + ["emu", "multidisplay", "add", "1", *map(str, mode), "0"])
            phase = await_mode(adb, root, mode, owners)
            if phase_probe is not None:
                phase["pointer"] = phase_probe(phase)
            result["phases"].append(phase)
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError, KeyError) as error:
        result["error"] = str(error)
    finally:
        try:
            run(adb + ["emu", "multidisplay", "add", "1", *map(str, original), "0"])
            result["restored"] = await_mode(adb, root, original, owners)
        except (OSError, subprocess.SubprocessError, RuntimeError, ValueError, KeyError) as error:
            result["cleanupError"] = str(error)
    result["passed"] = len(result["phases"]) == len(PHASES) and "error" not in result and "cleanupError" not in result
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--output", type=Path, default=Path("app/build/verification/display-geometry.json"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only the disposable emulator is supported")
    try:
        result = verify([args.adb, "-s", args.serial], Path(__file__).resolve().parents[1])
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError, KeyError, IndexError) as error:
        result = {"passed": False, "error": str(error)}
    result["serial"] = args.serial
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "output": str(args.output), "error": result.get("error"), "cleanupError": result.get("cleanupError")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
