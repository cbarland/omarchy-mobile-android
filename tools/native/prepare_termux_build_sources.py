#!/usr/bin/env python3
"""Build a portable, non-executing evidence bundle for six Termux trees."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import tarfile
import tempfile

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
    destination.chmod(stat.S_IMODE(source.stat().st_mode))


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
        if not isinstance(entry.get("archive"), str) or not entry["archive"].startswith("actual-"):
            fail("actual archive manifest has an invalid archive name")
        if len(entry.get("sha256", "")) != 64 or entry["bytes"] <= 0:
            fail("actual archive manifest has invalid size or digest")
    return manifest


def selected_provenance(evidence, repo_root):
    sources = {
        "actual-tree-archive-manifest.json": evidence / "termux-full/actual-tree-archive-manifest.json",
        "actual-input-blob-verification.json": evidence / "termux-full/actual-input-blob-verification.json",
        "input-blob-verification.json": evidence / "termux-full/input-blob-verification.json",
        "recipes": evidence / "termux-recipes",
        "source-inputs.lock.json": repo_root / "tools/native/source-inputs.lock.json",
        "prepare_source_inputs.py": repo_root / "tools/native/prepare_source_inputs.py",
        "official-checksums": evidence / "sol/official-checksums",
    }
    for name, source in sources.items():
        if not source.exists() or source.is_symlink():
            fail("missing provenance input: " + name)
    return sources


def copy_provenance(sources, destination):
    for name, source in sources.items():
        target = destination / name
        if source.is_dir():
            shutil.copytree(source, target, symlinks=False)
        else:
            copy_file(source, target)


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
                info.mode = stat.S_IMODE(directory.stat().st_mode)
                info.mtime = 0
                info.uid = info.gid = 0
                archive.addfile(info)
            for path in bundle_files(source):
                info = tarfile.TarInfo(path.relative_to(source).as_posix())
                info.size = path.stat().st_size
                info.mode = stat.S_IMODE(path.stat().st_mode)
                info.mtime = 0
                info.uid = info.gid = 0
                with path.open("rb") as data:
                    archive.addfile(info, data)


def verify_tar(target, records):
    expected = {record["path"]: record for record in records}
    with tarfile.open(target, "r:gz") as archive:
        files = {}
        for member in archive.getmembers():
            path = safe_relative(member.name.rstrip("/")) if member.name.rstrip("/") else None
            if member.issym() or member.islnk() or (path and path.parts[0] == ".."):
                fail("portable archive contains an unsafe member: " + member.name)
            if member.isfile():
                files[member.name] = (member.size, hashlib.sha256(archive.extractfile(member).read()).hexdigest())
        require_files = set(expected) | {"manifest.json"}
        if set(files) != require_files:
            fail("portable archive file set differs from the bundle manifest")
        for path, record in expected.items():
            if files[path] != (record["bytes"], record["sha256"]):
                fail("portable archive content differs: " + path)


def prepare(evidence, repo_root, output):
    evidence = Path(evidence).resolve()
    repo_root = Path(repo_root).resolve()
    output = Path(output).resolve()
    if output.exists() or output.is_symlink():
        fail("refusing to overwrite bundle: " + str(output))
    manifest = load_tree_manifest(evidence)
    sources = selected_provenance(evidence, repo_root)
    temporary = Path(tempfile.mkdtemp(prefix=".termux-source-bundle-", dir=output.parent))
    try:
        trees = temporary / "build-trees"
        for entry in manifest["archives"]:
            source = evidence / "termux-full/archives" / entry["archive"]
            if not source.is_file() or source.is_symlink():
                fail("missing actual archive: " + entry["archive"])
            if source.stat().st_size != entry["bytes"] or digest(source) != entry["sha256"]:
                fail("actual archive does not match manifest: " + entry["package"])
            copy_file(source, trees / entry["package"] / entry["archive"])
        copy_provenance(sources, temporary / "provenance")
        (temporary / "BUILD-SOURCES.md").write_text(
            "# Termux build source evidence\n\n"
            "This bundle contains six pinned workflow source trees, the locked Termux "
            "recipe material, and provenance metadata. It is for inspection and "
            "independent rebuild/relink attempts. It does not establish that shipped "
            "binaries came from these trees, and this tool never executes recovered "
            "build scripts. Review the manifest before any separate build.\n",
            encoding="utf-8")
        records = file_records(temporary)
        bundle_manifest = {"format": 1, "kind": "termux-build-source-evidence",
                           "packages": sorted(EXPECTED_PACKAGES),
                           "limitations": ["No build scripts were executed.",
                                          "Source evidence does not prove shipped binaries were built from these trees."],
                           "files": records}
        (temporary / "manifest.json").write_text(json.dumps(bundle_manifest, sort_keys=True, indent=2) + "\n",
                                                  encoding="utf-8")
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(temporary), str(output))
        archive = output.with_suffix(".tar.gz")
        write_deterministic_tar(output, archive)
        verify_tar(archive, records)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.evidence_root, args.repo_root, args.output)
    print(json.dumps({"bundle": str(args.output), "tar": str(args.output.with_suffix(".tar.gz"))}))


if __name__ == "__main__":
    main()
