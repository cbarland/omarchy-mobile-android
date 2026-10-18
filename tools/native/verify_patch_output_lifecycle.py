"""Check phase-two patch replay, repeatability, and mutation rejection."""
import json
import argparse
from pathlib import Path
import subprocess
import tempfile

import patch_output_lifecycle
from patch_output_lifecycle import apply, prepare
from generate_phase2_patch import original, SOURCE

HERE = Path(__file__).resolve().parent


def main(committed=False):
    hashes = json.loads((HERE / "output_lifecycle_hashes.json").read_text())
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        if committed:
            patch = root / "output_lifecycle.patch"
            patch.write_bytes(subprocess.check_output(["git", "-C", str(SOURCE.parent), "show", "HEAD:tools/native/output_lifecycle.patch"]))
            (root / "output_lifecycle_hashes.json").write_bytes((HERE / "output_lifecycle_hashes.json").read_bytes())
            patch_output_lifecycle.HERE = root
        for relative in hashes:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(original(relative))
        apply(root)
        apply(root)
        prepare(root)
        for relative in hashes:
            assert (root / relative).read_bytes().replace(b"\r\n", b"\n") == (SOURCE / relative).read_bytes().replace(b"\r\n", b"\n"), relative
        target = root / "src/android/Embed.h"
        target.write_bytes(target.read_bytes() + b"\n// mutation\n")
        try:
            apply(root)
        except RuntimeError:
            pass
        else:
            raise AssertionError("Mutated patch output was accepted")
    print("phase-two patch replay, repeat, and mutation rejection passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--committed", action="store_true", help="Replay the committed patch blob")
    main(parser.parse_args().committed)
