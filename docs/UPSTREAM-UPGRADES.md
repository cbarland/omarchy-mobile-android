# Following Omarchy Mobile upstream

The adapter consumes reviewed commits, not a live checkout in the installed Linux
home. `tools/shell/sources.lock.json` is the single source of truth for Omarchy,
Mobile and widget pins. Packaging, installer manifests and the public binary gate
read the same lock. Checking a Mobile upgrade needs Git, Python 3.11+ and Node,
not an NDK, emulator, private cache, or model-provider account.

The candidate here advances Mobile's portable shell package. It does not install
the upstream Pixel kernel, initramfs, firmware, hardware daemons or every Mobile
application package. Android retains those hardware responsibilities.

## Check the current reviewed payload

From a clean source checkout:

```sh
python3 tools/prepare-install-assets.py --output app/build/shell-check/install
python3 tools/tests/test_unified_shell_payload.py app/build/shell-check/install/linux-shell.tgz
```

The first command fetches exact public commits and prints its checkout paths.
`Pinned upstream shell payload` runs this in PR CI, plus the explicit canonical
source test. Its `pinned-shell-source-payload` artifact contains source and
evidence, not an installable APK. The canonical test dirties disposable clones
and verifies local/index changes cannot enter the archive.

## Probe Mobile main without promoting it

```sh
python3 tools/check-upstream.py --mobile-ref main
```

This resolves `main` once to a full commit, builds reviewed and candidate payloads
in an isolated new directory, checks shared-output contracts and canonical Git
bytes, and reports added/removed/changed stock files. It saves the exact candidate
lock, step logs and `report.json`; the printed report path is the entry point.
Failure exits nonzero and preserves diagnostics. Reproduce a run with
`--mobile-ref FULL_40_CHARACTER_COMMIT`.

Optional `--output NEW_DIRECTORY` and `--cache DIRECTORY` select workspace paths.
An explicit output must be new. Completed checkouts are cached by project and
commit, so branches do not change one another's source HEAD. Concurrent runs need
separate outputs and runtime tests separate emulators. Reference worktrees are
read through `git archive`, preserving local edits.

The manually triggered **Mobile upstream compatibility** GitHub workflow runs
the same command and saves candidate sources/diagnostics. It has read-only
repository permissions; it does not change branches, publish an APK, install
Linux, or promote pins. There is no recurring schedule.

`prepare-install-assets.py --sources-lock candidate.json` requires a separate
explicit `--output`; it refuses the normal APK asset path. The ordinary release
gate continues to require the reviewed repository lock.

## Promote only after runtime and data checks

A passing report establishes packaging compatibility, not runtime or migration
safety. Review changed shell sources, dependencies and application manifests.
Inspect files this adapter excludes, especially hardware, systemd, credential
and flashing setup. Do not run Mobile's complete phone installer on Android.

1. Build an isolated candidate APK with the candidate payload and verified native
   build. Keep the old APK, certificate, rootfs identity and user backup. Native
   source/relink and signing gates still apply to public distribution.
2. Exercise **Update Linux integration** on a disposable existing installation:
   conflicts, active-owner rejection, idempotent retry, preserved profiles/widgets/
   themes/home, and newly required signed packages. Source updates do not run
   arbitrary upstream package-manager migrations automatically.
3. Observe both shells, launcher, native Squeekboard typing, shared cursor/focus,
   themes/plugins, reconnect and reopening. Record which ABI ran. Existing audio
   and platform gaps remain visible.
4. Update the Mobile commit in the reviewed lock, matching credits and current
   build docs. Keep historical release/evidence records tied to their original
   revisions. Retain the stock-file diff and runtime results with the PR. A Mobile
   bump does not advance Omarchy or widgets implicitly.

Prefer upstream extension points and plugins to expanding substitutions. When a
guarded source hash or insertion point changes, review that contract; do not bump
hashes merely to turn checks green. See [payload recovery](PAYLOAD-UPGRADES.md) and
the [developer preview gate](DEVELOPER-PREVIEW.md).
