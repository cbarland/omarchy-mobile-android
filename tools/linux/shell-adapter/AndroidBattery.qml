pragma Singleton
import QtQuick
import Quickshell
import Quickshell.Io
import "AndroidBatteryModel.js" as Model

QtObject {
  id: root

  readonly property bool enabled: Quickshell.env("OMAMOBILE_SESSION_HOST") === "android"
  property var panelOwners: []
  function requestPanel(owner, visible) {
    var next = panelOwners.slice()
    var index = next.indexOf(owner)
    if (visible && index < 0) next.push(owner)
    if (!visible && index >= 0) next.splice(index, 1)
    panelOwners = next
  }
  readonly property bool panelVisible: panelOwners.length > 0
  readonly property bool polling: enabled && panelVisible
  property var sample: Model.unknown()
  readonly property bool present: sample.present === true
  readonly property real fraction: Model.fraction(sample)

  function accept(text) {
    var value = null
    try { value = JSON.parse(String(text || "").trim()) } catch (error) {}
    sample = Model.project(value)
  }

  function refresh() {
    if (!enabled || battery.running) return
    battery.running = true
  }

  property Process battery: Process {
    command: ["omamobile-android", "battery-state"]
    stdout: StdioCollector { waitForEnd: true; onStreamFinished: root.accept(text) }
    onExited: function(exitCode) { if (exitCode !== 0) root.sample = Model.unknown() }
  }

  property Timer pollTimer: Timer {
    interval: 5000
    repeat: true
    running: root.polling
    triggeredOnStart: true
    onTriggered: root.refresh()
  }
}
