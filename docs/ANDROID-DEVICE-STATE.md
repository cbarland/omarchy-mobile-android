# Android device state

The private Android application portal exposes a read-only `device-state`
operation for the rootless Linux session. It queries Android on every request
and returns separate `interactive`, `keyguardLocked`, and `deviceLocked`
booleans. The operation does not use Shizuku, a cache, a file snapshot, or a
generic shell command.

`interactive` is Android's awake/interactive state. `keyguardLocked` reports
whether the lock screen is showing, while `deviceLocked` reports whether the
current user requires authentication to access applications. They are kept
separate because Android assigns them different semantics. Missing Android
services or query failures return the portal's ordinary `ok:false` error; a
Linux caller must treat unavailable state as blocked.

The Unix socket remains application-UID-only, bounded, and private. The
operation is available while `DesktopService` owns the portal and disappears
when that service tears down.

Use `omamobile-android device-state` from Linux. Freshly prepared images include
the CLI. On an existing installation, an authenticated APK update may
atomically refresh this one helper when starting an ordinary command or new
graphical session if the existing file is an exact audited 0755 preimage. Missing,
mode-customized, symlinked, unknown, or user-edited helpers stay unchanged.
The update does not rerun setup or refresh the full Linux payload, packages, or
desktop entries; see [Android app integration](ANDROID-APPS.md#scope-and-cleanup)
for the complete helper contract. An older or malformed reply fails with stderr
and nonzero exit status.

On the disposable API35 emulator, the previous APK rejects the new operation.
The candidate passes direct Android API and real Linux CLI comparisons, plus
existing protocol/discovery checks (three tests, 2.321s). A separate short
sleep/wake probe observes `interactive` change true → false → true while both
display owners remain stable. No Shizuku app grant is present for these checks.
This does not verify secure-keyguard transitions: the emulator has no device
credential and both lock booleans remained false. The three API reads are
separate observations rather than an atomic security snapshot.

## Android radio state

The authenticated portal's `radio-state` operation and Linux
`omamobile-android radio-state` command expose whether the default network is
available to the host app's Android UID; Linux receives that same sample
through the portal. The response includes default-network transport and
capability facts, plus Wi-Fi and Bluetooth radio support and transition state.
`unavailable` means
[`ConnectivityManager.getActiveNetwork()`](https://developer.android.com/reference/android/net/ConnectivityManager#getActiveNetwork())
returned null: the app has no visible
default network, or Android blocked that network for the app. This API cannot
distinguish those cases or report device-global network availability.
`available` means a default network was returned, not that it is validated or
reaches the internet; those capabilities are reported separately. Missing
services or samples remain explicit as `unknown` or `null`, and unsupported
hardware as `unsupported`. The response contains no network identifiers or
nearby-device data. These are read-only observations. Android owns radio
hardware and connectivity; Linux does not gain NetworkManager or BlueZ control
from this interface.

The normal Android permissions are `ACCESS_NETWORK_STATE` and
`ACCESS_WIFI_STATE`; the legacy `BLUETOOTH` declaration is limited to API 30
and below. No nearby-device runtime permission is requested. A fresh Linux
payload includes this command. An APK update may refresh only an exact audited
helper preimage; it does not add a missing desktop entry or replace upstream
network and Bluetooth tools. A failed portal acknowledgement or malformed
response exits with stderr and a nonzero status.

## Android display controls

Choose **Android displays** in the Linux application menu, or run
`omamobile-android display-settings`, to request the existing host display panel
on the phone's default display. The panel supplies external display selection,
Android mode and rotation facts, and the existing desktop scale
preview/keep/revert flow. Omarchy's Display panel remains available for its
Hyprland monitor controls.

The private portal accepts only this fixed host action and reports that Android
was asked to show it; it cannot launch arbitrary components. Android still owns
activity launch policy. Fresh Linux payloads contain the command and desktop
entry. The narrow helper refresh above does not add a missing full payload or
desktop entry.

On API35 x86_64, the graphical integration test passed in 50.439s with both
upstream shells ready. It checked a cold host activity, reuse of the same
activity through the real Linux CLI, actual dialog visibility, and routing to
the default display. The APK passed 117 native/installer payload comparisons
and eight ELF presence/ABI checks. Installation identity markers were preserved.
This does not establish other Android versions or physical display behavior.
