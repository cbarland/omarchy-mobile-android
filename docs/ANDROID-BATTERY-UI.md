# Android battery UI

The phone bar and desktop power panel read the existing authenticated
`omamobile-android battery-state` command in Android sessions. Android remains
the battery authority; this does not emulate UPower or change Android power
settings. Sessions outside Android retain the upstream UPower behavior.

A shared QML singleton polls while a consumer is active, with one command in
flight and a five-second interval. Unknown presence or percentage displays an
unknown indicator and an em dash. Explicit absence hides the battery button
and percentage while retaining observation, so a returning battery can appear.
Charging, full, discharging, not-charging, and unknown status remain distinct.
The Android desktop panel hides the unsupported Linux power-profile controls.

The generated consumers import the singleton from its owned guest installation
path, `file:///usr/share/omamobile/shell/plugins/omamobile.android`. The payload
provides that directory; the unmodified upstream source checkouts do not need
it. Packaging checks the pinned input hashes and requires each desktop battery
insertion point to occur exactly once.

This is a fresh shell payload feature, like the [radio UI](ANDROID-RADIO-UI.md).
An APK update's managed CLI refresh does not replace an existing shell, user
profile, theme, or layout. Full-shell update delivery remains separate work.

The Python/Node presentation fixtures exercise the battery model. Actual QML
loading, visibility, live Android battery changes, and both-display rendering
also require runtime verification; model tests alone do not establish them.
