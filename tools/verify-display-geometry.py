#!/usr/bin/env python3
"""Verify in-place external display geometry changes without restarting Android."""
from __future__ import annotations
import argparse, json, os, re, shutil, subprocess, time
from pathlib import Path

PHASES = ((1920, 1080, 160), (1280, 720, 160), (720, 1280, 240), (1920, 1080, 160))

def geometry_for_display(text: str, display_id: int):
    for line in text.splitlines():
        if f"displayId={display_id}" not in line: continue
        match = re.search(r"(?:real|size)\s*[=:]?\s*(\d+)\s*[x×]\s*(\d+)", line, re.I)
        if match: return int(match.group(1)), int(match.group(2))
    return None

def run(command, timeout=30):
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode or ("emu" in command and "KO" in result.stdout):
        raise RuntimeError(f"command failed: {' '.join(command)}\n{result.stdout}{result.stderr}")
    return result.stdout.strip()

def session(root: Path, adb: str, serial: str, profile: str):
    command = [shutil.which("python") or "python", str(root / "tools/emulator-linux.py"), "--adb", adb,
               "--serial", serial, "session", profile, "hyprctl -j monitors"]
    return json.loads(run(command, 30))[0]

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--output", type=Path, default=Path("app/build/verification/display-geometry.json"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"): raise RuntimeError("refusing non-emulator serial")
    root = Path(__file__).resolve().parents[1]
    base = [args.adb, "-s", args.serial]
    result = {"serial": args.serial, "phases": [], "passed": False}
    original = PHASES[0]
    try:
        before = session(root, args.adb, args.serial, "desktop")
        original = (before["width"], before["height"], 160)
        for width, height, density in PHASES:
            run(base + ["emu", "multidisplay", "add", "1", str(width), str(height), str(density), "0"], 25)
            deadline = time.monotonic() + 30
            while True:
                desktop = session(root, args.adb, args.serial, "desktop")
                if (desktop["width"], desktop["height"]) == (width, height): break
                if time.monotonic() >= deadline: raise RuntimeError(f"geometry did not converge: {desktop}")
                time.sleep(.25)
            phone_pid = run(base + ["shell", "pidof", "dev.omamobile.host:phone"])
            desktop_pid = run(base + ["shell", "pidof", "dev.omamobile.host:desktop"])
            result["phases"].append({"width": width, "height": height, "density": density,
                                     "monitor": desktop, "phonePid": phone_pid, "desktopPid": desktop_pid})
        if len({phase["phonePid"] for phase in result["phases"]}) != 1: raise RuntimeError("phone owner restarted")
        if len({phase["desktopPid"] for phase in result["phases"]}) != 1: raise RuntimeError("desktop owner restarted")
        result["passed"] = True
    except (OSError, subprocess.SubprocessError, RuntimeError) as error: result["error"] = str(error)
    finally:
        try: run(base + ["emu", "multidisplay", "add", "1", str(original[0]), str(original[1]), str(original[2]), "0"], 25)
        except Exception as error: result.setdefault("cleanupErrors", []).append(str(error))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "output": str(args.output), "error": result.get("error")}))
    return 0 if result["passed"] else 1

if __name__ == "__main__": raise SystemExit(main())
