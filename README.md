# Omarchy Mobile on Android

An Android host for one shared, open Linux installation. Genuine Hyprland renders the upstream Omarchy desktop shell on an external display and Omarchy Mobile with widgets on the phone. Both profiles render concurrently on the ARM64 Nubia NX789J and an Android emulator. This is an independent integration prototype, not an official Omarchy release; the Omalauncher bridge PR remains frozen.

The [current accounting](docs/VERIFICATION.md#current-accounting--2026-10-15) separates verified behavior from pending work. Physical device development is paused. On prepared emulator-5554, the full graphics lifecycle (26.236s) and slow-broker shutdown (11.151s) checks are green; disposable emulator-5556 passed graphical Ando (14.874s), ordinary/rapid saved-selection reconnect (13.560s), and visible-IME Escape/Android Back checks (16.219s).

The single product acceptance criterion is installation on an existing, unmodified Android base. A normal APK install, app data and user-granted Android permissions are allowed. Root, an unlocked bootloader, flashing, a custom kernel and changed Android security policy must not be prerequisites.

The intended Linux foundation is Arch Linux ARM plus upstream Omarchy ARM / Omarchy Mobile packages. The host must not replace pacman, curate a restricted app catalog or reimplement each Omarchy plugin in Android. Omarchy Mobile supplies the phone experience; normal Omarchy supplies the desktop experience. Android continues to own telephony, hardware permissions, keyguard and Android applications.

## Implemented

- One app-private rootfs, shared by every command session and display profile.
- Native TAWC `tawcroot` runtime, built from pinned MIT source for ARM64 and emulator x86_64. No PRoot dependency.
- Import a standard unwrapped `.tar` or `.tar.gz` rootfs, preserving files, symbolic links and executable bits. When Android denies hardlinks, the importer uses relative-symlink aliases as TAWC does; exact imported hardlink identity remains a gap. The importer checks the shell's ELF architecture, rejects escaping paths and activates a fresh installation by atomic rename. Existing installations are never overwritten.
- `/android/shared` maps to this app's Android shared-files directory. This is a real directory bind, not a Storage Access Framework URI presented as a mount.
- Optional `ando` invokes Android commands as the app's real UID through a native broker. It adds no ADB, root, work-profile or shell-UID privilege.
- Optional Android DNS configuration writes the current network's DNS servers into the Linux resolver configuration only when selected.
- The default display gets the upstream mobile shell, clock/calendar and editable widget pages. One presentation display gets the upstream Omarchy bar and wallpaper. Native Hyprland presents real Wayland clients on Android Surfaces, with touch, mouse and keyboard forwarding.
- A user-started foreground service owns graphical sessions. Their launch threads remain alive for the runtime's parent-death contract. The development console runs batch commands; a real Linux Foot terminal provides interactive PTY sessions in the desktop.
- The pinned Omalauncher OWL store is included with Mobile's existing compiler/runtime, compatibility index and per-widget approvals. The upstream todo widget renders and its tap action updates the shared Linux note. This verifies that widget; the entire store and Android AppWidgets are not certified.
- An optional Shizuku adapter runs as Android shell UID 2000, with explicit user authorization. It launches Android apps on dedicated virtual displays and presents each through a normal GTK Wayland window, so Hyprland tiles it alongside Linux applications. Settings click/text input, resizing and display cleanup are verified on the emulator. Linux and the compositor retain the normal app UID. No accessibility service or root is used.
- Normal `omarchy plugin add` installation and the community `rob.scratchpad` bar widget have been tested, including its workspace-toggle action. Plugins remain Linux source, not Java ports. Generated data-only theme Git loopback HTTP install/apply/restore, peer-phone IPC, restart persistence and screenshots are covered; public HTTPS/TLS third-party theme networking remains unverified.
- Neovim is provisioned from the standard Arch signed package and opens through the upstream configuration in Foot. Exact text input, `Escape`, `:wq`, save and exit pass externally. A real phone Wayland client receives each Escape edge exactly once from a visible Android IME; normal Android Back still hides the keyboard. Broader Unicode/composition and IME compatibility remain open.

## Build and try

Requires JDK 17+, Android SDK 35, NDK `30.0.14904198`, CMake `4.1.2`. Set `JAVA_HOME` and `ANDROID_HOME`, or use Android Studio's SDK configuration.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Android 10+. The command runtime and staged graphics backend build for ARM64 and x86_64. Build/stage the compositor with [the graphics recipe](docs/GRAPHICS.md) before assembling a graphics APK; without a staged backend the APK contains only the command runtime. Physical verification and current limits are recorded in [VERIFICATION.md](docs/VERIFICATION.md).

Do not run Gradle's connected-test task against a prepared Linux installation: its uninstall cleanup removes app data. Use `adb install -r` plus explicit `am instrument` calls instead.

Open the app and select a trusted rootfs archive with `/bin/sh` at its normal root. The intended device image is the generic ARM64 userland from [Arch Linux ARM](https://archlinuxarm.org/platforms/armv8/generic); do not flash any bootloader or image to the phone. Verify the archive's upstream signature before importing it. This prototype records a SHA-256 fingerprint but does **not** authenticate publisher signatures or download a distribution automatically.

For emulator work, convert the official Arch x86_64 bootstrap's `root.x86_64/` wrapper into an unwrapped tar/gzip. ARM64 and x86_64 are not interchangeable. The compatibility fixture in instrumentation tests is a test executable, not an Arch image.

After import, try `uname -m; cat /etc/os-release; pacman --version`. With Android commands enabled, try `ando /system/bin/id`. To configure DNS from Android, select the DNS option before running a command. Linux packages, configuration and updates remain user-controlled.

Prepare the signed Linux graphics packages and pinned upstream shell sources as described in [GRAPHICS.md](docs/GRAPHICS.md), then press **Start Omarchy screens**. Phone and desktop have separate Wayland/session-bus sockets but share the rootfs, home, package database, plugins and theme.

The app has one Recent Apps card. Repeated starts reuse the two display hosts; dismissing that card stops both graphical sessions. Reopening and starting the screens restores mouse capture and reconnects an already authorized Android-window helper.

The desktop Omarchy stock display panel maps and answers on the external profile. The upstream Mobile phone bar has no stock desktop display panel, so a global summon returns `unknown`; the phone scale CLI works. Per-profile scale values (2 and 1.25) remain isolated, apply through an atomic `sed -i` reload, and persist across restart. Ordinary and rapid Android display-selection persistence passed Activity recreation and reconnect on disposable emulator-5556. Cross-session window movement and physical display mode control are unsupported.

For Android windows, start official Shizuku in **ADB mode**, authorize this host with its control-panel button, then use `omamobile-android sync` in Linux. Android apps appear as ordinary desktop entries; `omamobile-android open com.android.settings` also works. The adapter only controls its own displays. See [the adapter and its current limits](docs/ANDROID-APPS.md).

The standard Arch signed `yay` package is installed at version 13.0.1 for the normal removal picker, which passed local-query filtering and pacman removal without AUR commands. A developer provisioning step may configure the signed Omarchy repository, then run `pacman -Syu --needed yay`; `yay` is not assumed to be in Arch core's default preparation set. Codex account setup needs the Linux CLI installed first. This prepared Nubia runs official ARM64 Codex CLI 0.162.1 in the shared installation. For a fresh image, see the optional [Linux tool preparation](docs/LINUX-TOOLS.md); installing the CLI does not sign in to an account.

The focused external desktop requests relative mouse capture and draws its own cursor. The user confirmed Bluetooth keyboard input, mouse movement and clicks on the Nubia. The mouse initially failed while an older Omalateral privileged helper was running; it worked after that helper stopped. That helper can grab mouse events exclusively, so stop its input capture before using this desktop. **Ctrl+Alt+Escape** releases capture; losing window focus also releases it. Reopening the controls and pressing **Start Omarchy screens** returns focus to the running desktop.

## Remaining integration

Broader ARM64 hardware coverage, an automatic graphics/rootfs installer, Linux audio and Android hardware/status adapters, Android IME/text input, accessibility, suspend/resume, reserved keyboard shortcuts and phone home registration need further work. The shell logs missing system-bus/NetworkManager/UPower/PipeWire services; Android owns those devices. Internet access and signed pacman operations work independently of those status services. The full plugin/program ecosystem is not yet validated.

Linux Qt currently sends software-rendered `wl_shm` buffers to the Android GLES compositor. Accelerated Linux client EGL, Xwayland and arbitrary glibc Hyprland binary plugins are not claimed. Borrowed code and Android session adaptations are recorded in [REFERENCES.md](docs/REFERENCES.md).

`tawcroot` provides Linux compatibility inside the Android app UID; it is not a security sandbox around Linux code. Only run trusted Linux software. Android's sandbox and user grants remain the security boundary. Protected Android apps are not moved into Linux. Work-app / Wallet compatibility and power efficiency are product goals that still need physical-device checks; this project has not proved them.

Stopping a command terminates its initial process; a daemon that detaches can outlive it. Android app force-stop is the complete process-tree boundary in this prototype. App-private Linux data is removed on uninstall; export/recovery and rootfs replacement are future work. Arbitrary Android Documents storage needs a separate SAF adapter; this milestone binds only app-owned shared storage.

See [intent and implementation boundaries](docs/INTENT.md), [reference provenance](docs/REFERENCES.md) and [verification record](docs/VERIFICATION.md).
