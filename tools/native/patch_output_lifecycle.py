"""Apply the exact phase-two lifecycle delta after the pinned phase-one patch."""
import hashlib
import json
import argparse
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent


def apply(source: Path) -> None:
    hashes = json.loads((HERE / "output_lifecycle_hashes.json").read_text())
    normalized = {name: (source / name).read_bytes().replace(b"\r\n", b"\n") for name in hashes}
    current = {name: hashlib.sha256(data).hexdigest() for name, data in normalized.items()}
    if all(current[name] == expected["phase2"] for name, expected in hashes.items()):
        return
    mismatches = [name for name, expected in hashes.items() if current[name] != expected["phase1"]]
    if mismatches:
        raise RuntimeError(f"Phase-two input differs from reviewed phase-one source: {mismatches}")
    # The pinned source appears with both LF and CRLF checkouts. Apply one
    # canonical LF patch after validating the semantic source bytes.
    for name, data in normalized.items():
        (source / name).write_bytes(data)
    patch = HERE / "output_lifecycle.patch"
    subprocess.run(["git", "-C", str(source), "apply", "--whitespace=nowarn", "--check", str(patch)], check=True)
    subprocess.run(["git", "-C", str(source), "apply", "--whitespace=nowarn", str(patch)], check=True)
    actual = {name: hashlib.sha256((source / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest() for name in hashes}
    mismatches = [name for name, expected in hashes.items() if actual[name] != expected["phase2"]]
    if mismatches:
        raise RuntimeError(f"Phase-two patch output does not match reviewed hashes: {[(name, actual[name], hashes[name]['phase2']) for name in mismatches]}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    apply(parser.parse_args().source)
