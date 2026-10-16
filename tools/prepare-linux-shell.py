#!/usr/bin/env python3
"""Package pinned upstream Omarchy shells for an existing writable Arch rootfs.

No phone flashing, systemd units, login manager, kernel or Android policy files
are installed. Upstream Linux programs stay in Linux; this is a staging recipe.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

OMARCHY = "e71a816e9032f040225e086c7256f73e8080ae45"
MOBILE = "953bcb3058347459cdf7faaa1e149f16e0f7cfa6"
WIDGETS = "b63886b75e02ccef1a8a11baeef2d6eff7a374af"
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--omarchy", type=Path, required=True)
p.add_argument("--mobile", type=Path, required=True)
p.add_argument("--widgets", type=Path, required=True, help="Omalauncher OWL store checkout at the pinned revision")
p.add_argument("--output", type=Path, required=True)
a = p.parse_args()
a.omarchy, a.mobile, a.widgets = a.omarchy.resolve(), a.mobile.resolve(), a.widgets.resolve()
for path, pin in [(a.omarchy, OMARCHY), (a.mobile, MOBILE), (a.widgets, WIDGETS)]:
    head = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()
    if head != pin:
        raise SystemExit(f"Expected {path} at {pin}, got {head}")

def git_files(root, pin, paths):
    # Canonical Git blobs retain Unix shebangs and executable modes even when
    # reference checkouts were materialized on Windows with CRLF conversion.
    data = subprocess.check_output(["git", "-c", "core.autocrlf=false", "-c", "core.eol=lf", "-C", str(root), "archive", pin, *paths])
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:") as source:
        return {m.name: (source.extractfile(m).read(), m.mode) for m in source.getmembers() if m.isfile()}

omarchy_folders = ["shell", "bin", "config", "default", "themes/tokyo-night", "install/helpers"]
omarchy_files = ["LICENSE", "logo.txt", "logo.svg", "icon.txt", "icon.png", "version"]
canonical = {a.omarchy: git_files(a.omarchy, OMARCHY, [*omarchy_folders, *omarchy_files]),
             a.mobile: git_files(a.mobile, MOBILE, ["packages/omamobile-shell"])}

def blob(path):
    root = a.omarchy if path.is_relative_to(a.omarchy) else a.mobile
    return canonical[root][path.relative_to(root).as_posix()]

a.output.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(a.output, "w:gz") as archive:
    def put(name, data, mode=0o644):
        data = data.encode() if isinstance(data, str) else data
        info = tarfile.TarInfo(name)
        info.size, info.mode, info.mtime = len(data), mode, 0
        archive.addfile(info, io.BytesIO(data))

    def tree(source, target):
        root = a.omarchy if source.is_relative_to(a.omarchy) else a.mobile
        prefix = source.relative_to(root).as_posix() + "/"
        for relative, (data, mode) in sorted(canonical[root].items()):
            if not relative.startswith(prefix):
                continue
            name = relative[len(prefix):]
            if name == "shell.qml" and source.name == "shell":
                original = b'home + "/.config/omarchy/shell.json"'
                if data.count(original) != 1:
                    raise SystemExit("Upstream shell config contract changed")
                # One shell checkout, shared user plugins/theme, per-display config.
                data = data.replace(original, b'(Quickshell.env("OMARCHY_SHELL_CONFIG") || home + "/.config/omarchy/shell.json")')
            put(target + "/" + name, data, mode)

    omarchy = "root/.local/share/omarchy"
    for folder in omarchy_folders:
        tree(a.omarchy / folder, omarchy + "/" + folder)
    for name in omarchy_files:
        data, mode = canonical[a.omarchy][name]
        put(omarchy + "/" + name, data, mode)
    package = a.mobile / "packages/omamobile-shell"
    for folder in ["plugins", "profile"]:
        tree(package / folder, "usr/share/omamobile/shell/" + folder)
    for name in ["template", "examples"]:
        tree(package / "widgets" / name, "usr/share/omamobile/shell/widget-" + name)
    for name in ["WIDGETS.md", "OWL.md"]:
        put("usr/share/omamobile/shell/" + name, blob(package / "widgets" / name)[0])
    tree(package / "bin", "usr/bin")
    put("usr/share/licenses/omamobile-shell/LICENSE", blob(package / "LICENSE")[0])
    put("usr/share/licenses/omamobile-shell/LICENSE-owl-guide", blob(package / "widgets/LICENSE-owl-guide")[0])

    # Use Mobile's compatibility index, not a claim that every store widget works.
    # Canonical Git bytes are checked against its SHA-256 before shipping.
    store = git_files(a.widgets, WIDGETS, ["widgets", "order.txt", "LICENSE"])
    index_bytes = git_files(a.mobile, MOBILE, ["packages/omamobile-owl-widgets"])
    index = index_bytes["packages/omamobile-owl-widgets/index.json"][0]
    for widget in json.loads(index)["widgets"]:
        if widget.get("status") not in ("ok", "partial"):
            continue
        name = "widgets/" + widget["id"] + "/widget.owl"
        data, mode = store[name]
        if hashlib.sha256(data).hexdigest() != widget["sha256"]:
            raise SystemExit("Widget differs from upstream compatibility index: " + widget["id"])
        put("usr/share/omamobile/owl-widgets/" + name, data, mode)
    put("usr/share/omamobile/owl-widgets/index.json", index)
    put("usr/share/omamobile/owl-widgets/order.txt", store["order.txt"][0])
    put("usr/share/omamobile/owl-widgets/version", index_bytes["packages/omamobile-owl-widgets/version"][0])
    put("usr/share/licenses/omamobile-owl-widgets/LICENSE", store["LICENSE"][0])

    disabled = ["omarchy.idle", "omarchy.lock", "omarchy.polkit", "omarchy.nightlight", "omarchy.remote-session"]
    desktop = json.loads(blob(a.omarchy / "config/omarchy/shell.json")[0])
    desktop["idle"] = {"screensaver": 0, "lock": 0}
    desktop["disabledPlugins"] = disabled
    phone = json.loads(blob(package / "profile/shell.json")[0])
    phone["plugins"] = [{"id": x} for x in ["omamobile.home", "omamobile.launcher", "omamobile.switcher", "omamobile.quicksettings"]]
    phone["idle"] = desktop["idle"]
    phone["disabledPlugins"] = disabled
    for profile, config in [("phone", phone), ("desktop", desktop)]:
        put("usr/share/omamobile/android/" + profile + ".json", json.dumps(config, indent=2) + "\n")

    # These are the upstream QML widgets, seeded with offline data for a usable
    # first screen. User changes are subsequently kept by setup-linux-shell.
    layout = {"version": 1, "grid": {"columns": 4, "rows": 6}, "home": 0, "nextId": 6,
              "pages": [{"items": [
                  {"id": "w1", "kind": "widget", "type": "clock", "x": 0, "y": 0, "w": 4, "h": 2, "config": {"timeFormat": "HH:mm", "dateFormat": "dddd d MMMM"}},
                  {"id": "w2", "kind": "widget", "type": "agenda", "x": 0, "y": 2, "w": 4, "h": 3, "config": {}},
                  {"id": "a3", "kind": "app", "app": "foot", "x": 0, "y": 5, "w": 1, "h": 1}
              ]}, {"items": [
                  {"id": "w4", "kind": "widget", "type": "notes", "x": 0, "y": 0, "w": 4, "h": 2, "config": {"text": "One Linux installation. Phone and desktop."}},
                  {"id": "w5", "kind": "widget", "type": "media", "x": 0, "y": 2, "w": 4, "h": 1, "config": {}}
              ]}]}
    put("usr/share/omamobile/android/home.json", json.dumps(layout, indent=2) + "\n")
    tools = Path(__file__).parent
    for name in ["setup-linux-shell", "start-shell", "omamobile-android", "sync-session-theme"]:
        put("root/.local/share/omamobile/" + name, (tools / "linux" / name).read_bytes(), 0o755)
    put("usr/bin/omamobile-android", (tools / "linux/omamobile-android").read_bytes(), 0o755)
    put("usr/lib/omamobile/session-bin/uwsm-app", (tools / "linux/session-exec").read_bytes(), 0o755)
    put("usr/share/omamobile/android/sources.json", json.dumps({"omarchy": OMARCHY, "omarchy-mobile": MOBILE, "omarchy-widgets": WIDGETS,
        "adapter": "OMARCHY_SHELL_CONFIG override; Android-session-only uwsm-app argv adapter without systemd scopes; additive theme-set.d hook applies theme IPC to the active peer profile; upstream application launch code unchanged; Linux-only setup and launch scripts"}, indent=2) + "\n")
print(a.output, a.output.stat().st_size)
