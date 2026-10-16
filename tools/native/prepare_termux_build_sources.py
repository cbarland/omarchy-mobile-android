#!/usr/bin/env python3
"""Build a portable, non-executing evidence bundle for six Termux trees."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import tempfile
import re
import sys

CHUNK = 1024 * 1024
EXPECTED_PACKAGES = {"freetype", "fribidi", "glib", "libcairo", "libiconv", "pango"}


def fail(message):
    raise ValueError(message)


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def safe_relative(value):
    path = PurePosixPath(value)
    if not value or path.is_absolute() or any(part in ("", ".", "..") or "\\" in part or ":" in part
                                               for part in value.split("/")):
        fail("unsafe relative path: " + value)
    return path


def copy_file(source, destination):
    if not source.is_file() or source.is_symlink():
        fail("evidence input is not a regular file: " + str(source))
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(0o644)


def validate_regular_tree(source):
    if source.is_symlink() or not source.is_dir():
        fail("evidence directory is not a real directory: " + str(source))
    for path in source.rglob("*"):
        info = path.lstat()
        if stat.S_ISLNK(info.st_mode) or not (stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode)):
            fail("evidence tree contains an unsafe entry: " + str(path))


def load_tree_manifest(evidence):
    manifest_path = evidence / "termux-full/actual-tree-archive-manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        entries = manifest["archives"]
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise ValueError("invalid actual tree archive manifest") from error
    if {entry.get("package") for entry in entries} != EXPECTED_PACKAGES or len(entries) != 6:
        fail("actual archive manifest must contain exactly the six non-Graphite packages")
    for entry in entries:
        archive = entry.get("archive", "")
        commit = entry.get("commit", "")
        if not isinstance(archive, str) or not re.fullmatch(r"actual-[0-9a-f]{40}\.tar\.gz", archive):
            fail("actual archive manifest has an invalid archive name")
        if commit != archive[7:-7] or not re.fullmatch(r"[0-9a-f]{40}", commit):
            fail("actual archive name is not bound to its reviewed commit")
        safe_relative(archive)
        if type(entry.get("bytes")) is not int or entry["bytes"] <= 0 or not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")):
            fail("actual archive manifest has invalid size or digest")
    return manifest


def selected_provenance(evidence, repo_root):
    sources = {
        "actual-tree-archive-manifest.json": evidence / "termux-full/actual-tree-archive-manifest.json",
        "actual-input-blob-verification.json": evidence / "termux-full/actual-input-blob-verification.json",
        "input-blob-verification.json": evidence / "termux-full/input-blob-verification.json",
        "source-inputs.lock.json": repo_root / "tools/native/source-inputs.lock.json",
        "prepare_source_inputs.py": Path(__file__).with_name("prepare_source_inputs.py"),
    }
    for name, source in sources.items():
        if not source.exists() or source.is_symlink():
            fail("missing provenance input: " + name)
        if source.is_dir():
            validate_regular_tree(source)
    return sources


def source_inputs(evidence, repo_root):
    lock_path = repo_root / "tools/native/source-inputs.lock.json"
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    validator_dir = str(Path(__file__).resolve().parent)
    sys.path.insert(0, validator_dir)
    try:
        from prepare_source_inputs import validate
    finally:
        sys.path.pop(0)
    validate(lock)
    archives = evidence / "archives"
    validate_regular_tree(archives)
    selected = []
    for package in lock["packages"]:
        expected = package["upstream"]["sha256"]
        matches = [path for path in archives.iterdir() if path.is_file() and digest(path) == expected]
        if not matches:
            fail("source-input archive is missing: " + package["name"])
        preferred = [path for path in matches if "sfdownload" in path.name]
        selected.append((package["name"], sorted(preferred or matches)[0]))
    return lock, selected


def selected_recipe_files(evidence, lock):
    root = evidence / "termux-recipes"
    hashes_path = root / "blob-hashes.txt"
    if not hashes_path.is_file() or hashes_path.is_symlink():
        fail("missing reviewed recipe blob hashes")
    reviewed = {}
    for line in hashes_path.read_text(encoding="utf-8").splitlines():
        fields = line.split()
        if len(fields) != 3 or not re.fullmatch(r"[0-9a-f]{64}", fields[2]):
            fail("invalid reviewed recipe blob hash record")
        relative = safe_relative(fields[0]).as_posix()
        if relative in reviewed or not fields[1].isdigit():
            fail("duplicate or invalid reviewed recipe blob hash")
        reviewed[relative] = (int(fields[1]), fields[2])
    selected = []
    for package in lock["packages"]:
        entries = [package["recipe"]] + package.get("patches", [])
        for entry in entries:
            path = entry["path"]
            if not path.startswith("packages/"):
                fail("recipe path is outside packages: " + path)
            relative = safe_relative(path[len("packages/"):]).as_posix()
            source = root / relative
            expected = reviewed.get(relative)
            if expected is None or not source.is_file() or source.is_symlink():
                fail("locked recipe blob is missing: " + relative)
            if source.stat().st_size != expected[0] or digest(source) != expected[1] or digest(source) != entry["sha256"]:
                fail("locked recipe blob does not match its reviewed hash: " + relative)
            selected.append((relative, source))
    return selected


def validate_commit_records(evidence, manifest, lock):
    root = evidence / "sol/official-checksums"
    commits = [entry["commit"] for entry in manifest["archives"]]
    for commit in set(commits):
        record = root / ("commit-" + commit + ".json")
        if not record.is_file() or record.is_symlink():
            fail("missing reviewed commit provenance: " + commit)
        try:
            data = json.loads(record.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ValueError("invalid reviewed commit provenance: " + commit) from error
        if data.get("sha") != commit:
            fail("reviewed commit provenance does not match its filename: " + commit)


def copy_provenance(sources, destination):
    for name, source in sources.items():
        target = destination / name
        if source.is_dir():
            shutil.copytree(source, target, symlinks=False)
        else:
            copy_file(source, target)


def selected_official_files(evidence, manifest, lock, provenance_manifest):
    root = evidence / "sol/official-checksums"
    validate_regular_tree(root)
    try:
        entries = json.loads(Path(provenance_manifest).read_text(encoding="utf-8"))["files"]
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise ValueError("invalid provenance manifest") from error
    selected = []
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str) or not re.fullmatch(r"[0-9a-f]{64}", entry.get("sha256", "")):
            fail("invalid provenance manifest entry")
        relative = safe_relative(entry["path"]).as_posix()
        if relative in seen:
            fail("duplicate provenance manifest entry: " + relative)
        seen.add(relative)
        path = root / relative
        if not path.is_file() or path.is_symlink() or digest(path) != entry["sha256"]:
            fail("provenance file does not match manifest: " + relative)
        selected.append(path)
    required = {"commit-" + entry["commit"] + ".json" for entry in manifest["archives"]}
    if not required.issubset(seen):
        fail("provenance manifest omits a required commit record")
    return selected


def package_records(manifest, lock, inputs, official):
    locked = {package["name"]: package for package in lock["packages"]}
    input_paths = {package: "inputs/archives/" + package + "/" + source.name for package, source in inputs}
    generic_artifacts = {path.name for path in official if path.name.startswith("artifact-")}
    records = []
    for entry in sorted(manifest["archives"], key=lambda item: item["package"]):
        package = entry["package"]
        lock_package = locked.get(package)
        if lock_package is None:
            fail("actual tree has no locked package record: " + package)
        recipe_commit = lock_package["recipe"]["commit"]
        names = {path.name for path in official
                 if package in path.name or recipe_commit in path.name or entry["commit"] in path.name}
        names.update(generic_artifacts)
        records.append({"name": package, "actualArchive": "build-trees/" + package + "/" + entry["archive"],
                        "actualCommit": entry["commit"], "actualTree": entry["tree"],
                        "upstream": {"path": input_paths[package],
                                     "sha256": lock_package["upstream"]["sha256"]},
                        "recipe": {"commit": recipe_commit, "path": lock_package["recipe"]["path"],
                                   "sha256": lock_package["recipe"]["sha256"]},
                        "provenance": sorted(names)})
    return records


def bundle_files(root):
    result = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            fail("bundle contains a symlink: " + str(path))
        if path.is_file():
            result.append(path)
    return result


def file_records(root):
    records = []
    for path in bundle_files(root):
        relative = path.relative_to(root).as_posix()
        records.append({"path": relative, "bytes": path.stat().st_size,
                        "mode": stat.S_IMODE(path.stat().st_mode), "sha256": digest(path)})
    return records


def write_deterministic_tar(source, target):
    with target.open("wb") as output, gzip.GzipFile(fileobj=output, mode="wb", filename="", mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
            directories = sorted({path.parent for path in bundle_files(source) if path.parent != source})
            for directory in directories:
                info = tarfile.TarInfo(directory.relative_to(source).as_posix() + "/")
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                info.mtime = 0
                info.uid = info.gid = 0
                archive.addfile(info)
            for path in bundle_files(source):
                info = tarfile.TarInfo(path.relative_to(source).as_posix())
                info.size = path.stat().st_size
                info.mode = 0o644
                info.mtime = 0
                info.uid = info.gid = 0
                with path.open("rb") as data:
                    archive.addfile(info, data)


def verify_tar(target, records, manifest_bytes):
    expected = {record["path"]: record for record in records}
    with tarfile.open(target, "r:gz") as archive:
        files = {}
        seen = set()
        for member in archive.getmembers():
            if member.name in seen:
                fail("portable archive contains a duplicate member: " + member.name)
            seen.add(member.name)
            path = safe_relative(member.name.rstrip("/")) if member.name.rstrip("/") else None
            if member.issym() or member.islnk() or (path and path.parts[0] == ".."):
                fail("portable archive contains an unsafe member: " + member.name)
            if not member.isdir() and not member.isfile():
                fail("portable archive contains an unsupported member: " + member.name)
            if member.isdir() and member.mode != 0o755:
                fail("portable archive directory mode is not canonical: " + member.name)
            if member.isfile():
                if member.mode != 0o644:
                    fail("portable archive file mode is not canonical: " + member.name)
                files[member.name] = (member.size, hashlib.sha256(archive.extractfile(member).read()).hexdigest())
        require_files = set(expected) | {"manifest.json"}
        if set(files) != require_files:
            fail("portable archive file set differs from the bundle manifest")
        for path, record in expected.items():
            if files[path] != (record["bytes"], record["sha256"]):
                fail("portable archive content differs: " + path)
        manifest_member = archive.getmember("manifest.json")
        if archive.extractfile(manifest_member).read() != manifest_bytes:
            fail("portable archive manifest differs from the staged manifest")


def prepare(evidence, repo_root, output, provenance_manifest=None):
    evidence = Path(evidence).resolve()
    repo_root = Path(repo_root).resolve()
    output = Path(output).resolve()
    archive_output = output.with_suffix(".tar.gz")
    if output.exists() or output.is_symlink() or archive_output.exists() or archive_output.is_symlink():
        fail("refusing to overwrite bundle: " + str(output))
    manifest = load_tree_manifest(evidence)
    sources = selected_provenance(evidence, repo_root)
    lock, inputs = source_inputs(evidence, repo_root)
    validate_commit_records(evidence, manifest, lock)
    if provenance_manifest is None:
        fail("a reviewed provenance manifest is required")
    official = selected_official_files(evidence, manifest, lock, provenance_manifest)
    package_metadata = package_records(manifest, lock, inputs, official)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".termux-source-bundle-", dir=output.parent))
    tar_fd, tar_name = tempfile.mkstemp(prefix="." + archive_output.name + ".", suffix=".tmp", dir=output.parent)
    os.close(tar_fd)
    temporary_tar = Path(tar_name)
    published_output = False
    try:
        trees = temporary / "build-trees"
        for entry in manifest["archives"]:
            source = evidence / "termux-full/archives" / entry["archive"]
            if not source.is_file() or source.is_symlink():
                fail("missing actual archive: " + entry["archive"])
            if source.stat().st_size != entry["bytes"] or digest(source) != entry["sha256"]:
                fail("actual archive does not match manifest: " + entry["package"])
            copy_file(source, trees / entry["package"] / entry["archive"])
        input_root = temporary / "inputs"
        for package, source in inputs:
            copy_file(source, input_root / "archives" / package / source.name)
        copy_file(repo_root / "tools/native/source-inputs.lock.json", input_root / "source-inputs.lock.json")
        recipes = selected_recipe_files(evidence, lock)
        for relative, source in recipes:
            copy_file(source, input_root / "recipes" / relative)
        copy_provenance(sources, temporary / "provenance")
        for path in official:
            copy_file(path, temporary / "provenance/official-checksums" / path.name)
        (temporary / "BUILD-SOURCES.md").write_text(
            "# Termux build source evidence\n\n"
            "This bundle contains six pinned workflow source trees, the locked Termux "
            "recipe material, and provenance metadata. To attempt a rebuild or relink, "
            "unpack the six trees, select the matching package record in "
            "inputs/source-inputs.lock.json, verify the recipe and patch blobs under "
            "inputs/recipes, and use a separately reviewed Termux toolchain. This "
            "bundle does not supply all build-system objects or relink dependencies. "
            "It does not establish that shipped binaries came from these trees, and "
            "this tool never executes recovered build scripts. Review the manifest "
            "before any separate build.\n",
            encoding="utf-8")
        records = file_records(temporary)
        bundle_manifest = {"format": 2, "kind": "termux-build-source-evidence",
                           "packages": package_metadata,
                           "limitations": ["No build scripts were executed.",
                                          "Source evidence does not prove shipped binaries were built from these trees."],
                           "files": records}
        (temporary / "manifest.json").write_text(json.dumps(bundle_manifest, sort_keys=True, indent=2) + "\n",
                                                  encoding="utf-8")
        write_deterministic_tar(temporary, temporary_tar)
        verify_tar(temporary_tar, records, (temporary / "manifest.json").read_bytes())
        shutil.move(str(temporary), str(output))
        published_output = True
        shutil.move(str(temporary_tar), str(archive_output))
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        temporary_tar.unlink(missing_ok=True)
        if published_output:
            shutil.rmtree(output, ignore_errors=True)
        elif output.exists() and output.is_dir() and not (output / "manifest.json").exists():
            shutil.rmtree(output, ignore_errors=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--provenance-manifest", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.evidence_root, args.repo_root, args.output, args.provenance_manifest)
    print(json.dumps({"bundle": str(args.output), "tar": str(args.output.with_suffix(".tar.gz"))}))


if __name__ == "__main__":
    main()
