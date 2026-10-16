#!/usr/bin/env python3
"""Prepare a verified, non-executable Termux source-input snapshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import time
import urllib.parse
import urllib.request
import re

HEX = set("0123456789abcdef")
CHUNK = 1024 * 1024
TIMEOUT = 120
ARCHIVE_LIMIT = 256 * 1024 * 1024
SMALL_LIMIT = 1024 * 1024


def validate_url(value, label):
    parsed = urllib.parse.urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{label} must be an HTTPS URL")


def safe_relative(value, label):
    raw_parts = value.split("/")
    path = PurePosixPath(value)
    if (not value or path.is_absolute() or any("\\" in part or ":" in part or part in ("", ".", "..") for part in raw_parts)
            or any(part in ("", ".", "..") for part in path.parts)):
        raise ValueError(f"{label} must be a safe relative POSIX path")
    return path


def validate(lock):
    if lock.get("format") != 1 or lock.get("snapshotKind") != "source-input-snapshot":
        raise ValueError("unsupported source-input lock format")
    license_info = lock.get("termuxLicense")
    if not isinstance(license_info, dict) or license_info.get("license") != "per-package":
        raise ValueError("lock must include the Termux per-package license notice")
    validate_url(license_info.get("url", ""), "Termux license")
    if len(license_info.get("sha256", "")) != 64 or any(c not in HEX for c in license_info["sha256"]):
        raise ValueError("invalid Termux license SHA-256")
    packages = lock.get("packages")
    if not isinstance(packages, list) or not packages:
        raise ValueError("lock packages must be a non-empty list")
    names, archives = set(), set()
    for package in packages:
        name = package.get("name") if isinstance(package, dict) else None
        if not isinstance(name, str) or not name.isidentifier() or name in names:
            raise ValueError("invalid or duplicate package name")
        names.add(name)
        if not isinstance(package.get("version"), str) or not package["version"]:
            raise ValueError(f"{name} has invalid version")
        for section in ("upstream", "recipe"):
            item = package.get(section)
            if not isinstance(item, dict) or not isinstance(item.get("url"), str):
                raise ValueError(f"{name} missing {section} URL")
            validate_url(item["url"], f"{name} {section}")
            checksum = item.get("sha256", "")
            if len(checksum) != 64 or any(c not in HEX for c in checksum):
                raise ValueError(f"{name} has invalid {section} SHA-256")
        archive_name = PurePosixPath(urllib.parse.urlparse(package["upstream"]["url"]).path).name
        safe_relative(archive_name, f"{name} archive filename")
        if archive_name in archives:
            raise ValueError("archive filename collision")
        archives.add(archive_name)
        recipe = package["recipe"]
        if not isinstance(recipe.get("commit"), str) or not re.fullmatch(r"[0-9a-f]{40}", recipe["commit"]):
            raise ValueError(f"{name} has invalid recipe commit")
        recipe_path = safe_relative(recipe.get("path", ""), f"{name} recipe path")
        if recipe_path.parts[:2] != ("packages", name):
            raise ValueError(f"{name} recipe path must be under packages/{name}")
        patches = package.get("patches", [])
        if not isinstance(patches, list):
            raise ValueError(f"{name} patches must be a list")
        patch_paths = set()
        for patch in patches:
            if not isinstance(patch, dict) or not isinstance(patch.get("path"), str) or not isinstance(patch.get("url"), str):
                raise ValueError(f"{name} has malformed patch")
            validate_url(patch["url"], f"{name} patch")
            path = safe_relative(patch["path"], f"{name} patch path")
            if path.parts[:2] != ("packages", name) or path in patch_paths:
                raise ValueError(f"{name} patch path is outside package or duplicated")
            patch_paths.add(path)
            checksum = patch.get("sha256", "")
            if len(checksum) != 64 or any(c not in HEX for c in checksum):
                raise ValueError(f"{name} has invalid patch SHA-256")
        package_root = PurePosixPath("packages", name)
        recipe_destination = recipe_path.relative_to(package_root)
        destinations = [recipe_destination]
        destinations.extend(path.relative_to(package_root) for path in patch_paths)
        for index, first in enumerate(destinations):
            for second in destinations[index + 1:]:
                if first == second or first in second.parents or second in first.parents:
                    raise ValueError(f"{name} recipe and patch destinations conflict")


def copy_stream(source, destination, expected, deadline, limit):
    digest = hashlib.sha256()
    total = 0
    with destination.open("xb") as output:
        while True:
            if time.monotonic() > deadline:
                raise TimeoutError("source input exceeded overall timeout")
            chunk = source.read(CHUNK)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                raise ValueError("source input exceeds byte limit")
            digest.update(chunk)
            output.write(chunk)
    actual = digest.hexdigest()
    if actual != expected:
        raise ValueError(f"SHA-256 mismatch: {actual}")


class HttpsRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        validate_url(newurl, "redirect target")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def fetch(url, destination, expected):
    deadline = time.monotonic() + TIMEOUT
    opener = urllib.request.build_opener(HttpsRedirects)
    with opener.open(url, timeout=15) as response:
        copy_stream(response, destination, expected, deadline, ARCHIVE_LIMIT if destination.parent.name == "archives" else SMALL_LIMIT)


def cache_candidate(cache, item):
    basename = PurePosixPath(urllib.parse.urlparse(item["url"]).path).name
    preferred = cache / Path(item.get("path", "")).parent.name / basename
    candidates = [preferred] if preferred.exists() else list(cache.rglob(basename))
    first_file = None
    for candidate in candidates:
        if candidate.is_file():
            first_file = first_file or candidate
            digest = hashlib.sha256()
            with candidate.open("rb") as source:
                for chunk in iter(lambda: source.read(CHUNK), b""):
                    digest.update(chunk)
            if digest.hexdigest() == item["sha256"]:
                return candidate
    return first_file


def fetch_checked(item, destination, cache=None, fetcher=fetch):
    candidate = cache_candidate(cache, item) if cache else None
    if candidate:
        with candidate.open("rb") as source:
            copy_stream(source, destination, item["sha256"], time.monotonic() + TIMEOUT, ARCHIVE_LIMIT if destination.parent.name == "archives" else SMALL_LIMIT)
    else:
        if cache:
            raise ValueError(f"verified cache input missing: {item['url']}")
        fetcher(item["url"], destination, item["sha256"])


def prepare(lock_path, output, cache=None, fetcher=fetch):
    lock = json.loads(Path(lock_path).read_text(encoding="utf-8"))
    validate(lock)
    output = Path(output)
    if os.path.lexists(str(output)):
        raise FileExistsError(f"refusing to overwrite existing snapshot: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}-", dir=output.parent))
    try:
        snapshot = {"format": 1, "snapshotKind": "source-input-snapshot", "termuxLicense": lock["termuxLicense"], "packages": []}
        license_item = lock["termuxLicense"]
        license_destination = temporary / "notices" / "TERMUX-LICENSE.md"
        license_destination.parent.mkdir(parents=True, exist_ok=True)
        fetch_checked(license_item, license_destination, cache, fetcher)
        for package in lock["packages"]:
            name = package["name"]
            archive_name = PurePosixPath(urllib.parse.urlparse(package["upstream"]["url"]).path).name
            upstream = temporary / "archives" / archive_name
            recipe_path = PurePosixPath(*package["recipe"]["path"].split("/")[2:])
            recipe = temporary / "recipes" / name / Path(recipe_path)
            upstream.parent.mkdir(parents=True, exist_ok=True)
            recipe.parent.mkdir(parents=True, exist_ok=True)
            fetch_checked(package["upstream"], upstream, cache, fetcher)
            fetch_checked(package["recipe"], recipe, cache, fetcher)
            patch_records = []
            for patch in package.get("patches", []):
                relative = PurePosixPath(*patch["path"].split("/")[2:])
                destination = temporary / "recipes" / name / Path(relative)
                destination.parent.mkdir(parents=True, exist_ok=True)
                fetch_checked(patch, destination, cache, fetcher)
                patch_records.append({"path": patch["path"], "sha256": patch["sha256"]})
            snapshot["packages"].append({"name": name, "version": package["version"], "upstream": package["upstream"], "recipe": {"commit": package["recipe"]["commit"], "path": package["recipe"]["path"], "sha256": package["recipe"]["sha256"]}, "patches": patch_records})
        (temporary / "snapshot.json").write_text(json.dumps(snapshot, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        os.rename(temporary, output)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=Path(__file__).with_name("source-inputs.lock.json"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cache", type=Path)
    args = parser.parse_args()
    prepare(args.lock, args.output, args.cache)
    print(json.dumps({"snapshot": str(args.output), "kind": "source-input-snapshot"}))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, TimeoutError, json.JSONDecodeError) as error:
        raise SystemExit(str(error)) from error
