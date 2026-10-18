"""Apply the exact phase-two lifecycle delta after the pinned phase-one patch."""
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent


def apply(source: Path) -> None:
    hashes = json.loads((HERE / "output_lifecycle_hashes.json").read_text())
    current = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in hashes}
    if all(current[name] == expected["phase2"] for name, expected in hashes.items()):
        return
    mismatches = [name for name, expected in hashes.items() if current[name] != expected["phase1"]]
    if mismatches:
        raise RuntimeError(f"Phase-two input differs from reviewed phase-one source: {mismatches}")
    patch = HERE / "output_lifecycle.patch"
    subprocess.run(["git", "-C", str(source), "apply", "--whitespace=nowarn", "--check", str(patch)], check=True)
    subprocess.run(["git", "-C", str(source), "apply", "--whitespace=nowarn", str(patch)], check=True)
    actual = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in hashes}
    mismatches = [name for name, expected in hashes.items() if actual[name] != expected["phase2"]]
    if mismatches:
        raise RuntimeError(f"Phase-two patch output does not match reviewed hashes: {[(name, actual[name], hashes[name]['phase2']) for name in mismatches]}")
