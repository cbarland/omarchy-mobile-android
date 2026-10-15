# Release readiness

Audit date: 2026-10-15. No rootfs is
redistributed by the installer: users supply an official archive,
then the host prepares packages in its private Linux installation.

## Before an installable APK release

- **Require the complete graphics payload.** A fresh checkout lacks ignored
  `jniLibs`, `prebuilt` and native assets. CMake currently skips its Hyprland
  target when the staged library is absent. `preReleaseBuild` now runs the
  verifier for both ABIs, dependency closure, native/installer hashes, source
  pins and notices; it rejects incomplete or source-material-blocked releases.
  Debug core builds remain available for contributor CI.
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
  The factual remaining gap is the seven package versions' matching upstream
  source archives, Termux recipe/patch commits and distributable source bundles.
  `corresponding-sources.json` can record these inputs once they exist; the JSON
  result names the missing fields. A reference URL to the binary package or a
  generic license text does not supply them.
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

## Before public source publication

Keep contributor setup and CI honest about the command-only build versus the
separately staged graphical build. Publish the native patches and provenance.
Review operational notes
in INTENT/VERIFICATION for personal context before making them public.

A pattern scan of the 450 files tracked at the start of this audit found no
private-key blocks, common GitHub/provider/AWS credential patterns, credentialed
HTTP URLs or local Windows user paths. This was a current tracked-file check,
not a credential-store, Git-history, ignored-artifact or exhaustive secret audit.
No credentials, device actions or GitHub mutations were used.
