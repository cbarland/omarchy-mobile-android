# Android display adapter

The upstream Omarchy Quickshell shell and Omarchy Mobile plugins run in the
same ordinary Arch rootfs. Linux and the compositors run as the normal Android
application UID. The optional Android-window helper is separately authorized
to run as shell UID 2000; see [ANDROID-APPS.md](ANDROID-APPS.md).
This adapter does not flash, unlock, root, run systemd as
Android's PID 1, change SELinux, or replace Android's device credentials.

The compositor is genuine Hyprland from the public Android backend port
https://github.com/taowen/anhyprland at
`bc13ae5859097791b3e93a32632727a7f7075354` (BSD-3-Clause). Its Aquamarine backend
renders to an Android `Surface` with GLES. Linux programs remain glibc ELF
executables and connect with standard Wayland protocols. Initial Qt clients
use `wl_shm` software rendering, so their graphics do not require a Linux DRM
device or libhybris. This is not a claim of accelerated Linux client EGL or
compatibility with arbitrary glibc Hyprland binary plugins.

The port supports one Surface and one compositor lifetime per process.
`PhoneActivity` and `DesktopActivity` therefore live in dedicated Android
processes, each with a compositor, Wayland socket and session D-Bus. They
share the same UID, rootfs, home directory, installed packages, plugins and
theme. A profile override selects different shell layouts without forking
the shell or copying Linux installations. `DesktopService` owns the display
lifecycle and a visible foreground notification.

The private D-Bus daemon receives the same per-profile graphical environment
as the initial shell. Services started through D-Bus activation therefore inherit
the correct runtime, display, compositor signature and Omarchy path. A real Gio
service fixture in `GraphicsSmokeTest` verifies both profiles: the old daemon
environment omitted the runtime; the corrected APK passes the full graphical
check in 10.500s. This establishes activation context, not complete portal,
hardware or systemd compatibility.

## Display management helper prototype

The adapter has display inventory and selection policy code, but it remains a
prototype. The desktop stock display panel maps and answers on the external
profile; the upstream Mobile phone bar has no stock desktop panel, so a global
summon returns `unknown`. The phone scale CLI works. Per-profile scale values
(2 and 1.25) remain isolated, apply through an atomic `sed -i` reload, and
persist across restart. Ordinary and rapid Android display-selection UI
persistence pass Activity recreation and reconnect on disposable emulator-5556.
Prefer reusing Omarchy's existing UI and Linux monitor configuration, with
Android-specific behavior in the adapter.

Candidates checked against upstream documentation:

- [Omarchy Display panel](https://github.com/omacom/omarchy/blob/quattro/manual/05-the-top-bar.md):
  the desktop stock panel maps and answers with scaling and per-monitor
  controls. The upstream Mobile phone bar has no stock desktop panel; a
  global summon returns `unknown`. The phone scale CLI is the available phone
  route.
- [Hyprmon](https://github.com/erans/hyprmon): the
  [official monitor manual](https://github.com/omacom/omarchy/blob/quattro/manual/33-monitors.md)
  recommends this TUI for arranging multiple screens. Evaluate compatibility
  with the pinned Hyprland/Lua configuration before adopting it.
- [omarchy-display-arrange](https://github.com/xe-nvdk/omarchy-display-arrange):
  a community extension of the Display panel with relative placement,
  rotation and mode selection. Evaluate as an optional UI candidate; it has
  layout and keyboard-navigation limitations. Pin and review any adopted
  implementation before installation.

The default **Start Omarchy screens** path exposes one Surface per compositor process.
Phone and desktop therefore have separate Hyprland output inventories;
`DesktopService` also selects only one external presentation display. A
Linux monitor helper cannot by itself join these sessions, add another host
output or control physical Android display modes. Decide how to coordinate
the sessions, or extend the backend for multiple outputs, before claiming a
unified layout, mirroring or cross-display window/pointer movement.

**Start shared compositor preview** selects an experimental alternative: one
Hyprland process and Linux shell drive the phone (`ANDROID-1`) and the selected
external display (`ANDROID-2`). Android Activity/Surface replacements receive
new output generations; old owners cannot detach or send input to a replacement.
Surface loss pauses presentation, while physical external-display removal removes
that Hyprland output so its windows can return to the phone. Reconnection reuses
the logical output name even if Android assigns a new display ID.

The preview runs one Quickshell host with the Mobile bar, home widgets, launcher,
and quick settings on the phone, and the Omarchy desktop bar and panels on the
external output. With no active external output, Omarchy panels can open on the
phone so display controls remain reachable. Its editable config and logs live in `files/graphics/unified/`,
and its Linux runtime is `/run/omamobile/u`. The shared shell config is
`~/.local/share/omamobile/profiles/unified.json`; Mobile home/widget state remains
in `~/.config/omamobile/`. Each output has its own persisted scale in the shared
profile's `hypr/monitors.lua`. Android's desktop scale preview supports Keep,
Revert, and timeout recovery in either mode. Custom monitor files are preserved.

This preview requires Linux prepared from the updated APK's pinned shell payload.
Installing an APK over an existing prepared image does not replace its upstream
QML or user configuration. Stop Linux screens and commands, then use
**Update Linux integration** to apply changed stock files from the new APK.
Existing user profiles are preserved; customized stock files can block an update.
See [payload upgrades and recovery](PAYLOAD-UPGRADES.md). The automatic adapter
refresh only updates the Android command helper.

An exclusive graphical-mode lease prevents both modes from
running against the shared Linux configuration at once; normal Start and Stop
controls can switch back or close the preview. The private native build and
binary publication holds still apply.

Completion should demonstrate on emulator displays:

- Correct phone/external inventory and roles without replacing the phone's
  Mobile shell. Keep Android display IDs separate from persisted monitor
  identities; use stable identity where exposed and handle ambiguity.
- Working scale and rotation controls, and resolution/refresh selection only
  where the Android host exposes usable modes and control. Report unsupported
  operations clearly; Android remains the physical display owner.
- Per-profile scale settings are isolated, apply through an atomic reload and
  persist across restart. Ordinary and rapid Android display-selection UI
  persistence across reconnect/reopening pass on disposable emulator-5556.
- Safe preview/revert for settings that could make an output unusable,
  preserving a reachable phone recovery path.
- An explicit outcome for multiple external monitors and shared placement:
  the two sessions remain independent; cross-session window movement and
  hardware display mode control are unsupported. Do not equate two
  independent sessions with one extended desktop.

## Building the native backend

The WSL build uses Ubuntu 24.04, GCC/G++ 14 for host generators, the Android
NDK matching Gradle's NDK 30, Ninja, pkg-config, patchelf, autoconf, automake, libtool,
bison, flex, libexpat1-dev, libxml2-dev, libffi-dev, libinput-dev and libudev-dev.
An isolated Python environment supplies CMake 4.1.2, Meson 1.10 and Jinja2.

`tools/native/build_port.py --abi x86_64 --ndk <Linux NDK>` checks out the
pinned port in a separate build cache, fetches its pinned source dependencies,
and cross-compiles them. `--abi arm64-v8a` selects the device architecture.
Common C libraries come from official Termux package repositories, checked
against each repository's SHA-256 index. Package scripts are never executed.
They are Android native dependencies, not the Linux package installation.
`termux-packages.json` records the resolved package versions, URLs and hashes.
The recipes retain the port's `android/deps.lock.json` source pins.

Graphite 1.3.15 is built by our first-party `tools/native/build_graphite.py`,
using the archive and reviewed install-layout patch in `source-inputs.lock.json`.
This is an owned source build, not a reproduction of the official Termux package.
The per-ABI result lives at `<cache>/<abi>/owned/graphite2`; the official package
locks and extracted Termux libraries remain unchanged. An existing owned result
is reused only after checking the actual source/archive, recipe, patch, compiler,
CMake/Ninja, build evidence and library hashes. A stale result fails with a request
for a fresh native cache; failed builds do not replace an existing result.
`--source-cache <verified-cache>` uses read-only cached Graphite inputs with no
network fallback. Normal and `--core-only` builds both require the owned result.

Build adaptations select the target ABI, use build-host generators instead
of attempting to execute Android tools in WSL, supply the NDK's GLES/EGL
pkg-config metadata and disable Xwayland for this Wayland milestone. Hardware
libinput bodies are already excluded by the port's Android guards; public
type headers remain available. `patch_port.py` changes Android program
launch to `posix_spawn` with child-only environment metadata. The adapter's
wrapper enters the shared rootfs and preserves Hyprland workspace/exec tokens.
The GLES reset query selects core 3.2, KHR or EXT robustness according to the
current context's advertised capabilities. This avoids calling an unsupported
core entry point on the emulator's GLES 3.0 translator; genuine reset results
still use Hyprland's existing failure handling.

`tools/native/stage_port.py --abi x86_64 --ndk <Linux NDK>` collects the
required shared-library closure, magic database, license files and source
manifests. Generated binaries are ignored by Git. AGP supplies its matching
NDK C++ runtime. Then use the normal Gradle assemble tasks.
Staging requires the validated owned Graphite output and selects it explicitly;
there is no fallback to the downloaded Termux Graphite binary. Its notices and
hashed build evidence are copied alongside an explicit `project-built` origin.
Previously staged Termux Graphite files fail this check until both ABI owned
builds are produced and staged; existing generated assets are not migrated by
changing the Python tools alone.
The full retained source archive/object tree remains in the build cache, rather
than being duplicated in each ABI's APK notices. Corresponding-source and
relinking-material delivery remain publication holds.
Use `stage_port.py` rather than manually copying graphics inputs: it touches
`CMakeLists.txt` so AGP reconsiders the previously absent compositor target.
After assembling, `python tools/native/verify_release.py --apk <actual.apk>`
also checks that both ABI JNI bridges, compositor ports, TAWC and Ando are really
present as native ELF files. Checking staging alone does not prove APK contents.
Versioned Android native library SONAMEs are rewritten to private `lib*.so`
names for APK extraction, with their NEEDED entries updated and Termux RPATHs
removed. This modifies Android dependency packaging, not Linux executables.

## Preparing the Linux shells

The in-app [archive installer](INSTALLING.md) now performs fresh guest package
and shell preparation. Developers include its pinned assets using
`python3 tools/prepare-install-assets.py`. The manual recipe below remains useful
for adapter development and existing prepared test installations.

The ARM64 path was built and staged with the same recipe using
`--abi arm64-v8a`, then installed on the locked Nubia NX789J (Android 15,
Adreno 830). Do not use the emulator's x86_64 bootstrap on this device.
The official generic Arch Linux ARM tarball's detached signature was checked
against the build-system fingerprint published on its package-signing page;
the exact archive/hash are recorded in VERIFICATION.md.

`tools/linux/prepare-arch-graphics` is the development provisioning script
for a verified Arch/Arch Linux ARM image inside this app. It initializes the
distribution keyring and uses an official HTTPS mirror, preserving package
signature verification. It backs up changed pacman configuration and adapts
only the guest's downloader sandbox/UID options. It removes the generic ARM
image's unused Linux kernel/firmware packages through pacman before updating,
avoiding initramfs hooks against Android's kernel modules. Android boot and
firmware partitions are never modified.

Install signed Arch packages: `hyprland quickshell mesa dbus fontconfig
ttf-dejavu qt6-wayland qt6-declarative qt6-svg qt6-5compat qt6-multimedia
qt6-imageformats foot ttf-cascadia-code-nerd jq git curl ca-certificates
python python-gobject python-cairo neovim inotify-tools socat xkeyboard-config adwaita-cursors imagemagick gtk3 gum xdg-terminal-exec squeekboard`.

The phone uses Mobile's native Squeekboard input method and terminal layout;
see [INPUT.md](INPUT.md). Its process belongs to the phone/shared shell session.
The existing Mobile D-Bus controls, text-input activation and hide helper remain
Linux components; Android's software keyboard stays hidden on phone startup.

The normal developer provisioning path configures the signed Omarchy repository,
then runs `pacman -Syu --needed yay` when the standard removal picker is needed.
This is a manual signed-package step; `yay` is not assumed to be provided by
Arch core's default preparation set.

Pacman's downloader cannot use Landlock or switch to its `alpm` user inside
this app sandbox. The guest pacman configuration uses `DisableSandbox`, as
TAWC's Arch integration does. Package signature checking stays enabled;
Android's app isolation and SELinux remain unchanged.

`tools/prepare-linux-shell.py` packages the pinned Omarchy and Mobile sources.
It also requires `--widgets <checkout>` at OWL store commit
`b63886b75e02ccef1a8a11baeef2d6eff7a374af`. Only files marked `ok` or `partial`
in Mobile's pinned compatibility index are staged, after SHA-256 verification.
Fresh setups enable the upstream OWL feature; exact per-widget approvals
remain upstream behavior. Existing feature/layout files are preserved.
Extract its output into the existing rootfs, then run
`/root/.local/share/omamobile/setup-linux-shell`. It seeds shared theme and
plugins, per-display profiles, and an editable phone widget layout. Existing
user profile/layout files are retained. It deliberately omits the upstream
phone hardware, flashing, fingerprint, lock and system-service setup steps.
Android continues to own those facilities.

The only remaining shell source patch adds an optional `OMARCHY_SHELL_CONFIG`
override in `shell.qml`. Desktop/mobile application launch source is staged
unchanged. Android-hosted sessions prepend `/usr/lib/omamobile/session-bin`
to PATH, providing the `tools/linux/session-exec` adapter as `uwsm-app`.
It accepts an executable and literal argv, with an optional `--` separator,
preserving session variables, Hyprland launch tokens and child exit status.
Standard `gtk-launch` resolves desktop entries and the normal packaged
`xdg-terminal-exec` selects the terminal; `gum` supplies upstream interactive
prompts. The payload includes upstream logo/icon/version files used by these
commands, in addition to the shell trees.

This is an explicit subset of the UWSM launch interface, not a systemd
implementation. Android owns the session lifetime; no service or cgroup scope
is created. Unsupported launcher options fail with status 64 and stderr;
using the adapter outside an Android-hosted session fails with status 69.
The adapter directory is session-local on PATH and does not replace a distro's
`/usr/bin/uwsm-app`. `tools/tests/test_session_exec.py` checks argv/environment,
status propagation and rejected calls under Linux bash.
`NO_AT_BRIDGE=1` avoids a GTK launch wait for an unavailable Linux accessibility
bus; an Android accessibility adapter remains future work.
The phone and desktop still share the source tree,
ordinary plugin registry and user theme. Linux launch/initial setup scripts
are in `tools/linux/`. The in-app archive installer now runs this preparation
before activating a new rootfs; fresh x86_64 setup and cancellation are verified.
The development recipe remains available through the test-APK helper.

## Emulator verification

`tools/emulator-linux.py --serial <device>` and
`tools/capture-emulator.py --serial <device>` also support physical devices.
The capture helper discovers physical SurfaceFlinger output IDs; explicit
overrides are available when multiple monitors are present. Android logical
display IDs can change after reconnect, so use the current session's
`ANDROID_DISPLAY_ID` rather than a saved ID when injecting test input.

The external Surface requests Android's ordinary relative pointer capture
while focused. It translates deltas into compositor pixel coordinates and
forwards button-state transitions once. Hyprland software cursors remain
visible when Android hides its own captured pointer. Ctrl+Alt+Escape releases
capture, as does focus loss. Starting the existing sessions again brings
both activities forward and restores external focus.

On the Nubia, Android reports capture on the external activity, and an
injected relative event explicitly targeted at that display moves the real
Hyprland cursor. The user also confirmed physical Bluetooth mouse movement
and clicks after the older Omalateral helper stopped. Its privileged reader
can use EVIOCGRAB to own mouse events exclusively, preventing Android views
from receiving them despite valid pointer capture. Keep that older input
reader stopped when using this host. This is separate from the injected
test, which only verifies the app's targeted-event path.

Use the emulator's default phone display plus its external presentation
display. Do not run Gradle `connectedDebugAndroidTest` against the prepared
installation: its uninstall cleanup removes Linux user data. Install APKs
with `adb install -r` and invoke individual tests directly instead.

`tools/emulator-linux.py` transfers binary files with SHA-256 verification
and invokes the test-APK-only Linux command harness. Run setup commands before
starting graphics, because Android instrumentation restarts the application.
Native and Linux shell logs are in `files/graphics/{phone,desktop}/`.
The graphical runtime is physically created at the guest paths
`/run/omamobile/{p,d}` inside the installed rootfs. The host keeps one
exclusive short alias, `files/s`, pointing at that directory. Native broker
paths use the alias while guest processes use `/run/omamobile`; this keeps
Unix-domain socket paths short while making absolute links such as
Quickshell's `by-pid` records resolve inside the same runtime tree. Existing
`files/r` data is left untouched as private legacy state and is never retargeted
or migrated. Android-owned bind semantics are unchanged.

## Native guest dialogs

Android's native compositor cannot find guest programs by searching its own
`PATH`. The compositor warning, dialog creation, and ANR manager use one guest
availability probe through the session wrapper. The probe invokes a fixed
`/bin/sh` command with the executable name as a separate argument and waits for
up to one second before terminating and reaping that specific probe child.

Android sessions select an APK-owned software dialog frontend from the existing
profile-owned `/opt/omamobile/bin` read-only bind. Each session construction copies
`runtime-dialog/hyprland-dialog` (0755) and `runtime-dialog/dialog.qml` (0644) into
that staging directory. The original Linux `/usr/bin/hyprland-dialog` and packages
remain untouched. This dialog-only adapter uses installed Quickshell/Qt software
Wayland rendering; it does not add a dmabuf backend or fix general Hyprtoolkit
applications.

Before changing permissions or copying adapters, session preparation requires
literal `ando` and `ando/bin` directories beneath the checked profile runtime.
Symlinks and non-directories there stop startup without changing their targets;
the intentional host `files/s` alias remains supported. This check assumes the
session owns preparation; it is not protection against a concurrent same-UID
editor replacing directories after validation.

The frontend preserves the guiutils 0.2.2 title/apptitle/text/buttons CLI, including
button trimming and text newline normalization. Heading, message, and labels are
displayed as plain text. It passes data in child-only `OMAMOBILE_DIALOG_SPEC` JSON
and execs foreground `qs --path`, preserving the native-owned Wayland client PID.
It sets the child app ID to `hyprland-dialog`, disables that child's crash restart
and file watching, and uses independent instances. Quickshell diagnostics use the
inherited stderr sink. A saved stdout FD3 carries only the selected label plus
newline: a fixed shell/printf command receives the label as one argument and writes
directly to that descriptor, without reopening or truncating it. Closing the
window or failing to launch/respond returns no choice; Android permission policy
denies an empty/unrecognized response. The scrolling window fits the current
screen and supports pointer, Tab focus, and Escape/close. Equal capped minimum
and maximum size hints retain guiutils' fixed-size floating-dialog contract.

Android dialog launch uses `posix_spawn` with `/system/bin/sh`, the wrapper,
`--argv`, and separate guest arguments. Titles, text, and buttons are never
reconstructed into shell command text. The wrapper retains legacy command-string
launches by adapting them to `/bin/bash -c "$1"`; both modes then execute the
guest argv through the existing tawcroot/environment boundary. Exec-rule tokens
are child-only; the native compositor's environment and Hyprutils remain intact.

The stdout pipe uses close-on-exec descriptors and handles absent standard FDs.
Stderr inherits the native compositor's log sink (with a null fallback only if
the parent stderr was absent).
The event loop owns the dialog's PID, pipe, source, and promise. It drains the
button response, clears running/PID registration, and resolves the promise before
releasing the child owner. That owner alone reaps the PID; if closing stdout did
not end the child, cleanup allows brief exit/TERM intervals, then sends KILL and
waits for exit. It never signals a PID after reaping it or changes SIGCHLD policy.
Android permission-dialog creation, open, or response failures deny the request
and complete its pending permission promise. The other platforms retain their
existing missing-utility fallback.

ANR responses capture weak ownership of the real shared ANR actor and the exact
dialog instance. A delayed response is ignored after that actor is destroyed,
its dialog is canceled, or a replacement dialog is opened. Neither capture
retains the actor or creates an ownership cycle.

Rerunnable source and Linux host checks:

```sh
python3 tools/native/check_guest_dialog_source.py --port-repository /path/to/anhyprland --output /task/proof/generated
python3 -m unittest discover -s tools/tests -p test_guest_dialog.py -v
python3 -m unittest discover -s tools/tests -p test_software_dialog.py -v
```

The source check reads pristine pinned Git objects without trusting cache edits,
generates the patches twice, and records matching hashes. The host harness uses
the actual wrapper argument protocol and spawn helper under a seccomp no-fork
gate; it checks argv, stdout, token isolation, signal mask, PID preservation,
availability, timeout, FD cleanup, and targeted child reaping. It does not prove
Android ART, tawcroot, Wayland dialog mapping, or startup latency.
The software frontend check exercises its actual parser and fixed QML response
command under real Linux pipe/socket/file descriptors, including preserved file
offsets, exec PID, hostile label arguments, missing standard FDs, independent
invocations, and failure/empty-response paths. It substitutes a process fixture
for Quickshell; visible QML layout, input, and actual native-child interaction
still require the runtime checks below.

Add `--check-anr-owner --hyprutils-include /path/to/hyprutils/include` to the
source check to compile the emitted ANR callback against the real pointer types
with ASan/UBSan. It exercises valid Wait/Terminate, actor destruction, cancellation,
and dialog replacement; the original pinned callback is a negative control that
must reproduce its use-after-free. This requires a host compiler with sanitizers.

The remaining runtime check uses the existing native ANR flow, without a test
hook: launch an owned Foot window, record its PID/starttime and current profile,
and STOP only that identified process. Wait for Hyprland's real ANR dialog, record
its native-log PID and Wayland client PID, then click **Wait**. Confirm the Foot
process remains owned/alive and the dialog is gone; CONT the same Foot process
in guaranteed cleanup and confirm it responds again. Capture artifact/source
identities, both PID identities, raw dialog output evidence and timing, and verify
no dialog zombie remains. Run independently on phone/external owners. Measure
normal guest-probe/dialog latency there; the host test is not an ART performance
claim. Do not freeze an unrelated process or weaken the existing graphics smoke
deadline. Native build and Android runtime validation remain required before
claiming this path works on a device.
