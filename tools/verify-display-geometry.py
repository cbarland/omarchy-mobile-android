#!/usr/bin/env python3
"""Exercise external-display geometry and owner replacement on a disposable emulator."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Any

APP = "dev.omamobile.host"
TEST = f"{APP}.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
AVD = "Omamobile_Adapter_Test_API35"
SLOT = "1"
PHASES = ((1920, 1080, 160), (1280, 720, 160), (720, 1280, 240), (1920, 1080, 160))


def display_ids(text: str) -> set[int]:
    return {int(value) for value in re.findall(r"displayId\s*[=:]?\s*(\d+)", text)}


def has_geometry(text: str, width: int, height: int) -> bool:
    pair = rf"\b{width}\s*[x×]\s*{height}\b"
    fields = rf"width\s*[=:]\s*{width}[^\n]*height\s*[=:]\s*{height}"
    return bool(re.search(pair, text, re.I) or re.search(fields, text, re.I))


def report_value(raw: str) -> dict[str, Any]:
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("geometry probe report is not an object")
    for field in ("expectedWidth", "expectedHeight", "actualWidth", "actualHeight", "desktopDisplayId"):
        if field not in value:
            raise ValueError(f"geometry probe report missing {field}")
    return value


class AuditError(RuntimeError):
    pass


class Adb:
    def __init__(self, path: str, serial: str, log: list[dict[str, Any]]) -> None:
        self.path, self.serial, self.log = path, serial, log

    def run(self, args: list[str], timeout: float = 20.0, check: bool = True) -> str:
        command = [self.path, "-s", self.serial, *args]
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
        self.log.append({"command": command, "returncode": result.returncode,
                         "stdout": result.stdout or "", "stderr": result.stderr or ""})
        if check and result.returncode:
            raise AuditError(f"adb failed ({result.returncode}): {' '.join(command)}: {result.stderr.strip()}")
        return result.stdout or ""


def wait_for_display(adb: Adb, before: set[int], width: int, height: int, deadline: float) -> int:
    while time.monotonic() < deadline:
        dump = adb.run(["shell", "dumpsys", "display"])
        ids = display_ids(dump) - before
        if len(ids) == 1 and has_geometry(dump, width, height):
            return next(iter(ids))
        time.sleep(0.5)
    raise AuditError(f"display {width}x{height} was not observed")


def probe(adb: Adb, display_id: int, width: int, height: int) -> dict[str, Any]:
    command = ["shell", "am", "instrument", "-w", "-e", "geometry", "true",
               "-e", "expectedDisplayId", str(display_id), "-e", "expectedWidth", str(width),
               "-e", "expectedHeight", str(height), "-e", "graphics", "true", "-e", "class",
               "dev.omamobile.host.DisplayGeometryProbeTest", f"{TEST}/{RUNNER}"]
    output = adb.run(command, timeout=100)
    if "OK (1 test)" not in output:
        raise AuditError(f"geometry instrumentation failed: {output[-2000:]}")
    raw = adb.run(["exec-out", "run-as", APP, "cat", "files/graphics/desktop/geometry-probe.json"])
    report = report_value(raw)
    if report["actualWidth"] != width or report["actualHeight"] != height:
        raise AuditError(f"Hyprland geometry mismatch: {report}")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--output", type=Path, default=Path("app/build/verification/display-geometry.json"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise AuditError("refusing non-emulator serial")
    log: list[dict[str, Any]] = []
    adb = Adb(args.adb, args.serial, log)
    result: dict[str, Any] = {"serial": args.serial, "phases": [], "passed": False, "adb": log}
    created = False
    phone_owner: str | None = None
    desktop_owners: set[str] = set()
    try:
        if adb.run(["get-state"], 10).strip() != "device":
            raise AuditError("emulator is not in device state")
        avd = next((line.strip() for line in adb.run(["emu", "avd", "name"], 10).splitlines()
                    if line.strip() and line.strip().upper() != "OK"), "")
        if avd != AVD:
            raise AuditError(f"refusing unapproved AVD: {avd!r}")
        before = display_ids(adb.run(["shell", "dumpsys", "display"]))
        for width, height, density in PHASES:
            adb.run(["emu", "multidisplay", "add", SLOT, str(width), str(height), str(density), "0"], 20)
            created = True
            display_id = wait_for_display(adb, before, width, height, time.monotonic() + 45)
            report = probe(adb, display_id, width, height)
            owner = adb.run(["shell", "pidof", f"{APP}:desktop"], 10).strip()
            if not owner:
                raise AuditError("desktop owner disappeared after geometry probe")
            current_phone = adb.run(["shell", "pidof", f"{APP}:phone"], 10).strip()
            if not current_phone:
                raise AuditError("phone owner disappeared after geometry probe")
            if phone_owner is None:
                phone_owner = current_phone
            elif current_phone != phone_owner:
                raise AuditError(f"phone owner changed during external geometry cycle: {phone_owner} -> {current_phone}")
            if owner in desktop_owners:
                raise AuditError(f"desktop owner was reused for geometry phase: {owner}")
            desktop_owners.add(owner)
            result["phases"].append({"width": width, "height": height, "density": density,
                                     "displayId": display_id, "desktopPid": owner,
                                     "phonePid": current_phone, "report": report})
            adb.run(["emu", "multidisplay", "del", SLOT], 20)
            created = False
            before = display_ids(adb.run(["shell", "dumpsys", "display"]))
        result["passed"] = True
    except (AuditError, OSError, subprocess.SubprocessError) as error:
        result["error"] = str(error)
    finally:
        if created:
            try: adb.run(["emu", "multidisplay", "del", SLOT], 20)
            except Exception as error: result.setdefault("cleanupErrors", []).append(str(error))
        try: adb.run(["shell", "am", "startservice", "-a", "dev.omamobile.host.STOP_SCREENS", APP + "/.DesktopService"], 20)
        except Exception as error: result.setdefault("cleanupErrors", []).append(str(error))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "output": str(args.output), "error": result.get("error")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
