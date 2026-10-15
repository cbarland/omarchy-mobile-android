#!/usr/bin/env python3
"""Stage Android-native C dependency libraries; never run a package's install scripts.

These are compositor build dependencies, not the Linux userland. The Arch rootfs
continues to use ordinary glibc/pacman packages. Every downloaded deb is checked
against its HTTPS repository index and recorded with SHA-256 for reproduction.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.request
import fcntl

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--abi", choices=["x86_64", "aarch64"], required=True)
parser.add_argument("--directory", type=Path, required=True)
args = parser.parse_args()
base = args.directory.resolve()
base.mkdir(parents=True, exist_ok=True)
stage_lock = (base / ".native-stage.lock").open("w")
fcntl.flock(stage_lock, fcntl.LOCK_EX)
if (base / "termux-packages.json").exists():
    print("Native dependency prefix already staged:", base)
    raise SystemExit(0)
prefix = base / "ndk-prefix"
cache = base.parent / "downloads"
prefix.mkdir(parents=True, exist_ok=True)
cache.mkdir(parents=True, exist_ok=True)
packages = {}
for repo, distribution in [("termux-main", "stable"), ("termux-x11", "x11")]:
    origin = f"https://packages.termux.dev/apt/{repo}/"
    index = origin + f"dists/{distribution}/main/binary-{args.abi}/Packages.gz"
    index_file = cache / f"{repo}-{args.abi}-Packages.gz"
    if not index_file.exists():
        index_file.write_bytes(urllib.request.urlopen(index, timeout=60).read())
    for paragraph in gzip.decompress(index_file.read_bytes()).decode().split("\n\n"):
        fields = {}
        key = None
        for line in paragraph.splitlines():
            if line.startswith(" ") and key:
                fields[key] += " " + line.strip()
            elif ": " in line:
                key, value = line.split(": ", 1)
                fields[key] = value
        if "Package" in fields:
            packages[fields["Package"]] = {**fields, "Origin": origin}

requested = ["libwayland", "libwayland-protocols", "libdrm", "libpixman", "pango", "libcairo",
             "glib", "libxkbcommon", "libxcursor", "libpng", "fontconfig", "freetype",
             "libxcb", "libxml2", "libexpat", "libffi", "zlib", "bzip2"]
selected = {}
while requested:
    name = requested.pop()
    if name in selected:
        continue
    if name not in packages:
        raise SystemExit("Missing native dependency: " + name)
    package = packages[name]
    selected[name] = package
    for dependency in package.get("Depends", "").split(","):
        alternatives = [re.split(r"[ (:]", item.strip())[0] for item in dependency.split("|")]
        resolved = next((item for item in alternatives if item in packages), None)
        if dependency.strip() and resolved is None:
            raise SystemExit("Unresolved dependency: " + dependency)
        if resolved:
            requested.append(resolved)

manifest = []
for name, package in sorted(selected.items()):
    filename = package["Filename"]
    archive = cache / Path(filename).name
    digest = package["SHA256"]
    if not archive.exists():
        print("Downloading", name, package["Version"], flush=True)
        archive.write_bytes(urllib.request.urlopen(package["Origin"] + filename, timeout=120).read())
    if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
        raise SystemExit("Native package checksum mismatch: " + name)
    subprocess.run(["dpkg-deb", "-x", str(archive), str(base / "extracted")], check=True)
    manifest.append({"package": name, "version": package["Version"], "url": package["Origin"] + filename,
                     "sha256": digest, "homepage": package.get("Homepage", "")})
import shutil
source = base / "extracted/data/data/com.termux/files/usr"
shutil.copytree(source, prefix, dirs_exist_ok=True, symlinks=True)
for pc in prefix.rglob("*.pc"):
    pc.write_text(pc.read_text().replace("/data/data/com.termux/files/usr", str(prefix)))
(base / "termux-packages.json").write_text(json.dumps(manifest, indent=2) + "\n")
print("Staged", len(manifest), "native dependencies in", prefix)
