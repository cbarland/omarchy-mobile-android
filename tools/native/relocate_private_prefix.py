"""Relocate pkg-config paths after copying a native dependency prefix.

Run only on a private copy. This keeps CMake from mixing Aquamarine headers
from the original build with a rebuilt Aquamarine in the private prefix.
"""
import argparse
from pathlib import Path


def relocate(prefix: Path, old: str) -> int:
    if not (prefix / "lib/pkgconfig/aquamarine.pc").is_file():
        raise RuntimeError("Private prefix lacks aquamarine.pc")
    new = prefix.as_posix()
    changed = 0
    for path in prefix.rglob("*.pc"):
        text = path.read_text()
        if old in text:
            path.write_text(text.replace(old, new))
            changed += 1
    if old in (prefix / "lib/pkgconfig/aquamarine.pc").read_text():
        raise RuntimeError("Aquamarine prefix relocation failed")
    return changed


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prefix", type=Path)
    parser.add_argument("old_prefix")
    args = parser.parse_args()
    print(f"Relocated {relocate(args.prefix, args.old_prefix)} pkg-config files")
