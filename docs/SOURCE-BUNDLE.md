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
