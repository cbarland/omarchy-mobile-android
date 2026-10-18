"""Check phase-two patch replay, repeatability, and mutation rejection."""
import json
from pathlib import Path
import subprocess
import tempfile

from patch_output_lifecycle import apply
from generate_phase2_patch import original, SOURCE

HERE = Path(__file__).resolve().parent


def main():
    hashes = json.loads((HERE / "output_lifecycle_hashes.json").read_text())
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        for relative in hashes:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(original(relative))
        try:
            apply(root)
        except Exception:
            actual = (root / "src/devices/TouchDevice.cpp").read_bytes()
            expected = (SOURCE / "src/devices/TouchDevice.cpp").read_bytes()
            print("touch size/newlines", len(actual), actual.count(b"\r\n"), len(expected), expected.count(b"\r\n"))
            print("first difference", next(((i, actual[i:i+40], expected[i:i+40]) for i in range(min(len(actual), len(expected))) if actual[i] != expected[i]), None))
            raise
        apply(root)
        for relative in hashes:
            assert (root / relative).read_bytes() == (SOURCE / relative).read_bytes(), relative
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
    main()
