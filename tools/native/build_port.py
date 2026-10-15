#!/usr/bin/env python3
"""Build the pinned Android output backend of Hyprland in a Linux/WSL build cache.

Requires the matching Linux NDK and tools in docs/GRAPHICS.md. No Android device
modifications are involved. The stock Arch userland remains a separate ABI.
"""
import argparse
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
from fetch_termux import default_lock, load_lock

PIN = "bc13ae5859097791b3e93a32632727a7f7075354"
ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--abi", choices=["x86_64", "arm64-v8a"], required=True)
parser.add_argument("--cache", type=Path, default=ROOT / ".build-tools/native")
parser.add_argument("--ndk", type=Path, required=True)
parser.add_argument("--from-dependency", help="Resume a failed build at this dependency (previous installs must exist)")
parser.add_argument("--core-only", action="store_true", help="Rebuild compositor after all dependencies are installed")
parser.add_argument("--termux-lock", type=Path, help="Override the committed per-ABI native dependency lock")
parser.add_argument("--refresh-termux", action="store_true", help="Resolve new Termux versions in a fresh cache for manifest review")
args = parser.parse_args()
architecture = "aarch64" if args.abi == "arm64-v8a" else "x86_64"
if sys.platform != "linux" or platform.machine().lower() not in ("x86_64", "amd64"):
    parser.error("The native build requires a Linux x86_64 host (including WSL)")
if args.refresh_termux and (args.termux_lock or args.core_only):
    parser.error("--refresh-termux requires a fresh full build and cannot be combined with --termux-lock/--core-only")
args.ndk = args.ndk.resolve()
properties = args.ndk / "source.properties"
if not properties.is_file():
    parser.error("NDK source.properties is missing: " + str(properties))
metadata = dict(line.split("=", 1) for line in properties.read_text().splitlines() if "=" in line)
metadata = {key.strip(): value.strip() for key, value in metadata.items()}
expected_ndk = re.search(r'ndkVersion\s*=\s*"([^"]+)"', (ROOT / "app/build.gradle.kts").read_text()).group(1)
revision = metadata.get("Pkg.BaseRevision", metadata.get("Pkg.Revision", "").split("-")[0])
if revision != expected_ndk:
    parser.error(f"Expected the project's NDK {expected_ndk}, got {revision or 'unknown'}")
compiler = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin" / (architecture + "-linux-android29-clang")
if not compiler.is_file():
    parser.error("The selected NDK lacks the Linux compiler for " + args.abi + ": " + str(compiler))
termux_lock = args.termux_lock or default_lock(architecture)
if not args.refresh_termux:
    try:
        load_lock(termux_lock, architecture)
    except ValueError as error:
        parser.error(str(error))
args.cache = args.cache.resolve()
base = args.cache / args.abi
source = base / "anhyprland"
prefix = base / "ndk-prefix"
base.mkdir(parents=True, exist_ok=True)

def run(command, **kwargs):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True, **kwargs)

fetch = [sys.executable, Path(__file__).with_name("fetch_termux.py"), "--abi", architecture, "--directory", base]
fetch += ["--refresh"] if args.refresh_termux else ["--lock", termux_lock]
run(fetch)

if not source.exists():
    run(["git", "clone", "--depth", "1", "--branch", "android", "https://github.com/taowen/anhyprland.git", source])
head = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
if head != PIN:
    run(["git", "-C", source, "fetch", "--depth", "1", "origin", PIN])
    run(["git", "-C", source, "checkout", "--detach", "FETCH_HEAD"])
run(["git", "-C", source, "submodule", "update", "--init", "--depth", "1"])
shared = base / "build/ndk-prefix"
shared.parent.mkdir(exist_ok=True)
if not shared.exists(): shared.symlink_to(prefix, target_is_directory=True)

# Android injects input through Embed.h; Linux hardware libinput code is excluded
# by upstream's __ANDROID__ guards. Keep its public types available to headers.
shutil.copy2("/usr/include/libinput.h", prefix / "include/libinput.h")
shutil.copy2("/usr/include/libudev.h", prefix / "include/libudev.h")
cmake = source / "CMakeLists.txt"
text = cmake.read_text().replace("set(PLATFORM_DEPS libinput>=1.26)", "set(PLATFORM_DEPS)")
cmake.write_text(text)
for filename in ["build-deps.py", "build-core.py"]:
    path = source / "android" / filename
    text = path.read_text()
    if architecture == "x86_64":
        text = text.replace("aarch64", "x86_64").replace("arm64-v8a", "x86_64")
    if filename == "build-core.py":
        if '"-DNO_XWAYLAND=ON"' not in text:
            text = text.replace('"-DBUILD_TESTING=OFF",', '"-DBUILD_TESTING=OFF", "-DNO_XWAYLAND=ON", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",')
        if '"-DPython3_EXECUTABLE=/usr/bin/python3"' not in text:
            text = text.replace('"-DBUILD_TESTING=OFF",', '"-DBUILD_TESTING=OFF", "-DPython3_EXECUTABLE=/usr/bin/python3",')
    else:
        text = text.replace('flags += [f"-DCMAKE_PREFIX_PATH={HOST}"]',
                            'flags += [f"-DCMAKE_PREFIX_PATH={HOST}", "-DCMAKE_C_COMPILER=/usr/bin/gcc-14", "-DCMAKE_CXX_COMPILER=/usr/bin/g++-14"]')
    path.write_text(text)
env = os.environ.copy()
env.update(ARLINUX_DIR=str(base), ANDROID_NDK_HOME=str(args.ndk), JOBS=env.get("JOBS", "4"), ARLINUX_ANDROID_API="29")
env["PATH"] = str(args.cache / "venv/bin") + os.pathsep + env["PATH"]

# The protocol generator runs on the build host, never from an Android .deb.
# Wayland 1.26 added enum-header, which Ubuntu's older scanner cannot emit.
host = source / "build/host-prefix"
if not (host / "bin/wayland-scanner").exists():
    wayland = base / "host-wayland"
    if not wayland.exists():
        run(["git", "clone", "--depth", "1", "--branch", "1.26.0", "https://github.com/wayland-mirror/wayland.git", wayland])
    host_build = base / "host-wayland-build"
    run([args.cache / "venv/bin/meson", "setup", host_build, wayland, "--prefix=" + str(host),
         "-Dscanner=true", "-Dlibraries=false", "-Ddocumentation=false", "-Dtests=false"], env=env)
    run([args.cache / "venv/bin/meson", "compile", "-C", host_build], env=env)
    run([args.cache / "venv/bin/meson", "install", "-C", host_build], env=env)
scanner_pc = prefix / "lib/pkgconfig/wayland-scanner.pc"
scanner_pc.write_text(f"prefix={host}\nbindir=${{prefix}}/bin\nwayland_scanner=${{bindir}}/wayland-scanner\nName: Wayland Scanner\nDescription: Build-host protocol generator\nVersion: 1.26.0\n")
for name, version, library in [("glesv2", "3.2", "GLESv2"), ("egl", "1.5", "EGL")]:
    (prefix / "lib/pkgconfig" / (name + ".pc")).write_text(
        f"Name: Android {library}\nDescription: Android NDK platform library\nVersion: {version}\nLibs: -l{library}\n")

# Use the public Wayland core XML from the native package, not a private arlinux checkout.
core = next(iter(prefix.glob("share/wayland/wayland.xml")), None)
if core is None:
    core_dir = base / "wayland-core"
    core_dir.mkdir(exist_ok=True)
    import urllib.request
    core = core_dir / "wayland.xml"
    if not core.exists():
        core.write_bytes(urllib.request.urlopen("https://raw.githubusercontent.com/wayland-mirror/wayland/1.26.0/protocol/wayland.xml").read())
env["WAYLAND_CORE_PROTOCOL_DIR"] = str(core.parent)

dependencies = ["scanner", "hyprutils", "hyprlang", "glslang", "muparser", "tomlplusplus", "re2", "lua",
                "libjpeg-turbo", "libwebp", "lcms", "libzip", "lunasvg", "libeis", "file",
                "wayland-protocols", "hyprcursor", "hyprgraphics", "aquamarine"]
if args.from_dependency:
    dependencies = dependencies[dependencies.index(args.from_dependency):]
if not args.core_only:
    run([sys.executable, source / "android/build-deps.py", *dependencies], env=env)
from patch_port import apply, apply_renderer
apply(source)
apply_renderer(source)
run([sys.executable, source / "android/build-core.py"], env=env)
print("Built", source / "build/android-hyprland/libanhyprland.so")
