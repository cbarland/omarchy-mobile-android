# Shared desktop developer preview

This is a source-only prerelease for contributors. It contains the Android
integration and native build patches, not an installable graphical APK or a
ready-to-import Linux image. The clean CI APK validates the core host and cannot
display the desktop. Seven corresponding-source/relinking holds still block
publication of the staged graphical APK; see [release readiness](RELEASE-READINESS.md).

## Behavior

The default **Start Omarchy screens** path remains available. The opt-in
**Start shared compositor preview** drives two logical outputs from one
Hyprland compositor and one Quickshell process:

- `ANDROID-1`: Mobile bar, widget home, launcher, quick settings, and switcher.
- `ANDROID-2`: Omarchy desktop bar, panels, and applications.

Both use one Linux rootfs, home, installed programs, and theme. Linux windows
can move between outputs. Output generations prevent retired Android surfaces
from controlling their replacements. Removing the external display returns
its windows to the phone; its logical output name is stable on reconnect.

Captured mouse movement uses one Wayland seat across both outputs, even when
Android delivers the Bluetooth input to the phone window. Hyprland owns cursor
movement, application focus and scrolling across the configured monitor layout.
Ctrl+Alt+Escape releases capture; phone touch input remains screen-local.

Each output has its own persisted scale. The upstream display panel remains
reachable on the phone when the external output is absent. Android's scale
preview supports Keep, Revert, timeout, and recovery after process termination.
Custom monitor files are preserved. See [graphics integration](GRAPHICS.md)
for configuration paths and [the build guide](BUILDING.md) for contributor setup.

Existing prepared Linux images are not migrated by an APK update: the new
profile and QML require an updated shell payload. Safe in-place payload upgrades
are follow-on work. Do not reset an existing installation to try this preview
without first exporting and verifying your data backup.

## Verification and limits

The 18 October 2026 x86_64 emulator checks exercised the native panel's scale
control, independent phone/external CLI scaling and persistence, Keep/Revert/
timeout, a phone-only display panel, external disconnect/reconnect with the
desktop bar inside the output viewport, native and fast IPC opening the same
single panel, and preview rollback after force-stop. Final captures showed a
Foot application on the desktop alongside the phone clock/calendar widgets.
The test restored the original APK and affected Linux files afterward.

The behavior run used source `ffe5eeaf1dcd`. The wording-only follow-up
`807fc35cb776` passed APK build, JVM tests, lint, and mechanical packaging checks.
Its native libraries and shell payload match the tested APK. Resolved DEX
instructions and data match after normalizing the expected build-revision
string and relocated string-table indexes; its changed caption was checked in
the compiled resources. The full display matrix was not repeated for that caption.

This functional result is not a clean diagnostic gate. The run retained 18
overlapping IPC-registration warnings and one reconnect readiness timeout before
the next query succeeded. An earlier startup stalled after `EGL_BAD_CONFIG`;
unchanged reruns succeeded, but its cause remains open. Existing Linux hardware-
service and portal warnings also remain. Physical ARM64 testing of this shared
mode, arbitrary additional monitors, accelerated Linux clients, and comprehensive
application/plugin compatibility are not established by these checks.

Ordinary Linux audio has passed private transport tests but is not yet wired
into normal sessions. Integrated audio and safe payload upgrades are the next
development priorities. Existing battery/radio adapters and input behavior retain
the limits documented in the [verification record](VERIFICATION.md).

Omarchy, Omarchy Mobile, TAWC, OWL, and the other upstream projects remain the
foundations of this work. See [credits](../CREDITS.md) and
[source references](REFERENCES.md) for attribution and provenance.
