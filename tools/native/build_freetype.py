#!/usr/bin/env python3
"""Build the owned FreeType library from locked inputs."""
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

from prepare_source_inputs import fetch_checked, safe_relative, validate

ROOT = Path(__file__).resolve().parents[2]
RECIPE_FILES = ("build_freetype.py", "prepare_source_inputs.py")
ABIS = {"x86_64": "x86_64-linux-android", "arm64-v8a": "aarch64-linux-android"}
DEPENDENCIES = ("libz.so.1", "libbz2.so.1.0", "libpng16.so", "libbrotlidec.so")
ANDROID_SYSTEM = {"libc.so", "libm.so", "libdl.so"}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def dependency_inputs(prefix, pkg_config=None):
    """Fingerprint the bounded in-prefix dependency closure used by CMake.

    The attacked premise is that a nominal prefix plus inherited environment
    describes CMake's inputs. It does not: symlinked headers and pkg-config
    overrides can redirect resolution. The closure and clean environment below
    make those routes explicit and reject escapes.
    """
    files = {}
    for root_name in ("include", "lib/pkgconfig"):
        root = prefix / root_name
        if not root.is_dir():
            raise ValueError("Missing dependency input tree: " + str(root))
        for current, directories, _ in os.walk(root, followlinks=False):
            if any((Path(current) / name).is_symlink() for name in directories):
                raise ValueError("Dependency input directory symlink is not supported: " + str(root))
        for path in sorted(root.rglob("*")):
            if path.is_dir():
                continue
            relative = path.relative_to(prefix).as_posix()
            resolved = path.resolve(strict=True)
            try:
                resolved.relative_to(prefix.resolve())
            except ValueError as error:
                raise ValueError("Dependency input symlink escapes prefix: " + str(path)) from error
            if resolved.is_dir():
                raise ValueError("Dependency input directory symlink is not supported: " + str(path))
            if path.is_symlink():
                files[relative] = {"kind": "symlink", "target": resolved.relative_to(prefix).as_posix(),
                                   "sha256": sha256(resolved)}
            elif path.is_file():
                files[relative] = {"kind": "file", "sha256": sha256(resolved)}
            else:
                raise ValueError("Unsupported dependency input: " + str(path))
    aliases = {}
    for soname in DEPENDENCIES:
        stem = soname.split(".so", 1)[0] + ".so"
        alias = prefix / "lib" / stem
        if not alias.is_file():
            raise ValueError("Missing linker alias for " + soname)
        aliases[soname] = {"path": (Path("lib") / stem).as_posix(),
                           "target": os.readlink(alias) if alias.is_symlink() else None,
                           "sha256": sha256(alias.resolve())}
    pkg_config = Path(pkg_config or shutil.which("pkg-config") or "").resolve()
    if not pkg_config.is_file():
        raise ValueError("A fixed pkg-config executable is required")
    pkg = {"path": str(pkg_config), "sha256": sha256(pkg_config)}
    encoded = json.dumps({"files": files, "aliases": aliases, "pkgConfig": pkg}, sort_keys=True).encode()
    return {"files": files, "aliases": aliases,
            "pkgConfig": pkg, "sha256": hashlib.sha256(encoded).hexdigest()}


def sanitized_environment(dependencies, pkg_config):
    env = {"PATH": str(pkg_config.parent), "PKG_CONFIG": str(pkg_config)}
    env["PKG_CONFIG_LIBDIR"] = str(dependencies / "lib/pkgconfig")
    env["PKG_CONFIG_PATH"] = env["PKG_CONFIG_LIBDIR"]
    return env


def specification(project=ROOT):
    lock = json.loads((project / "tools/native/source-inputs.lock.json").read_text())
    validate(lock)
    package = next(p for p in lock["packages"] if p["name"] == "freetype")
    if package["version"] != "2.15.0" or package["patches"]:
        raise ValueError("Review the owned FreeType recipe for changed source or patches")
    return {
        "component": "freetype",
        "version": package["version"],
        "upstream": package["upstream"],
        "patches": package["patches"],
        "referenceRecipe": package["recipe"],
        "recipes": {name: sha256(project / "tools/native" / name) for name in RECIPE_FILES},
        "settings": {
            "api": 29,
            "buildType": "Release",
            "shared": True,
            "tests": False,
            "harfbuzz": False,
            "requiredDependencies": list(DEPENDENCIES),
        },
    }


def checked_file(directory, relative, expected):
    relative = safe_relative(relative, "FreeType material")
    path = directory
    if directory.is_symlink():
        raise ValueError("Owned FreeType directory must not be a symlink")
    for part in relative.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("Owned FreeType material must not be a symlink: " + str(path))
    if not path.is_file() or sha256(path) != expected:
        raise ValueError("Missing or changed owned FreeType material: " + str(path))
    return path


def validate_materials(directory, abi, project=ROOT):
    record = json.loads((directory / "build.json").read_text())
    if (record.get("format") != 1 or record.get("kind") != "project-built"
            or record.get("abi") != abi or record.get("specification") != specification(project)):
        raise ValueError("Stale or wrong-ABI owned FreeType output; use a fresh native cache")
    for name, expected in record["specification"]["recipes"].items():
        checked_file(directory, "recipes/" + name, expected)
    reference = record["specification"]["referenceRecipe"]
    checked_file(directory, "recipes/termux-reference-build.sh", reference["sha256"])
    for relative, expected in record["materials"].items():
        parts = PurePosixPath(relative).parts
        if (not parts or parts[0] not in {"notices", "recipes", "logs"}
                or "\\" in relative or ":" in relative
                or any(p in {"..", ".", ""} for p in relative.split("/"))):
            raise ValueError("Invalid FreeType material path")
        checked_file(directory, relative, expected)
    if set(record["dependencies"]) != set(DEPENDENCIES) or set(record.get("dependencyHashes", {})) != set(DEPENDENCIES):
        raise ValueError("Owned FreeType dependency closure differs from the reviewed contract")
    if not record.get("dependencyInputs", {}).get("sha256"):
        raise ValueError("Owned FreeType dependency input fingerprint is missing")
    if not record["toolchain"].get("files") or not record["materials"]:
        raise ValueError("Owned FreeType build evidence is incomplete")
    return record


def cmake_executable(command):
    path = Path(shutil.which(str(command)) or str(command)).resolve()
    with path.open("rb") as stream:
        launcher = stream.read(4096)
    if launcher.startswith(b"#!") and b"from cmake import cmake" in launcher:
        python = launcher.splitlines()[0][2:].decode().strip()
        directory = subprocess.check_output([python, "-c", "import cmake; print(cmake.CMAKE_BIN_DIR)"], text=True).strip()
        path = (Path(directory) / "cmake").resolve()
    return path


def toolchain(ndk, cmake, project=ROOT):
    properties = dict(line.split("=", 1) for line in (ndk / "source.properties").read_text().splitlines() if "=" in line)
    properties = {k.strip(): v.strip() for k, v in properties.items()}
    wanted = re.search(r'ndkVersion\s*=\s*"([^"]+)"', (project / "app/build.gradle.kts").read_text()).group(1)
    actual = properties.get("Pkg.BaseRevision", properties.get("Pkg.Revision", "").split("-")[0])
    if actual != wanted:
        raise ValueError("Owned FreeType requires the project's NDK " + wanted)
    binaries = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    files = [binaries / name for name in ("clang", "clang++", "ld.lld", "llvm-readelf")]
    files += [cmake_executable(cmake), Path(shutil.which("ninja") or "/missing-ninja")]
    return {"ndkRevision": properties["Pkg.Revision"], "ndkPropertiesSha256": sha256(ndk / "source.properties"),
            "files": {str(path): sha256(path) for path in files}}


def extract(archive, destination):
    with tarfile.open(archive) as source:
        for member in source:
            parts = PurePosixPath(member.name).parts
            if (not parts or parts[0] != "freetype-2.15.0" or ".." in parts
                    or "\\" in member.name or not (member.isdir() or member.isfile())):
                raise ValueError("Unexpected FreeType archive member: " + member.name)
        source.extractall(destination)
    return destination / "freetype-2.15.0"


def readelf(llvm_readelf, library):
    return subprocess.check_output([str(llvm_readelf), "-h", "-d", str(library)], text=True)


def check_elf(elf, abi):
    machine = "AArch64" if abi == "arm64-v8a" else "Advanced Micro Devices X86-64"
    if machine not in elf or not re.search(r"\(SONAME\).*\[libfreetype\.so\]", elf):
        raise ValueError("Unexpected FreeType ELF ABI/SONAME")
    needed = re.findall(r"\(NEEDED\).*\[([^]]+)\]", elf)
    missing = set(DEPENDENCIES) - set(needed)
    if missing:
        raise ValueError("FreeType omitted required dependencies: " + ", ".join(sorted(missing)))
    unexpected = set(needed) - set(DEPENDENCIES) - ANDROID_SYSTEM
    if unexpected:
        raise ValueError("FreeType has unapproved NEEDED entries: " + ", ".join(sorted(unexpected)))
    if any("/usr/" in line or "/lib/" in line and "RUNPATH" in line for line in elf.splitlines()):
        raise ValueError("FreeType ELF contains a host library path")


def build(output, abi, ndk, cmake="cmake", source_cache=None, dependencies=None, project=ROOT):
    spec, compiler = specification(project), toolchain(ndk, cmake, project)
    if dependencies is None:
        raise ValueError("FreeType requires an explicit verified dependency prefix")
    dependencies = Path(dependencies).resolve()
    missing = [name for name in DEPENDENCIES if not (dependencies / "lib" / name).is_file()]
    if missing:
        raise ValueError("Missing verified FreeType dependency libraries: " + ", ".join(missing))
    pkg_config = Path(shutil.which("pkg-config") or "").resolve()
    inputs = dependency_inputs(dependencies, pkg_config)
    if str(pkg_config) != inputs["pkgConfig"]["path"]:
        raise ValueError("pkg-config identity changed during build")
    env = sanitized_environment(dependencies, pkg_config)
    if os.path.lexists(output):
        validate_output(output, abi, ndk, cmake, dependencies, project)
        return output
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".freetype-", dir=output.parent))
    try:
        for name in ("archives", "recipes", "notices", "logs", "source"):
            (temporary / name).mkdir()
        archive = temporary / "archives" / "freetype-2.15.0.tar.xz"
        fetch_checked(spec["upstream"], archive, source_cache)
        shutil.move(archive, temporary / "upstream.tar.xz")
        source = extract(temporary / "upstream.tar.xz", temporary / "source")
        for name in RECIPE_FILES:
            shutil.copyfile(project / "tools/native" / name, temporary / "recipes" / name)
        fetch_checked(spec["referenceRecipe"], temporary / "recipes/termux-reference-build.sh", source_cache)
        shutil.copyfile(project / "app/src/main/assets/licenses/native/arm64-v8a/termux/doc/freetype/copyright", temporary / "notices/copyright")
        commands = []
        def run(argv, log, environment=None):
            argv = list(map(str, argv)); commands.append(argv)
            with (temporary / "logs" / log).open("w") as stream:
                stream.write(repr(argv) + "\n"); stream.flush()
                result = subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT, env=environment)
            if result.returncode:
                raise RuntimeError(log + " failed; inspect private build evidence")
        cmake_bin = cmake_executable(cmake)
        binaries = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        build_dir, install_dir = temporary / "build", temporary / "install"
        run([cmake_bin, "--version"], "cmake-version.txt", env)
        run(["ninja", "--version"], "ninja-version.txt", env)
        run([cmake_bin, "-S", source, "-B", build_dir, "-G", "Ninja",
             "-DCMAKE_TOOLCHAIN_FILE=" + str(ndk / "build/cmake/android.toolchain.cmake"),
             "-DANDROID_ABI=" + abi, "-DANDROID_PLATFORM=android-29", "-DCMAKE_BUILD_TYPE=Release",
             "-DCMAKE_INSTALL_PREFIX=" + str(install_dir), "-DCMAKE_PREFIX_PATH=" + str(dependencies),
             "-DCMAKE_FIND_ROOT_PATH=" + str(dependencies), "-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER",
             "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY", "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY",
             "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY", "-DFT_DISABLE_HARFBUZZ=ON",
             "-DFT_REQUIRE_ZLIB=ON", "-DFT_REQUIRE_BZIP2=ON", "-DFT_REQUIRE_PNG=ON",
             "-DFT_REQUIRE_BROTLI=ON", "-DBUILD_SHARED_LIBS=ON", "-DFT_BUILD_TESTS=OFF"], "configure.txt", env)
        run([cmake_bin, "--build", build_dir, "--parallel", "4", "--verbose"], "build.txt", env)
        run([cmake_bin, "--install", build_dir], "install.txt", env)
        library = temporary / "libfreetype.so"
        shutil.copyfile(install_dir / "lib/libfreetype.so", library)
        elf = readelf(binaries / "llvm-readelf", library); check_elf(elf, abi)
        (temporary / "logs/elf.txt").write_text(elf)
        dependency_hashes = inputs["aliases"]
        materials = {str(path.relative_to(temporary)).replace("\\", "/"): sha256(path)
                     for folder in ("recipes", "notices", "logs") for path in (temporary / folder).rglob("*") if path.is_file()}
        record = {"format": 1, "kind": "project-built", "abi": abi, "specification": spec,
                  "toolchain": compiler, "dependencies": list(DEPENDENCIES), "dependencyHashes": dependency_hashes,
                  "dependencyPrefix": str(dependencies), "commands": commands,
                  "librarySha256": sha256(library), "materials": materials,
                  "dependencyInputs": inputs,
                  "buildEnvironment": {"path": env["PATH"], "pkgConfig": env["PKG_CONFIG"]}}
        (temporary / "build.json").write_text(json.dumps(record, indent=2) + "\n")
        validate_output(temporary, abi, ndk, cmake, dependencies, project)
        temporary.rename(output)
        return output
    finally:
        if temporary.exists(): shutil.rmtree(temporary)


def validate_output(directory, abi, ndk, cmake, dependencies, project=ROOT):
    record = validate_materials(directory, abi, project)
    if record["toolchain"] != toolchain(ndk, cmake, project): raise ValueError("Owned FreeType toolchain changed")
    if record["dependencyPrefix"] != str(Path(dependencies).resolve()): raise ValueError("Dependency prefix changed")
    current_pkg_config = Path(shutil.which("pkg-config") or "").resolve()
    if record["dependencyInputs"] != dependency_inputs(Path(dependencies).resolve(), current_pkg_config):
        raise ValueError("Dependency headers, pkg-config files, or linker aliases changed")
    library = checked_file(directory, "libfreetype.so", record["librarySha256"])
    header = library.read_bytes()[:20]
    if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != (62 if abi == "x86_64" else 183):
        raise ValueError("Owned FreeType ELF architecture does not match ABI")
    checked_file(directory, "upstream.tar.xz", record["specification"]["upstream"]["sha256"])
    return record


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", choices=ABIS, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--cmake", default="cmake")
    parser.add_argument("--source-cache", type=Path)
    args = parser.parse_args()
    if sys.platform != "linux": parser.error("FreeType cross-build requires a Linux host")
    print(build(args.directory.resolve() / "owned/freetype", args.abi, args.ndk.resolve(), args.cmake, args.source_cache, args.dependencies))
