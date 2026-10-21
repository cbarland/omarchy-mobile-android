# Developer preview candidate and device gate

The preview target is one app on unmodified Android: Omarchy Mobile and native
Squeekboard on the phone, an Omarchy desktop on an external display, and one
shared Linux installation. Android continues to own device policy and hardware.
See [project intent](INTENT.md), [input](INPUT.md), [audio](AUDIO.md),
[display behavior](GRAPHICS.md), and [payload recovery](PAYLOAD-UPGRADES.md).

## Prepare one candidate

Keep an exact source revision, upstream pins, APK SHA-256, signing-certificate
fingerprint, native build receipts and the output of `verify_release.py` together.
Keep the instrumentation APK with the candidate it tests. Rebuilding creates a
new artifact; old screenshots and receipts do not automatically verify it.

Follow [BUILDING.md](BUILDING.md) for core, full graphics, host, JVM and lint
checks. Inspect both ABI entries in the actual graphical APK. Core CI does not
prove graphics packaging, and x86_64 execution does not prove ARM64 execution.

Before a physical test, exercise the same candidate on a disposable prepared
emulator:

- Update a stock installed payload, reject a customized changed file before
  writes, reject an update while Linux owners are active, and repeat a successful
  update with zero changes. Preserve home, profiles, Android grants and rootfs
  identity. JVM transaction tests additionally cover interruption and rollback.
- In separate and shared compositor modes, type into Mobile search and Foot
  using Squeekboard, including terminal controls and symbols. Hide the keyboard,
  reopen the session and check that only one keyboard remains. Its reservation
  must affect only the phone output.
- Exercise ordinary PipeWire and Pulse clients, concurrent mixing, real Android
  focus loss/recovery, audio-owner replacement, idle release and stop cleanup.
  Check that graphics-mode switching and external-display replacement retain the
  shared audio owner. Playback-head progress establishes transport, not audibility.
- Inspect actual phone widgets and desktop bar/background after reconnect.
  Readiness files and successful IPC alone are insufficient.

Retain failed attempts and their reasons alongside the successful run. Restore
the prepared emulator after testing; do not use a contributor's everyday device
as a disposable installation fixture.

## Install or update on the test device

Run the read-only preflight before updating a prepared device (Git, Python 3.11+,
JDK and the SDK's Build Tools 36.0.0 are sufficient):

```sh
python tools/device-preflight.py --serial DEVICE --candidate path/to/candidate.apk --output app/build/verification/device-before
```

Use an explicit ADB serial and a new private output directory each time. The
report records candidate/installed APK hashes, verified signer fingerprints,
upstream pins, device ABI/SDK, active display geometry and available Linux
identity/profile hashes. It rejects a missing compositor/runtime library pair,
unsupported Android SDK, mismatched signing certificates or missing active
presentation display. Exact certificate equality is conservative; key rotation
requires manual migration review. A passing report is not an installation,
native dependency-closure check or functional device pass. Nothing is installed,
started, stopped, typed or granted by this command. Reports can contain device
identifiers and should be redacted before sharing.

Record the Android version, ABI, app revision and certificate before changing
the installation. Export Linux user data with the host controls and keep the
export off-device. Do not uninstall an existing app to work around a signing
mismatch: that can erase its private Linux installation. A mismatched certificate
requires an explicit data-preserving migration plan.

For a fresh installation, use the authenticated Arch-base action in the full
graphical APK. Setup needs network access, storage and time to install the signed
Linux packages. The command-only core APK cannot install the graphical system.
For an existing installation with a compatible signing key, replacing the APK
preserves Linux. Stop screens and command sessions, then choose **Update Linux
integration**. That updates adapter-owned files, rather than Linux packages.

Existing images missing keyboard/audio dependencies require a separate signed
package transaction inside their Linux environment:

```sh
pacman -Syu --needed squeekboard pipewire pipewire-audio pipewire-pulse wireplumber
```

Read the package-manager transaction before accepting it. Existing custom
profiles remain authoritative; enable `omamobile.keyboard` in the phone/shared
plugin list if absent. Missing keyboard defaults initialize on session entry;
an explicit disabled preference and a custom layout are preserved.

The Linux desktop and keyboard do not require Shizuku. Android application windows
use its separately authorized helper. Keep that authorization as the device owner
chooses; revocation is not a routine step in keyboard or mouse troubleshooting.

## Nubia acceptance still requires observation

Run these on the ARM64 phone with its monitor, mouse and keyboard attached:

1. Start the phone and desktop, stop them, close the app's Recents card, and reopen.
   Confirm the intended phone/desktop layouts, one app card and working input each
   time. Try display scaling/selection and disconnect/reconnect the monitor.
2. Tap phone search, type a query, open Foot, enter `date` and edit a line with
   Ctrl/arrows/Backspace. Test the Keyboard tile, symbols, hide control, and
   hardware keyboard. Confirm that the desktop cursor moves and clicks after
   reopening; use Ctrl+Alt+Escape to release capture.
3. Play native and Pulse audio and listen on each available route: phone speaker,
   monitor/USB and Bluetooth. Check Android media volume and transient focus loss.
   Record unavailable routes rather than marking them passed. Microphone input
   remains outside this output-only implementation.
4. Install/select a data-only theme, reopen, and inspect both screens. Launch a
   normal Linux application; exercise the approved widget/plugin and, when
   authorized, an Android app beside Linux. Verify shared files and persisted
   user configuration after updating and reopening.
5. Verify Android work apps and Wallet through their ordinary user flows, plus
   idle/resume, thermal and battery behavior. Emulator success does not establish
   physical-device policy compatibility, power efficiency or GPU performance.

For scripted startup, `tools/emulator-linux.py --serial DEVICE start-unified`
waits for visible shell layers as well as live IPC. This helper also supports
physical devices despite its historical filename. Inspect real output pixels:

```sh
python tools/capture-emulator.py --serial DEVICE --wait-content 10 --output app/build/verification/device-screens
```

The optional content gate saves the same raw RGB(A) frame losslessly as PNG and
records sampled brightness metrics. It rejects empty/near-black/uniform frames
and retains the last frame/report on failure. This check targets the prepared
shell; a deliberately uniform screen can fail it. It does not identify the UI
or prove input works. Inspect both screenshots and perform the steps above.
Unknown raw screencap formats fail explicitly; ordinary PNG capture remains
available without the option. Ambiguous displays require explicit
`--phone-display`/`--desktop-display` SurfaceFlinger IDs.

The opt-in `SharedPointerCaptureTest#realReconnectRetainsTheSeatAndRenderedFrames`
gate is restricted to the disposable `Omamobile_Radio_Test_API35` AVD. It replaces
its configured external display and repeats real shared input/frame checks within
one instrumentation invocation. Separate `am instrument` runs restart Android
and cannot establish preservation of the compositor across hotplug. Do not run
the emulator display-mutation gate on a phone; reconnect the Nubia monitor
manually and verify input and frames on that same running compositor.

## Recovery and evidence

Use **Update Linux integration** again after an interrupted update. A pending
journal blocks normal startup; a newer user edit blocks automatic rollback.
Keep the conflicting file and reconcile it using the operation log and
[PAYLOAD-UPGRADES.md](PAYLOAD-UPGRADES.md). Never clear app storage as a first
recovery step. Recover exported user data into a separate copy before replacing
a working installation.

Record the source revision shown in host controls, the failing action, display
topology, relevant package versions and a focused log excerpt. Installation and
update messages are in the host operation log (`files/linux/install.log`).
Graphics logs are under `files/graphics/<profile>/`; audio logs are under
`files/audio/` and logcat tag `OmamobileAudio`. Files are app-private; `run-as`
collection requires a debuggable build. Redact account names, tokens, shared-file
paths and unrelated user content before attaching evidence to an issue.

## Public binary release remains gated

The graphical release check still holds seven native source/relink mappings.
The local input snapshot and recovered Termux trees are preparation, not complete
public corresponding-source delivery. See [SOURCE-INPUTS.md](SOURCE-INPUTS.md),
[SOURCE-BUNDLE.md](SOURCE-BUNDLE.md) and the exact release report. Complete and
verify those materials, establish a maintained release signing/update path, and
pass the ARM64 device gate before publishing an installable graphical preview.
Do not bypass the release check or relabel a debug APK as a verified public release.
