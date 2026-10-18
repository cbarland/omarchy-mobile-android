"""Prove the pinned native patch applies cleanly and repeats without changes."""
import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile

from patch_multi_output import apply, PIN

FILES = (
    "src/android/Embed.h",
    "src/android/Embed.cpp",
    "subprojects/aquamarine/include/aquamarine/backend/Android.hpp",
    "subprojects/aquamarine/src/backend/Android.cpp",
    "src/render/OpenGL.hpp",
    "src/render/AndroidEGL.cpp",
)


def hashes(root: Path) -> dict[str, str]:
    return {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in FILES}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prepared_source", type=Path, help="read-only prepared pinned source")
    parser.add_argument("private_repo", type=Path, help="private source tree at the same pin")
    args = parser.parse_args()
    # The scratch tree is inside private_repo so git rev-parse finds its
    # pinned metadata; no reference-source or shared build file is written.
    with tempfile.TemporaryDirectory(prefix="multi-output-proof-", dir=args.private_repo) as directory:
        scratch = Path(directory)
        for name in FILES:
            target = scratch / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(args.prepared_source / name, target)
        apply(scratch)
        first = hashes(scratch)
        apply(scratch)
        second = hashes(scratch)
        if first != second:
            raise RuntimeError("Second patch application changed output")
        negative = scratch / "changed-anchor"
        for name in FILES:
            target = negative / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(args.prepared_source / name, target)
        header = negative / "src/android/Embed.h"
        header.write_text(header.read_text().replace("int  anhyprland_window(", "int  changed_window(", 1))
        try:
            apply(negative)
        except RuntimeError:
            pass
        else:
            raise RuntimeError("Changed anchor was silently accepted")
        deletion = scratch / "changed-deletion"
        for name in FILES:
            target = deletion / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(args.prepared_source / name, target)
        backend_header = deletion / "subprojects/aquamarine/include/aquamarine/backend/Android.hpp"
        backend_header.write_text(backend_header.read_text().replace("bool m_frameScheduled = false;", "bool changedFrame = false;", 1))
        try:
            apply(deletion)
        except RuntimeError:
            pass
        else:
            raise RuntimeError("Changed deletion anchor was silently accepted")
        print(f"Pinned {PIN}: first and second application identical")
        print("Changed C API and deletion anchors rejected")
        for name, digest in first.items():
            print(f"{digest}  {name}")
