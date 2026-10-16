#!/usr/bin/env python3
"""Verify absolute external-display taps through the real graphical session.

This driver delegates display-size mutation and restoration to
verify-display-geometry.py.  It only owns one uniquely named GTK fixture and
its process/files inside the selected emulator's private Linux root.
"""
import argparse
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "tools" / "pointer_geometry_fixture.py"
GEOMETRY_SPEC = importlib.util.spec_from_file_location("display_geometry", ROOT / "tools" / "verify-display-geometry.py")
GEOMETRY = importlib.util.module_from_spec(GEOMETRY_SPEC)
GEOMETRY_SPEC.loader.exec_module(GEOMETRY)


def surface_geometry(dump):
    """Return the single desktop SurfaceFlinger output frame and source crop."""
    marker = "Output Layer "
    name = "SurfaceView[dev.omamobile.host/dev.omamobile.host.DesktopActivity](BLAST)"
    matches = []
    for block in dump.split(marker)[1:]:
        if name not in block[:240]:
            continue
        frame = re.search(r"displayFrame=\[\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*\]", block)
        crop = re.search(r"sourceCrop=\[\s*(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s*\]", block)
        if frame and crop:
            matches.append({"frame": tuple(map(int, frame.groups())),
                            "crop": tuple(map(float, crop.groups()))})
    if len(matches) != 1:
        raise RuntimeError(f"Desktop SurfaceFlinger layer is ambiguous: {len(matches)} matches")
    value = matches[0]
    left, top, right, bottom = value["frame"]
    if left != 0 or top != 0 or right <= left or bottom <= top:
        raise RuntimeError(f"Desktop SurfaceFlinger frame is not an origin-sized viewport: {value}")
    fw, fh = right - left, bottom - top
    crop = value["crop"]
    if crop != (0.0, 0.0, float(fw), float(fh)) or not all(math.isfinite(part) for part in crop):
        raise RuntimeError(f"Desktop SurfaceFlinger crop is not the full finite buffer: {value}")
    return value


def compare_phase(phase, surface, events, taps):
    monitor = phase["monitor"]
    if monitor["transform"] != 0:
        raise RuntimeError(f"Unsupported non-zero Hyprland transform: {monitor['transform']}")
    fw, fh = surface["frame"][2], surface["frame"][3]
    cw, ch = surface["crop"][2], surface["crop"][3]
    if (fw, fh) != tuple(phase["mode"][:2]) or (cw, ch) != (float(fw), float(fh)):
        raise RuntimeError(f"Surface frame/crop does not equal verified Android mode: {surface} vs {phase['mode']}")
    scale = float(monitor["scale"])
    if not math.isfinite(scale) or scale <= 0:
        raise RuntimeError(f"Invalid Hyprland scale: {scale}")
    if len(events) < len(taps):
        raise RuntimeError(f"Fixture recorded {len(events)} events, expected {len(taps)}")
    observed = events[-len(taps):]
    expected_cells = [0, 3]
    for event, (x, y), cell in zip(observed, taps, expected_cells):
        allocation = phase["fixtureAllocation"]
        expected_x = x / scale
        expected_y = y / scale
        if not all(math.isfinite(float(event.get(key))) for key in ("x", "y")):
            raise RuntimeError(f"Non-finite fixture event: {event}")
        if event["cell"] != cell:
            raise RuntimeError(f"Wrong cell for tap {(x, y)}: {event}")
        if abs(event["x"] - expected_x) > 1 or abs(event["y"] - expected_y) > 1:
            raise RuntimeError(f"Coordinate mismatch for tap {(x, y)} at scale {scale}: {event}")
        if (allocation["width"], allocation["height"]) != (round(fw / scale), round(fh / scale)):
            raise RuntimeError(f"Fixture allocation disagrees with output scale: {allocation}")
    return {"surface": surface, "taps": taps, "events": observed, "scale": scale}


def wait_phase_viewport(phase, surface_reader, allocation_reader, timeout=10):
    """Poll SurfaceFlinger and GTK together before allowing any input."""
    scale = float(phase["monitor"]["scale"])
    if phase["monitor"]["transform"] != 0:
        raise RuntimeError(f"Unsupported non-zero Hyprland transform: {phase['monitor']['transform']}")
    if not math.isfinite(scale) or scale <= 0:
        raise RuntimeError(f"Invalid Hyprland scale: {scale}")
    expected_frame = tuple(phase["mode"][:2])
    expected_allocation = (round(expected_frame[0] / scale), round(expected_frame[1] / scale))
    deadline = time.monotonic() + timeout
    last_error = "no viewport sample"
    while time.monotonic() < deadline:
        try:
            surface = surface_reader()
            frame = surface["frame"]
            crop = surface["crop"]
            if frame[2:] != (0 + expected_frame[0], 0 + expected_frame[1]):
                raise RuntimeError(f"Surface frame {frame} does not match phase {expected_frame}")
            if crop != (0.0, 0.0, float(expected_frame[0]), float(expected_frame[1])):
                raise RuntimeError(f"Surface crop {crop} does not match phase {expected_frame}")
            allocation = allocation_reader()
            if not allocation or (allocation["width"], allocation["height"]) != expected_allocation:
                raise RuntimeError(f"GTK allocation {allocation} does not match {expected_allocation}")
            return surface, allocation
        except (RuntimeError, KeyError, TypeError, ValueError) as error:
            last_error = str(error)
            time.sleep(.1)
    raise RuntimeError(f"Surface/GTK viewport did not converge: {last_error}")


class Fixture:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial
        self.helper = ROOT / "tools" / "emulator-linux.py"
        self.token = f"pointer-geometry-{uuid.uuid4().hex}"
        self.remote_script = f"/data/local/tmp/{self.token}.py"
        self.guest_dir = f"/root/.{self.token}"
        self.guest_script = f"{self.guest_dir}/fixture.py"
        self.guest_log = f"{self.guest_dir}/events.json"
        self.pid = None
        self.remote_pushed = False
        self.guest_dir_owned = False
    def start(self):
        self._call([self.adb, "-s", self.serial, "push", str(FIXTURE), self.remote_script])
        self.remote_pushed = True
        self._app(f"mkdir files/linux/rootfs/root/.{self.token}")
        self.guest_dir_owned = True
        self._app(f"cp {self.remote_script} files/linux/rootfs/root/.{self.token}/fixture.py")
        started = self._session(f"nohup /usr/bin/python {self.guest_script} {self.guest_log} "
                                f">{self.guest_log}.log 2>&1 </dev/null & echo $!")
        try:
            self.pid = int(started.strip().splitlines()[-1])
        except (ValueError, IndexError):
            raise RuntimeError(f"Fixture did not return a PID: {started!r}")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            value = self.read()
            if value and value.get("allocation"):
                return
            time.sleep(.2)
        raise RuntimeError("GTK pointer fixture did not publish an allocation")

    def _call(self, command):
        result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=60)
        return result.stdout

    def _session(self, text):
        return self._call([sys.executable, str(self.helper), "--adb", self.adb,
                           "--serial", self.serial, "session", "desktop", text])

    def _app(self, text):
        return self._call([self.adb, "-s", self.serial, "shell", "run-as", "dev.omamobile.host",
                           *shlex.split(text)])

    def read(self):
        try:
            return json.loads(self._session(f"cat {self.guest_log}"))
        except (subprocess.CalledProcessError, subprocess.SubprocessError, json.JSONDecodeError):
            return None

    def tap(self, display, x, y):
        self._call([self.adb, "-s", self.serial, "shell", "input", "-d", str(display),
                    "tap", str(x), str(y)])

    def stop(self):
        if self.pid is not None:
            try:
                identity = self._session(f"ps -o args= -p {self.pid}").strip()
                if self.guest_script in identity:
                    self._session(f"kill {self.pid}")
            except (subprocess.SubprocessError, OSError):
                pass
        if self.guest_dir_owned:
            self._app(f"rm -f files/linux/rootfs/root/.{self.token}/fixture.py "
                      f"files/linux/rootfs/root/.{self.token}/events.json "
                      f"files/linux/rootfs/root/.{self.token}/events.json.tmp "
                      f"files/linux/rootfs/root/.{self.token}/events.json.log")
            self._app(f"rmdir files/linux/rootfs/root/.{self.token}")
            self.guest_dir_owned = False
        if self.remote_pushed:
            self._call([self.adb, "-s", self.serial, "shell", "rm", self.remote_script])
            self.remote_pushed = False


def probe_phase(adb, serial, root, phase, fixture):
    surface, allocation = wait_phase_viewport(
        phase,
        lambda: surface_geometry(GEOMETRY.run(adb + ["shell", "dumpsys", "SurfaceFlinger", "--layers"])),
        lambda: (fixture.read() or {}).get("allocation"),
    )
    current = fixture.read() or {}
    before_count = len(current.get("events", []))
    taps = [(round(phase["mode"][0] * .25), round(phase["mode"][1] * .25)),
            (round(phase["mode"][0] * .75), round(phase["mode"][1] * .75))]
    for x, y in taps:
        fixture.tap(phase["owners"]["desktop"]["displayId"], x, y)
    deadline = time.monotonic() + 5
    latest = None
    while time.monotonic() < deadline:
        latest = fixture.read()
        if latest and len(latest.get("events", [])) == before_count + 2:
            break
        time.sleep(.1)
    if latest is None:
        raise RuntimeError("Fixture log disappeared")
    phase["fixtureAllocation"] = allocation
    if len(latest.get("events", [])) != before_count + 2:
        raise RuntimeError(f"Fixture did not record exactly two new events: before={before_count}, after={latest}")
    return compare_phase(phase, surface, latest.get("events", [])[before_count:], taps)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--output", type=Path, default=Path("app/build/verification/pointer-geometry.json"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only the disposable emulator is supported")
    fixture = None
    result = {"passed": False}
    try:
        adb = [args.adb, "-s", args.serial]
        avd = GEOMETRY.run(adb + ["emu", "avd", "name"]).splitlines()[0].strip()
        if avd not in (GEOMETRY.AVD, GEOMETRY.INSTALL_AVD):
            raise RuntimeError(f"Refusing AVD {avd!r}")
        owners = {name: GEOMETRY.profile(adb, name) for name in ("phone", "desktop")}
        if owners["desktop"]["displayId"] == 0:
            raise RuntimeError("Refusing external desktop display ID 0")
        original = GEOMETRY.android_mode(GEOMETRY.run(adb + ["shell", "dumpsys", "display"]),
                                         owners["desktop"]["displayId"])
        GEOMETRY.await_mode(adb, ROOT, original, owners)
        if GEOMETRY.monitor(adb, ROOT)["width"] <= 0:
            raise RuntimeError("Desktop monitor is not healthy")
        fixture = Fixture(args.adb, args.serial)
        try:
            fixture.start()
            result = GEOMETRY.verify(adb, ROOT,
                                     phase_probe=lambda phase: probe_phase(adb, args.serial, ROOT, phase, fixture))
        finally:
            fixture.stop()
            fixture = None
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError, KeyError, IndexError) as error:
        result["error"] = str(error)
    finally:
        if fixture is not None:
            try:
                fixture.stop()
            except (OSError, subprocess.SubprocessError, RuntimeError) as error:
                result["cleanupError"] = str(error)
    if result.get("error") or result.get("cleanupError"):
        result["passed"] = False
    result["serial"] = args.serial
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "output": str(args.output),
                      "error": result.get("error"), "cleanupError": result.get("cleanupError")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
