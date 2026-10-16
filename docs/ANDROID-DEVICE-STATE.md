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
