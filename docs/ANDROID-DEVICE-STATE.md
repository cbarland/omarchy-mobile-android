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
the CLI; updating the APK alone does not refresh an existing Linux installation.
An older or malformed reply fails with stderr and nonzero exit status.

On the disposable API35 emulator, the previous APK rejects the new operation.
The candidate passes direct Android API and real Linux CLI comparisons, plus
existing protocol/discovery checks (three tests, 2.321s). A separate short
sleep/wake probe observes `interactive` change true → false → true while both
display owners remain stable. No Shizuku app grant is present for these checks.
This does not verify secure-keyguard transitions: the emulator has no device
credential and both lock booleans remained false. The three API reads are
separate observations rather than an atomic security snapshot.

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
entry; an APK update alone does not refresh installed Linux helper files.

On API35 x86_64, the graphical integration test passed in 50.439s with both
upstream shells ready. It checked a cold host activity, reuse of the same
activity through the real Linux CLI, actual dialog visibility, and routing to
the default display. The APK passed 117 native/installer payload comparisons
and eight ELF presence/ABI checks. Installation identity markers were preserved.
This does not establish other Android versions or physical display behavior.
