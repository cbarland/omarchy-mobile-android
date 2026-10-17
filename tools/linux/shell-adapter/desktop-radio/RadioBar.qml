import QtQuick
import Quickshell
import qs.Ui
import "../omamobile.android" as Android

// Android-session-only desktop radio shortcuts. The shared singleton is the
// only state owner; this widget never loads NetworkManager or BlueZ objects.
Item {
  id: root

  // BarIconButton uses the host bar for registration and pointer routing.
  property QtObject bar: null

  readonly property bool enabled: Android.AndroidRadio.enabled
  readonly property var network: Android.AndroidRadio.network
  readonly property var wifi: Android.AndroidRadio.wifi
  readonly property var bluetooth: Android.AndroidRadio.bluetooth

  visible: enabled
  implicitWidth: enabled ? row.implicitWidth : 0
  implicitHeight: enabled ? row.implicitHeight : 0

  Component.onCompleted: if (enabled) Android.AndroidRadio.barVisible = true
  Component.onDestruction: if (enabled) Android.AndroidRadio.barVisible = false

  function networkText() {
    if (!enabled || network.state === "unknown") return "?"
    if (network.state !== "available") return "?"
    var transports = network.transports || []
    if (transports.indexOf("wifi") >= 0) return "󰤯"
    if (transports.indexOf("cellular") >= 0) return "CELL"
    return transports.length > 0 ? "NET" : "?"
  }

  function wifiStatus() {
    if (!enabled) return "Unknown"
    var state = wifi.state
    if (state === "unknown") return "Unknown"
    if (state === "unsupported") return "Unsupported"
    if (state === "enabled") return "On · Open Android settings"
    if (state === "disabled") return "Off · Open Android settings"
    return "Transitioning · Open Android settings"
  }

  function bluetoothText() {
    if (!enabled || bluetooth.state === "unknown" || bluetooth.state === "unsupported") return "?"
    return bluetooth.state === "on" || bluetooth.state === "turning-on" ? "󰂯" : "󰂲"
  }

  function bluetoothStatus() {
    if (!enabled) return "Unknown"
    var state = bluetooth.state
    if (state === "unknown") return "Unknown"
    if (state === "unsupported") return "Unsupported"
    if (state === "on") return "On · Open Android settings"
    if (state === "off") return "Off · Open Android settings"
    return "Transitioning · Open Android settings"
  }

  function openSettings(action) {
    if (!enabled) return
    Quickshell.execDetached(["omamobile-android", action])
  }

  Row {
    id: row
    spacing: 0

    BarIconButton {
      bar: root.bar
      text: root.networkText()
      tooltipText: root.wifiStatus()
      onPressed: root.openSettings("network-settings")
    }

    BarIconButton {
      bar: root.bar
      text: root.bluetoothText()
      tooltipText: root.bluetoothStatus()
      onPressed: root.openSettings("bluetooth-settings")
    }
  }
}
