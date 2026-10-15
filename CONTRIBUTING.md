# Contributing

Start with [the project intent](docs/INTENT.md), [building](docs/BUILDING.md), and
[current verification evidence](docs/VERIFICATION.md). This is an Android adapter
for upstream Omarchy and Omarchy Mobile, with one shared app-private Linux
installation and independent phone/external compositor sessions.

Useful small contributions include installer failure recovery, clean-checkout
build tooling, archive-import edge cases, Android display lifetime tests, keyboard
and IME compatibility, documentation, and reproducible application compatibility
reports. A focused issue describing a concrete user problem is enough to begin.

## Keep changes reviewable

Prefer a small fix with a reproducer over a broad rewrite. Preserve upstream
application and shell behavior where possible; document an Android-specific
adapter or upstream patch with its source revision and reason. Keep generated
native libraries, rootfs images, local SDK paths, signing material, and device
captures out of source changes. Preserve applicable third-party notices.

For a larger change, describe the intended behavior and a completion check in an
issue first so contributors can coordinate. No contributor agreement is required
by this repository; its [license](LICENSE) and included third-party licenses apply.

## Validate the affected behavior

Run the host tests and relevant JVM/lint checks in [BUILDING.md](docs/BUILDING.md).
Use a meaningful regression for changes to process lifetime, archive safety,
display routing, or resource ownership. Documentation-only changes do not need a
device run. Report what you actually exercised: a core APK build does not verify
native graphics, and an emulator run does not establish physical-device support.

Graphics and Android-window instrumentation require prepared displays/rootfs and,
for the optional Android windows, an existing Shizuku ADB-mode grant. They are
separate from ordinary pull-request CI. Use a disposable emulator for lifecycle
and installation checks; preserve prepared installations and authorization when
working on a shared test environment.

## Report results without exposing device data

Include the source commit, ABI, Android version, build mode, reproduction steps,
expected/actual result, and a short relevant log excerpt. Redact serial numbers,
account names, tokens, notification contents, and personal paths. Do not upload a
private Linux home/rootfs or full device bugreport just to report a small failure.
Screenshots should show the application state needed to understand the problem.

Use the pull-request template to explain the final behavior and validation. For a
security issue, use the private route in [SECURITY.md](SECURITY.md).
