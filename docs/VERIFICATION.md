# Verification record

## Native Squeekboard keyboard — 2026-10-19

Both the separate phone/desktop sessions and shared compositor passed the
complete native keyboard flow on the API35 x86_64 disposable emulator, with
Squeekboard 1.43.1-6 and Foot 1.28.0-2. Runtime implementation:
`5a812ff5c0ec07fb269b941f1ba62831da6031d8`; private staged APK SHA-256:
`b2dee0c21dd40ce0924ac11bf60db5facac408393ce981417680b5a5ddfa92ac`.

Paired Wayland traces and PTY bytes isolated the earlier dropped letters:
Squeekboard submitted them, but Foot discarded edits with older text-input-v3
state serials. The terminal layout now selects native virtual-keyboard events
for printable characters alongside its controls. Its views and geometry, other
layouts' composition, and user overrides remain intact. No Foot binary is
patched. The pinned input, derived layout hash, source references and retained
GPL notices are recorded in [REFERENCES.md](REFERENCES.md#native-phone-keyboard).

The final UI run used one trial per mode, 80 ms stationary presses, and no
Wayland debug logging. Actual touch targets were inspected from framebuffers;
the terminal fixture and PTY input records independently confirmed:

- Tapping Mobile's search field opened Squeekboard automatically; native typing
  changed the query to `foot`, and Mobile's hide button dismissed it.
- The unchanged Keyboard tile opened the terminal layout. Ctrl+A, arrows,
  Backspace and ordinary typing edited a line to `date`; Enter submitted it
  and the fixture executed `date -I`. Esc/Alt+b delivered exactly `1b1b62`.
- The shared-mode symbol views entered exactly `A1!π€√`.
- Keyboard layers remained on the phone. Its output stayed 1080×2424 at
  scale 3; reserved bottom space changed 72 → 282 → 72 logical pixels.
  The shared external desktop's size, scale and reservation stayed unchanged.
  Android's software keyboard remained hidden.
- Normal stop/reopen produced one new Squeekboard process in each mode,
  without duplicate keyboards. Original APK/test APK, rootfs identity,
  configuration and Android grants were restored with no cleanup errors.
  Settled captures confirmed the original phone widgets and external desktop.

A separate terminal-only shared-mode check passed ten consecutive `date`
editing/execution and Esc/Alt cycles without Wayland debug logging, using
instantaneous taps and the same derived layout. That APK's SHA-256 was
`9535cc08d995396ed7ae75344b29c3d20f07b96136c00571986ab9a89ef36ec5`.
Its direct keyboard opening does not establish automatic popup or tile behavior;
those passed in the final UI run above.
That run's input proof passed, but screen restoration failed during an Android
service restart and was recovered before final acceptance.

Debug/test APK builds, JVM checks, lint, mechanical audit, four focused host
tests, native `squeekboard-test-layout`, and the 1309-member canonical payload
comparison passed. The unchanged session-ownership script had also passed three
POSIX lifecycle checks during the initial integration.

Private evidence is retained under the parent workspace's
`.build-tools/omamobile-dev-20261017/`: the failing protocol trace is
`native-keyboard-runtime/run-1792421458740695100`, the ten-cycle check is
`run-1792422720451580800`, and final acceptance is `run-1792426943474705600`
in the same runtime directory. Exact APK, generated native metadata and final
runtime artifacts are sealed in `native-keyboard-fixed-artifacts/`.
Interrupted runs remain recorded as failures: Android-wide ANRs and unreliable
accessibility snapshots blocked testing before input. The final run followed
a restart of that same disposable AVD with its data preserved and 6 GiB RAM.
This is emulator evidence; physical ARM64 native-keyboard validation, other
languages and broader client compatibility remain open. All seven native
binary publication holds remain; no graphical APK was published for this change.

## Direct IME composition contract — 2026-10-16

On emulator `emulator-5556` (API 35, signed `wev` installed and the approved
external presentation display attached), the focused phone-process test was
run with:

```text
adb -s emulator-5556 shell am instrument -w -r \
  -e graphics true -e imeComposition true \
  -e class dev.omamobile.host.ImeCompositionTest \
  dev.omamobile.host.test/dev.omamobile.host.PhoneProcessTestRunner
```

The old host failed at composing `xy` followed by `commitText("z")`, leaving
the local draft. The candidate passed the same test, including exact
ASCII/Unicode key-state streams, pending-draft flush, repeated finish,
unsupported `replaceText` rejection, delete behavior, aliased text, and close
cleanup. The implementation keeps only a local pending draft; it does not
claim full IME history, selection, preedit protocol, or universal Unicode
toolkit compatibility. Non-ASCII transport uses the pinned US-keyboard
Ctrl+Shift+U route and remains client/toolkit dependent.

The default `androidx.test.runner.AndroidJUnitRunner` remains registered for
main-process tests, alongside the phone-process runner. After a normal
graphics cleanup, the unchanged `EscapeImeTest` passed against both the old
host and the candidate host through the default runner (65.116s and 63.947s).

## Owned Graphite build and runtime — 2026-10-16

The first-party Graphite 1.3.15 recipe built both ABIs from locked source and
the reviewed install-layout patch with NDK r30/API29. Raw recipe-byte validation
caught a pre-commit CRLF cache after Git normalized the recipe to LF; fresh
builds from the committed bytes replaced that proof. Both builds retained
source, objects, exact recipes, patch, toolchain hashes and logs privately.
Independent Sol review and 11 focused host tests passed.

Root staging and the actual APK audit checked 114 native libraries, all three
installer files and eight required APK native entries. The packaged Graphite
bytes match staging for both ABIs. APK: 96,642,100 bytes, SHA-256
`2fdcfa72cc74e3db5e72a8ce4b4e51fc96bb5f16b1785358ad2dc20e6adf886b`.
Debug/test APKs, 70 JVM checks and lint passed; the POSIX-only JVM fixture is
skipped on Windows. The opt-in real two-display shell smoke check passed on
the API35 x86_64 emulator in 9.914s. After normal startup, both native compositor
owners mapped the packaged Graphite library.

A separate empty-rootfs emulator compared the final APK's Graphite bytes with
the original library using both the APK and cached Termux C++ runtimes. All
four cases parsed the real Padauk font and produced glyphs
`[234, 99, 209, 172, 226, 224]` with advance `47.320312`; `dladdr` confirmed the
intended libraries. Root checked the actual fixture against the APK entry
after rejecting an earlier run that accidentally used prototype bytes.
This is one font-shaping compatibility case. ARM64 execution remains untested.

All seven source/relink delivery holds remain active, including explicit owned
Graphite delivery holds. This change does not authorize a public graphical APK.
Generated per-build notices/manifests are recreated by staging and are not
committed with host-specific logs. Private proof is retained under the parent
workspace's `.build-tools/omamobile-night-20261015/source-audit/`.

## Installation ownership lease — 2026-10-16

The shared installation lease now covers graphical owner processes and the
exclusive installer path. Two live owners deny `EXCLUSIVE_INSTALL`; stopping
the desktop owner still leaves the phone owner holding the lease; stopping
both permits exclusive ownership. A normal start attempted while exclusive
ownership is held reports `Linux installation is busy` in each fresh owner PID,
creates no new ready signature, and does not publish a compositor session.
After release, normal startup produces new ready signatures and responsive
`hyprctl monitors` replies. Failed startup after acquiring the lease remains
busy until the existing normal stop/process-death path releases the owner
lease. Detached Linux batch processes outside these Android owner lifetimes
remain outside this foundation's coverage.

The opt-in emulator check is `GraphicsSmokeTest#installationLeaseTracksBothGraphicalOwners`
with `-e leaseGraphics true -e graphics true`. It passed on the prepared API35
x86_64 `emulator-5560` after two-ABI root staging. Raw trace:
`.build-tools/omamobile-night-20261015/lease-evidence-5560/graphics-lease-final7.txt`
(SHA-256
`2900C649C805BB7070A81E59D7C36607562A38F3B8304C4DE406797825652750`). Earlier
C0/C1 premise evidence is retained privately beside that trace.

## Public HTTPS theme persistence — 2026-10-16

On the merged `aaf3a1d` host in the disposable API35 x86_64 emulator, the opt-in
fixed Hackerboy fixture used unchanged
upstream `omarchy-theme-install` over public HTTPS. The remote and downloaded
commit, all 14 canonical file hashes/modes, staged palette and selected
background were checked. Both live shells acknowledged the update. After the
normal Stop and Start controls, both new compositor signatures and mapped
shell surfaces passed the persistence checks. Private, unedited captures of
both displays before and after reopening showed the downloaded background,
phone widgets and desktop bar. IPC checks alone do not establish appearance;
the harness deliberately leaves `renderedPaletteVerified` false.

Restore reapplied `tokyo-night`, verified the exact saved theme data and both
shell acknowledgements, and removed only the harness-owned download. The final
result reports public HTTPS verified and both owners reopened. Six focused
Linux host tests pass. No theme scripts were executed and downloaded images
are not redistributed. This checks one reviewed data-only theme, not arbitrary
theme hooks or general ecosystem compatibility.

The tested host APK contains both ABI JNI bridges, compositor ports, TAWC and
Ando libraries: 146,967,976 bytes, SHA-256
`2b688716bf580161330deb9b03c6a59962a8bd83d5af004e58763a24e170f273`.
An earlier provider-test worktree build omitted the graphics bridge after
manual native staging. Its cancellation and recovery results below remain
valid for those paths, but its successful build did not establish graphics
availability. The merged APK above was checked for the actual native entries
and ran both real Hyprland sessions. Use the native staging tool, which
refreshes AGP's target configuration, rather than manually copying inputs.

## Cancellation while opening an archive provider

Manual import and separate-copy recovery now register Android's
`CancellationSignal` before entering provider IPC. Read-only asset descriptors
preserve provider offsets and declared lengths. Stop snapshots its current
worker/resources under the runtime lock, then cancels the provider and closes
an opened stream independently, keeping remote cancellation off the UI thread.

On the new empty API35 provider-test emulator, the final test APK against the
previous full host APK fails after 10.239 seconds because the worker remains
busy inside provider open. With the candidate full APK, both held-open and
held-read tests pass in 0.208 seconds. They prove the provider is held before
Stop, then observe worker idle, service exit, no activated rootfs or abandoned
import staging, and preserved unrelated sentinel data. The held-read provider
retains its writer until test cleanup, so EOF cannot explain that pass.

The prepared emulator also passes five export/recovery/service checks in
25.857 seconds, including a successful separate-copy recovery that preserves
live data. The host APK/test APK, 70 JVM checks and lint pass; one
POSIX-only JVM fixture is skipped on Windows. Provider-open cancellation is
cooperative: Android cannot compel arbitrary provider code to honor its signal.
No prepared installation was replaced or reset.

## Guest command failure diagnostics

The graphical instrumentation helper runs from the guest home and captures
output in an owned temporary file. A nonzero exit, five-second timeout, or
successful output above 64 KiB fails explicitly with command/profile context
and bounded output/session log tails. The output limit bounds accepted data
and diagnostics, not disk writes during the command. No retry or longer command
timeout was added. The existing shell-startup readiness loop still tolerates a
stale ready file while its replacement session starts; later IPC failures fail
directly.

On the API35 Adapter Test emulator, the opt-in diagnostics test exercised real
success, exit 7, a timeout, and 200 KiB of output (5.469s). The complete desktop
scale Keep/Revert/timeout test then passed with the final helper (50.496s).
The intermittent final phone IPC timeout was later captured as Android freezing
the separate compositor process; see [process ownership](COMPOSITOR-LIFETIME.md)
for the fix and matching regression evidence.

Development began 2026-10-14. This file records the tested milestone, not overall product completion.

## Current accounting — 2026-10-16

### Android portal runtime regression — 2026-10-16

Moving the graphical runtime exposed a missed dependent consumer: the Android
app portal still bound `files/r/android.sock`, while Linux clients looked in
the guest-owned runtime. The live Linux command failed with `ENOENT`; the new
host/guest integration test also fails on that APK. Shared alias initialization
now puts the portal at the same physical location as its unchanged Linux path.

The candidate passes three direct emulator tests in 13.630s: protocol rejection,
the real Linux app-list command compared with the host socket response, and
controller shutdown after proving both profile brokers initially accept
connections. The full graphical APK/test APK build, 69 JVM checks and lint
pass, with the POSIX-only JVM fixture skipped on Windows. This socket test
does not by itself reverify Android-window frames or Shizuku input.

### Fourth merged batch — 2026-10-16

PRs #19 (guest-owned runtime paths) and #20 (bounded sleep/wake audit) were
merged and checked together at `be3a1df`. The full graphical APK and test APK
built; 68 JVM checks and lint passed, with one POSIX-only JVM fixture skipped
on Windows. All 72 Linux host checks passed in 8.380s without skips. Direct
instrumentation on the updated prepared API35 emulator passed 12 graphics,
Ando lifetime, installer asset, display-preview, export/recovery and service
tests in 98.709s. Both real shells then reopened and the sleep/wake audit
passed with stable owners, signatures, display IDs and monitor geometry.
Linux data and Shizuku grants were preserved. This short emulator cycle does
not establish deep-idle, power-efficiency or physical-device coverage.

### Stalled manual archive cancellation — 2026-10-16

On a new empty disposable API35 emulator (`emulator-5560`), a test-only
cross-process content provider holds an archive pipe open without producing
bytes. The previous APK remains busy after Stop and fails the ten-second
deadline. With manual streams registered for cancellation, the same test
passes in 0.136s: the provider still holds its writer while the worker becomes
idle, import staging disappears, no rootfs activates, the foreground service
exits and unrelated sentinel data survives. Test cleanup then releases the
writer. The graphical APK/test APK build, 68 JVM checks and lint pass; the
blocking provider is absent from the production APK manifest.

This covers cancellation of a stalled read after a provider has opened the
archive. A provider that blocks inside its open call can still delay Stop.

### Guest-owned graphical runtime — 2026-10-15

Before the change, a data-only fixture in the actual session runtime passed
relative and canonical lookup but failed absolute intermediate lookup, `openat`,
and create/remove with `ENOENT`. With physical rootfs runtime storage and the
short host alias, that same fixture passes on both emulator-5558 profiles.
Unmodified Quickshell discovery now returns one real running instance per
profile. Direct instrumentation passed graphics and Android command lifetime
tests in 15.890s, including both native command/event sockets, terminal stdin,
arguments, environment, return codes and independent broker shutdown.

The full graphical build, lint and 68 JVM checks passed on Windows; the one
POSIX absolute-link fixture is skipped there and is covered by Linux CI.
The reviewed generated HTTPS theme fixture also passed certificate rejection,
trusted installation, peer synchronization and reopening with new signatures.
Unmodified captures showed its cyan background on both screens before and after
reopening; exact original theme state was then restored. This remains a local
generated fixture, not a public third-party theme compatibility claim.

### Third merged batch — 2026-10-15

PRs #17 (verified source-input snapshots) and #18 (authenticated automatic
installation) were merged and checked together at `f10ef7f`. The complete
graphical APK and test APK built; all 62 JVM checks and lint passed. All 59 Linux
host checks passed in 7.737s without skips. Direct instrumentation on the new
API35 installation passed 11 graphics, assets, display preview, user-data
export/recovery and service-lifecycle tests in 74.955s. The prepared emulator
data and grants were preserved, and the physical device was untouched.

### Authenticated automatic installation — 2026-10-15

A new empty disposable API35 x86_64 emulator (`Omamobile_Install_Trust_API35`,
emulator-5558) installed the full graphical APK and used its actual **Download
and install authenticated Arch base** control. The app fetched the official
127,355,946-byte zstd bootstrap and detached signature over HTTPS, verified full
primary fingerprint `3E80CA1A8B89F69CBA57D98A76A5EF9054449A5C` before extraction,
and logged SHA-256 `0c9c97b7a58a8a023c9e07f84cb307a9e8006e65d8fc4c17b83ddc8010b45c10`.
Signed package preparation completed and activated the new installation.

An initial attempt rejected the official archive's two release metadata files
beside `root.x86_64/`; it published no rootfs. The corrected importer consumes
only those two bounded regular files without extracting them. The retry passed.
Both native Hyprland sessions then responded to live IPC. Unmodified captures
were visually inspected: phone clock/calendar widgets and launcher, external
1920×1080 desktop bar and matching wallpaper. Android's first-run fullscreen
tips were dismissed before capturing the unobstructed shells.

The final graphical APK build, 62 JVM tests and lint passed. Direct Android
instrumentation passed five graphics, installer-asset and service-lifecycle
checks in 13.706s, including refusal of an authenticated install over existing
Linux data and idle foreground-service cleanup. No connected-test runner, root,
Shizuku grant, physical device or prepared-emulator reset was involved.
Automatic ARM64 installation, public binary distribution and full ecosystem
compatibility remain unverified.

### Combined PR verification — 2026-10-15

PRs #11 (HTTPS theme fixture), #12 (user-data export), and #13 (desktop scale
preview) were squash-merged and tested together at `9b11d11`. The full graphical
debug APK and test APK built with both native ABIs; 34 JVM tests and lint passed.
The Linux host suite passed 42 tests in 6.608s, and merged-main contribution CI
[run 38854437427](https://github.com/cbarland/omarchy-mobile-android/actions/runs/38854437427)
passed both jobs.

The existing disposable API35 emulator was cold-booted without wiping its data,
and both APKs were installed as updates. Direct instrumentation passed all six
display-preview, user-data-export and service-lifecycle tests in 69.506s. This
includes the native scale acknowledgment, Keep/Revert, dialog-independent
timeout, export cancellation and service completion races. Both real shells
then reopened with live Hyprland IPC. Unmodified SurfaceFlinger captures were
visually checked: the phone showed its clock/calendar widgets and launcher,
and the 1920×1080 desktop showed the upstream bar and matching wallpaper.
These combined checks do not replace the individual PRs' broader evidence or
establish new physical-device or public APK release readiness.

PRs #14–#16 were then merged at `edc3147`: upstream shell command auditing,
in-place external-display geometry checks and separate-copy recovery. The
combined full graphical build, 39 JVM tests and lint pass; 53 Linux host tests
pass in 7.106s without skips. Direct instrumentation on the updated disposable
emulator passes all eight preview/export/recovery/service tests in 71.887s.
The individual real document-picker recovery check and four-phase geometry
audit are recorded in INSTALLING.md and display-geometry.md. These increments
leave the broader issues open, including authenticated image download,
in-place replacement, display power/input coverage and release source bundles.

Physical device work remains paused for this batch. Resumed verification uses
the prepared emulator and a separate disposable emulator; this task has not
reconnected to or changed the Nubia. Preserve the prepared Linux data and
Shizuku authorization. The emulator server was restarted after boot using the
already authorized official starter; its app grant remains enabled.

### Installation and contributor setup — 2026-10-15

The new archive installer passed on disposable API35 x86_64 emulator-5556 after
clearing only that disposable app's data. The normalized official Arch bootstrap
was 211,166,021 bytes, SHA-256
`b8e123034d389c150a1579d26c84e0a6bac9366336812ef7f7ade3765c513a63`.
Its original upstream checksum had been checked; this test does not add an
independent PGP-verification claim. Users verify bootstrap signatures separately.
The importer extracts to unpublished staging, verifies packaged source assets,
installs signed packages, prepares both profiles and activates only on success.
The packaged-asset test plus fresh-install/real-layer check passed in 215.296s.
Hyprland 0.56.2, Quickshell 0.3.2, Foot 1.28.0 and glibc 2.44 were installed through
pacman. Both upstream phone and desktop layers mapped without config errors.
The first attempt exposed AAPT's transparent `.gz` expansion; `.tgz` preserves
the payload bytes and the Android packaged-hash/unpack regression now covers it.

The actual MainActivity document picker separately delivered a SAF content URI
and read grant to LinuxService. Android reported its foreground notification and
bounded setup wake lock. Cancelling during signed package downloads left no
active setup children, service, wake lock, staging rootfs or setup directory.
The stopped verified installation was temporarily parked for this disposable
test, then restored. It was not merged with the cancelled attempt. Two service
generation/duplicate-start regressions plus packaged assets passed in 9.286s.
The new flow has not been exercised as a fresh ARM64 phone install. The Nubia and
the prepared emulator's Linux data/Shizuku grant were not changed by this batch.

A source-only snapshot with no generated compositor/installer assets passed the
core APK, 25 JVM tests and lint in 18s. The Linux host suite passed 36 tests, and
the CI C credential probe passed 19 checks. Full graphical debug APK assembly and
lint pass separately; both ABIs remain included. Release preflight correctly
rejects public binary release while seven version-specific corresponding-source
mappings are missing. Mechanical checks pass 114 library hashes, 3 installer
files/pins, closure and restored notices. Hosted GitHub CI is separate from
these local checks. Fresh-install screenshots in `docs/images/` are genuine
SurfaceFlinger captures from the disposable emulator after reopening both shells.

The final attribution build was installed on the disposable emulator. Its visible
credits control opened the scrollable, linked upstream acknowledgements with
readable theme colors. The APK's generated `CREDITS.md` and
`licenses/REFERENCES.txt` assets match the canonical repository files byte for
byte. The first hosted source-only GitHub validation run also passed both jobs:
[run 38775780730](https://github.com/cbarland/omarchy-mobile-android/actions/runs/38775780730).

### Cleanup review — 2026-10-15

An independent Sol PR reviewer evaluated baseline `09b3109` against INTENT.md.
Five code defects are resolved: stop reception now belongs to the controller
instead of a replaceable Activity; Android-window creation/launch/release stays
on its connection worker rather than blocking the main looper or handing back
an abandoned display lease; the shared magic database is published atomically;
and profiles/licenses are read from pinned Git blobs rather than dirty checkouts.
Both graphical launch paths now use one environment definition, retaining the
native compositor's actual Wayland socket and per-launch Hyprland tokens.
Host ADB operations are bounded, and timeout evidence remains JSON-serializable.

Validation on the cleanup APK: both ABI builds, 23 JVM tests, and lint (zero
errors, 13 existing warnings); five portal tests including real Shizuku display
release and blocked-helper regressions (3.789s); simultaneous cold startup plus
stop after both output Activities are gone, followed by graphical Ando (two
tests, 29.055s); prepared OWL/input/reopen/helper regression (37.823s); and rapid
display reconnect with restored preferences. Five host-tool tests pass. An
explicit payload integration check compared all 1,291 tar members by content,
mode and size before/after dirty profiles, licenses, widget files, staged modes
and an untracked file; all matched and the original references were unchanged.
Physical hardware was not used; the existing emulator Shizuku grant is retained.

The review did not change the product goals. Two compositor sessions cannot
move a live window between outputs. Software Linux client rendering and CPU/JPEG
Android windows remain performance tradeoffs; inactive polling needs measurement
and reduction. Dependency manifests should become reconstruction inputs, and
installation/recovery and Android-owned service adapters remain unfinished.
These limits are not resolved by consolidating Java session setup. The final
read-only review found no additional actionable defects in the cleanup delta.

### Working with direct evidence

- **Unmodified Android foundation:** a normal APK and app-private Linux run
  on the locked Nubia NX789J, Android 15, with green verified boot. No root,
  PRoot, bootloader unlock, flashing or Android security-policy change was
  required. ARM64 and emulator x86_64 runtime/graphics backends build.
- **Shared Linux:** real Arch Linux ARM, internet access and signed pacman
  updates work. Phone and desktop share one rootfs, home, package database,
  plugins and theme. They currently use two Hyprland sessions and separate
  session buses/sockets; moving a live window between those sessions is not
  implemented.
- **Real graphical shells:** upstream Omarchy desktop bar/wallpaper and
  Linux Wayland clients render on the external monitor; upstream Omarchy
  Mobile clock, calendar, notes/media and widget pages render on the phone.
  This was demonstrated concurrently on the emulator and physical Nubia.
  Hardware-dependent status/media functions are a separate unfinished layer.
- **Input and reopening:** phone touch/swipes and real Foot PTY input work.
  The user confirmed Bluetooth keyboard input, mouse movement/clicks, then
  explicitly confirmed the mouse works after closing and reopening the app.
  The task/capture fix produces one visible Recent Apps card and reuses the
  two output hosts. Its completed physical regression also verified helper
  reconnection with the existing Shizuku grant.
- **Mixed Android/Linux windows:** Android Settings and Linux Foot tile on
  the same external Hyprland workspace. Settings content, click/text input,
  resizing and release of its hosted display are tested. The optional
  display-scoped helper requires Shizuku ADB mode (shell UID 2000); Linux and
  the compositor stay at the normal Android app UID.
- **Representative ecosystem support:** the reviewed OWL todo widget renders
  and a phone tap updates its Linux Markdown note. The unmodified Scratchpad
  community shell plugin installs/loads; its populated workspace toggle was
  verified on the emulator. These are representative integrations, not proof
  of the whole ecosystem or Android AppWidgets.
- **Storage and Android commands:** app-owned shared storage binds, read-only
  and read/write bind behavior, and Ando command execution as the app UID are
  tested, including graphical-terminal access described below. Arbitrary Android document/storage trees are not
  already POSIX binds.
- **Codex CLI preparation:** official Linux ARM64 Codex CLI 0.162.1 installs
  and runs from both profiles. Omarchy's command-presence check succeeds.
  This repairs the missing-CLI installation step; account sign-in, model
  requests and the Codex sandbox have not been verified.
- **Upstream terminal launch prerequisite:** on the emulator, the unchanged
  theme-menu command now opens a real Foot terminal with the upstream `gum`
  repository-URL prompt. It previously exited 127 because `uwsm-app` was
  missing. Signed Arch `gum`/`xdg-terminal-exec` packages and a session-only
  argv adapter supply the missing launch boundary. This verifies opening the
  installer, not installing/applying a remote theme.

- **Theme and package commands:** a local, data-only Git theme installs,
  applies and restores through unchanged upstream commands. The additive
  theme hook receives actual peer phone-shell acknowledgments. The unchanged
  package-install picker installs a signed Arch package. The unchanged removal
  picker also passes using the official Omarchy repository's signed `yay`
  binary for local installed-package queries, then normal pacman removal.
  The disposable
  image also imports the fingerprint-verified official Omarchy key and installs
  its signed keyring package through a full upgrade transaction. No AUR recipes
  are executed. Repeated repository configuration is stable; conflicting user
  configuration is rejected before any key-trust operation.
- **Native settings transport:** the outbound Unix-credential adapter passes
  the actual Android sender/receiver probe, including descriptor delivery and
  rejected foreign credentials. The receiver still sees the app's real Android
  UID. A private native dconf session now persists and reads settings; it
  previously failed with credential-send EPERM. Existing graphical profiles
  retain their GLib keyfile backend until preference migration is designed.
- **Graphical Ando:** real Foot terminals on both displays pass app-UID,
  arguments, environment, working directory, stdin/stdout/stderr, exit-status
  and signal-status checks. Stopping one profile leaves the other usable;
  closing either broker makes its endpoint unreachable and terminates its
  test workers. A dead Unix socket pathname may remain after abrupt owner
  death; that is not a live broker, and startup replaces it.
- **Planned widget shutdown:** the full reopen regression passes with the
  reviewed OWL widget, one visible app card, singleton compositor sessions,
  Shizuku reconnection and real Wayland mouse motion/release. A separate
  SIGSTOP-broker test verifies widget loading markers clear before the
  six-second owner watchdog, while recorded crashes are preserved.
- **Detached graphical jobs:** all six ordinary, setsid and truly orphaned
  fixture jobs disappear after stopping their display owners. The orphan
  cases have PPID 1 before shutdown. Android's display-process cgroup cleanup
  performs the final cleanup; no broad UID kill or fixture cleanup kill was
  needed. Batch-console daemon lifetime is a separate limitation.
- **Display scale persistence:** the stock desktop Display panel maps and
  responds through upstream IPC. Independent phone/desktop scale settings
  survive atomic file replacement and owner restart (24.023s instrumentation
  pass). Upstream Mobile's phone bar does not implement the stock desktop
  panel; its normal scale CLI is verified instead. Physical Android modes and
  rotation remain Android-owned, read-only facts in the host helper.
- **Saved display routing:** actual settings-button/radio clicks survive
  Activity recreation. Ordinary and immediate reconnects change the Android
  display ID (12 → 13 → 14), start fresh desktop owners, and preserve the
  phone owner. A missing saved target refuses another usable output. The
  test restores all routing preferences and removes its temporary output
  (13.560s pass). Admission checks exclude untrusted virtual displays that
  Android refuses to host our Activity. Controller-owned removal handling
  stops orphaned compositors and removes only that profile's task, preventing
  Android from restoring the desktop onto the phone after unplugging.
- **Upstream config editing:** the missing `nvim` fallback was reproduced as
  exit 127. Signed Neovim is now included in preparation; the unchanged
  config-editor command opens a real Foot terminal. On the external emulator
  display, Android keyboard input inserts an exact test string, Escape leaves
  insert mode, and `:wq` saves that string and closes the window. Escape is
  forwarded before the Android IME; the reserved mouse-release shortcut still
  passes (14.857s).
- **Phone Escape and Android Back:** with the actual Android keyboard visibly
  open before each pair, signed `wev` observes exactly one press/release for
  both synthetic and physical-scan-code Escape input. Android may hide its
  keyboard after the event; that outcome is recorded separately. The normal
  Android Back command hides a re-opened keyboard without adding Wayland
  Escape events. The 16.219s test restores the keyboard setting and stops its
  observer. Broader IME composition/Unicode remains unverified.
- **Downloaded theme persistence:** unchanged upstream theme-install downloads
  a generated two-file data theme through real loopback Git HTTP. Its exact
  generated commit, peer-shell acknowledgments, shared palette/background
  hashes and new compositor signatures on both profiles are verified. Actual
  screenshots show the cyan wallpaper/accent before and after reopening.
  Original theme state, cache and GTK keyfile data are restored exactly.
  This does not establish public HTTPS/TLS or third-party-theme compatibility.

### Open defects and incomplete integration

1. **Basic Omarchy functions:** theme install/apply/restore, loopback HTTP
   download, peer reload, reopening persistence, config editing and normal
   package install/removal now have direct evidence. Public HTTPS themes and
   broader everyday menu coverage remain pending. The pinned normal removal
   picker still calls `yay`; provide its signed repository binary after
   configuring the official Omarchy repository. The Arch-only graphics
   preparation does not supply it. No fake replacement or AUR build has been
   introduced. Retain
   useful upstream-aligned logs and exit status. See the
   [basic-function checks](#basic-omarchy-function-checks).
2. **Broader display management:** Android inventory, saved target policy,
   modes/density/rotation facts, upstream scaling and ordinary/rapid reconnect
   are verified on the emulator. Rotation, sleep/resume, hardware reconnect
   and mode changes still need broader coverage. Two independent single-output sessions and one external
   target do not form a unified extended desktop; live cross-session window
   movement and physical display-mode changes are unsupported. See the
   [display helper](GRAPHICS.md#display-management-helper-prototype).
3. **Broader lifecycle/input:** targeted reopen, pointer release, slow broker
   and detached graphical-process tests pass. Rapid hotplug, lock/unlock,
   rotation, sleep/resume, resolution changes and long runs need stress
   coverage. Old Omalateral helpers can exclusively own the
   mouse; input ownership/coexistence remains a known conflict.
4. **Linux completeness:** the image is a minimal prepared Arch userland,
   not a complete Omarchy package/service installation. Linux audio,
   PipeWire, system/hardware D-Bus services, NetworkManager/UPower/BlueZ status
   and Android hardware adapters are incomplete. Quickshell instance discovery
   did not find the running hosted shell, although Omarchy's fast socket IPC
   works. Native compositor GUI dialogs also remain incomplete.
5. **Android integration:** broader file/document
   access, Android clipboard/notification integration, IME/Unicode,
   accessibility, default phone HOME registration and native Android
   AppWidget hosting remain pending. General app compatibility and work
   profiles are not established by the Settings demonstration.
6. **Graphics/performance:** Linux clients use software `wl_shm` buffers.
   Accelerated Linux client EGL, Xwayland, arbitrary glibc Hyprland binary
   plugins and advanced GPU paths are unverified. Android app windows use
   CPU-copy JPEG at up to ten polls/second. Latency, battery, thermal and
   sustained-performance measurements are outstanding.
7. **Installation/data/release:** the in-app archive installer now prepares
   signed Linux packages and pinned shells before activation; fresh x86_64
   installation and cancellation pass. Authenticated bootstrap download,
   fresh ARM64 installer coverage, data export/recovery and safe rootfs
   replacement remain pending. Imported hardlinks can become relative symlink
   aliases. Native package locks exist, but full clean-cache reconstruction and
   seven dependency source bundles remain open. The source repository is public,
   private vulnerability reporting is enabled and core contributor CI passes;
   no public graphical APK release is published. The frozen Omalauncher work is
   separate. See the [current emulator backlog](INTENT.md#emulator-development-backlog--2026-10-15).
8. **Device security/product validation:** work-app and Google Wallet behavior,
   protected content, phone roles and wider device coverage remain untested.
   Preserving the stock boot/security configuration has been observed; those
   application-level compatibility goals are not yet established.

### Basic Omarchy function checks

Added from the user's report on 2026-10-14. The direct-evidence list above
records the completed subset; the checks below define broader remaining
coverage. Continue on emulators before resuming physical-device work. Basic
functions and display management remain the development priorities.

- Reproduce theme installation through the same menu/UI a user uses. Record
  the invoked command, exit status and relevant stderr/logs; do not assume
  the already-known minimal package set explains every failure.
- Install a compatible theme, select it, switch back, and verify wallpaper,
  shell and supported application styling. Verify the shared selection is
  picked up by both phone and desktop and survives closing/reopening. Keep
  profile-specific layout and user configuration intact.
- Exercise everyday menus/shortcuts, app launching, settings/configuration,
  package and plugin install/enable/disable paths, and shell reloads. Record
  each workflow as passing, failing or explicitly unsupported with evidence.
- Follow upstream Omarchy's error-reporting conventions. Logs and command
  exit status can provide sufficient diagnostics; new visible error UI is
  not required. Check representative missing-dependency, download and
  invalid-input failures, retaining the cause in the appropriate existing
  reporting path. Do not report success merely because a terminal/process
  launched. Identify unavailable host capabilities through that same path.
- Repair the Linux preparation or Android/session adapters where appropriate,
  preserving upstream Omarchy commands and ordinary package management. Add
  focused regression coverage for reproduced defects.

### Build and test accounting

The last completed physical checkpoint passed seven JVM tests and ten
Android tests (9.642 seconds), with zero lint errors and nine warnings.
Its signed APK and copied reports are retained under
`.build-tools/remote-apks/omamobile-nubia-reopen-20261014/` in the parent
workspace. That test pass preceded the latest planned-widget-shutdown change
and did not catch the later widget guard attribution.

The 2026-10-15 build passes 23 JVM tests with zero lint errors and 13 warnings.
Both ARM64 and x86_64 native libraries build. Separate Linux regressions pass
eight session-launch and ten theme-hook checks; 19 hosted native credential
checks pass normally and with ASan/UBSan. The actual Android credential probe
passes ten cases. The two upstream application-launch patches have been
removed in favor of the session PATH adapter; the remaining upstream shell
patch selects each profile's config.

Latest targeted emulator passes are full lifecycle/reopen (26.236s), slow
broker shutdown (11.151s), graphical Ando (14.874s), and monitor panel plus
profile scale persistence (24.023s). Saved-routing plus ordinary/rapid
reconnect passes in 13.560s; the launch-admission catalog test passes in
0.112s, and visible-IME Escape/Android Back passes in 16.219s. The detached graphical-job regression
also passed (41.639s). Logs live in the parent workspace's
`.build-tools/omamobile-test/`. Earlier failed lifecycle runs are retained
there as history; they do not describe the current result.

The disposable emulator's previous SwiftShader/2GB configuration twice
crashed its host QEMU process with Windows access violation 0xc0000005.
Repeating with host GPU/4GB, matching the stable prepared emulator, passed
the targeted runs. This is a test-host limitation, not a successful product
test or an Android app crash. App-private Linux data was preserved.

The preceding full physical run was interrupted when the presentation display
disappeared and wireless ADB disconnected; no physical run was attempted in
this development pass.
The current emulator-verified development APK is 38,226,260 bytes, SHA-256
`13e38ceddc92ce4cdaf2cf4536ce07eadb793bc7e3ec0b1bdb2cc92b23bed0f5`.
It includes both ABIs and is newer than the retained physical checkpoint;
these latest changes have not been retested on the paused Nubia.

All 93 vendored TAWC base hashes match the pinned reference. A primary-owned
replay of the regenerated credential patch produces the current vendor hash
exactly; canonical LF attributes preserve that source/patch identity on Windows.
RIPP scoped model admission succeeded for the Luna/Sol work packages. Its
latest provider choice returned HTTP 401, so subsequent work used direct
source evidence, XERJ and the same parallel agents without credential retries.

Next: verify public HTTPS theme workflows and continue the everyday menu
audit, then integrate Android-owned audio/network/power facilities. Establish a
committed baseline before expanding broader integration work. Keep the
shared standard Linux installation and upstream shells; focus new work on
the Android integration layer.

## Reopening and Linux tools — 2026-10-14

After the initial cleanup below, the user reported multiple Omarchy cards
and another mouse failure after closing and reopening the app. Shizuku and
the old privileged helpers were still absent, so stopping those helpers
had not resolved the separate reopening defect.

The host now has one Recent Apps card for controls. Its phone and desktop
hosts are excluded from Recents, repeated starts reuse their tasks, and
the previous explicit multiple-task launch flag is gone. Legacy visible
output cards are removed when controls open. Dismissing the controls card
stops both hidden sessions. The Surface controller tracks its attaching
view so a destroyed older Surface cannot detach a replacement. Relative
capture is restored on resume, new intent, Surface attachment and focus
gain, with a bounded retry while focused; Ctrl+Alt+Escape still releases it.

The physical lifecycle regression repeats Start three times, checks one
native compositor per output and one visible app card, dismisses the app,
waits for both screen processes to stop, and starts fresh sessions with new
Hyprland signatures. Targeted Wayland mouse input and the capture-release
shortcut work afterward. The user then repeated the close/reopen workflow
with the Bluetooth mouse and confirmed **"Works after reopening"**.

The user subsequently requested that Shizuku remain enabled. It was
restarted and Omarchy Mobile's authorization restored. Starting Shizuku also
started three older desktop helpers; their apps (`com.omalateral`,
`com.lateral`, `com.lateral.dev`) were stopped afterward without changing
their authorizations. Keep those competing mouse readers stopped. The new
host unbinds its own helper when screens close and reconnects using the
existing grant when reopened. It does not revoke that grant or stop the
Shizuku server during normal screen cleanup. The lifecycle test verifies
shell UID 2000 reconnects after reopening without another permission prompt.

Completed checkpoint before the latest widget shutdown change: **seven JVM tests and ten physical Android
tests passed**; the combined Android run took **9.642 seconds**. Native
runtime/import/binds, display routing, real Arch reuse, both real shells,
Android window ownership/frame/cleanup checks, pointer capture and the
close/reopen regression passed. Debug and test APK builds succeed; lint
reports zero errors and nine warnings. No prepared Linux data was removed.

Checkpoint APK in the parent workspace:
`.build-tools/remote-apks/omamobile-nubia-reopen-20261014.apk`,
38,021,480 bytes, SHA-256
`95d8ba33992bc298ed1aafc07c1472493b90abc92ff7cb9228ee19475adb2e88`.
Its Android v2 signature verifies against the same debug certificate as the
installed predecessor. The directory with the same stem contains test and
installation records; the previous APK and cleanup history are preserved.

The account-panel error "Installing the Codex CLI didn't finish" came from
its normal `mise use -g codex` path with neither mise nor Codex installed.
The reviewed official standalone installer selected Linux ARM64 and installed
`codex-cli 0.162.1` in the shared rootfs. Both profiles resolve and execute
`/usr/local/bin/codex`; Omarchy's command-presence check now succeeds, and
`codex login --help` runs. Upstream account scripts were not patched.
No account was signed in, credentials inspected, or model request executed.
See `LINUX-TOOLS.md` for the repeatable preparation helper and its digest.

The earlier cleanup record below is historical; **current Shizuku
authorization is intentionally retained** under the user's later instruction.

## Initial physical Nubia verification — 2026-10-14

The ARM64 graphics backend was built from the pinned Android Hyprland port
and installed on the Nubia NX789J, Android 15, Adreno 830. Genuine Hyprland
renders upstream Omarchy Mobile widgets on the phone and the upstream
Omarchy desktop on its Sceptre Z27 external monitor at 2560×1440/60 Hz.
Both sessions share the app-private Arch Linux ARM installation and home.
Android reported a locked bootloader and green verified boot. No root,
flashing or Android security-policy change was used.

The official generic Arch Linux ARM archive was verified against its
detached signature using the exact build-system fingerprint published on
the [Arch Linux ARM package-signing page](https://archlinuxarm.org/about/package-signing).
Archive SHA-256:
`42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319`.
Signed package updates completed, including Hyprland 0.56.2-4 and Quickshell
0.3.2-1. Unused guest kernel/firmware packages were removed inside the rootfs;
Android's own kernel and firmware were not changed. Linux/compositor processes
retain Android app UID 10689; `ando` also runs with that app UID.

- Seven JVM checks and nine Android instrumentation checks passed. The
  combined physical Android run took 8.267 seconds. The checks cover native
  runtime/import/binds, display routing, reuse of real Arch, concurrent real
  Hyprland sessions, Android-window ownership/cleanup and targeted relative
  input. Lint reports zero errors and nine warnings.
- The official tarball's literal backslashes in systemd unit filenames
  exposed an import bug. Unix filenames are now accepted while traversal
  checks remain; a real escaped filename/symlink regression passed.
- Mobile page swipes work. The approved, unmodified OWL todo widget renders,
  and a phone tap checked its first Markdown task. This proves that widget;
  Android AppWidgets and general store compatibility remain untested.
- Android Settings and Linux Foot tile together as Wayland windows. Adding
  another Linux window resized the Android tile from 1266×1400 to 1266×693;
  closing it restored 1266×1400. A hosted-display focus fix keeps the physical
  desktop focused while forwarding input to Android. Injected search input
  entered all seven characters of `battery`; Foot executed `uname -m` and
  returned `aarch64`.
- The user confirmed the Bluetooth keyboard works after the focus fix.
  The Bluetooth mouse initially failed on both screens, including after
  the relative-capture build. Android reported external capture, and a
  relative test event explicitly targeted at that display moved Hyprland
  from (1280,720) to (1316,738). Ctrl+Alt+Escape released capture. These
  injected results alone did not establish hardware behavior. After the
  test-started Shizuku server and older Omalateral privileged helper were
  stopped, the user confirmed: **"Mouse moves and clicks"**. The unchanged
  installed APK then handled the physical mouse successfully. The older
  helper's source uses EVIOCGRAB for exclusive mouse ownership; the observed
  recovery points to that competing reader as the cause. Other applications'
  permissions were not changed. Simultaneous operation with that older raw
  input reader remains unsupported.
- Scratchpad commit `ce789b627c66134b8f020b283b04dcdca014924a` was installed
  and loaded. Its populated two-way workspace toggle was not completed
  before the display disconnected; the earlier emulator result remains
  the completed toggle evidence.

Temporary Shizuku access for this physical-device test was explicitly
authorized. **Cleanup completed after ADB was re-enabled:** this host's
grant was visibly off in the official manager, and the test-started server,
own shell helper and older Omalateral shell helper were absent from the
process list. The five other existing authorizations stayed enabled. No
host-owned Android virtual display remained. Both Linux screens restarted
without Shizuku, the todo widget remained approved and `ok`, and attempting
to open Settings returned the expected authorization-required error.
The physical external display's logical ID changed again to 475; test tools
must read the current session ID after reconnection.

Local APK: `.build-tools/remote-apks/omamobile-nubia-20261014.apk` in the
parent workspace; 38,019,688 bytes, SHA-256
`e587646752a9c22cfb182a8e17935ddee7b9b402210d415bf5934223416c91f4`.
Its private evidence directory has the same stem. This is the installed
build on which the user confirmed Bluetooth mouse movement and clicks.
The Linux archive/payload is prepared separately. After reconnection, the
installed `base.apk` SHA-256 matched this packaged APK. The private
`cleanup.json` records the completed checks.

Remaining limits include broader hardware input coverage, Android IME/accessibility,
Linux audio and hardware status services, advanced Linux GPU paths,
compositor GUI dialogs, suspend/resume, work-profile/Wallet behavior,
power measurements and general plugin/program compatibility. The Android
frame transport remains CPU-copy JPEG at up to ten polls per second.

## Ecosystem and mixed-window milestone — 2026-10-14

Three representative integrations now work on API 35 x86_64 emulator-5554:

- **Omalauncher OWL widget:** upstream `simonschubert/todo` from store commit
  `b63886b75e02ccef1a8a11baeef2d6eff7a374af` is imported by Mobile's normal
  store helper, compiled and rendered by its existing OWL runtime. Tapping
  the first checkbox changed `~/notes/todo.md` from `[ ]` to `[x]`. Its source
  still matches the compatibility index SHA-256. Approval and runtime status
  are `ok`; its startup guard settled to empty loading/crashed maps. This
  proves this OWL widget, not the whole store or Android AppWidgets.
- **Android alongside Linux:** real Android Settings and stock Linux Foot
  are simultaneously mapped, non-floating Wayland windows on the same
  Hyprland monitor/workspace. Both measured 946×1040. Opening another Linux
  window resized the Android client to 946×513, and closing it restored
  946×1040. The underlying Android display also measured 946×1040. Clicking
  Settings search and typing `battery` works through the Linux window;
  switching to Foot and executing `echo LINUX_WINDOW_OK` works too.
- **Community Omarchy plugin:** normal `omarchy plugin add ... --enable
  --yes` installed unmodified `rob.scratchpad` at commit
  `ce789b627c66134b8f020b283b04dcdca014924a`. Its bar button closed and opened
  a populated `special:scratchpad` workspace; the test terminal was then
  returned to workspace 1. This is one tested community shell plugin.

The Android-window integration requires separately authorized Shizuku in ADB
mode (shell UID 2000). The app-only virtual-display launch was rejected by
Android; that protection is not bypassed by changing Android policy. Linux,
Hyprland and the host still run as the ordinary app UID. The helper accepts
only its own display leases, and display-0 input is rejected by a real test.
The frame transport is currently CPU-copy JPEG with at most ten polls per
second, not a performance or battery-efficiency result.

Final regression: **seven JVM tests and eight Android tests passed**. The
combined Android run completed in 7.043 seconds. Its two new tests cover
invalid/unowned requests, real nonempty Settings frame content, shell UID,
rejection of phone-display input and automatic display release on disconnect.
The existing native runtime, display-routing, real-Arch and dual-Hyprland
checks also passed. Debug and unsigned release builds succeed; lint reports
**zero errors and nine warnings**, with no baseline. APK signature verifies.
Tests use `adb install -r` and explicit instrumentation, never uninstalling
the prepared Linux data. The opt-in Android-window test needs
`-e androidWindows true`; the graphics/Arch options remain as described below.

Two development issues were reproduced and addressed: missing per-display
focus caused input-dispatch ANRs; Android's fallback IME then covered the
external desktop. Own-focus plus a per-hosted-display IME-hide policy resolved
the reproduced keyboard path. Historical ANR log entries remain in logcat.
The initial combined test also deleted a live compositor's ready file; the
graphics check now validates live IPC while accepting an already running
session and refreshing replacement-session metadata.

Forced instrumentation teardown inside OWL's three-second startup guard
produced one false crash attribution for the test todo widget. Its guard
record was backed up and only that marker reset with the home plugin stopped.
The widget source, approval and normal crash-guard logic were not changed.
A normal subsequent restart passed without another attribution. This remains
a caveat for force-stop or update during widget startup, not a claim that all
Android lifecycle handling is complete.

The final mixed-display screenshots and machine-readable evidence are in
the parent workspace's `.build-tools/remote-apks/omamobile-compatibility-20261014/`.
They were captured before cleanup and are unedited. Closing the Android
Wayland window removed its virtual display without moving its task to the
phone. The temporary Shizuku app grant was then revoked through its manager,
the test server stopped, and both normal Linux screens restarted. The todo
widget remains `ok` without Shizuku. Android-window launch now fails closed
with the expected authorization message. No physical device was touched.

Emulator APK: `.build-tools/remote-apks/omamobile-compatibility-emulator-20261014.apk`,
29,307,256 bytes, SHA-256
`c860af533e2810574b207639125f0b5c16333df69583736a83aec39d74be5630`.
Its Linux image/payload is prepared separately; it is not an ARM64 phone build.
General plugin/store compatibility, native glibc Hyprland plugins, protected
Android apps/work profiles, physical ARM64, Unicode/IME/accessibility and
power/performance remain unverified. See `ANDROID-APPS.md` for adapter limits.

## Earlier emulator graphics milestone — 2026-10-14

Both requested interfaces were displayed together on the emulator: genuine
Hyprland with Omarchy's upstream desktop bar/wallpaper on the 1920×1080
external presentation display, and upstream Omarchy Mobile with clock,
calendar, notes and media widgets on the 1344×2992 phone display. Unedited
SurfaceFlinger screenshots are under `app/build/verification/graphics/`.

- Seven JVM checks and six Android checks passed on the graphics build:
  native filesystem/import/bind/broker tests, output hotplug, reuse of the
  real Arch installation, and the opt-in `GraphicsSmokeTest` (5.289 seconds).
- Debug and unsigned release APKs build; Android lint reports zero errors.
  Remaining warnings include SDK/dependency updates, a static application
  context, surface accessibility and optional storage allocation APIs.
- The graphics test queries real Hyprland IPC, checks Android display IDs,
  verifies the two sessions use the identical rootfs, and confirms native
  `omarchy-background`, `omarchy-bar`, `omamobile-home` and dock surfaces.
- A stock Linux Foot client maps with `xwayland: false`. Android keyboard
  input typed and executed `printf OMAMOBILE_DESKTOP_OK` in its real PTY.
  Mouse input moved Hyprland's cursor to approximately `(1200, 500)`.
  Phone touch swipes changed between upstream clock/calendar and notes/media
  pages. Omarchy shell IPC responds. A file written in the desktop session
  is readable in the phone session.
- After the Android launch adapter and GTK accessibility setting were applied,
  tapping the phone's Foot shortcut opened a real Wayland client. The upstream
  mobile empty-workspace action returned the phone to its widgets. The final
  desktop screenshot shows a floating terminal executing `uname -m`.
- Signed Arch package updates and graphics-package installation succeeded.
  The guest downloader uses `DisableSandbox` because Android supplies the
  process/user isolation; package signature verification stays enabled.
  No Android root, security policy or process-limit modification was used.
- The emulator's old SwiftShader host process crashed during terminal testing
  (Windows recorded qemu access violation `0xc0000005`). The same AVD/data was
  restarted with `-gpu host -memory 4096`; GLES 3.1 on the host GPU passed the
  checks above. This is emulator development evidence, not a power/performance
  or physical-device stability result.
- Source staging preserves Unix shebangs and Git executable modes. GTK entry
  launch uses the Android session adapter instead of a systemd scope;
  `NO_AT_BRIDGE=1` avoids waiting for an absent Linux accessibility bus.

At this earlier milestone the staged graphics backend was x86_64. ARM64 command executables
build, but ARM64 graphics and physical displays remain unverified. Automatic
installation, Android IME/accessibility, audio, hardware/status services,
complete daemon lifetime management, reserved desktop shortcuts and full
plugin compatibility remain open. Ando is verified in command operations;
it is not yet exposed to graphical session terminals. Linux network access
works, while NetworkManager/UPower/PipeWire status services are not connected
to Android. Work-app/Wallet behavior has not been tested in this new host.

Graphics APK: `.build-tools/remote-apks/omamobile-wayland-emulator-20261014.apk`
in the parent workspace, 29,307,172 bytes, SHA-256
`0961e66e223a2af96c3a37ac785939084b39c0e100dc88b21026cf3a8201770e`.
This is an emulator graphics build; its Linux image is prepared separately.

## Historical command-runtime milestone

The following records the earlier APK before the graphics integration.

## Automated checks

- ARM64 and x86_64 native runtime/client build from source.
- Seven JVM tests: per-output phone/desktop profiles share one installation; safe argv boundaries; invalid binds; traversal rejection; expanded-size rollback; wrong-architecture rollback; no replacement of existing Linux data.
- Four Android instrumentation checks on API 35 x86_64 emulator: secure import/link handling, native Linux rootfs execution through an imported hardlink alias with shared read/write and read-only binds, `ando` executing Android `id` as the app UID, and real Android virtual-display attachment/removal preserving the phone profile and runtime identity.
- Android lint: zero errors, five warnings (newer target/compile SDK and Android test dependencies available; optional storage-allocation API suggestion). No lint baseline.
- Debug and unsigned release APKs build successfully. Both include ARM64 and x86_64; the release omits the debug-only guest fixture.
- ARM64 ELF inspection: static `EXEC`, no dynamic interpreter, image base `0x2000000000`, 16 KiB load alignment. x86_64 uses upstream base `0x40000000`.

The guest fixture deliberately is not Arch: it tests the real runtime, filesystem translation, import and broker as a regular Android app. No `adb root`, `su`, security-policy change, process-limit change or device flashing is used.

## Real Arch userland

An additional opt-in instrumentation test imported the official Arch Linux x86_64 bootstrap dated 2026-10-01. The 127,355,946-byte zstd download was checked against the [official release SHA-256 list](https://geo.mirror.pkgbuild.com/iso/2026.10.01/sha256sums.txt): `0c9c97b7a58a8a023c9e07f84cb307a9e8006e65d8fc4c17b83ddc8010b45c10`. `tools/prepare-arch-emulator.py` removes its `root.x86_64/` archive wrapper and converts compression without extracting/running the image on Windows. This test used HTTPS plus the published checksum; no independent PGP verification is claimed.

Import plus real-userland test passed in 29.565 seconds on the final APK with Commons Compress 1.28.0. Arch's `/bin/sh`, `uname`, `/etc/os-release`, pacman 7.1.0/libalpm 16.0.1, package-database queries for bash/glibc, shared-file write and `ando /system/bin/id` all ran. Android reported UID 10228 and SELinux `untrusted_app`; Linux did not acquire Android root. The stock Android SELinux hardlink denial discovered in this test is handled by the reference-backed relative-symlink import fallback, with exact initial hardlink identity recorded as an outstanding compatibility gap. The bootstrap has no downloaded repository databases yet; pacman's query warns accordingly. Package installation/update is not claimed.

This proves a real Arch CLI userland, not Omarchy graphics or package-install/update compatibility. The ordinary test suite skips this opt-in image test. `RealUserlandTest` needs `-e archArchive arch-emulator.tar.gz`; rerunning against its existing installation also requires explicit `-e archReuse true`. It never replaces an existing rootfs. Android Gradle's connected-test runner uninstalls the test apps afterward, so use a throwaway emulator and run the opt-in test separately after installing both APKs.

The final APK was also launched through its visible Android activity. Its command button started the foreground Linux service, ran `uname`, OS identification, Linux `id` and a shared-directory listing, then returned exit 0. Android's activity-manager service dump showed no service remaining while idle. UI XML, screenshot and real-userland stdout are retained locally under `app/build/verification/` (excluded from Git).

Final debug APK: SHA-256 `e5179769aef1f52899b9c6d126aefad37d3875b1483be60bd3c97b6e5f7aaeee`, 12,942,313 bytes. Stable workspace copy: `.build-tools/remote-apks/omamobile-rootless-0.1.0-20261014.apk` in the parent workspace.

## Historical unverified list before graphics work

The original command-runtime APK did not verify graphics or interactive terminals. The graphics and physical-device milestones above supersede that status; full ecosystem and hardware/security/power checks remain open.
## APK payload binding

When `verify_release.py --apk` is used, every recorded external native library
for both ABIs and all three installer payloads are read from the APK and
compared with their staged manifest SHA-256 and byte metadata. Duplicate or
missing ZIP entries and malformed payload manifests fail closed. Android
platform libraries and AGP-supplied `libc++_shared.so` remain outside this
staged ownership check.

## Integrated developer-preview candidate (2026-10-19)

Private graphical APK SHA-256
`c9672e5d517562108fe86770c253baaf3fd900e3852437d0a983ea0cfd25d461`
combines shared audio, safe payload updates and native Squeekboard at functional
source commit `dbe7137c1cd8bc217201de4bc79509157c3a435d`. Later preview-guide
edits do not change its runtime code. Both ABI payloads passed mechanical
candidate matching; execution below was on a prepared API 35 x86_64 emulator.

Normal host controls passed busy-update rejection, custom-file conflict
preflight, replacement of 20 changed stock paths plus one missing profile, and
a repeated update with zero changes. Native audio playback worked before and
after updating. The audio owner survived compositor-mode switching and actual
external-display removal/recreation; main-process death cleaned it up. Phone
widgets and the desktop bar/background were inspected after reconnect.

The same APK passed all 11 audio instrumentation cases and the native keyboard
gate in separate and shared modes: automatic Mobile search input, Keyboard tile,
Foot editing/Enter, Ctrl/arrows/Backspace, Esc/Alt, native hide, phone-only workspace
reservation and one replacement keyboard after stop/reopen. Shared-mode symbol
input produced exactly `A1!π€√` in the terminal fixture. Both normal-control gates
restored the original APK, test APK where changed, rootfs identity, backed-up
files and Android grants; no Shizuku authorization was changed.

An isolated fixture additionally exercised the candidate's session-entry script
with the real Gio keyfile backend. It enabled missing defaults and preserved an
explicit disabled preference, custom layout and dangling layout link. Upstream
theme install/set/restore and session-settings persistence passed in a temporary
home. This headless theme check does not establish rendered theme appearance.
The fixture was removed and the original APK/user-file hashes remained identical.

Full graphical build, JVM/lint checks, canonical comparison of 1,309 payload
members and 19 socket-credential regressions passed. Source CI at `d5f6ec5`
passed host contracts and all 125 JVM cases with zero ignored tests. The private
source-evidence bundle was rebuilt and its 152 files rehashed; it retains SHA-256
`166309462514fa6b8a38469eba9faed5418751707faa7cf114b9020e7328040f`.
All seven public native source/relink holds remain. Physical ARM64 bootstrap,
audio audibility/routes, work-policy/Wallet compatibility and power behavior
still require the [device gate](DEVELOPER-PREVIEW.md).
