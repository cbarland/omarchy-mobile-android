// Pure presentation mapping for the Android radio packet. Packet validation
// remains in /usr/bin/omamobile-android; this module only supplies safe
// fallbacks for the QML boundary.

function section(packet, name, fallback) {
  var value = packet && packet[name]
  return value && typeof value === "object" ? value : fallback
}

function project(packet) {
  return {
    network: section(packet, "network", { state: "unknown", transports: null }),
    wifi: section(packet, "wifi", { supported: null, state: "unknown" }),
    bluetooth: section(packet, "bluetooth", { supported: null, state: "unknown" })
  }
}

function networkKind(network) {
  var transports = network && Array.isArray(network.transports) ? network.transports : []
  if (transports.indexOf("wifi") >= 0) return "wifi"
  if (transports.indexOf("ethernet") >= 0) return "ethernet"
  return "disconnected"
}

function radioAvailable(radio) {
  return !!radio && radio.supported !== false && radio.state !== "unsupported"
}

if (typeof module !== "undefined") {
  module.exports = { section, project, networkKind, radioAvailable }
}
