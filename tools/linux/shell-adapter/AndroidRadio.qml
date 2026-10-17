pragma Singleton
import QtQuick
import Quickshell
import Quickshell.Io
import "AndroidRadioModel.js" as Model

// One Android-owned radio observation shared by the phone bar and quick
// settings. The CLI owns packet validation; this boundary only projects the
// already validated fields and fails closed to unknown on execution errors.
QtObject {
  id: root

  readonly property bool enabled: Quickshell.env("OMAMOBILE_SESSION_HOST") === "android"
  property bool barVisible: false
  property bool quickSettingsVisible: false
  readonly property bool polling: enabled && (barVisible || quickSettingsVisible)
  readonly property var snapshot: sample
  property var sample: ({
    network: { state: "unknown", transports: null },
    wifi: { supported: null, state: "unknown" },
    bluetooth: { supported: null, state: "unknown" }
  })

  readonly property var network: sample.network || ({ state: "unknown", transports: null })
  readonly property var wifi: sample.wifi || ({ supported: null, state: "unknown" })
  readonly property var bluetooth: sample.bluetooth || ({ supported: null, state: "unknown" })

  function unknown() {
    sample = {
      network: { state: "unknown", transports: null },
      wifi: { supported: null, state: "unknown" },
      bluetooth: { supported: null, state: "unknown" }
    }
  }

  function accept(text) {
    var value = null
    try { value = JSON.parse(String(text || "").trim()) } catch (error) {}
    if (!value || typeof value !== "object" || !value.network || !value.wifi || !value.bluetooth) {
      unknown()
      return
    }
    sample = Model.project(value)
  }

  function refresh() {
    if (!enabled || radio.running) return
    radio.running = true
  }

  property Process radio: Process {
    command: ["omamobile-android", "radio-state"]
    stdout: StdioCollector {
      waitForEnd: true
      onStreamFinished: root.accept(text)
    }
    onExited: function(exitCode) {
      if (exitCode !== 0) root.unknown()
    }
  }

  property Timer pollTimer: Timer {
    interval: root.quickSettingsVisible ? 1000 : 5000
    repeat: true
    running: root.polling
    triggeredOnStart: true
    onTriggered: root.refresh()
  }
}
