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
sub.add_parser("start-unified", help="Launch the shared compositor preview using the visible Android control")
sub.add_parser("stop", help="Stop both displays using the visible Android app control")
command = sub.add_parser("command", help="Run a setup command through Android instrumentation (restarts the app)",
                         description="Run a setup command through Android instrumentation. This may restart the app; acknowledge it with --allow-app-restart.")
command.add_argument("text", nargs="?")
command.add_argument("--file", type=Path)
command.add_argument("--allow-app-restart", action="store_true",
                     help="Acknowledge that Android instrumentation restarts the app")
session = sub.add_parser("session", help="Run in an active display's Linux environment without restarting Android")
session.add_argument("profile", choices=["phone", "desktop", "unified"])
session.add_argument("text", nargs="?")
session.add_argument("--file", type=Path, help="Read a Linux script without nesting shell quotes")
push = sub.add_parser("push")
push.add_argument("file", type=Path)
push.add_argument("destination", help="Path relative to the app data directory")
a = p.parse_args()
if a.action == "command" and not a.allow_app_restart:
    p.error("command runs Android instrumentation and may restart the app; pass --allow-app-restart for setup tests, use session for a running profile, or use direct adb for post-stop observations")
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
    runtime = "/run/omamobile/" + {"phone": "p", "desktop": "d", "unified": "u"}[profile]
    argv = ["run-as", "dev.omamobile.host", "/system/bin/env", "-i", "HOME=/root", "USER=root", "LANG=C.UTF-8",
            "PATH=/usr/lib/omamobile/session-bin:/root/.local/share/omarchy/bin:/usr/local/bin:/usr/bin:/bin", "OMARCHY_PATH=/root/.local/share/omarchy",
            "OMAMOBILE_SESSION_HOST=android", "OMAMOBILE_PROFILE=" + profile, "GSETTINGS_BACKEND=keyfile",
            "XDG_RUNTIME_DIR=" + runtime, "WAYLAND_DISPLAY=" + ready["WAYLAND_DISPLAY"],
            "PIPEWIRE_RUNTIME_DIR=/run/omamobile/a-shared", "PIPEWIRE_REMOTE=pipewire-0",
            "PULSE_SERVER=unix:/run/omamobile/a-shared/pulse/native",
            "HYPRLAND_INSTANCE_SIGNATURE=" + ready["HYPRLAND_INSTANCE_SIGNATURE"], "DBUS_SESSION_BUS_ADDRESS=unix:path=" + runtime + "/bus",
            "QT_QPA_PLATFORM=wayland", "QT_QUICK_BACKEND=software", "QSG_RENDER_LOOP=basic",
            "/system/bin/sh", "-c", 'cd "$1" && shift && exec "$@"', "omamobile-session", root + "/root",
            native + "/libtawcroot.so", "-s", "-r", root, "-b", "/dev:/dev", "-b", "/proc:/proc",
            "--", "/bin/bash", "-c", session_text]
    return adb + ["shell", shlex.join(argv)]

if a.action in ("start", "start-unified", "stop"):
    control = {"start": "START OMARCHY SCREENS", "start-unified": "START SHARED COMPOSITOR PREVIEW", "stop": "STOP GRAPHICAL SESSIONS"}[a.action]
    adb_run(adb + ["shell", "am", "start", "--display", "0", "-W", "-n", "dev.omamobile.host/.MainActivity"], check=True)
    adb_run(adb + ["shell", "uiautomator", "dump", "/data/local/tmp/omamobile-start.xml"], check=True, capture_output=True)
    tree = ET.fromstring(adb_output(adb + ["exec-out", "cat", "/data/local/tmp/omamobile-start.xml"]))
    # Nubia drops injected touchscreen taps on the control panel. Keyboard
    # navigation reaches the same visible button without assuming coordinates.
    for attempt in range(16):
        button = next((n for n in tree.iter("node") if n.get("text") == control and n.get("package") == "dev.omamobile.host"), None)
        # ScrollView omits off-screen controls from the accessibility dump.
        # Bounded Tab navigation can reveal them, but never navigate a system dialog.
        if button is None and not any(n.get("package") == "dev.omamobile.host" for n in tree.iter("node")):
            raise RuntimeError(control + " control is obscured; unlock the phone and dismiss Android system panels")
        if button is not None and button.get("focused") == "true":
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
            owners = adb_run(adb + ["shell", "pidof", "dev.omamobile.host:phone", "dev.omamobile.host:desktop", "dev.omamobile.host:unified"],
                                    capture_output=True, text=True, check=False, timeout=5)
            if owners.returncode == 1 and not owners.stdout.strip():
                print("Both display owners stopped")
                raise SystemExit(0)
            if owners.returncode not in (0, 1):
                raise RuntimeError("Could not inspect display owner shutdown: " + owners.stderr)
            time.sleep(0.2)
        raise RuntimeError("Display owners did not stop after the normal Stop control")
    # IPC is available before Quickshell maps. Waiting only for monitors drops
    # the next search tap during cold startup, even though the compositor is live.
    for profile in (("unified",) if a.action == "start-unified" else ("phone", "desktop")):
        deadline = time.monotonic() + 45
        last = "No live compositor response"
        while time.monotonic() < deadline:
            try:
                probe = session_adb_command(profile, "hyprctl -j layers", metadata_timeout=5, deadline=deadline)
                budget = deadline - time.monotonic()
                if budget <= 0:
                    raise subprocess.TimeoutExpired(probe, 0)
                result = adb_run(probe, capture_output=True, text=True, timeout=min(5, budget))
            except (subprocess.TimeoutExpired, subprocess.CalledProcessError, KeyError):
                time.sleep(0.2)
                continue
            try:
                layers = json.loads(result.stdout)
                # A phone can be showing its launcher or a Linux application;
                # its bar is the persistent shell surface in those states.
                required = [("ANDROID-1", "2", "omarchy-bar")]
                if profile == "desktop":
                    required.append(("ANDROID-1", "0", "omarchy-background"))
                elif profile == "unified":
                    required += [("ANDROID-2", "0", "omarchy-background"),
                                 ("ANDROID-2", "2", "omarchy-bar")]
                missing = [namespace + " on " + output for output, level, namespace in required
                           if not any(item.get("namespace") == namespace and item.get("alpha", 0) >= .9
                                      and item.get("w", 0) > 0 and item.get("h", 0) > 0
                                      for item in layers.get(output, {}).get("levels", {}).get(level, []))]
                last = "Waiting for " + ", ".join(missing) if missing else result.stderr[-300:]
                if result.returncode == 0 and not missing:
                    print(profile, "shell layers ready (rendered pixels still require capture)")
                    break
            except (ValueError, KeyError, TypeError, AttributeError):
                last = "Invalid shell layer response"
            time.sleep(0.2)
        else:
            raise RuntimeError(profile + " shell did not become ready: " + last + "; inspect graphics logs")
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
