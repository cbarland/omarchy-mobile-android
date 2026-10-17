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
MOBILE_TRANSFORM_HASHES = {
    "packages/omamobile-shell/plugins/omamobile.bar/Bar.qml": "de1f04dd367758032b0d4e8ad09b3aeadcb1d44dde3c910c674b8c3476f849c0",
    "packages/omamobile-shell/plugins/omamobile.quicksettings/QuickSettings.qml": "db944640ddbcd6dfa736e3059cb2e5aa0c4ce56073fbf31868a91e1887896ff7",
}
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

def mobile_transform(relative, data):
    expected = MOBILE_TRANSFORM_HASHES.get(relative)
    if expected is None:
        return data
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise SystemExit(f"Pinned mobile transform input changed for {relative}: {actual}")
    text = data.decode("utf-8")
    if relative.endswith("omamobile.bar/Bar.qml"):
        text = text.replace('import "StatusModel.js" as StatusModel\n',
            'import "StatusModel.js" as StatusModel\nimport "../omamobile.android" as Android\n', 1)
        text = text.replace('  id: root\n',
            '  id: root\n\n'
            '  Component.onCompleted: Android.AndroidRadio.barVisible = true\n'
            '  Component.onDestruction: Android.AndroidRadio.barVisible = false\n', 1)
        start = text.index("  readonly property var networkDevices:")
        end = text.index("  // Cellular signal.", start)
        text = text[:start] + '''  readonly property bool androidRadio: Android.AndroidRadio.enabled
  readonly property var networkDevices: Networking.devices ? Networking.devices.values : []
  readonly property var wifiDevice: StatusModel.findDevice(networkDevices, DeviceType.Wifi)
  readonly property var wiredDevice: StatusModel.findDevice(networkDevices, DeviceType.Wired)
  readonly property var wifiNetwork: StatusModel.connectedNetwork(wifiDevice && wifiDevice.networks ? wifiDevice.networks.values : [])
  readonly property var androidNetwork: Android.AndroidRadio.network
  readonly property string networkKind: {
    if (androidRadio) {
      var transports = androidNetwork.transports || []
      if (transports.indexOf("wifi") >= 0) return "wifi"
      if (transports.indexOf("ethernet") >= 0) return "ethernet"
      return "disconnected"
    }
    if (wiredDevice && wiredDevice.connected) return "ethernet"
    if (wifiNetwork) return "wifi"
    return wifiDevice && !Networking.wifiEnabled ? "off" : "disconnected"
  }
  // Android exposes no RSSI in radio-state; -1 selects the no-signal glyph.
  readonly property string networkIcon: StatusModel.networkIcon(networkKind,
    androidRadio ? -1 : (wifiNetwork ? Math.round((wifiNetwork.signalStrength || 0) * 100) : -1))

''' + text[end:]
        text = text.replace('visible: !!root.btAdapter && root.btAdapter.enabled\n            text: root.bluetoothConnected ? "󰂱" : "󰂯"',
            'visible: root.androidRadio ? (Android.AndroidRadio.bluetooth.state === "on" || Android.AndroidRadio.bluetooth.state === "turning-on") : (!!root.btAdapter && root.btAdapter.enabled)\n            text: root.androidRadio ? "󰂯" : (root.bluetoothConnected ? "󰂱" : "󰂯")', 1)
    else:
        text = text.replace('import Quickshell.Wayland\n',
            'import Quickshell.Wayland\nimport "../omamobile.android" as Android\n', 1)
        text = text.replace('  function open(payloadJson) {\n',
            '  function open(payloadJson) {\n    Android.AndroidRadio.quickSettingsVisible = true\n    Android.AndroidRadio.refresh()\n', 1)
        text = text.replace('  function close() {\n',
            '  function close() {\n    Android.AndroidRadio.quickSettingsVisible = false\n', 1)
        text = text.replace('  function openWifi() {\n    if (!wifi.available) return "unavailable"\n',
            '  function openWifi() {\n    if (Android.AndroidRadio.enabled) { Quickshell.execDetached(["omamobile-android", "network-settings"]); return "settings-requested" }\n    if (!wifi.available) return "unavailable"\n', 1)
        text = text.replace('  function openBluetooth() {\n    if (!bluetooth.available) return "unavailable"\n',
            '  function openBluetooth() {\n    if (Android.AndroidRadio.enabled) { Quickshell.execDetached(["omamobile-android", "bluetooth-settings"]); return "settings-requested" }\n    if (!bluetooth.available) return "unavailable"\n', 1)
        text = text.replace('  function openWifiPanel() {\n    openPage("wifi")\n',
            '  function openWifiPanel() {\n    if (Android.AndroidRadio.enabled) { Quickshell.execDetached(["omamobile-android", "network-settings"]); return }\n    openPage("wifi")\n', 1)
        text = text.replace('  function openBluetoothPanel() {\n    openPage("bluetooth")\n',
            '  function openBluetoothPanel() {\n    if (Android.AndroidRadio.enabled) { Quickshell.execDetached(["omamobile-android", "bluetooth-settings"]); return }\n    openPage("bluetooth")\n', 1)

        wifi_start = text.index('  QtObject {\n    id: wifi\n')
        wifi_end = text.index('  // The picker behind the tile', wifi_start)
        wifi = '''  QtObject {
    id: wifi

    readonly property bool android: Android.AndroidRadio.enabled
    readonly property bool backendAvailable: Networking.backend === NetworkBackendType.NetworkManager
    readonly property var devices: Networking.devices ? Networking.devices.values : []
    readonly property var device: {
      for (var i = 0; i < devices.length; i++)
        if (devices[i] && devices[i].type === DeviceType.Wifi) return devices[i]
      return null
    }
    readonly property var network: {
      var networks = device && device.networks ? device.networks.values : []
      for (var i = 0; i < networks.length; i++)
        if (networks[i] && networks[i].connected) return networks[i]
      return null
    }
    readonly property bool available: android
      ? (Android.AndroidRadio.wifi.state !== "unsupported" && Android.AndroidRadio.wifi.supported !== false)
      : (backendAvailable && !!device && Networking.wifiHardwareEnabled !== false)
    readonly property bool enabled: android ? Android.AndroidRadio.wifi.state === "enabled"
      : (available && Networking.wifiEnabled)
    readonly property string icon: enabled ? "󰤨" : "󰤭"
    readonly property bool details: !android
    function openDetails() { root.openWifiPanel() }
    readonly property string status: {
      if (android) {
        var state = Android.AndroidRadio.wifi.state
        if (state === "unknown") return "Unknown"
        if (state === "unsupported") return "Unsupported"
        if (state === "enabling") return "Turning on…"
        if (state === "disabling") return "Turning off…"
        if (state === "disabled") return "Off · Open Android settings"
        return "On · Open Android settings"
      }
      if (!backendAvailable) return "NetworkManager not running"
      if (!device) return "No Wi-Fi radio"
      if (!Networking.wifiEnabled) return "Off"
      return network ? network.name : "Not connected"
    }

    function toggle() {
      if (android) { root.openWifiPanel(); return }
      if (available) Networking.wifiEnabled = !Networking.wifiEnabled
    }
  }

'''
        text = text[:wifi_start] + wifi + text[wifi_end:]

        bt_start = text.index('  QtObject {\n    id: bluetooth\n')
        bt_end = text.index('  // The panel behind the Bluetooth tile', bt_start)
        bt = '''  QtObject {
    id: bluetooth

    readonly property bool android: Android.AndroidRadio.enabled
    readonly property var adapter: Bluetooth.defaultAdapter
    readonly property bool available: android
      ? (Android.AndroidRadio.bluetooth.state !== "unsupported" && Android.AndroidRadio.bluetooth.supported !== false)
      : !!adapter
    readonly property bool enabled: android ? Android.AndroidRadio.bluetooth.state === "on"
      : (available && adapter.enabled)
    property bool pending: false
    readonly property bool busy: pending
    readonly property string icon: enabled ? (connected ? "󰂱" : "󰂯") : "󰂲"
    readonly property bool details: !android
    function openDetails() { root.openBluetoothPanel() }
    readonly property var connected: {
      var devices = Bluetooth.devices ? Bluetooth.devices.values : []
      for (var i = 0; i < devices.length; i++)
        if (devices[i] && devices[i].connected) return devices[i]
      return null
    }
    readonly property string status: {
      if (android) {
        var state = Android.AndroidRadio.bluetooth.state
        if (state === "unknown") return "Unknown"
        if (state === "unsupported") return "Unsupported"
        if (state === "turning-on") return "Turning on…"
        if (state === "turning-off") return "Turning off…"
        if (state === "off") return "Off · Open Android settings"
        return "On · Open Android settings"
      }
      if (!available) return "No adapter"
      if (pending) return enabled ? "Turning off…" : "Turning on…"
      if (!enabled) return "Off"
      return connected ? (connected.name || "Connected") : "On"
    }

    onEnabledChanged: pending = false

    function toggle() {
      if (android) { root.openBluetoothPanel(); return }
      if (!available || pending) return
      pending = true
      bluetoothPendingTimeout.restart()
      Quickshell.execDetached(["omarchy-bluetooth-power", enabled ? "off" : "on"])
    }
  }

'''
        text = text[:bt_start] + bt + text[bt_end:]
    return text.encode("utf-8")

a.output.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(a.output, "w:gz") as archive:
    written = set()
    def put(name, data, mode=0o644):
        if name in written:
            raise SystemExit(f"Duplicate payload member: {name}")
        written.add(name)
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
            if root is a.mobile:
                data = mobile_transform(relative, data)
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
    adapter = Path(__file__).parent / "linux/shell-adapter"
    for name in ["AndroidRadio.qml", "AndroidRadioModel.js", "qmldir"]:
        put("usr/share/omamobile/shell/plugins/omamobile.android/" + name,
            (adapter / name).read_bytes())
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
    phone["plugins"] = [{"id": x} for x in ["omamobile.home", "omamobile.launcher", "omamobile.switcher", "omamobile.quicksettings", "omamobile.access"]]
    desktop_plugins = desktop.setdefault("plugins", [])
    if not any(isinstance(plugin, dict) and plugin.get("id") == "omamobile.access" for plugin in desktop_plugins):
        desktop_plugins.append({"id": "omamobile.access"})
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
    put("usr/share/applications/org.omamobile.AndroidDisplays.desktop",
        b"[Desktop Entry]\n"
        b"Name=Android displays\n"
        b"Type=Application\n"
        b"Exec=/usr/bin/omamobile-android display-settings\n"
        b"Terminal=false\n"
        b"Icon=video-display\n"
        b"Categories=Settings;HardwareSettings;\n")
    for filename, title, action, icon in [
        ("org.omamobile.AndroidNetworkSettings.desktop", "Android network settings", "network-settings", "network-wireless"),
        ("org.omamobile.AndroidBluetoothSettings.desktop", "Android Bluetooth settings", "bluetooth-settings", "bluetooth"),
    ]:
        put("usr/share/applications/" + filename,
            ("[Desktop Entry]\nName=" + title + "\nType=Application\n"
             "Exec=/usr/bin/omamobile-android " + action + "\nTerminal=false\nIcon=" + icon + "\n"
             "Categories=Settings;HardwareSettings;\n").encode())
    tools = Path(__file__).parent
    for name in ["setup-linux-shell", "start-shell", "omamobile-android", "omamobile-access-shell", "verify-access-portal", "sync-session-theme"]:
        put("root/.local/share/omamobile/" + name, (tools / "linux" / name).read_bytes(), 0o755)
    put("usr/bin/omamobile-android", (tools / "linux/omamobile-android").read_bytes(), 0o755)
    put("usr/share/xdg-desktop-portal/portals/omamobile.portal", blob(package / "portal/omamobile.portal")[0])
    # Select only the Access backend. Other interfaces are intentionally
    # unverified; this payload makes no GNOME/FUSE/PipeWire claim.
    put("etc/xdg/xdg-desktop-portal/hyprland-portals.conf",
        b"[preferred]\ndefault=*\norg.freedesktop.impl.portal.Access=omamobile\n")
    # Private user D-Bus activates this backend directly.  Keep only the
    # canonical Name/Exec contract; this rootless Android payload has no
    # systemd --user manager to consume SystemdService metadata.
    put("usr/share/dbus-1/services/org.freedesktop.impl.portal.desktop.omamobile.service",
        b"[D-BUS Service]\n"
        b"Name=org.freedesktop.impl.portal.desktop.omamobile\n"
        b"Exec=/usr/bin/omamobile-portal-access\n")
    put("usr/lib/omamobile/session-bin/uwsm-app", (tools / "linux/session-exec").read_bytes(), 0o755)
    put("usr/share/omamobile/android/sources.json", json.dumps({"omarchy": OMARCHY, "omarchy-mobile": MOBILE, "omarchy-widgets": WIDGETS,
        "adapter": "OMARCHY_SHELL_CONFIG override; Android-session-only uwsm-app argv adapter without systemd scopes; additive theme-set.d hook applies theme IPC to the active peer profile; rootless Android lock-state adapter and canonical omamobile.access D-Bus Access backend; Android radio-state QML singleton for fresh payloads only; upstream application launch code unchanged; Linux-only setup and launch scripts"}, indent=2) + "\n")
print(a.output, a.output.stat().st_size)
