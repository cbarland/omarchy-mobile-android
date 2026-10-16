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
