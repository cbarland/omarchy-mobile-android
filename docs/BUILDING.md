# Building and checking a contribution

There are two build paths. A clean checkout builds the Android host and native
command runtime. A graphics APK additionally requires separately built/staged
Hyprland libraries and the installer payload. The CI artifact is explicitly the
core validation APK; it cannot provide a working Omarchy desktop by itself.

## Toolchain

The source currently selects JDK 17, Android Gradle Plugin 9.3.1, Gradle 9.5.0,
compile SDK 37, target SDK 35, minimum Android API 29, NDK `30.0.14904198`, and Android SDK
CMake `4.1.2`. The NDK package identifies itself as r30-beta1. The Gradle wrapper
pins its distribution checksum. Keep these versions consistent when proposing
toolchain changes.

Install Android SDK command-line tools and set `ANDROID_HOME` to that SDK, or use
Android Studio's SDK Manager. From the SDK's command-line-tools `bin` directory:

```sh
sdkmanager --install --channel=3 \
  "platforms;android-37" "build-tools;36.0.0" \
  "ndk;30.0.14904198" "cmake;4.1.2"
sdkmanager --licenses
```

The channel option includes preview packages; it does not change the requested
version. If an exact package is unavailable, report that failure rather than
silently switching versions. Google's [SDK Manager reference](https://developer.android.com/tools/sdkmanager)
documents package paths, channels, and license acceptance. These exact platform,
NDK, and CMake packages were present in Google's SDK catalog on 2026-10-15. The
`platforms;android-37` package is required by the resolved `zstd-jni:1.5.7-23`
Android archive; target 35 and minimum API 29 remain the app compatibility policy.

## Core APK from a clean checkout

Run from the repository root, using a fresh checkout with no staged `.so` files:

```sh
bash ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On Windows PowerShell, use `./gradlew.bat` with the same task arguments. The APK is
`app/build/outputs/apk/debug/app-debug.apk`. Reports are under
`app/build/reports/tests/` and `app/build/reports/lint-results-debug.html`.

`app/src/main/cpp/CMakeLists.txt` includes the graphical JNI adapter only when
`app/src/main/prebuilt/<abi>/libanhyprland.so` exists. Generated `prebuilt`,
`jniLibs`, and native assets are ignored by Git. A successful clean core build
therefore proves neither compositor packaging nor desktop rendering.

## Host checks

Use Linux or WSL with Python 3.11+, Bash, a C compiler, and the standard Unix
utilities. These checks do not connect to Android or require Shizuku:

```sh
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
cc -D_GNU_SOURCE -O1 -g -ffunction-sections -fdata-sections \
  -Ivendor/tawcroot/include tools/tests/test_socket_credentials.c \
  -Wl,--gc-sections -o /tmp/test_socket_credentials
/tmp/test_socket_credentials
```

The Python suite covers launcher/session argv, theme IPC behavior, host helpers,
and installer asset preparation. `test_prepare_linux_shell.py` is an additional
explicit integration CLI: supply real pinned `--omarchy`, `--mobile`, and
`--widgets` checkouts. It creates disposable shared clones and compares tar
member contents/modes after dirtying those clones. It is not automatically run
by unittest discovery and does not modify the source checkouts.

## Build and stage graphics

Use a Linux x86_64 build host (Ubuntu 24.04 or WSL) and the matching **Linux** NDK.
A Windows NDK cannot run its compiler inside WSL. Host prerequisites:

```sh
sudo apt-get update
sudo apt-get install -y git python3-venv gcc-14 g++-14 ninja-build pkg-config \
  patchelf autoconf automake libtool bison flex libexpat1-dev libxml2-dev \
  libffi-dev libinput-dev libudev-dev
export NATIVE_CACHE="$HOME/omamobile-native"
python3 -m venv "$NATIVE_CACHE/venv"
"$NATIVE_CACHE/venv/bin/pip" install 'cmake==4.1.2' 'meson==1.10.0' Jinja2
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/30.0.14904198"
```

Build and stage each ABI you intend to distribute:

```sh
python3 tools/native/build_port.py --abi x86_64 --cache "$NATIVE_CACHE" --ndk "$ANDROID_NDK_HOME"
python3 tools/native/stage_port.py --abi x86_64 --cache "$NATIVE_CACHE" --ndk "$ANDROID_NDK_HOME"
python3 tools/native/build_port.py --abi arm64-v8a --cache "$NATIVE_CACHE" --ndk "$ANDROID_NDK_HOME"
python3 tools/native/stage_port.py --abi arm64-v8a --cache "$NATIVE_CACHE" --ndk "$ANDROID_NDK_HOME"
```

The scripts pin the Android Hyprland port and retain its dependency lock file.
The default build also produces owned FreeType and Graphite proofs. FreeType
runs after dependency installs and generated pkg-config shims are final. A clean-cache run,
runtime font/render checks, and corresponding-source/delivery review remain
separate gates.
Termux dependency downloads use the committed per-ABI package manifests as locks:
exact versions, official URLs, and SHA-256 values. Missing pinned packages fail
without falling back to a newer version. `build_port.py --refresh-termux` resolves
current indexes only into a fresh cache; review the resulting manifest before
updating the committed lock. Complete APK byte reproducibility is not claimed.
Use the same explicit `--cache` for build and stage. `stage_port.py` includes the
dependency closure, magic database, manifests, and third-party notices. See
[GRAPHICS.md](GRAPHICS.md) for the native adaptations and current limitations.
Staging available notices does not establish that every packaged library's
license obligations are satisfied; the per-library distribution audit must be
completed before public APK publication; publishing the adapter source is separate.

## Include installer assets

Before assembling an APK intended for the in-app installer, run:

```sh
python3 tools/prepare-install-assets.py
bash ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The helper fetches the exact Omarchy, Mobile, and widget Git revisions into its
cache and stages `app/src/main/assets/install/`. To use already available pinned
checkouts without fetching them:

```sh
python3 tools/prepare-install-assets.py --omarchy /path/to/omarchy \
  --mobile /path/to/omarchy-mobile --widgets /path/to/omarchy-widgets
```

Optional `--cache` and `--output` select alternate directories. The manifest
records source pins and payload hashes. The archive contains shell sources/data
and adapter scripts, with required notices; the Linux distribution is supplied
separately by the user. Gzip container byte reproducibility is not claimed.
Graphics libraries must also be staged for the device ABI.

## Release payload gate

`preReleaseBuild` runs `tools/native/verify_release.py`. It requires both native
ABIs, complete library hashes/closure/notices, the installer files and their
pins/hashes, and version-specific corresponding-source records. It currently
rejects public binary releases because seven source-material mappings are still
missing. This does not block the core debug build or source contributions.

For diagnostics, use `python3 tools/native/verify_release.py --mechanical-only`.
Set `-Ppython=/absolute/path/to/python` when Gradle needs a specific interpreter;
the default is `python3` on Linux or `python` on Windows. See
[RELEASE-READINESS.md](RELEASE-READINESS.md) for the remaining binary release work.

## Runtime checks and signing

Use [INSTALLING.md](INSTALLING.md) to prepare a fresh installation and
[VERIFICATION.md](VERIFICATION.md) for existing evidence. Graphics, hotplug, and
Android-window checks require prepared emulator outputs and explicit test
arguments; they are not part of the GitHub validation workflow. State separately
which ABI and device/emulator were actually exercised.

Debug APKs use each builder's local development key. Keys from different machines
or CI runs may differ, so these artifacts are not a stable update channel. Do
not commit or share a release private key. Release signing, key custody, versioned
distribution, and a published download location still need a maintainer-managed
release process; this workflow only validates contributions and uploads temporary
core debug artifacts.

The workflow uses read-only repository permissions, no project secrets, and full
commit pins for its official GitHub actions. Fork PRs run through `pull_request`,
not `pull_request_target`. The first hosted run passed the clean-checkout,
toolchain, core APK, JVM/lint and host checks on 2026-10-15. Each commit has its
own validation result in the repository's Actions tab.
