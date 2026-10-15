#!/usr/bin/env python3
"""Test-APK Linux command runner and binary-safe private-file transfer.

This developer helper never uninstalls the application or requests adb root.
Use it before starting graphics: Android instrumentation restarts the app.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--adb", default=shutil.which("adb") or (str(Path(os.environ.get("LOCALAPPDATA", ""))
               / "Android/Sdk/platform-tools/adb.exe") if os.name == "nt" else "adb"))
p.add_argument("--serial", default="emulator-5554")
sub = p.add_subparsers(dest="action", required=True)
sub.add_parser("start", help="Launch both displays using the visible Android app control")
sub.add_parser("stop", help="Stop both displays using the visible Android app control")
command = sub.add_parser("command")
command.add_argument("text", nargs="?")
command.add_argument("--file", type=Path)
session = sub.add_parser("session", help="Run in an active display's Linux environment without restarting Android")
session.add_argument("profile", choices=["phone", "desktop"])
session.add_argument("text", nargs="?")
session.add_argument("--file", type=Path, help="Read a Linux script without nesting shell quotes")
push = sub.add_parser("push")
push.add_argument("file", type=Path)
push.add_argument("destination", help="Path relative to the app data directory")
a = p.parse_args()
adb = [a.adb, "-s", a.serial]

def adb_run(command, *, timeout=30, **kwargs):
    """Run one adb operation with a finite host-side wait."""
    return subprocess.run(command, timeout=timeout, **kwargs)

def adb_output(command, *, timeout=30, **kwargs):
    """Capture one adb operation with a finite host-side wait."""
    return subprocess.check_output(command, timeout=timeout, **kwargs)

def session_adb_command(profile, session_text, *, metadata_timeout=30, deadline=None):
    """Build the direct adb shell command used by both sessions and readiness probes."""
    def metadata(args):
        timeout = metadata_timeout if deadline is None else min(metadata_timeout, deadline - time.monotonic())
        if timeout <= 0:
            raise subprocess.TimeoutExpired(adb + args, metadata_timeout)
        return adb_output(adb + args, text=True, timeout=timeout)

    state = metadata(["exec-out", "run-as", "dev.omamobile.host", "cat",
                      "files/graphics/" + profile + "/ready.properties"])
    ready = dict(line.split("=", 1) for line in state.splitlines() if "=" in line)
    apk = metadata(["shell", "pm", "path", "dev.omamobile.host"]).strip().removeprefix("package:")
    architecture = metadata(["shell", "getprop", "ro.product.cpu.abi"]).strip()
    native = apk.rsplit("/", 1)[0] + "/lib/" + ("arm64" if architecture == "arm64-v8a" else architecture)
    root = ready["ROOTFS"]
    files = root.removesuffix("/linux/rootfs")
    runtime = "/run/omamobile/" + ("p" if profile == "phone" else "d")
    argv = ["run-as", "dev.omamobile.host", "/system/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/lib/omamobile/session-bin:/root/.local/share/omarchy/bin:/usr/local/bin:/usr/bin:/bin", "OMARCHY_PATH=/root/.local/share/omarchy",
            "OMAMOBILE_SESSION_HOST=android", "OMAMOBILE_PROFILE=" + profile, "GSETTINGS_BACKEND=keyfile",
            "XDG_RUNTIME_DIR=" + runtime, "WAYLAND_DISPLAY=" + ready["WAYLAND_DISPLAY"],
            "HYPRLAND_INSTANCE_SIGNATURE=" + ready["HYPRLAND_INSTANCE_SIGNATURE"], "DBUS_SESSION_BUS_ADDRESS=unix:path=" + runtime + "/bus",
            "QT_QPA_PLATFORM=wayland", "QT_QUICK_BACKEND=software", "QSG_RENDER_LOOP=basic",
            "/system/bin/sh", "-c", 'cd "$1" && shift && exec "$@"', "omamobile-session", root + "/root",
            native + "/libtawcroot.so", "-s", "-r", root, "-b", "/dev:/dev", "-b", "/proc:/proc",
            "-b", files + "/r:/run/omamobile", "--", "/bin/bash", "-c", session_text]
    return adb + ["shell", shlex.join(argv)]

if a.action in ("start", "stop"):
    control = "START OMARCHY SCREENS" if a.action == "start" else "STOP GRAPHICAL SESSIONS"
    adb_run(adb + ["shell", "am", "start", "--display", "0", "-W", "-n", "dev.omamobile.host/.MainActivity"], check=True)
    adb_run(adb + ["shell", "uiautomator", "dump", "/data/local/tmp/omamobile-start.xml"], check=True, capture_output=True)
    tree = ET.fromstring(adb_output(adb + ["exec-out", "cat", "/data/local/tmp/omamobile-start.xml"]))
    # Nubia drops injected touchscreen taps on the control panel. Keyboard
    # navigation reaches the same visible button without assuming coordinates.
    for attempt in range(16):
        button = next((n for n in tree.iter("node") if n.get("text") == control and n.get("package") == "dev.omamobile.host"), None)
        if button is None:
            raise RuntimeError(control + " control is obscured; unlock the phone and dismiss Android system panels")
        if button.get("focused") == "true":
            break
        adb_run(adb + ["shell", "input", "keyboard", "-d", "0", "keyevent", "KEYCODE_TAB"], check=True)
        adb_run(adb + ["shell", "uiautomator", "dump", "/data/local/tmp/omamobile-start.xml"], check=True, capture_output=True)
        tree = ET.fromstring(adb_output(adb + ["exec-out", "cat", "/data/local/tmp/omamobile-start.xml"]))
    else:
        raise RuntimeError("Could not focus the visible " + control + " button")
    adb_run(adb + ["shell", "input", "keyboard", "-d", "0", "keyevent", "KEYCODE_ENTER"], check=True)
    if a.action == "stop":
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            owners = adb_run(adb + ["shell", "pidof", "dev.omamobile.host:phone", "dev.omamobile.host:desktop"],
                                    capture_output=True, text=True, check=False, timeout=5)
            if owners.returncode == 1 and not owners.stdout.strip():
                print("Both display owners stopped")
                raise SystemExit(0)
            if owners.returncode not in (0, 1):
                raise RuntimeError("Could not inspect display owner shutdown: " + owners.stderr)
            time.sleep(0.2)
        raise RuntimeError("Display owners did not stop after the normal Stop control")
    # A tap queues native compositor startup. Confirm live IPC instead of
    # handing subsequent checks a ready file left by the previous process.
    for profile in ("phone", "desktop"):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                probe = session_adb_command(profile, "hyprctl -j monitors", metadata_timeout=5, deadline=deadline)
                budget = deadline - time.monotonic()
                if budget <= 0:
                    raise subprocess.TimeoutExpired(probe, 0)
                result = adb_run(probe, capture_output=True, text=True, timeout=min(5, budget))
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError, KeyError):
                time.sleep(0.2)
                continue
            try:
                monitors = json.loads(result.stdout)
                if result.returncode == 0 and monitors and monitors[0]["width"] > 0:
                    print(profile, "Hyprland IPC ready")
                    break
            except (ValueError, KeyError, TypeError):
                pass
            time.sleep(0.2)
        else:
            raise RuntimeError(profile + " compositor did not become ready; attach an external emulator display and inspect graphics logs")
elif a.action == "push":
    destination = a.destination
    if not destination.startswith("files/") or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/_.-" for c in destination) or ".." in destination.split("/"):
        p.error("Use an unambiguous app-private files/ path")
    adb_run(adb + ["shell", "run-as", "dev.omamobile.host", "mkdir", "-p", str(Path(destination).parent).replace("\\", "/")], check=True)
    digest = hashlib.sha256(a.file.read_bytes()).hexdigest()
    with a.file.open("rb") as stream:
        adb_run(adb + ["exec-in", f"run-as dev.omamobile.host sh -c 'cat > {destination}'"], stdin=stream, check=True)
    for attempt in range(60):
        result = adb_run(adb + ["shell", "run-as", "dev.omamobile.host", "sha256sum", destination], capture_output=True, text=True)
        if result.stdout.startswith(digest):
            print("Verified", digest, destination)
            break
        time.sleep(0.5)
    else:
        raise SystemExit("ADB file transfer did not match SHA-256")
elif a.action == "session":
    session_text = a.file.read_text(encoding="utf-8") if a.file else a.text
    if not session_text:
        p.error("Provide a session command or --file")
    raise SystemExit(adb_run(session_adb_command(a.profile, session_text), timeout=240).returncode)
else:
    text = a.file.read_text(encoding="utf-8") if a.file else a.text
    if not text:
        p.error("Provide a Linux command or --file")
    result = adb_run(adb + ["shell", "am", "instrument", "-w", "-e", "class", "dev.omamobile.host.LinuxCommandTest",
        "-e", "commandBase64", base64.b64encode(text.encode()).decode(),
        "dev.omamobile.host.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True, timeout=240)
    output = adb_output(adb + ["exec-out", "run-as", "dev.omamobile.host", "cat", "files/linux-command-output.txt"])
    print(output.decode("utf-8", errors="replace"))
    if "OK (1 test)" not in result.stdout or result.returncode:
        print(result.stdout, result.stderr)
        raise SystemExit(1)
