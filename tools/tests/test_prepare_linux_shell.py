#!/usr/bin/env python3
"""Verify canonical payloads against real pinned local checkouts, without editing them.

Example (PowerShell):
  python tools/tests/test_prepare_linux_shell.py --omarchy <checkout> --mobile <checkout> --widgets <checkout>

Disposable shared clones and the JSON report are retained beneath .build-tools.
This is an explicit integration check, not part of automatic unit discovery.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import uuid
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from shell.sources import DEFAULT_LOCK, load_sources


ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "tools/prepare-linux-shell.py"


def run(*arguments):
    environment = os.environ.copy()
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    return subprocess.check_output(arguments, text=True, encoding="utf-8", env=environment, timeout=60).strip()


def pins(lock=DEFAULT_LOCK):
    return {name: source['commit'] for name, source in load_sources(lock).items()}


def members(path):
    result = {}
    with tarfile.open(path, "r:gz") as archive:
        for item in archive.getmembers():
            if item.name in result:
                raise AssertionError(f"Duplicate payload member: {item.name}")
            if not item.isfile():
                raise AssertionError(f"Unexpected non-file payload member: {item.name}")
            data = archive.extractfile(item).read()
            result[item.name] = {"sha256": hashlib.sha256(data).hexdigest(), "mode": item.mode, "size": len(data)}
    return result


def member_bytes(path, name):
    with tarfile.open(path, "r:gz") as archive:
        item = archive.getmember(name)
        return archive.extractfile(item).read()

def verify_android_settings_router(quicksettings):
    start = quicksettings.index("  function openAndroidSettings(page) {")
    end = quicksettings.index("\n  }", start) + len("\n  }")
    router = quicksettings[start:end]
    script = '''const assert = require("node:assert/strict");
const Android = { AndroidRadio: { enabled: true } };
const launches = [];
const Quickshell = { execDetached: argv => launches.push(argv) };
''' + router + '''
assert.equal(openAndroidSettings("wifi"), true);
assert.equal(openAndroidSettings("bluetooth"), true);
assert.equal(openAndroidSettings("unexpected"), false);
Android.AndroidRadio.enabled = false;
assert.equal(openAndroidSettings("wifi"), false);
assert.equal(openAndroidSettings("bluetooth"), false);
assert.deepEqual(launches, [
  ["omamobile-android", "network-settings"],
  ["omamobile-android", "bluetooth-settings"]
]);
'''
    run("node", "-e", script)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("omarchy", "mobile", "widgets"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--work-directory", type=Path,
                        help="New disposable directory; defaults to a unique workspace .build-tools child")
    parser.add_argument("--sources-lock", type=Path, default=DEFAULT_LOCK)
    arguments = parser.parse_args()
    sources = {name: getattr(arguments, name).resolve() for name in ("omarchy", "mobile", "widgets")}
    revisions = pins(arguments.sources_lock)
    original_status = {}
    for name, source in sources.items():
        assert run("git", "-C", str(source), "rev-parse", "HEAD") == revisions[name], f"Wrong {name} reference revision"
        original_status[name] = run("git", "-C", str(source), "status", "--porcelain=v1", "--untracked-files=all")
    directory = (arguments.work_directory or ROOT.parent / ".build-tools" / ("payload-canonical-" + uuid.uuid4().hex[:12])).resolve()
    assert all(not directory.is_relative_to(source) for source in sources.values()), "Disposable output must be outside reference checkouts"
    directory.mkdir(parents=True, exist_ok=False)
    clones = {}
    for name, source in sources.items():
        destination = directory / name
        run("git", "clone", "--quiet", "--shared", "--no-checkout", str(source), str(destination))
        run("git", "-C", str(destination), "-c", "core.autocrlf=false", "-c", "core.eol=lf",
            "checkout", "--quiet", "--detach", revisions[name])
        clones[name] = destination

    def payload(name):
        archive = directory / (name + ".tar.gz")
        run(sys.executable, str(PREPARE), "--omarchy", str(clones["omarchy"]), "--mobile", str(clones["mobile"]),
            "--widgets", str(clones["widgets"]), "--output", str(archive),
            "--sources-lock", str(arguments.sources_lock.resolve()))
        return members(archive)

    before = payload("clean")
    assert before, "Preparation produced an empty payload"
    required_portal = {
        "usr/bin/omamobile-portal-access",
        "usr/share/xdg-desktop-portal/portals/omamobile.portal",
        "etc/xdg/xdg-desktop-portal/hyprland-portals.conf",
        "usr/share/dbus-1/services/org.freedesktop.impl.portal.desktop.omamobile.service",
        "root/.local/share/omamobile/verify-access-portal",
    }
    desktop_entry = "usr/share/applications/org.omamobile.AndroidDisplays.desktop"
    assert desktop_entry in before
    assert member_bytes(directory / "clean.tar.gz", desktop_entry) == (
        b"[Desktop Entry]\nName=Android displays\nType=Application\n"
        b"Exec=/usr/bin/omamobile-android display-settings\nTerminal=false\n"
        b"Icon=video-display\nCategories=Settings;HardwareSettings;\n")
    for action, title, icon in (("network-settings", "Android network settings", "network-wireless"),
                                ("bluetooth-settings", "Android Bluetooth settings", "bluetooth")):
        name = "org.omamobile.Android" + ("Network" if action == "network-settings" else "Bluetooth") + "Settings.desktop"
        assert member_bytes(directory / "clean.tar.gz", "usr/share/applications/" + name) == (
            f"[Desktop Entry]\nName={title}\nType=Application\nExec=/usr/bin/omamobile-android {action}\n"
            f"Terminal=false\nIcon={icon}\nCategories=Settings;HardwareSettings;\n").encode()
    assert required_portal <= before.keys(), f"Access portal payload members missing: {required_portal - before.keys()}"
    adapter_prefix = "usr/share/omamobile/shell/plugins/omamobile.android/"
    assert {adapter_prefix + name for name in ("AndroidRadio.qml", "AndroidRadioModel.js", "AndroidBattery.qml", "AndroidBatteryModel.js", "qmldir")} <= before.keys()
    assert 'battery-state' in member_bytes(directory / "clean.tar.gz", adapter_prefix + "AndroidBattery.qml").decode()
    desktop_radio_prefix = "usr/share/omamobile/shell/plugins/omamobile.desktop-radio/"
    assert {desktop_radio_prefix + name for name in ("RadioBar.qml", "manifest.json")} <= before.keys()
    desktop_manifest = json.loads(member_bytes(directory / "clean.tar.gz", desktop_radio_prefix + "manifest.json"))
    assert desktop_manifest["schemaVersion"] == 1
    assert desktop_manifest["id"] == "omamobile.desktop-radio"
    assert desktop_manifest["kinds"] == ["bar-widget"]
    assert desktop_manifest["entryPoints"] == {"barWidget": "RadioBar.qml"}
    radio_bar = member_bytes(directory / "clean.tar.gz", desktop_radio_prefix + "RadioBar.qml").decode()
    assert 'import "../omamobile.android" as Android' in radio_bar
    assert '["omamobile-android", action]' in radio_bar
    assert '"network-settings"' in radio_bar and '"bluetooth-settings"' in radio_bar
    assert 'visible: enabled' in radio_bar and 'requestBar(root, enabled && visible)' in radio_bar and 'onVisibleChanged' in radio_bar and 'requestBar(root, false)' in radio_bar
    assert 'property QtObject bar: null' in radio_bar
    assert radio_bar.count('bar: root.bar') == 2
    assert 'Grid {' in radio_bar and 'Model.radioGridColumns(root.bar && root.bar.vertical, 2)' in radio_bar
    bar = member_bytes(directory / "clean.tar.gz", "usr/share/omamobile/shell/plugins/omamobile.bar/Bar.qml").decode()
    quicksettings = member_bytes(directory / "clean.tar.gz", "usr/share/omamobile/shell/plugins/omamobile.quicksettings/QuickSettings.qml").decode()
    assert 'import "../omamobile.android" as Android' in bar
    assert "Android.AndroidRadio.enabled" in bar and "Android.AndroidRadio.network" in bar
    assert "Android.AndroidRadio.requestBar(root, Android.AndroidRadio.enabled && root.visible)" in bar
    assert "Android.AndroidRadio.requestBar(root, root.visible)" in bar
    assert "Android.AndroidRadio.requestBar(root, false)" in bar
    assert 'networkKind === "cellular" ? "CELL"' in bar
    assert 'networkKind === "connected" ? "NET"' in bar
    assert 'StatusModel.cellularIcon(-1)' not in bar
    assert 'import "file:///usr/share/omamobile/shell/plugins/omamobile.android" as AndroidBattery' in bar
    assert 'AndroidBattery.AndroidBattery.requestPanel' in bar
    assert 'visible: root.hasBattery' in bar
    assert 'AndroidBatteryModel.icon' not in bar
    assert 'root.batteryPercent >= 0 ? root.batteryPercent + "%" : "—"' in bar
    power = member_bytes(directory / "clean.tar.gz", "root/.local/share/omarchy/shell/plugins/panels/power/Panel.qml").decode()
    assert 'import "file:///usr/share/omamobile/shell/plugins/omamobile.android" as AndroidBattery' in power
    assert power.count('Component.onCompleted:') == 1
    assert 'AndroidBattery.AndroidBattery.requestPanel' in power
    assert 'androidBattery || batteryPresent' in power
    assert 'visible: root.batteryPresent' in power
    assert 'implicitWidth: batteryPresent ? button.implicitWidth : 0' in power
    assert 'implicitHeight: batteryPresent ? button.implicitHeight : 0' in power
    assert 'open: root.opened && root.batteryPresent' in power
    assert 'AndroidBattery.AndroidBattery.displayPercentage(AndroidBattery.AndroidBattery.sample)' in power
    assert 'import "../omamobile.android" as Android' in quicksettings
    assert 'function openAndroidSettings(page)' in quicksettings
    assert quicksettings.index('function openAndroidSettings(page)') < quicksettings.index('function openPage(page)')
    for entry in ('function open(payloadJson)', 'function openWifi()', 'function openBluetooth()',
                  'function openWifiPanel()', 'function openBluetoothPanel()'):
        entry_start = quicksettings.index(entry)
        entry_end = quicksettings.find('\n  function ', entry_start + len(entry))
        body = quicksettings[entry_start:entry_end if entry_end >= 0 else None]
        assert 'openAndroidSettings' in body, entry
    verify_android_settings_router(quicksettings)
    assert "Android.AndroidRadio.quickSettingsVisible = true" in quicksettings
    assert "tile-state.sh" in quicksettings
    portal_config = member_bytes(directory / "clean.tar.gz", "etc/xdg/xdg-desktop-portal/hyprland-portals.conf")
    assert portal_config == b"[preferred]\ndefault=*\norg.freedesktop.impl.portal.Access=omamobile\n"
    for profile in ("phone", "desktop"):
        config = json.loads(member_bytes(directory / "clean.tar.gz", f"usr/share/omamobile/android/{profile}.json"))
        assert any(item.get("id") == "omamobile.access" for item in config.get("plugins", [])), profile
    phone = json.loads(member_bytes(directory / "clean.tar.gz", "usr/share/omamobile/android/phone.json"))
    assert "omarchy.network" not in phone.get("disabledPlugins", [])
    assert "omarchy.bluetooth" not in phone.get("disabledPlugins", [])
    desktop = json.loads(member_bytes(directory / "clean.tar.gz", "usr/share/omamobile/android/desktop.json"))
    desktop_right = desktop.get("bar", {}).get("layout", {}).get("right", [])
    desktop_ids = [entry.get("id") for entry in desktop_right if isinstance(entry, dict)]
    assert desktop_ids.count("omamobile.desktop-radio") == 1
    assert "omarchy.network" not in desktop_ids and "omarchy.bluetooth" not in desktop_ids
    assert {"omarchy.network", "omarchy.bluetooth"} <= set(desktop.get("disabledPlugins", []))
    # Invalid JSON makes accidental worktree reads fail loudly. License changes
    # cover both explicit put() calls and trees; widget bytes cover hash checks.
    dirty_paths = {
        "omarchy": ["config/omarchy/shell.json", "LICENSE"],
        "mobile": ["packages/omamobile-shell/profile/shell.json", "packages/omamobile-shell/LICENSE",
                   "packages/omamobile-shell/widgets/LICENSE-owl-guide", "packages/omamobile-owl-widgets/index.json"],
        "widgets": ["LICENSE", "order.txt"],
    }
    index_path = clones["mobile"] / "packages/omamobile-owl-widgets/index.json"
    widget = next(item for item in json.loads(index_path.read_text(encoding="utf-8"))["widgets"]
                  if item.get("status") in {"ok", "partial"})
    dirty_paths["widgets"].append("widgets/" + widget["id"] + "/widget.owl")
    for name, paths in dirty_paths.items():
        for relative in paths:
            (clones[name] / relative).write_bytes(b"deliberately dirty worktree; never ship this\r\n")
    untracked = clones["omarchy"] / "shell/.canonical-payload-untracked"
    untracked.write_bytes(b"untracked file must not enter payload\n")
    mode_path = "bin/omarchy-theme-set"
    mode = run("git", "-C", str(clones["omarchy"]), "ls-tree", revisions["omarchy"], "--", mode_path).split()[0]
    assert mode in {"100644", "100755"}, "Expected a regular tracked launcher"
    run("git", "-C", str(clones["omarchy"]), "update-index", "--chmod=" + ("-x" if mode == "100755" else "+x"), mode_path)
    after = payload("dirty")
    changes = sorted(name for name in before.keys() | after.keys() if before.get(name) != after.get(name))
    assert not changes, f"Dirty references changed payload members: {changes[:20]}"
    for name, source in sources.items():
        assert run("git", "-C", str(source), "rev-parse", "HEAD") == revisions[name]
        assert run("git", "-C", str(source), "status", "--porcelain=v1", "--untracked-files=all") == original_status[name], f"Original {name} checkout changed"
    report = {"passed": True, "pins": revisions, "referenceSources": {key: str(value) for key, value in sources.items()},
              "membersCompared": len(before), "dirtyPaths": dirty_paths,
              "indexModeFlipped": mode_path, "untrackedExcluded": str(untracked.relative_to(clones["omarchy"])),
              "originalCheckoutStatusUnchanged": True, "comparison": "tar member SHA-256, mode and size; gzip headers excluded"}
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "membersCompared": len(before), "report": str(directory / "report.json")}))


if __name__ == "__main__":
    main()
