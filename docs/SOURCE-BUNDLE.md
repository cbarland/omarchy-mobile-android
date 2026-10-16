# Termux source evidence bundle

`tools/native/prepare_termux_build_sources.py` packages the six recovered
official Termux workflow source trees: freetype, fribidi, glib, libcairo,
libiconv, and pango. It copies only the verified `actual-<40-hex-commit>.tar.gz`
archives, the seven lock-selected upstream archives and their lock-selected recipe
or patch files, and the reviewed per-package commit, artifact, and job metadata.
Graphite is excluded from the build trees because its owned native builder has a
separate source and release-evidence path; its lock-selected upstream input may
remain under `inputs/` for the complete historical input snapshot.

Example, using the private evidence snapshot supplied to the review:

```text
python3 tools/native/prepare_termux_build_sources.py \
  --evidence-root /path/to/source-audit \
  --repo-root . \
  --output /tmp/termux-build-source-evidence \
  --provenance-manifest /path/to/reviewed-provenance-manifest.json
```

The command verifies archive size and SHA-256 values from the actual-tree
manifest and lock, rejects missing, unsafe, duplicate, or symlinked inputs,
requires the separately reviewed provenance path-and-SHA-256 allowlist before
copying official metadata, emits a deterministic directory and
`termux-build-source-evidence.tar.gz`, and checks the tar contents before
publishing either output. To attempt an
independent rebuild or relink, unpack the matching tree, select its lock record,
verify its recipe and patches, then provide a separately reviewed Termux
toolchain and missing build-system objects. The bundle does not contain every
relink dependency, does not prove Debian package identity or shipped-binary
provenance, and never executes recovered source or build scripts.

Root verification on 2026-10-16 packaged six full build-tree archives, seven
locked upstream archives, 30 recipe/patch files and 102 explicitly reviewed
official provenance files. All 152 recorded files were rehashed; the original
12 ABI artifact-digest and job-log references were present in the bundle.
The resulting private archive SHA-256 is
`166309462514fa6b8a38469eba9faed5418751707faa7cf114b9020e7328040f`.
Nine focused tests pass on Windows and Linux, including a fresh-process check
that Python supplied through `--repo-root` is never executed. The archive is
retained as private release preparation; corresponding-source/relink delivery
and all public graphical APK release holds remain open.
