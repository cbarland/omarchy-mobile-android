#!/usr/bin/env python3
"""Capture Android phone and external-display outputs without editing pixels."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from capture_content import raw_frame, frame_content, frame_png

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--adb", default=shutil.which("adb") or (str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe") if os.name == "nt" else "adb"))
p.add_argument("--serial", default="emulator-5554")
p.add_argument("--output", type=Path, default=Path("app/build/verification/graphics"))
p.add_argument("--suffix", default="")
p.add_argument("--phone-display", help="Explicit SurfaceFlinger display ID")
p.add_argument("--desktop-display", help="Explicit SurfaceFlinger display ID")
p.add_argument("--wait-content", type=float, default=0,
               help="Wait up to 60 seconds per output for nonblank pixels; retain the last frame and report on failure")
a = p.parse_args()
if not 0 <= a.wait_content <= 60:
    p.error("--wait-content must be between 0 and 60 seconds")
adb = [a.adb, "-s", a.serial]
physical = subprocess.check_output(adb + ["shell", "dumpsys", "SurfaceFlinger", "--display-id"], text=True, timeout=15)
ids = re.findall(r'^Display (\d+) \(HWC display (\d+)\):', physical, re.M)
phone = a.phone_display or next((identifier for identifier, hwc in ids if hwc == "0"), None)
external = [identifier for identifier, hwc in ids if hwc != "0"]
desktop = a.desktop_display
if not desktop and len(external) == 1:
    desktop = external[0]
if not phone or not desktop:
    dump = subprocess.check_output(adb + ["shell", "dumpsys", "SurfaceFlinger"], text=True, timeout=15)
    internal = re.search(r'^Display (\d+) \(physical,', dump, re.M)
    emulator = re.search(r'^Display (\d+) \(virtual, "Emulator 2D Display"\)', dump, re.M)
    phone = phone or (internal.group(1) if internal else None)
    if not external:
        desktop = desktop or (emulator.group(1) if emulator else None)
if not phone or not desktop or phone == desktop:
    p.error("Cannot identify both outputs; supply --phone-display and --desktop-display SurfaceFlinger IDs")
a.output.mkdir(parents=True, exist_ok=True)
for name, identifier in [("phone", phone), ("desktop", desktop)]:
    if a.wait_content:
        deadline = time.monotonic() + a.wait_content
        report = {"surfaceFlingerId": identifier, "frames": [], "passed": False,
                  "scope": "Nonblank pixels only; inspect shell content and test input separately"}
        try:
            while True:
                budget = deadline - time.monotonic()
                if budget <= 0:
                    raise TimeoutError(name + " did not produce nonblank pixels before the deadline")
                raw = subprocess.check_output(adb + ["exec-out", "screencap", "-d", identifier], timeout=min(15, budget))
                frame = raw_frame(raw)
                content = frame_content(frame)
                report["frames"].append(content)
                data = frame_png(frame)
                (a.output / (name + a.suffix + ".png")).write_bytes(data)
                if content["hasContent"]:
                    report["passed"] = True
                    break
                time.sleep(min(.2, max(0, deadline - time.monotonic())))
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            report["error"] = str(error)
            raise
        finally:
            (a.output / (name + a.suffix + ".json")).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    else:
        data = subprocess.check_output(adb + ["exec-out", "screencap", "-p", "-d", identifier], timeout=15)
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError(name + ": screenshot was not PNG")
    path = a.output / (name + a.suffix + ".png")
    path.write_bytes(data)
    print(path, len(data), "SurfaceFlinger", identifier)
