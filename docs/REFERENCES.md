# Reference coding provenance

Physical-device input references: Android 15's
[DisplayManager](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/core/java/android/hardware/display/DisplayManager.java)
defines own-focus and steal-top-focus-disabled for trusted hosted displays.
The latter prevents the hosted editor from becoming the physical keyboard's
top display. Android's
[pointer-capture contract](https://developer.android.com/develop/ui/views/touch-and-input/gestures/movement#pointer-capture)
provides rootless relative mouse events to the focused host view and releases
them on focus loss. XERJ `ax-anhyprland-host-docs` retrieved
`Embed.cpp / anhyprland_pointer`; the pinned port's
`subprojects/aquamarine/src/backend/Android.cpp:231` confirms that the input
adapter accepts compositor pixel coordinates. The new adapter code translates
Android relative deltas into that existing contract; it does not change
Android's global input settings or add privileged input APIs to Linux.

For task reopening, XERJ retrieved `Embed.cpp:148 / anhyprland_window` in
the same pinned port. Source inspection confirmed replacement Surfaces are
retained and queued into the existing backend. The Android controller now
tracks each Surface's owner so a destroyed old view cannot detach its
replacement. Android's [activity manifest contract](https://developer.android.com/guide/topics/manifest/activity-element)
supplies single-task reuse and exclusion of internal output hosts from
Recents. Capture is restored on resume, a new intent, Surface replacement
and focus gain, with a bounded retry while the window has focus.

Optional Codex CLI preparation follows the
[official OpenAI CLI installer](https://learn.chatgpt.com/docs/codex/cli).
The inspected script and its checksum are recorded in `LINUX-TOOLS.md`;
it remains separate from the APK and upstream account code.

XERJ's shared local indexes were used for concrete mechanism lookup, followed by source inspection. Searches are lexical retrieval, not validation. The full `omarchy-android` corpus is not activated; the working scopes are `ax-tawc-native-*` and `ax-omamobile-shell-*`.

## TAWC

- Repository: https://github.com/wmww/tawc
- Revision: `64e3736699684a4fc0029e71bdfb7cc1e0884323`
- Vendored: `tawcroot/src/`, `tawcroot/include/`, `LICENSE.MIT` under `vendor/tawcroot/`, pinned to the revision above. The downstream copy of `src/syscalls_socket.c` carries the bounded outbound `SCM_CREDENTIALS` retry recorded in `vendor/tawcroot/patches/0001-outbound-credentials.patch`; all other vendored files remain at their canonical hashes.
- License: MIT, copyright 2026 Sophie Winter. No GPL Termux UI dependencies are copied.
- XERJ hits checked against source: `src/exec_handler.c:241` (`tawcroot_exec_handler_prepare`), `src/main.c:230` (`tawcroot_parse_bind_spec`), plus `path_orchestrate.c` and `syscalls_fs.c` bind mechanics.
- Build contract: upstream `tawcroot/build.sh`; our CMake adapter retains the fixed image bases, freestanding static executable, architecture stubs and 16 KiB alignment. The Android runtime executes the APK-extracted `libtawcroot.so`; it is an executable, not a JNI library.
- Android spawn/bind reference: `app/src/main/java/me/phie/tawc/install/TawcrootMethod.kt:542`.
- Downstream provenance: canonical `src/syscalls_socket.c` SHA-256 `cceb9eb95b4014299f011a5371546e799a48781b47409a5f8bd1248908dde114`; current vendored SHA-256 `db4a553ae0e8abf5e44f644218312662446fc4c865848f303c2640e8a65ff2de`. The patch only retries outbound credentials for the process's own virtual identity after the kernel returns `EPERM`; it does not virtualize received credentials or change kernel identity.
- Stock Android hardlink denial: `install/util/FsOps.kt:86`, `install/ProotArchiveExtractor.kt:201`. The importer uses the same relative-symlink fallback for EACCES/EPERM; this does not add a PRoot runtime. Exact imported hardlink identity remains a compatibility gap. XERJ's guessed `tawcroot_linkstore_link` symbol returned no match; direct source inspection found the relevant extraction mechanism.
- ARM distro reference: `install/distro/arch/ArchLinuxArm.kt` (standard gzip rootfs; upstream detached PGP signature). No TAWC Android app source copied.

## Authenticated bootstrap sources

- Arch Linux ARM official downloads: https://archlinuxarm.org/about/downloads
- Arch Linux ARM package-signing guidance and Build System primary fingerprint: https://archlinuxarm.org/about/package-signing
- Arch Linux official downloads and release-key guidance: https://archlinux.org/download/
- Arch Linux official developer/key listing: https://archlinux.org/people/developers/
- The automatic source, download boundary and importer layout are local code in `app/src/main/java/dev/omamobile/host/BootstrapSource.java`, `BootstrapDownload.java`, and `RootfsImporter.java`. The automatic path is pending final device/emulator runtime proof; manual import/setup evidence remains separate.
- `BootstrapSignature.java` is adapted from TAWC's MIT `SignatureVerifier` at `64e3736699684a4fc0029e71bdfb7cc1e0884323`; it pins full primary fingerprints and does not resolve arbitrary subkeys. The attribution and pinned key provenance are recorded in `docs/AUTHENTICATED_INSTALL_PROVENANCE.md`.

## Android commands / earlier Omalauncher work

- Repository: https://github.com/SimonSchubert/Omalauncher
- Integration reference: PR #99 head `715306a2e363f40ba53832b52f8ee3cf24ebf365`, local `omalauncher_ando` checkout.
- Copied native `app/src/main/cpp/ando.c` and `ando_broker.c` to `vendor/ando/`.
- MIT (Simon Schubert and contributors); client retains its TAWC MIT notice, originally adapted from TAWC `e463fee718d04b2c7068b655963e1d3a20b5adb8`.
- Local client patch: replace `strtok` with `strtok_r` for NDK 30's deprecation error. Broker source unchanged. The inherited private broker flag/environment key retain their Omalauncher names for source/protocol compatibility; guest-facing paths use `/opt/omamobile/bin/ando` and `/run/omamobile-ando`.
- Native supervisor checks peer UID, bounds handshake size/time, forks outside ART and terminates worker groups when its lifetime pipe closes. The Android host owns the pipe.

## Omarchy Mobile

- Repository: https://github.com/derekross/omarchy-mobile
- Revision: `953bcb3058347459cdf7faaa1e149f16e0f7cfa6`
- Included in the pinned installer payload: phone shell plugins, profiles, QML widgets and setup helpers. XERJ's shell scope located the package/profile mechanisms before direct source inspection. The payload is staged into the shared Linux installation.
- Main project MIT; retained shell and OWL-guide notices accompany the payload. The package also declares GPL-3.0-or-later for its squeekboard layout, which this payload does not include. The Android APK's installed credits name this upstream foundation prominently.
- The Android app is an adapter project, not a fork of the Linux phone hardware stack. Upstream device flashing scripts are not used.
- Session launch reference: XERJ `ax-omamobile-shell-docs` retrieved
  `test/launch.py` and `test/all`, then source inspection verified
  `packages/omamobile-shell/bin/omamobile-launch:354` at the revision above:
  it hands `gtk-launch` argv to `uwsm-app`. Its launch-rule token is inherited
  by the client. The host now supplies that argv handoff at the session PATH
  boundary instead of patching the upstream launcher. The adapter is original
  code and does not implement UWSM's systemd unit/service semantics.

## Other shipped dependencies

- Apache Commons Compress 1.28.0, Commons IO 2.20.0, Codec 1.19.0 and Lang 3.18.0: Apache-2.0, resolved through Gradle. Their license/notice resources are copied into APK assets. AGP also supplies Kotlin stdlib 2.2.10 and JetBrains annotations 13.0 (Apache-2.0).
- Bouncy Castle Java 1.86: `bcpg-jdk18on`, `bcprov-jdk18on`, and transitively `bcutil-jdk18on`. Maven Central artifacts: https://central.sonatype.com/artifact/org.bouncycastle/bcpg-jdk18on, https://central.sonatype.com/artifact/org.bouncycastle/bcprov-jdk18on, and https://central.sonatype.com/artifact/org.bouncycastle/bcutil-jdk18on. Exact resolved notices are copied under `app/src/main/assets/licenses/bouncycastle-*.txt`.
- zstd-jni 1.5.7-23: BSD-2-Clause binding from https://github.com/luben/zstd-jni; bundled Zstandard native code carries its BSD-3-Clause notice at https://github.com/luben/zstd-jni/blob/v1.5.7-23/src/main/native/LICENSE. Exact notices from that tag are copied under `app/src/main/assets/licenses/zstd-jni.txt` and `app/src/main/assets/licenses/zstd.txt`.
- Statically linked Android bionic code: Android NDK notices included in APK assets under `licenses/`.
- Gradle wrapper: copied from the existing workspace, pinned Gradle 9.5.0 distribution SHA-256 in `gradle-wrapper.properties`.

The Linux image is user-supplied, not embedded in the APK. Its packages and licenses remain the distribution's own.

## Hyprland Android output backend

- Public port: https://github.com/taowen/anhyprland, revision `bc13ae5859097791b3e93a32632727a7f7075354`, BSD-3-Clause. Genuine Hyprland 0.56 with the vendored Android Aquamarine backend; this is not a desktop UI simulated in Java.
- XERJ scope `ax-anhyprland-host-*` found `src/android/Embed.h:15` / `anhyprland_run`; the full embedding, input, Surface replacement and process-lifetime contracts were inspected in source.
- `src/config/supplementary/executor/Executor.cpp` supplies `ARLINUX_EXEC_WRAPPER`. Our small patch replaces its Android fork/exec path with `posix_spawn`, retaining workspace and exec-rule tokens. `src/render/OpenGL.cpp` selects core/KHR/EXT robustness according to the actual GLES context; reset failures are still handled by the original renderer.
- Build-only changes target x86_64 or ARM64, use host generators, retain public libinput/udev type headers while excluding Android-inapplicable hardware dependencies, and disable Xwayland for this milestone. The recipe and patches are in `tools/native/` rather than edits to shared reference repositories.
- Android C-library dependencies are official Termux packages extracted without running package scripts. SHA-256, versions, upstream URLs and the exact port dependency pins are recorded in APK `assets/licenses/native/<ABI>/`. Required library SONAME/NEEDED metadata is adapted to APK-compatible private names; Termux RPATHs are removed. Linux/glibc executables are not rewritten for APK paths.
- Native license texts/notices and source manifests are staged beside the backend. The matching NDK shared C++ runtime is provided by AGP. The native dependency staging script records hashes before and after metadata changes. Committed per-ABI Termux manifests are now the default download locks; resolving moving indexes requires an explicit refresh into a fresh cache. Recovered Aquamarine/Lua and shared Termux notice origins are recorded in `assets/licenses/native/notice-sources.json`. Corresponding-source mappings still block public native APK releases; see RELEASE-READINESS.md.

## Running upstream Omarchy shells

- Omarchy: https://github.com/omacom/omarchy at `e71a816e9032f040225e086c7256f73e8080ae45`; MIT license retained in the staged Linux checkout.
- Omarchy Mobile: https://github.com/derekross/omarchy-mobile at `953bcb3058347459cdf7faaa1e149f16e0f7cfa6`. Its MIT shell plugins, native QML widgets and widget authoring documentation are now staged into Linux. `HomeModel.js:346` / `defaultLayout` was located through XERJ and checked against source. Phone widget rendering and swipe behavior are verified.
- The earlier reference-only description above records milestone 1. The current Linux payload includes these upstream userland sources and their MIT notices. It does not copy the GPL squeekboard keyboard layout, hardware services, flashing tools or kernel/firmware stack into the host.
- `tools/prepare-linux-shell.py` archives canonical pinned Git content with `core.autocrlf=false` / LF endings and Git file modes. It patches one upstream integration point: the per-display `OMARCHY_SHELL_CONFIG` override in `shell.qml`. Omarchy's `AppLibrary.qml` and Mobile's `omamobile-launch` remain unchanged; Android-hosted sessions provide the original launcher's `uwsm-app` entry through a session-only PATH adapter. Standard GTK desktop-entry resolution and Hyprland launch rules remain upstream mechanisms. Other hosts retain upstream behavior.
- Linux initial setup and launch scripts seed user configuration once. A temporary `NO_AT_BRIDGE=1` avoids activating an absent Linux accessibility bus; Android accessibility integration remains open. The source payload manifest records the upstream revisions and patch scope.

## Backend selection evidence

Current upstream Aquamarine's nested Wayland backend requires linux-dmabuf and a render node; its headless path also requires a GBM allocator. The emulator exposes neither a DRM render node nor a usable Linux graphics device. The public Android Hyprland port provides the missing Surface/AHardwareBuffer boundary while standard Linux clients use Wayland `wl_shm`. This preserves real Hyprland rather than substituting a Java recreation. glibc Hyprland binary plugins are a different ABI and have not been validated against the Android compositor.

## Ecosystem and Android-window verification

- OWL store: https://github.com/SimonSchubert/omarchy-widgets, MIT, commit `b63886b75e02ccef1a8a11baeef2d6eff7a374af`. The staged data is checked against Mobile's own `packages/omamobile-owl-widgets/index.json` SHA-256 values. Store files and MIT notice remain unmodified. Mobile's optional OWL feature and existing approval flow run the tested `simonschubert/todo` widget.
- XERJ `ax-omamobile-shell-docs` retrieved `widgets/OWL.md` and the store/checker contracts. Source inspection covered `plugins/omamobile.home/Home.qml:384`, `owl/catalog.js`, `UserWidgets.js` and `bin/omamobile-widget`. No separate widget interpreter was added to Android.
- Community plugin: https://github.com/nlboris/omarchy-scratchpad, MIT, commit `ce789b627c66134b8f020b283b04dcdca014924a`. Its unmodified `Scratchpad.qml` reads Hyprland workspaces and runs the workspace toggle through the bar API. Installed into the test Linux home by the ordinary upstream CLI; it is not bundled in the host APK or enabled in fresh user installations.
- Shizuku API/provider `dev.rikka.shizuku:13.1.5`, Apache-2.0, via Gradle. Test manager: official https://github.com/RikkaApps/Shizuku release v13.6.0, source commit `2650830c5b099ae0dd34fedf614d4f592ca05d65`. APK SHA-256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`; apksigner verified its signature (certificate SHA-256 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`). The release API supplied no asset digest, so no independent publisher hash check is claimed. `manager/.../starter/Starter.kt` documents the native ADB startup command used under explicit emulator-only approval.
- Omalateral's Apache-2.0 `PrivilegedServer.kt` supplied reference evidence for trusted display flags, own-focus handling and content destruction on removal; its `ShizukuPrivilegeBackend.kt` demonstrates the user-service binding/teardown contract. This project implements a much smaller, display-scoped Binder interface, without copying its general privilege server. The frozen Omalauncher PR was not changed.
- Android's primary sources: [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager) and [ActivityTaskSupervisor](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/core/java/com/android/server/wm/ActivityTaskSupervisor.java). The app-only launch denial was reproduced before adding the optional shell adapter. No privileged permission was granted directly to the normal application UID.
