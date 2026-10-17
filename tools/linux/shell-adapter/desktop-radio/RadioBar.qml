import QtQuick
import Quickshell
import qs.Ui
import "../omamobile.android" as Android
import "../omamobile.bar/StatusModel.js" as StatusModel
import "../omamobile.android/AndroidRadioModel.js" as Model

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
  implicitWidth: enabled ? layout.implicitWidth : 0
  implicitHeight: enabled ? layout.implicitHeight : 0

  Component.onCompleted: if (enabled) Android.AndroidRadio.requestBar(root, true)
  Component.onDestruction: if (enabled) Android.AndroidRadio.requestBar(root, false)

  function networkText() {
    if (!enabled) return "?"
    var kind = Model.networkKind(network)
    var display = Model.networkDisplay(kind)
    if (display === "disconnected") return StatusModel.networkIcon(kind, -1)
    if (display === "wifi") return "󰤯"
    return display
  }

  function networkStatus() {
    if (!enabled) return "Unknown"
    return Model.networkTooltip(Model.networkKind(network))
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

  Grid {
    id: layout
    spacing: 0
    columns: Model.radioGridColumns(root.bar && root.bar.vertical, 2)

    BarIconButton {
      bar: root.bar
      text: root.networkText()
      tooltipText: root.networkStatus()
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
