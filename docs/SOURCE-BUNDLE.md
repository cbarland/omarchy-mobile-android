# Termux source evidence bundle

`tools/native/prepare_termux_build_sources.py` packages the six recovered
official Termux workflow source trees: freetype, fribidi, glib, libcairo,
libiconv, and pango. It copies only the verified `actual-<commit>.tar.gz`
archives, the locked recipe and patch snapshot, and the supplied provenance
metadata. Graphite is excluded because its owned native builder has a separate
source and release-evidence path.

Example, using the private evidence snapshot supplied to the review:

```text
python3 tools/native/prepare_termux_build_sources.py \
  --evidence-root /path/to/source-audit \
  --repo-root . \
  --output /tmp/termux-build-source-evidence
```

The command verifies archive size and SHA-256 values from the actual-tree
manifest, rejects missing or unsafe inputs, emits a deterministic directory and
`termux-build-source-evidence.tar.gz`, and never executes recovered source or
build scripts. The output records that it is evidence for inspection and
independent rebuild or relink attempts; it does not prove binary provenance or
clear a release gate.
