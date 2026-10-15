#!/usr/bin/env python3
"""Capture Android phone and external-display outputs without editing pixels."""
import argparse
import os
from pathlib import Path
import re
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--adb", default=str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platform-tools/adb.exe"))
p.add_argument("--serial", default="emulator-5554")
p.add_argument("--output", type=Path, default=Path("app/build/verification/graphics"))
p.add_argument("--suffix", default="")
p.add_argument("--phone-display", help="Explicit SurfaceFlinger display ID")
p.add_argument("--desktop-display", help="Explicit SurfaceFlinger display ID")
a = p.parse_args()
adb = [a.adb, "-s", a.serial]
physical = subprocess.check_output(adb + ["shell", "dumpsys", "SurfaceFlinger", "--display-id"], text=True)
ids = re.findall(r'^Display (\d+) \(HWC display (\d+)\):', physical, re.M)
phone = a.phone_display or next((identifier for identifier, hwc in ids if hwc == "0"), None)
external = [identifier for identifier, hwc in ids if hwc != "0"]
desktop = a.desktop_display
if not desktop and len(external) == 1:
    desktop = external[0]
if not phone or not desktop:
    dump = subprocess.check_output(adb + ["shell", "dumpsys", "SurfaceFlinger"], text=True)
    internal = re.search(r'^Display (\d+) \(physical,', dump, re.M)
    emulator = re.search(r'^Display (\d+) \(virtual, "Emulator 2D Display"\)', dump, re.M)
    phone = phone or (internal.group(1) if internal else None)
    if not external:
        desktop = desktop or (emulator.group(1) if emulator else None)
if not phone or not desktop or phone == desktop:
    p.error("Cannot identify both outputs; supply --phone-display and --desktop-display SurfaceFlinger IDs")
a.output.mkdir(parents=True, exist_ok=True)
for name, identifier in [("phone", phone), ("desktop", desktop)]:
    data = subprocess.check_output(adb + ["exec-out", "screencap", "-p", "-d", identifier])
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError(name + ": screenshot was not PNG")
    path = a.output / (name + a.suffix + ".png")
    path.write_bytes(data)
    print(path, len(data), "SurfaceFlinger", identifier)
