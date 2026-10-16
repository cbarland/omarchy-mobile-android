#!/usr/bin/env python3
"""Stage only Hyprland's required Android shared libraries, assets and notices."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from build_graphite import validate_output

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--abi", choices=["x86_64", "arm64-v8a"], required=True)
p.add_argument("--cache", type=Path, default=Path("/opt/omamobile-native"))
p.add_argument("--ndk", type=Path, required=True)
p.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[2])
a = p.parse_args()
base = a.cache / a.abi
owned = base / "owned/graphite2"
# Validate before writing the destination, even when a legacy Termux copy exists.
cache_cmake = a.cache / "venv/bin/cmake"
validate_output(owned, a.abi, a.ndk.resolve(), cache_cmake if cache_cmake.is_file() else "cmake")
source = base / "anhyprland"
prefix = source / "build/android-prefix"
tools = a.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
triple = "x86_64-linux-android" if a.abi == "x86_64" else "aarch64-linux-android"
ndklib = tools.parent / "sysroot/usr/lib" / triple
search = [prefix / "lib", base / "ndk-prefix/lib", ndklib]
system = {"libandroid.so", "liblog.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so", "libc.so", "libm.so",
          "libdl.so", "libz.so", "libvulkan.so", "libjnigraphics.so", "libnativewindow.so"}
destination = a.project / "app/src/main/jniLibs" / a.abi
destination.mkdir(parents=True, exist_ok=True)
seen = {}

def apk_name(name):
    if name.endswith(".so"):
        return name
    if ".so." not in name or name in {"libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0"}:
        raise RuntimeError("Not an Android native dependency: " + name)
    # APK extraction accepts lib*.so, not Unix version suffixes. Keep these
    # private so a packaged zlib cannot alias Android's preloaded libz.so.
    return "libomamobile_" + name.removeprefix("lib").split(".so.")[0] + ".so"

def copy_library(path, name=None):
    name = name or path.name
    if name in seen:
        return
    dynamic = subprocess.check_output([tools / "llvm-readelf", "-d", path], text=True)
    needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
    output_name = apk_name(name)
    seen[name] = {"name_in_apk": output_name, "sha256_source": hashlib.sha256(path.read_bytes()).hexdigest(), "needed": needed,
                  "source": str(path.relative_to(base)) if path.is_relative_to(base) else "Android NDK " + str(path.relative_to(a.ndk))}
    if path.is_relative_to(base / "ndk-prefix"):
        seen[name]["origin"] = {"kind": "termux-package"}
    if name == "libgraphite2.so":
        seen[name]["origin"] = {"kind": "project-built", "component": "graphite2", "version": "1.3.15",
                                "buildManifestSha256": hashlib.sha256((owned / "build.json").read_bytes()).hexdigest()}
    for dep in needed:
        if dep in system:
            continue
        # Use the exact NDK C++ runtime shared by our JNI adapter and compositor.
        candidate = (owned / dep if dep == "libgraphite2.so" else ndklib / dep if dep == "libc++_shared.so"
                     else next((folder / dep for folder in search if (folder / dep).exists()), None))
        if candidate is None or not candidate.exists():
            raise RuntimeError(f"{name}: unresolved native dependency {dep}")
        copy_library(candidate, dep)
    if name == "libc++_shared.so":
        seen[name]["packaging"] = "AGP supplies the same NDK runtime with the JNI adapter"
        return
    output_dir = a.project / "app/src/main/prebuilt" / a.abi if name == "libanhyprland.so" else destination
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / output_name
    shutil.copy2(path, output)
    rewrite = ["patchelf", "--page-size", "16384", "--remove-rpath", "--set-soname", output_name]
    for dependency in needed:
        if dependency not in system and apk_name(dependency) != dependency:
            rewrite += ["--replace-needed", dependency, apk_name(dependency)]
    subprocess.run([*rewrite, output], check=True)
    subprocess.run([tools / "llvm-strip", "--strip-unneeded", output], check=True)
    seen[name]["sha256_apk"] = hashlib.sha256(output.read_bytes()).hexdigest()

copy_library(source / "build/android-hyprland/libanhyprland.so")
assets = a.project / "app/src/main/assets/native"
assets.mkdir(parents=True, exist_ok=True)
shutil.copy2(prefix / "share/misc/magic.mgc", assets / "magic.mgc")
notices = a.project / "app/src/main/assets/licenses/native" / a.abi
notices.mkdir(parents=True, exist_ok=True)
proof = notices / "owned/graphite2"
proof.mkdir(parents=True, exist_ok=True)
shutil.copyfile(owned / "build.json", proof / "build.json")
for folder in ("recipes", "patches", "notices", "logs"):
    shutil.copytree(owned / folder, proof / folder, dirs_exist_ok=True)
shutil.copy2(source / "LICENSE", notices / "Hyprland-BSD-3-Clause.txt")
shutil.copy2(source / "android/deps.lock.json", notices / "upstream-deps.lock.json")
shutil.copy2(base / "termux-packages.json", notices / "termux-packages.json")
for root, label in [(source / "build/sources", "built"), (base / "ndk-prefix/share", "termux")]:
    for path in root.rglob("*"):
        if path.is_file() and any(path.name.lower().startswith(n) for n in ["license", "licence", "copying", "copyright", "notice"]):
            relative = path.relative_to(root)
            if ".git" in relative.parts or any(part.startswith("test") for part in relative.parts[:-1]):
                continue
            output = notices / label / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, output)
manifest = {"port": "https://github.com/taowen/anhyprland", "commit": subprocess.check_output(["git", "-C", source, "rev-parse", "HEAD"], text=True).strip(),
            "abi": a.abi, "libraries": seen}
(notices / "libraries.json").write_text(json.dumps(manifest, indent=2) + "\n")
(a.project / "app/src/main/cpp/CMakeLists.txt").touch()
print("Staged", len(seen), "libraries for", a.abi, "and their source manifests/notices")
