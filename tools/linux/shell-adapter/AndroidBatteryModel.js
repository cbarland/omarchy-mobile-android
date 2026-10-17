function unknown() {
  return { present: null, percentage: null, plugged: null, state: "unknown" }
}

function project(packet) {
  if (!packet || typeof packet !== "object") return unknown()
  var present = packet.present === true || packet.present === false ? packet.present : null
  var percentage = Number.isFinite(packet.percentage) && packet.percentage >= 0 && packet.percentage <= 100
    ? packet.percentage : null
  var states = ["charging", "discharging", "full", "not-charging", "unknown"]
  var state = states.indexOf(packet.state) >= 0 ? packet.state : "unknown"
  return { present: present, percentage: percentage, plugged: packet.plugged === true ? true : packet.plugged === false ? false : null, state: state }
}

function fraction(snapshot) {
  return snapshot && snapshot.present === true && Number.isFinite(snapshot.percentage) ? snapshot.percentage / 100 : 0
}

function displayPercentage(snapshot) {
  return snapshot && snapshot.present === true && Number.isFinite(snapshot.percentage) ? Math.round(snapshot.percentage) + "%" : "—"
}

function icon(snapshot) {
  if (!snapshot || snapshot.present !== true || !Number.isFinite(snapshot.percentage)) return "?"
  if (snapshot.state === "charging") return "󰂆"
  if (snapshot.state === "full") return "󰂅"
  var icons = ["󰁺", "󰁻", "󰁼", "󰁽", "󰁾", "󰁿", "󰂀", "󰂁", "󰂂", "󰁹"]
  return icons[Math.max(0, Math.min(9, Math.floor(snapshot.percentage / 10)))]
}

function label(snapshot) {
  if (!snapshot || snapshot.present !== true) return "Battery unknown"
  if (!Number.isFinite(snapshot.percentage)) return "Battery level unknown"
  if (snapshot.state === "charging") return "Charging"
  if (snapshot.state === "full") return "Fully charged"
  if (snapshot.state === "discharging") return "On battery"
  return "Not charging"
}

if (typeof module !== "undefined") module.exports = { unknown, project, fraction, displayPercentage, icon, label }
