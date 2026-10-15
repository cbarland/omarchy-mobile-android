"""Normalize a verified official Arch bootstrap for the x86_64 emulator.

Uses Windows' bundled libarchive tar to decode zstd, never extracts onto Windows.
Example: python prepare-arch-emulator.py archive.tar.zst --sha256 SHA --out arch-emulator.tar.gz
The checksum must come from the official Arch release; this does not verify PGP.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tarfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("archive", type=Path)
parser.add_argument("--sha256", required=True)
parser.add_argument("--out", type=Path, required=True)
args = parser.parse_args()
with args.archive.open("rb") as stream:
    actual = hashlib.file_digest(stream, "sha256").hexdigest()
if actual != args.sha256.lower():
    raise SystemExit("Bootstrap SHA-256 mismatch")
if args.archive.resolve() == args.out.resolve() or args.out.exists():
    raise SystemExit("Refusing to replace an existing file")
prefix = "root.x86_64/"
try:
    with subprocess.Popen(["C:/Windows/System32/tar.exe", "-cf", "-", "@" + str(args.archive.resolve())], stdout=subprocess.PIPE) as child:
        with tarfile.open(fileobj=child.stdout, mode="r|") as source, tarfile.open(args.out, mode="w:gz", compresslevel=1) as target:
            for entry in source:
                if entry.name.rstrip("/") == "root.x86_64" or entry.name in ("pkglist.x86_64.txt", "version"):
                    continue
                if not entry.name.startswith(prefix):
                    raise ValueError("Unexpected bootstrap wrapper: " + entry.name)
                original = entry.name
                data = source.extractfile(entry) if entry.isfile() else None
                entry.name = original[len(prefix):]
                if entry.islnk() and entry.linkname.startswith(prefix):
                    entry.linkname = entry.linkname[len(prefix):]
                target.addfile(entry, data)
        if child.wait() != 0:
            raise RuntimeError("Windows tar could not decode the archive")
except BaseException:
    args.out.unlink(missing_ok=True)
    raise
print(f"Prepared {args.out} from official bootstrap SHA-256 {actual}")
