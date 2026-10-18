"""Record the reviewed phase-two source delta from archived phase-one inputs."""
import difflib
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / ".native-source"
ARCHIVE = ROOT / "phase1-archive/source"
FILES = (
    "src/android/Embed.cpp",
    "src/android/Embed.h",
    "src/devices/TouchDevice.cpp",
    "subprojects/aquamarine/include/aquamarine/backend/Android.hpp",
    "subprojects/aquamarine/src/backend/Android.cpp",
)


def original(relative):
    archived = ARCHIVE / relative
    if archived.exists():
        return archived.read_bytes()
    return subprocess.check_output(["git", "-C", str(SOURCE), "show", f"HEAD:{relative}"])


def main():
    patch = []
    hashes = {}
    for relative in FILES:
        before = original(relative).replace(b"\r\n", b"\n")
        after = (SOURCE / relative).read_bytes().replace(b"\r\n", b"\n")
        hashes[relative] = {
            "phase1": hashlib.sha256(before).hexdigest(),
            "phase2": hashlib.sha256(after).hexdigest(),
        }
        patch.extend(difflib.unified_diff(
            before.decode().splitlines(keepends=True),
            after.decode().splitlines(keepends=True),
            fromfile=f"a/{relative}", tofile=f"b/{relative}",
        ))
    target = Path(__file__).with_name("output_lifecycle.patch")
    target.write_text("".join(patch), newline="\n")
    Path(__file__).with_name("output_lifecycle_hashes.json").write_text(
        json.dumps(hashes, indent=2, sort_keys=True) + "\n", newline="\n"
    )


if __name__ == "__main__":
    main()
