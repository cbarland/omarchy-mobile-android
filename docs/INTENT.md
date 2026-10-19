# Intent and success parameters

Recorded from the product discussion, 2026-10-14.

## Acceptance

The product can be installed on an existing, unmodified Android base. Normal APK installation, app data and user permissions/settings are acceptable. No root, bootloader unlock, ROM/kernel flashing or Android security-policy modification may be required.

Baseline ARM64 installation, shared Arch Linux ARM execution and concurrent phone/external Hyprland rendering now run on the locked Nubia NX789J. The device's verified boot state remained green. Broader device coverage and product-level validation remain open; see VERIFICATION.md.

## Product direction

1. One Linux installation, package database, user data and runtime owner beneath phone and external desktop modes. No separate distro per display, launcher or companion app.
2. Omarchy Mobile's upstream shell/plugins provide the touch phone profile. The external monitor uses normal Omarchy's desktop profile and keyboard/pointer behavior. Profiles are per output and can coexist. An external monitor must not replace the phone profile.
3. Keep Linux open and aligned with Omarchy ARM: normal distro layout, pacman, ARM-native programs, ordinary configuration and as much of the upstream plugin ecosystem as the Android host permits. Compatibility gaps must be explicit and solved in adapters where possible.
4. Android remains the hardware and security owner. It handles cellular service, Android apps, work profiles, authentication, permissions and Google Wallet. Preserve the installed base and its security posture.
5. Integrate authorized Android storage and Android shell commands. A file-picker URI is not a POSIX directory. `ando` has the Android app's privileges unless a separately authorized system facility provides more.
6. Prefer native, efficient runtime/graphics mechanisms; avoid duplicating Linux environments, continuous polling or keeping inactive sessions awake. Performance and battery life need measurement on hardware.
7. Preserve and eventually export/recover user data. Package updates and user customization must remain independently possible.

These are engineering goals, not additional acceptance gates. Omalauncher and Omalateral are references/migration sources; neither is the foundation of this new host. The existing upstream Omalauncher bridge PR stays frozen.

Prefer upstream Linux interfaces and services wherever they can run rootlessly.
The integration layer supplies platform capabilities beneath that experience.
This includes Mobile's native Squeekboard input method and keyboard controls;
Android-specific interfaces serve Android-owned capabilities and explicit fallbacks.

## Adapter boundaries

- **Runtime:** pinned TAWC/tawcroot backend, one app-private rootfs. Do not patch distro executables for a private installation prefix.
- **Storage:** app-private Linux data; explicit directory binds for Android-accessible paths; future SAF file operations as a separate capability.
- **Android commands:** an opt-in native broker, UID-checked local socket, with command exit status and descriptors forwarded. No fork from Android's Java runtime.
- **Outputs/input:** Android display lifecycle and surface/input adapters around genuine Hyprland and upstream Linux shells. Both emulator and Nubia ARM64 present real Wayland surfaces on phone and external outputs. The external host requests relative pointer capture. The user confirmed Bluetooth keyboard input, mouse movement and clicks on the Nubia after stopping the older Omalateral input helper. Broader physical input and lifecycle coverage remain part of device verification.
- **Linux shell:** upstream packages own UI, layouts, plugins and themes. Do not port individual plugins into Java to simulate ecosystem compatibility.
- **Native environment contract:** present the paths, session environment, command interfaces and Linux services expected by Omarchy ARM/Mobile through a small adapter layer. Install normal upstream packages when they can run here; translate Android-owned facilities at their boundary. Prefer this to feature-specific edits in upstream shells. Preserve argv, exit status and diagnostics; document unsupported service/hardware semantics rather than returning false success. Reference coding tools are optional; contributor builds require no agent, model-provider account or private reference library.
- **Lifecycle:** user-started foreground work; no boot persistence or disabled Android process limits. Real Linux PTY terminals work inside the graphical session. Complete detached-daemon cleanup and suspend/resume still need validation.

## Emulator development backlog — 2026-10-15

Use disposable emulator data for destructive clean-install and recovery tests.
Preserve prepared installations and explicitly authorize each physical-device
test. Prefer official signed Arch/Arch Linux ARM and Omarchy packages. AUR source
builds are a separate compatibility and trust decision.

The shared Arch ARM runtime, signed package operations and simultaneous real
phone/external shells have been demonstrated on the locked Nubia. The user
confirmed physical keyboard/mouse operation, including mouse use after
reopening. Current new-installer development uses the emulator; physical
verification of the new installation flow remains separate. Keep an existing
Shizuku authorization unless a specific test requires changing it.

The current baseline includes fresh x86_64 archive installation, cancellation,
both real shells, basic theme/package/config workflows, representative OWL and
Scratchpad integrations, saved display routing, and targeted lifecycle tests.
The source repository is public and contributor CI passes. These are completed
milestones, not evidence that every upstream feature or Android app works.

1. **Complete everyday Omarchy workflows.** Preserve the passing theme
   install/apply/restore and reopen checks. Verify public HTTPS/TLS theme
   downloads, reviewed theme/plugin compatibility, normal menus and shortcuts,
   app launching, configuration and shell reload/discovery. Supply missing normal
   packages or session adapters rather than per-feature shell rewrites. Completion
   means each audited workflow passes or records a specific unavailable
   capability through upstream-aligned logs and exit status. New error UI is not
   required. See the [basic-function checks](VERIFICATION.md#basic-omarchy-function-checks).
2. **Finish display recovery and controls.** Existing inventory, target
   persistence, independent scale values and reconnect checks stay covered. Add
   safe apply/revert and a reachable phone recovery path; test rotation, virtual
   resolution/density changes, ambiguous/missing targets, lock/unlock and
   sleep/resume. Extend the existing upstream display UI where feasible. Hyprmon
   evaluation cannot turn the current two sessions into one extended desktop.
   Physical mode control remains conditional on Android capabilities. See
   [the display plan](GRAPHICS.md#display-management-helper-prototype).
3. **Complete installation and data recovery.** Build authenticated image
   download with a pinned signer and bad-signature rejection, bounded retries,
   interrupted-setup recovery and useful persistent logs. Add user-requested
   Linux data export/restore and safe replacement without overwriting a live
   installation. Verify round-trip home/configuration/permissions and failed
   restores on a disposable emulator. Existing archive setup/cancellation already
   passes; fresh ARM64 installation remains a separate physical checkpoint.
4. **Make full graphical builds reproducible and distributable.** Rebuild and
   stage native inputs from empty documented caches; detect stale staging files.
   Complete the seven native dependency versions' corresponding-source bundles,
   recipes and patches. Exercise the full payload gate, signing/update path and
   same-key APK updates preserving Linux data. Core CI is already green but does
   not build or verify the complete graphics payload. See
   [release readiness](RELEASE-READINESS.md).
5. **Finish text, clipboard and file integration.** The simple committed-text
   InputConnection is not full IME support. Add/test composition, Unicode,
   selection and editing semantics, Android/Wayland clipboard synchronization,
   and permission-aware document open/save operations. Retain existing shared
   directory binds; do not present SAF URIs as ordinary POSIX paths. Verify
   revoked permissions, large files and cancellation without data loss.
6. **Broaden Android app and phone-shell integration.** Test multiple hosted
   apps, focus, resize, close/crash recovery and helper reconnection; extend
   ASCII/touch forwarding for Unicode, hover and context menus. Add optional HOME
   registration and app launching from the phone shell. Use a disposable managed
   profile to develop work-app discovery/launch behavior. Native Android
   AppWidgets would be a separate hosting feature; current OWL widgets already
   have direct evidence. Preserve grants unless a particular test requires a
   permission change.
7. **Implement Android-owned service adapters.** Start with Linux audio output
   and volume, then media controls, notifications, network and battery status.
   Expose the Linux-facing interfaces expected by upstream where feasible and
   report unsupported operations accurately. Emulators can verify transport,
   permissions, events and restart behavior; real Bluetooth, cellular and power
   behavior require physical validation. Linux systemd is not Android's PID 1.
8. **Reduce idle work and improve graphics transport.** Measure CPU, memory,
   frame latency and child-process counts for idle/active/hidden sessions and
   multiple Android windows. Replace or throttle the JPEG polling path, stop
   inactive work and verify cleanup across repeated reopen/hotplug cycles.
   Prototype accelerated Linux clients and assess Xwayland compatibility
   separately; neither is established by compositor GLES rendering. Emulator
   results cannot establish device battery life, thermals or driver compatibility.

Keep the existing Ando, widget, pointer-release, slow-broker, detached-process
and saved-selection regressions green when related changes land. Broader runtime
ownership for batch-console detached daemons also needs explicit validation.

**Larger architectural track:** a shared multi-output compositor/backend, or
another explicit window-transfer design, is needed for live window movement,
one monitor inventory and multiple external monitors. This can be prototyped with
emulator displays, but is a substantial change beyond finishing the current
phone-plus-one-external arrangement. Two sessions sharing a rootfs are not one
extended desktop.

**Physical checkpoints:** fresh ARM64 installation, USB-C/display modes and
reconnect behavior, real Bluetooth/IME combinations, audio routing, GPU drivers,
battery/thermal measurements, OEM work-profile policy, protected content and
Google Wallet. Emulator coverage of managed profiles does not establish Wallet
or real-device policy compatibility.

Do not execute Omarchy Mobile's device flashing, kernel, modem, firmware or partition-install tools in this host. Its current device-specific installation workflow is a reference for a different base system; only compatible userland packages belong here.
