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
  if (!network || network.state === "unknown") return "unknown"
  if (network.state !== "available") return "unavailable"
  var transports = network && Array.isArray(network.transports) ? network.transports : []
  if (transports.indexOf("wifi") >= 0) return "wifi"
  if (transports.indexOf("ethernet") >= 0) return "ethernet"
  if (transports.indexOf("cellular") >= 0) return "cellular"
  if (transports.length > 0) return "connected"
  return "unavailable"
}

function radioAvailable(radio) {
  return !!radio && radio.supported !== false && radio.state !== "unsupported"
}

function requestBarOwners(owners, owner, visible) {
  var next = Array.isArray(owners) ? owners.slice() : []
  var index = next.indexOf(owner)
  if (visible && index < 0) next.push(owner)
  if (!visible && index >= 0) next.splice(index, 1)
  return next
}

function radioGridColumns(vertical, buttonCount) {
  return vertical ? 1 : buttonCount
}

function networkDisplay(kind) {
  if (kind === "unknown") return "?"
  if (kind === "unavailable") return "disconnected"
  if (kind === "wifi") return "wifi"
  if (kind === "cellular") return "CELL"
  return "NET"
}

function networkTooltip(kind) {
  if (kind === "unknown") return "Network unknown"
  if (kind === "unavailable") return "No default network · Open Android settings"
  if (kind === "wifi") return "Wi-Fi connected · Open Android settings"
  if (kind === "cellular") return "Cellular connected · Open Android settings"
  return "Network connected · Open Android settings"
}

if (typeof module !== "undefined") {
  module.exports = { section, project, networkKind, radioAvailable, requestBarOwners,
    radioGridColumns, networkDisplay, networkTooltip }
}
