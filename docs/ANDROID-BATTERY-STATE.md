# Android battery state

The private Android application portal exposes a read-only `battery-state`
operation. It reads the public sticky `ACTION_BATTERY_CHANGED` broadcast for
each request and returns exactly `present`, `percentage`, `plugged`, and
`state`. `percentage` is a finite number from 0 through 100 when Android
provides a valid present battery level and scale; unavailable values are JSON
null. `state` is one of `charging`, `discharging`, `full`, `not-charging`, or
`unknown`. No permission, shell service, persistent receiver, or Linux-side
hardware ownership is involved.

The Linux command is `omamobile-android battery-state`. It rejects malformed,
non-finite, out-of-range, or extra/missing fields. This is a data API only:
the UPower facade and Omarchy battery widget are not enabled by this unit.
