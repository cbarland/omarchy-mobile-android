#!/usr/bin/env python3
"""Build our Graphite library from verified inputs; never replace Termux packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

from prepare_source_inputs import fetch_checked, validate, safe_relative

ROOT = Path(__file__).resolve().parents[2]
RECIPE_FILES = ("build_graphite.py", "prepare_source_inputs.py")
ABIS = {"x86_64": "x86_64-linux-android", "arm64-v8a": "aarch64-linux-android"}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def specification(project=ROOT):
    lock = json.loads((project / "tools/native/source-inputs.lock.json").read_text())
    validate(lock)
    package = next(p for p in lock["packages"] if p["name"] == "libgraphite")
    if package["version"] != "1.3.15" or len(package["patches"]) != 1:
        raise ValueError("Review the owned Graphite recipe for the changed source lock")
    return {"component": "graphite2", "version": package["version"],
            "upstream": package["upstream"], "patches": package["patches"],
            "referenceRecipe": package["recipe"],
            "recipes": {name: sha256(project / "tools/native" / name) for name in RECIPE_FILES},
            "settings": {"api": 29, "stl": "c++_shared", "buildType": "Release",
                         "shared": True, "tests": False, "vm": "direct", "fileFace": True}}


def checked_file(directory, relative, expected):
    relative = safe_relative(relative, "Graphite material")
    path = directory
    if directory.is_symlink():
        raise ValueError("Owned Graphite directory must not be a symlink")
    for part in relative.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("Owned Graphite material must not be a symlink: " + str(path))
    if not path.is_file() or sha256(path) != expected:
        raise ValueError("Missing or changed owned Graphite material: " + str(path))
    return path


def validate_materials(directory, abi, project=ROOT):
    """Validate portable build evidence, also after copying it into notices."""
    if directory.is_symlink():
        raise ValueError("Owned Graphite directory must not be a symlink")
    record = json.loads((directory / "build.json").read_text())
    if (record.get("format") != 1 or record.get("kind") != "project-built"
            or record.get("abi") != abi or record.get("specification") != specification(project)):
        raise ValueError("Stale or wrong-ABI owned Graphite output; use a fresh native cache")
    for name, expected in record["specification"]["recipes"].items():
        checked_file(directory, "recipes/" + name, expected)
    for relative, expected in record["materials"].items():
        parts = PurePosixPath(relative).parts
        if (not parts or parts[0] not in {"notices", "patches", "recipes", "logs"}
                or "\\" in relative or ":" in relative or any(p in {"..", ".", ""} for p in relative.split("/"))):
            raise ValueError("Invalid Graphite material path")
        checked_file(directory, relative, expected)
    for patch in record["specification"]["patches"]:
        checked_file(directory, "patches/" + PurePosixPath(patch["path"]).name, patch["sha256"])
    checked_file(directory, "recipes/termux-reference-build.sh", record["specification"]["referenceRecipe"]["sha256"])
    if not record["toolchain"].get("files") or not record["materials"]:
        raise ValueError("Owned Graphite build evidence is incomplete")
    return record


def cmake_executable(command):
    path = Path(shutil.which(str(command)) or str(command)).resolve()
    with path.open("rb") as stream:
        launcher = stream.read(4096)
    if launcher.startswith(b"#!"):
        # Our documented venv uses pip's Python launcher. Hash and execute its
        # actual bundled CMake binary, rather than just the unchanged wrapper.
        if b"from cmake import cmake" not in launcher:
            raise ValueError("Pass the native CMake executable or pip CMake launcher")
        python = launcher.splitlines()[0][2:].decode().strip()
        directory = subprocess.check_output([python, "-c", "import cmake; print(cmake.CMAKE_BIN_DIR)"], text=True).strip()
        path = (Path(directory) / "cmake").resolve()
    return path


def toolchain(ndk, cmake, project=ROOT):
    properties = dict(line.split("=", 1) for line in (ndk / "source.properties").read_text().splitlines() if "=" in line)
    properties = {k.strip(): v.strip() for k, v in properties.items()}
    wanted = re.search(r'ndkVersion\s*=\s*"([^"]+)"', (project / "app/build.gradle.kts").read_text()).group(1)
    if properties.get("Pkg.BaseRevision", properties.get("Pkg.Revision", "").split("-")[0]) != wanted:
        raise ValueError("Owned Graphite requires the project's NDK " + wanted)
    binaries = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    files = [binaries / name for name in ("clang", "clang++", "ld.lld")]
    files += [cmake_executable(cmake), Path(shutil.which("ninja") or "/missing-ninja"), Path(shutil.which("patch") or "/missing-patch")]
    return {"ndkRevision": properties["Pkg.Revision"], "ndkPropertiesSha256": sha256(ndk / "source.properties"),
            "files": {str(path): sha256(path) for path in files}}


def validate_output(directory, abi, ndk, cmake, project=ROOT):
    record = validate_materials(directory, abi, project)
    if record["toolchain"] != toolchain(ndk, cmake, project):
        raise ValueError("Owned Graphite toolchain changed; use a fresh native cache")
    library = checked_file(directory, "libgraphite2.so", record["librarySha256"])
    header = library.read_bytes()[:20]
    if (header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != (62 if abi == "x86_64" else 183)):
        raise ValueError("Owned Graphite ELF architecture does not match ABI")
    checked_file(directory, "upstream.tar.gz", record["specification"]["upstream"]["sha256"])
    return record


def extract(archive, destination):
    with tarfile.open(archive) as source:
        for member in source:
            parts = PurePosixPath(member.name).parts
            if (not parts or parts[0] != "graphite-1.3.15" or ".." in parts
                    or "\\" in member.name or not (member.isdir() or member.isfile())):
                raise ValueError("Unexpected Graphite archive member: " + member.name)
        source.extractall(destination)
    return destination / "graphite-1.3.15"


def build(output, abi, ndk, cmake="cmake", source_cache=None, project=ROOT):
    spec, compiler = specification(project), toolchain(ndk, cmake, project)
    if os.path.lexists(output):
        validate_output(output, abi, ndk, cmake, project)
        return output
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".graphite2-", dir=output.parent))
    try:
        for name in ("archives", "recipes", "patches", "notices", "logs", "source"):
            (temporary / name).mkdir()
        archive = temporary / "archives" / "1.3.15.tar.gz"
        fetch_checked(spec["upstream"], archive, source_cache)
        shutil.move(archive, temporary / "upstream.tar.gz")
        source = extract(temporary / "upstream.tar.gz", temporary / "source")
        for name in RECIPE_FILES:
            shutil.copyfile(project / "tools/native" / name, temporary / "recipes" / name)
        fetch_checked(spec["referenceRecipe"], temporary / "recipes/termux-reference-build.sh", source_cache)
        patch = spec["patches"][0]
        fetch_checked(patch, temporary / "patches" / PurePosixPath(patch["path"]).name, source_cache)
        for name in ("COPYING", "LICENSE"):
            shutil.copyfile(source / name, temporary / "notices" / name)
        commands = []
        def run(argv, log):
            argv = list(map(str, argv))
            commands.append(argv)
            with (temporary / "logs" / log).open("w") as stream:
                stream.write(repr(argv) + "\n"); stream.flush()
                result = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT)
            if result.returncode:
                with (temporary / "logs" / log).open("rb") as stream:
                    stream.seek(max(0, stream.seek(0, 2) - 4096))
                    tail = stream.read().decode("utf-8", errors="replace")
                raise RuntimeError(log + " failed (private staging removed):\n" + tail)
        run(["patch", "--batch", "--fuzz=0", "-p1", "--directory", source, "--input", temporary / "patches" / PurePosixPath(patch["path"]).name], "patch.txt")
        cmake = cmake_executable(cmake)
        run([cmake, "--version"], "cmake-version.txt")
        run(["ninja", "--version"], "ninja-version.txt")
        binaries = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        run([binaries / "clang++", "--version"], "compiler-version.txt")
        build_dir = temporary / "build"
        run([cmake, "-S", source, "-B", build_dir, "-G", "Ninja",
             "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
             "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-29", "-DANDROID_STL=c++_shared",
             "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=ON", "-DBUILD_TESTING=OFF",
             "-DGRAPHITE2_VM_TYPE=direct", "-DGRAPHITE2_NFILEFACE=OFF", "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON"], "configure.txt")
        run([cmake, "--build", build_dir, "--target", "graphite2", "--parallel", "4", "--verbose"], "build.txt")
        library = temporary / "libgraphite2.so"
        shutil.copyfile(build_dir / "src/libgraphite2.so", library)
        elf = subprocess.check_output([str(binaries / "llvm-readelf"), "-h", "-d", str(library)], text=True)
        machine = "AArch64" if abi == "arm64-v8a" else "Advanced Micro Devices X86-64"
        if machine not in elf or not re.search(r"\(SONAME\).*\[libgraphite2.so\]", elf):
            raise ValueError("Unexpected Graphite ELF ABI/SONAME")
        (temporary / "logs/elf.txt").write_text(elf)
        materials = {str(path.relative_to(temporary)).replace("\\", "/"): sha256(path)
                     for folder in ("recipes", "patches", "notices", "logs") for path in (temporary / folder).rglob("*") if path.is_file()}
        record = {"format": 1, "kind": "project-built", "abi": abi, "specification": spec,
                  "toolchain": compiler, "commands": commands, "librarySha256": sha256(library), "materials": materials}
        (temporary / "build.json").write_text(json.dumps(record, indent=2) + "\n")
        validate_output(temporary, abi, ndk, cmake, project)
        temporary.rename(output)
        return output
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, required=True)
    parser.add_argument("--directory", type=Path, required=True, help="Per-ABI native build directory")
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--source-cache", type=Path, help="Read-only verified source-input cache; no network fallback")
    args = parser.parse_args()
    if sys.platform != "linux":
        parser.error("Graphite cross-build requires a Linux host")
    print(build(args.directory.resolve() / "owned/graphite2", args.abi, args.ndk.resolve(), args.cmake, args.source_cache))
