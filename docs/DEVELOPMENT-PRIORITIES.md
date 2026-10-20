# Development priorities: onboarding and upstream compatibility

The current focus is making the existing system accessible to testers and making
contributions independent of a maintainer's prepared machine. Prefer finishing
these paths over adding more desktop features.

## Install and use without a maintainer's private setup

The user-facing completion condition is a maintained graphical APK download,
authenticated Linux bootstrap, useful progress/retry diagnostics, and updates
that preserve the existing Linux installation. A contributor should be able to
follow public instructions from a fresh checkout and reproduce the candidate.

Public graphical distribution still requires the seven native corresponding-
source/relink records, reproducible native input/build evidence, a maintained
signing/update path, and the physical-device gate. Do not present the core CI APK
or source-only prerelease as an installable desktop. Track distribution in
[issue 8](https://github.com/cbarland/omarchy-mobile-android/issues/8), installation
and recovery in [issue 3](https://github.com/cbarland/omarchy-mobile-android/issues/3),
and ARM64 device acceptance in [issue 10](https://github.com/cbarland/omarchy-mobile-android/issues/10).

Independent contributions can cover individual owned native dependencies,
installer failure/retry states, export/recovery behavior, clean build verification,
and device reports. Signing keys and user data are not contribution prerequisites.

Before expanding tester onboarding, close the observed startup input-readiness
and phone black-frame-after-reconnect gaps. Hyprland output/layer presence alone
does not establish a usable Android frame. See the current
[verification record](VERIFICATION.md#mobile-main-upgrade-and-contributor-checks-20-october-2026).

## Keep the phone experience current with Mobile main

Use the [upstream upgrade path](UPSTREAM-UPGRADES.md): one reviewed lock, isolated
branch/commit probes, an explicit stock-file diff, and in-place migration checks.
The first upgrade targets Mobile shell 0.2.9 at `f1885a7`, keeping Omarchy and the
widget store fixed. A green source check must be followed by real shell/input and
data-preservation observations before accepting an installed-system upgrade.

Reduce downstream shell changes through upstream extension points. Schema,
dependency and application-entry changes need review; do not silently treat
successful substitution as complete upstream compatibility. Do not let a user
`git pull` bypass the APK-owned file manifest and recovery transaction.

## Enable concurrent contributors

Shell and UI work must have a source-payload check without Android/NDK setup.
Android host work must retain clean core CI without staged graphics. Native
distribution and hardware tests are separate paths with their own evidence.
See [CONTRIBUTING](../CONTRIBUTING.md) for entry points.

Keep PRs scoped to one layer and state their completion check. Use separate
branches, commit-keyed source caches, output directories and runtime emulators.
Share reproducible source/test artifacts and redacted reports, not private rootfs
backups or mutable build directories. Broader clipboard, acceleration, hardware
and power features remain useful work once these onboarding paths are reliable.
