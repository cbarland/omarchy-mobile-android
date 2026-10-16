# Release readiness

Audit date: 2026-10-15. No rootfs is
embedded in the APK: users download or supply an official archive,
then the host prepares packages in its private Linux installation.

Follow-up: the automatic x86_64 path now verifies the official detached
publisher signature before extraction and passed a fresh emulator installation
through both graphical shells. Automatic ARM64 installation is unverified.
The [source-input snapshot](SOURCE-INPUTS.md) now includes 38 hash-checked
archives, recipes, patches and notice inputs for seven audited dependencies.
The owned Graphite builder, Termux source-bundle tool and candidate-APK
verification provide build evidence and artifact integrity checks. Owned Graphite source
bundle delivery and relinking materials remain held, and all seven
corresponding-source/relink holds still block graphical APK publication.

## Before an installable APK release

- **Require the complete graphics payload.** A fresh checkout lacks ignored
  `jniLibs`, `prebuilt` and native assets. CMake currently skips its Hyprland
  target when the staged library is absent. `preReleaseBuild` runs before APK
  assembly and checks both ABIs, dependency closure, staged hashes,
  installer hashes, source pins and notices; it rejects incomplete or
  source-material-blocked releases. It cannot replace a final-candidate APK
  check. After building the candidate, match every externally staged library
  listed by both ABI manifests and all three installer payload files against
  the candidate APK, while retaining the eight required owner/runtime JNI ELF
  presence and ABI checks:

  ```text
  python tools/native/verify_release.py --project . --apk app/build/outputs/apk/debug/app-debug.apk --mechanical-only
  ```

  The candidate check binds staged external-library and installer bytes to the
  APK; it does not claim hash binding for host-built JNI binaries. The number
  of payload entries is an output detail, not a release contract. Debug core
  builds remain available for contributor CI.
- **Reconstruct native inputs.** `fetch_termux.py` now consumes committed package
  manifests as exact locks by default, failing on missing pinned packages.
  Explicit refresh resolves current indexes only into a fresh cache. Preserve
  the matching source recipes and patches, and exercise both ABIs from an empty
  documented Linux build environment. Stage into a fresh directory: the current
  staging script does not remove obsolete libraries from an earlier run.
- **Complete corresponding-source delivery.** Missing Aquamarine and Lua notices
  were recovered from their canonical pinned Git blobs. Seven Termux components
  had dangling `copyright` links into the shared `LICENSES` directory; the
  referenced texts were restored from official `termux-licenses` 2.2, checked
  against its cached repository-index SHA-256. `notice-sources.json` records
  origins and hashes. This repairs mechanical notice coverage, not the complete
  source/attribution inventory. Map each shipped library to
  its exact license, notices, source version, packaging changes and build recipe;
  provide the corresponding source and modification/relinking materials required
  by its applicable terms. The host's MIT license does not replace dependency
  licenses. GLib's [upstream COPYING](https://github.com/GNOME/glib/blob/main/COPYING)
  and Pango's [upstream COPYING](https://github.com/GNOME/pango/blob/main/COPYING)
  identify library copyleft licenses; verify the exact shipped versions.
  Run `python tools/native/verify_release.py` for JSON describing missing
  notices, package versions, staged file/hash errors, unexpected libraries and
  installer payload/script hashes and pinned source revisions.
  `passed` covers mechanical checks; `readyForPublicBinaryRelease` also requires
  version-specific corresponding-source records. The default command exits
  nonzero while either fails; `--mechanical-only` is available for diagnostics.
  Neither field certifies license compliance or completeness of source delivery.
  Candidate matching upstream archives and Termux recipes/patches are now locked
  by the source-input snapshot. Their relationship to the shipped binaries and
  complete distributable source bundles still needs to be established.
  `corresponding-sources.json` can record these inputs once they exist; the JSON
  result names the missing fields. A reference URL to the binary package or a
  generic license text does not supply them.
  The `tools/native/prepare_termux_build_sources.py` source-bundle tool
  packages the reviewed Termux build evidence using the command documented in
  [SOURCE-BUNDLE.md](SOURCE-BUNDLE.md). Its output is private release preparation;
  it does not itself satisfy corresponding-source or relinking delivery.
- **Separate integrity from publisher authentication.** A recorded SHA-256
  identifies bytes; it does not authenticate the bootstrap publisher. Before
  executing an imported archive in an automatic install path, verify its
  detached signature against an independently pinned public key. Exercise
  signature failure, wrong architecture, cancellation, failed provisioning and
  retry without overwriting an existing installation. Keep the current work,
  Wallet, GPU, service and lifecycle limits visible in release documentation.

## Arch Linux ARM bootstrap reference

The [official generic AArch64 page](https://archlinuxarm.org/platforms/armv8/generic)
names `ArchLinuxARM-aarch64-latest.tar.gz`. The
[official signing page](https://archlinuxarm.org/about/package-signing) publishes
the build-system fingerprint `68B3537F39A313B3E574D06777193F152BDBE6A6`.
The inspected TAWC reference uses
`https://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`
and its `.sig`, with the public key bundled in the APK and streaming OpenPGP
verification before use. This is an implementation reference, not evidence that
this host already authenticates imports. The initial installer may instead use
an archive independently verified by its user. Fail closed on an unexpected signer;
key rotation needs an explicit trust update. The current remote archive/signature
were not downloaded or verified during this audit.

## Public source checkpoint

The [source repository](https://github.com/cbarland/omarchy-mobile-android) is
public as of 2026-10-15, with private vulnerability reporting enabled. Contributor
setup distinguishes the core CI APK from the separately staged graphical build;
native patches, provenance and prominent upstream credits are published. Both
hosted validation jobs passed for the implementation checkpoint `c16b480`.

A pattern scan of the files tracked at the start of this audit found no
private-key blocks, common GitHub/provider/AWS credential patterns, credentialed
HTTP URLs or local Windows user paths. A follow-up scan across all six source
commits before publication found no matches for the same patterns. These checks
did not inspect credential stores or ignored artifacts and are not an exhaustive
secret or security audit. Public source publication does not clear the binary
release work above.
