# Rootless access-shell adapter

Graphical sessions set `OMAMOBILE_ACCESS_SHELL` to the staged
`omamobile-access-shell` adapter. It intercepts exactly `omarchy-shell lock
isLocked`, queries the live Android portal with `omamobile-android
device-state`, and reports locked when the keyguard or device is locked, or
when Android is not interactive. Missing, malformed, or failed state queries
return failure and therefore fail closed.

Every other argument vector is delegated unchanged to the pinned upstream
`$OMARCHY_PATH/bin/omarchy-shell`. The adapter does not alter upstream shell
code and does not provide a cache, daemon, or synthetic unlocked state.

The upstream mechanism is the MIT-licensed `omamobile-portal-access` pattern
at Omarchy Mobile commit `953bcb3058347459cdf7faaa1e149f16e0f7cfa6`; its
lock check invokes the shell as `omarchy-shell lock isLocked`. This adapter
keeps that call boundary while replacing only the Android-owned lock query.

The adapter is staged into a newly prepared shell payload. An APK update does
not rewrite that full installed payload; the narrow refresh path for the single
`/usr/bin/omamobile-android` helper does not update this shell hook. An existing
installation therefore needs the refreshed Linux shell payload applied before
running its normal shell setup. Rerunning `setup-linux-shell` alone cannot add
a missing helper. If the helper is absent, the environment hook cannot provide
a synthetic unlocked result and the upstream call fails closed. See
[Android app integration](ANDROID-APPS.md#scope-and-cleanup) for the separate
helper-refresh contract.

On the disposable API35 emulator, the healthy upstream shell answered `ping`
but its Linux `lock isLocked` target was unavailable. With the adapter staged,
the real Android query mapped awake → asleep → awake to false → true → false,
and ordinary shell `ping` still delegated successfully. Both display owners
remained stable and were healthy after waking. A graphical instrumentation
check also verified the hook inside a genuinely D-Bus-activated process on
both profiles (one test, 10.138s). These checks required no Shizuku app grant.
Secure credential-lock transitions and the complete Access portal UI remain
separate verification tasks.
