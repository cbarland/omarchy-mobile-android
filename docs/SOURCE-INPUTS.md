# Native source-input snapshot

`tools/native/source-inputs.lock.json` records the seven native inputs currently
needed by the graphical APK payload. Each package records its upstream archive
URL and SHA-256, the pinned Termux `build.sh` commit/blob, and every pinned
Termux patch blob. The URLs and hashes are source attribution and input
integrity evidence; they do not attest that a shipped binary was built from
those inputs.

To prepare a local snapshot:

```powershell
python tools/native/prepare_source_inputs.py --output .build/native-source-inputs
# Or use a read-only directory of previously verified inputs:
python tools/native/prepare_source_inputs.py --cache <verified-cache> --output .build/native-source-inputs
```

The preparer validates the lock, downloads into a temporary sibling directory,
checks every SHA-256, writes a sorted `snapshot.json`, and renames the complete
staging directory once into a new destination. It refuses an existing output;
failed downloads, mismatches, or malformed locks never publish an output. A
cache is read-only and every cached byte is hashed on each use. The output path
is an exclusive single-writer destination for one preparation run. It never
extracts an archive or executes a recipe.

The result is a **source-input snapshot**, not a complete corresponding-source
distribution. Binary-to-recipe build attestation, the exact Termux build-system
revision, build logs, and a complete public corresponding-source bundle remain
release-gate evidence gaps.
