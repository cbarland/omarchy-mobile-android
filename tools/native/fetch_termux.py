#!/usr/bin/env python3
"""Stage committed locked Android-native packages; never run package scripts.

--refresh resolves current HTTPS indexes into a fresh stage for lock review.
The Android-native prefix is separate from the ordinary Arch Linux guest.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
REQUESTED = ["libwayland", "libwayland-protocols", "libdrm", "libpixman", "pango", "libcairo",
             "glib", "libxkbcommon", "libxcursor", "libpng", "fontconfig", "freetype",
             "libxcb", "libxml2", "libexpat", "libffi", "zlib", "bzip2"]


def default_lock(abi):
    apk_abi = "arm64-v8a" if abi == "aarch64" else abi
    return ROOT / "app/src/main/assets/licenses/native" / apk_abi / "termux-packages.json"


def validate_lock(entries, abi):
    if not isinstance(entries, list) or not entries:
        raise ValueError("Native dependency lock must be a nonempty JSON list")
    names, filenames = set(), set()
    specific = False
    result = []
    for entry in entries:
        if not isinstance(entry, dict):
            raise ValueError("Native dependency lock entries must be objects")
        for field in ("package", "version", "url", "sha256"):
            if not isinstance(entry.get(field), str) or not entry[field]:
                raise ValueError("Native dependency lock is missing " + field)
        name, version, url, digest = (entry[key] for key in ("package", "version", "url", "sha256"))
        if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", name):
            raise ValueError("Invalid native package name: " + name)
        if not re.fullmatch(r"[0-9][0-9A-Za-z.+:~-]*", version):
            raise ValueError("Invalid native package version: " + name)
        if name in names:
            raise ValueError("Duplicate native package: " + name)
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError("Invalid SHA-256 for " + name)
        parsed = urllib.parse.urlsplit(url)
        path = urllib.parse.unquote(parsed.path)
        if (parsed.scheme != "https" or parsed.netloc != "packages.termux.dev" or parsed.query or parsed.fragment
                or not path.startswith(("/apt/termux-main/pool/", "/apt/termux-x11/pool/"))
                or "\\" in path or any(part in {".", ".."} for part in path.split("/"))):
            raise ValueError("Invalid HTTPS native package URL: " + name)
        filename = path.rsplit("/", 1)[-1]
        if filename in filenames:
            raise ValueError("Duplicate native package filename: " + filename)
        if filename == f"{name}_{version}_{abi}.deb":
            specific = True
        elif filename != f"{name}_{version}_all.deb":
            raise ValueError(f"Native lock ABI/filename mismatch for {name}: expected {abi} or all")
        names.add(name)
        filenames.add(filename)
        result.append({**entry, "homepage": entry.get("homepage", "")})
    if not specific:
        raise ValueError("Native dependency lock has no package establishing requested ABI " + abi)
    return sorted(result, key=lambda item: item["package"])


def load_lock(path, abi):
    try:
        return validate_lock(json.loads(path.read_text(encoding="utf-8")), abi)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise ValueError(f"Invalid native dependency lock {path}: {error}") from error


def read_https(url):
    class HttpsRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, response, code, message, headers, redirected):
            if urllib.parse.urlsplit(redirected).scheme != "https":
                raise ValueError("Refusing non-HTTPS native package redirect")
            return super().redirect_request(request, response, code, message, headers, redirected)
    try:
        with urllib.request.build_opener(HttpsRedirect()).open(url, timeout=120) as response:
            if urllib.parse.urlsplit(response.geturl()).scheme != "https":
                raise ValueError("Refusing non-HTTPS native package response")
            return response.read()
    except urllib.error.HTTPError as error:
        raise ValueError(f"Locked native package unavailable at {url} (HTTP {error.code}); no latest-version fallback") from error


def download(entry, cache):
    filename = urllib.parse.unquote(urllib.parse.urlsplit(entry["url"]).path).rsplit("/", 1)[-1]
    archive = cache / filename
    data = archive.read_bytes() if archive.exists() else read_https(entry["url"])
    if hashlib.sha256(data).hexdigest() != entry["sha256"]:
        raise ValueError("Native package checksum mismatch: " + entry["package"])
    if not archive.exists():
        # ABI stages share downloads. Never publish a half-written _all.deb.
        with tempfile.NamedTemporaryFile(dir=cache, prefix=".package-", delete=False) as temporary:
            temporary.write(data)
            temporary_path = Path(temporary.name)
        try:
            os.replace(temporary_path, archive)
        finally:
            temporary_path.unlink(missing_ok=True)
    return archive


def paragraphs(data):
    for paragraph in data.split("\n\n"):
        fields, key = {}, None
        for line in paragraph.splitlines():
            if line.startswith(" ") and key:
                fields[key] += " " + line.strip()
            elif ": " in line:
                key, value = line.split(": ", 1)
                fields[key] = value
        if fields:
            yield fields


def resolve_latest(abi):
    packages = {}
    for repo, distribution in (("termux-main", "stable"), ("termux-x11", "x11")):
        origin = f"https://packages.termux.dev/apt/{repo}/"
        url = origin + f"dists/{distribution}/main/binary-{abi}/Packages.gz"
        for fields in paragraphs(gzip.decompress(read_https(url)).decode("utf-8")):
            if "Package" in fields:
                packages[fields["Package"]] = {**fields, "Origin": origin}
    requested, selected = list(REQUESTED), {}
    while requested:
        name = requested.pop()
        if name in selected:
            continue
        if name not in packages:
            raise ValueError("Missing native dependency in refreshed indexes: " + name)
        package = packages[name]
        for key in ("Version", "Filename", "SHA256"):
            if not package.get(key):
                raise ValueError(f"Refreshed native package {name} is missing {key}")
        selected[name] = package
        for dependency in package.get("Depends", "").split(","):
            alternatives = [re.split(r"[ (:]", item.strip())[0] for item in dependency.split("|")]
            resolved = next((item for item in alternatives if item in packages), None)
            if dependency.strip() and resolved is None:
                raise ValueError("Unresolved refreshed dependency: " + dependency)
            if resolved:
                requested.append(resolved)
    return validate_lock([{"package": name, "version": package["Version"],
                           "url": package["Origin"] + package["Filename"], "sha256": package["SHA256"],
                           "homepage": package.get("Homepage", "")} for name, package in selected.items()], abi)


def stage(entries, base, abi):
    prefix, cache = base / "ndk-prefix", base.parent / "downloads"
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".termux-", dir=base) as directory:
        work = Path(directory)
        for entry in entries:
            archive = download(entry, cache)
            control = next(paragraphs(subprocess.check_output(["dpkg-deb", "-f", str(archive)], text=True)), {})
            filename = urllib.parse.unquote(urllib.parse.urlsplit(entry["url"]).path).rsplit("/", 1)[-1]
            expected_arch = "all" if filename.endswith("_all.deb") else abi
            if (control.get("Package") != entry["package"] or control.get("Version") != entry["version"]
                    or control.get("Architecture") != expected_arch):
                raise ValueError("Native package control metadata differs from lock: " + entry["package"])
            subprocess.run(["dpkg-deb", "-x", str(archive), str(work / "extracted")], check=True)
        staged = work / "ndk-prefix"
        shutil.copytree(work / "extracted/data/data/com.termux/files/usr", staged, symlinks=True)
        for pc in staged.rglob("*.pc"):
            pc.write_text(pc.read_text().replace("/data/data/com.termux/files/usr", str(prefix)))
        staged.rename(prefix)
    (base / "termux-packages.json").write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
    print("Staged", len(entries), "locked native dependencies in", prefix)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=["x86_64", "aarch64"], required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--lock", type=Path, help="Override the committed per-ABI manifest")
    parser.add_argument("--refresh", action="store_true", help="Resolve current indexes in a fresh staging directory for lock review")
    args = parser.parse_args(argv)
    if sys.platform != "linux":
        parser.error("Native dependency extraction requires Linux/WSL and dpkg-deb")
    if args.refresh and args.lock:
        parser.error("--refresh and --lock are mutually exclusive")
    base = args.directory.resolve()
    # Validate lock and ABI before any staging writes.
    entries = None if args.refresh else load_lock(args.lock or default_lock(args.abi), args.abi)
    base.mkdir(parents=True, exist_ok=True)
    import fcntl
    with (base / ".native-stage.lock").open("a") as stage_lock:
        fcntl.flock(stage_lock, fcntl.LOCK_EX)
        manifest, prefix = base / "termux-packages.json", base / "ndk-prefix"
        if manifest.exists():
            if args.refresh:
                raise ValueError("--refresh requires a fresh --directory; review the new manifest before replacing a committed lock")
            if load_lock(manifest, args.abi) != entries:
                raise ValueError("Existing native stage differs from selected lock; use a fresh --directory")
            if not prefix.is_dir():
                raise ValueError("Recorded native stage is incomplete; use a fresh --directory")
            print("Native dependency prefix matches lock:", base)
            return
        if prefix.exists():
            raise ValueError("Unrecorded native dependency prefix exists; use a fresh --directory")
        if args.refresh:
            entries = resolve_latest(args.abi)
        stage(entries, base, args.abi)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
