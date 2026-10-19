#!/usr/bin/env python3
"""Build the pinned upstream Linux shell sources and setup scripts for the APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile


PINS = {
    "omarchy": ("https://github.com/omacom/omarchy.git", "e71a816e9032f040225e086c7256f73e8080ae45"),
    "mobile": ("https://github.com/derekross/omarchy-mobile.git", "953bcb3058347459cdf7faaa1e149f16e0f7cfa6"),
    "widgets": ("https://github.com/SimonSchubert/omarchy-widgets.git", "b63886b75e02ccef1a8a11baeef2d6eff7a374af"),
}
# AAPT transparently inflates .gz assets and strips that suffix. Keep the
# packaged compressed bytes unchanged so the runtime can verify their hash.
PAYLOAD = "linux-shell.tgz"
MANIFEST = "install-manifest.json"
SQUEEKBOARD_SOURCE = {
    "usr/share/omamobile/squeekboard/keyboards/terminal/us.yaml":
        "225f52782379d66c3929303749fb3a65bf97a8f6de35ffc37a3bd1e27d601cfa",
    "usr/share/licenses/squeekboard-terminal-layout/COPYING":
        "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
}


def run(command: list[str], *, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, check=True, text=True, capture_output=True, timeout=300)


def checkout(name: str, requested: Path | None, cache: Path) -> Path:
    _, pin = PINS[name]
    if requested is not None:
        path = requested.resolve()
        head = run(["git", "-C", str(path), "rev-parse", "HEAD"]).stdout.strip()
        if head != pin:
            raise SystemExit(f"{name} checkout is {head}, expected pinned {pin}")
        return path
    path = cache / name
    if not (path / ".git").is_dir():
        path.mkdir(parents=True, exist_ok=True)
        run(["git", "init", "--quiet"], cwd=path)
        run(["git", "remote", "add", "origin", PINS[name][0]], cwd=path)
    run(["git", "fetch", "--depth", "1", "origin", pin], cwd=path)
    run(["git", "checkout", "--detach", "FETCH_HEAD"], cwd=path)
    return path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def archive_entries(path: Path) -> list[dict[str, object]]:
    entries: list[dict[str, object]] = []
    with tarfile.open(path, "r:gz") as archive:
        for member in archive.getmembers():
            if not member.isfile():
                continue
            data = archive.extractfile(member).read()
            entries.append({"path": member.name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
    keyboard_source = {entry["path"]: entry["sha256"] for entry in entries
                       if "squeekboard" in entry["path"].lower()}
    if keyboard_source != SQUEEKBOARD_SOURCE:
        raise SystemExit("Squeekboard layout/source license differs from the pinned payload")
    return entries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/install"))
    parser.add_argument("--cache", type=Path, default=Path(".build-tools/sources"))
    parser.add_argument("--omarchy", type=Path)
    parser.add_argument("--mobile", type=Path)
    parser.add_argument("--widgets", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = (root / args.output).resolve() if not args.output.is_absolute() else args.output.resolve()
    cache = (root / args.cache).resolve() if not args.cache.is_absolute() else args.cache.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    cache.mkdir(parents=True, exist_ok=True)

    sources = {
        "omarchy": checkout("omarchy", args.omarchy, cache),
        "mobile": checkout("mobile", args.mobile, cache),
        "widgets": checkout("widgets", args.widgets, cache),
    }
    temp_root = Path(tempfile.mkdtemp(prefix="install-assets-", dir=str(output.parent)))
    try:
        temp_payload = temp_root / PAYLOAD
        prepare = root / "tools/prepare-linux-shell.py"
        command = [sys.executable, str(prepare), "--omarchy", str(sources["omarchy"]),
                   "--mobile", str(sources["mobile"]), "--widgets", str(sources["widgets"]),
                   "--output", str(temp_payload)]
        completed = subprocess.run(command, cwd=root, text=True, capture_output=True, timeout=300)
        if completed.returncode:
            raise SystemExit(completed.stderr or completed.stdout or "Linux shell packaging failed")

        payload_entries = archive_entries(temp_payload)
        script_entries = []
        for name in ("prepare-arch-graphics", "configure-omarchy-repository"):
            source = root / "tools/linux" / name
            destination = temp_root / name
            shutil.copyfile(source, destination)
            script_entries.append({"path": name, "sha256": sha256(destination), "bytes": destination.stat().st_size})

        manifest = {
            "format": 1,
            "pins": {name: {"url": PINS[name][0], "commit": PINS[name][1]} for name in PINS},
            "files": [{"path": PAYLOAD, "sha256": sha256(temp_payload), "bytes": temp_payload.stat().st_size}, *script_entries],
            "payloadMembers": payload_entries,
            "licensePayload": sorted(entry["path"] for entry in payload_entries if "/licenses/" in entry["path"]),
            "gzipMtimeControlled": False,
            "reproducibility": "byte reproducibility is not claimed for the gzip container",
        }
        (temp_root / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
        # Publish each completed artifact only after packaging, inspection and manifest generation succeed.
        output.mkdir(parents=True, exist_ok=True)
        for name in (PAYLOAD, "prepare-arch-graphics", "configure-omarchy-repository", MANIFEST):
            os.replace(temp_root / name, output / name)
        (output / "linux-shell.tar.gz").unlink(missing_ok=True)
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)
    print(json.dumps({"output": str(output), "manifest": str(output / MANIFEST)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
