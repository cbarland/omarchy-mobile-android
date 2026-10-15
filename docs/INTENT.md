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

## Adapter boundaries

- **Runtime:** pinned TAWC/tawcroot backend, one app-private rootfs. Do not patch distro executables for a private installation prefix.
- **Storage:** app-private Linux data; explicit directory binds for Android-accessible paths; future SAF file operations as a separate capability.
- **Android commands:** an opt-in native broker, UID-checked local socket, with command exit status and descriptors forwarded. No fork from Android's Java runtime.
- **Outputs/input:** Android display lifecycle and surface/input adapters around genuine Hyprland and upstream Linux shells. Both emulator and Nubia ARM64 present real Wayland surfaces on phone and external outputs. The external host requests relative pointer capture. The user confirmed Bluetooth keyboard input, mouse movement and clicks on the Nubia after stopping the older Omalateral input helper. Broader physical input and lifecycle coverage remain part of device verification.
- **Linux shell:** upstream packages own UI, layouts, plugins and themes. Do not port individual plugins into Java to simulate ecosystem compatibility.
- **Native environment contract:** present the paths, session environment, command interfaces and Linux services expected by Omarchy ARM/Mobile through a small adapter layer. Install normal upstream packages when they can run here; translate Android-owned facilities at their boundary. Prefer this to feature-specific edits in upstream shells. Preserve argv, exit status and diagnostics; document unsupported service/hardware semantics rather than returning false success. Reference coding tools are optional; contributor builds require no agent, model-provider account or private reference library.
- **Lifecycle:** user-started foreground work; no boot persistence or disabled Android process limits. Real Linux PTY terminals work inside the graphical session. Complete detached-daemon cleanup and suspend/resume still need validation.

## Next development sequence

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

1. Keep the green emulator lifecycle baseline under regression coverage:
   full lifecycle 26.236s and slow broker 11.151s on prepared emulator-5554,
   plus graphical Ando 14.874s and ordinary and rapid saved-selection reconnect
   on disposable emulator-5556. Repeat normal phone/desktop and Android-window
   recovery checks when related changes land.
2. Restore basic Omarchy functions, alongside display management as the next
   priority. Start with the user-reported silent theme-install failure;
   reproduce through the normal UI, diagnose the actual failed command and
   dependencies, and verify install/apply/switch/persistence across both
   profiles. Generated data-only theme Git loopback HTTP install/apply/restore,
   peer IPC and screenshots are verified; public HTTPS/TLS third-party theme
   networking remains unverified. Audit everyday menu actions, app launching,
   configuration,
   package/plugin operations and shell reloads. Align error reporting with
   upstream Omarchy conventions; useful logs and command exit status can be
   sufficient, without requiring new visible error UI. Unsupported
   Android-host operations must be identifiable in the appropriate existing
   reporting path. Rendering the shell alone is
   not evidence that these workflows work. See the
   [basic-function checks](VERIFICATION.md#basic-omarchy-function-checks).
3. Continue the display helper using upstream Omarchy display controls where
   possible, evaluating Hyprmon for arrangement. Desktop panel mapping,
   per-profile scale isolation, atomic reload and restart persistence are
   verified. Ordinary and rapid Android display-selection UI persistence passed
   on disposable emulator-5556. Physical display modes and cross-session window
   movement are unsupported. See [the display plan](GRAPHICS.md#display-management-helper-prototype).
4. The in-app archive installer now prepares signed Linux packages and pinned
   shell sources before activation; fresh x86_64 emulator setup and cancellation
   pass. Broaden fresh ARM64 coverage, automate authenticated image downloading,
   and implement data export/recovery. Native inputs use committed package locks;
   full reproducibility and binary corresponding-source delivery remain open.
5. Complete Linux/Android integration: storage/document adapters,
   IME/clipboard/notifications, audio and hardware/status services.
6. Broaden lifecycle and app compatibility: hotplug, rotation, sleep/resume,
   HOME registration and Android work profiles. Validate Wallet independently
   when physical-device testing is resumed.
7. Measure graphics transport, CPU, latency, battery and thermals; validate
   accelerated Linux clients and broader Omarchy programs/plugins.

Do not execute Omarchy Mobile's device flashing, kernel, modem, firmware or partition-install tools in this host. Its current device-specific installation workflow is a reference for a different base system; only compatible userland packages belong here.
