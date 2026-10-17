# Android radio UI adapter

The phone payload adds one shared `omamobile.android` QML singleton. It reads
the already validated `omamobile-android radio-state` command and is consumed
by the pinned phone bar and quick-settings tile backends only when
`OMAMOBILE_SESSION_HOST=android`. Desktop sessions retain the upstream
NetworkManager and BlueZ paths.

The Android branch reports availability, transition, unsupported, unknown, and
no-RSSI states. Wi-Fi and Bluetooth tile taps/details invoke only the fixed
`network-settings` and `bluetooth-settings` portal commands. They do not claim
to toggle a radio, open the upstream NM/BlueZ pickers, enumerate identifiers,
or run arbitrary commands. The existing `tile-state.sh` and `tile-action.sh`
remain unchanged.

The adapter is packaged by `tools/prepare-linux-shell.py` as a bounded
transform of Omarchy Mobile commit
`953bcb3058347459cdf7faaa1e149f16e0f7cfa6`; the transform checks the pinned
Bar and QuickSettings input hashes before changing them. The added mapping
module is pure and has fixture coverage in
`tools/tests/test_android_radio_ui.py`.

The phone Bar and QuickSettings integration remains a fresh-payload feature.
The desktop profile now also seeds an Android-session-only
`omamobile.desktop-radio` bar widget that replaces the canonical desktop
NetworkManager/BlueZ bar entries in new payloads. An APK update's existing `AdapterRefresh`
path updates only the exact `/usr/bin/omamobile-android` helper; it does not
overwrite `/usr/share/omamobile/shell`, user plugins, profiles, themes, home,
or quick-settings configuration. Existing installations need a future
explicit signed full-shell-payload delivery before this UI appears. No
migration or automatic user-tree overwrite is part of this slice.
