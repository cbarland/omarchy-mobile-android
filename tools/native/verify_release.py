#!/usr/bin/env python3
"""Read-only staged-native release gate; prints JSON and exits nonzero on gaps.

Notice presence is a mechanical check, not a complete license/source audit.
No network, Android device, build, or signing operation is performed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

ABIS = ("arm64-v8a", "x86_64")
INSTALL_PINS = {
    "omarchy": {"url": "https://github.com/omacom/omarchy.git", "commit": "e71a816e9032f040225e086c7256f73e8080ae45"},
    "mobile": {"url": "https://github.com/derekross/omarchy-mobile.git", "commit": "953bcb3058347459cdf7faaa1e149f16e0f7cfa6"},
    "widgets": {"url": "https://github.com/SimonSchubert/omarchy-widgets.git", "commit": "b63886b75e02ccef1a8a11baeef2d6eff7a374af"},
}
INSTALL_FILES = {"linux-shell.tgz", "prepare-arch-graphics", "configure-omarchy-repository"}
SOURCE_PACKAGES = {"glib", "pango", "libcairo", "freetype", "fribidi", "libiconv", "libgraphite"}
SYSTEM = {"libandroid.so", "liblog.so", "libEGL.so", "libGLESv2.so", "libGLESv3.so",
          "libc.so", "libm.so", "libdl.so", "libz.so", "libvulkan.so",
          "libjnigraphics.so", "libnativewindow.so"}
# Library stem -> (Termux package, directory under share/doc).
TERMUX = {
    "android-execinfo": ("libandroid-execinfo", "libandroid-execinfo"),
    "pixman-1": ("libpixman", "libpixman"), "drm": ("libdrm", "libdrm"),
    "z": ("zlib", "zlib"), "cairo": ("libcairo", "libcairo"),
    "android-shmem": ("libandroid-shmem", "libandroid-shmem"),
    "png16": ("libpng", "libpng"), "fontconfig": ("fontconfig", "fontconfig"),
    "freetype": ("freetype", "freetype"), "bz2": ("bzip2", "libbz2"),
    "brotlidec": ("brotli", "brotli"), "brotlicommon": ("brotli", "brotli"),
    "expat": ("libexpat", "libexpat"), "X11": ("libx11", "libx11"),
    "xcb": ("libxcb", "libxcb"), "Xau": ("libxau", "libxau"),
    "Xdmcp": ("libxdmcp", "libxdmcp"),
    "android-support": ("libandroid-support", "libandroid-support"),
    "Xext": ("libxext", "libxext"), "Xrender": ("libxrender", "libxrender"),
    "xcb-render": ("libxcb", "libxcb"), "xcb-shm": ("libxcb", "libxcb"),
    "pangocairo-1.0": ("pango", "pango"), "pango-1.0": ("pango", "pango"),
    "pangoft2-1.0": ("pango", "pango"), "glib-2.0": ("glib", "glib"),
    "gobject-2.0": ("glib", "glib"), "gio-2.0": ("glib", "glib"),
    "gmodule-2.0": ("glib", "glib"), "iconv": ("libiconv", "libiconv"),
    "pcre2-8": ("pcre2", "pcre2"), "ffi": ("libffi", "libffi"),
    "fribidi": ("fribidi", "fribidi"), "harfbuzz": ("harfbuzz", "harfbuzz"),
    "graphite2": ("libgraphite", "libgraphite"),
    "xkbcommon": ("libxkbcommon", "libxkbcommon"),
    "wayland-server": ("libwayland", "libwayland"),
    "Xcursor": ("libxcursor", "libxcursor"), "Xfixes": ("libxfixes", "libxfixes"),
}
BUILT = {"aquamarine": "aquamarine", "hyprutils": "hyprutils", "hyprlang": "hyprlang",
         "hyprcursor": "hyprcursor", "zip": "libzip", "lunasvg": "lunasvg",
         "plutovg": "lunasvg/plutovg", "hyprgraphics": "hyprgraphics",
         "jpeg": "libjpeg-turbo", "webp": "libwebp", "sharpyuv": "libwebp",
         "magic": "file", "eis": "libeis", "re2": "re2", "muparser": "muparser",
         "lcms2": "lcms", "lua5.5": "lua"}


def notice_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path] if path.stat().st_size > 0 else []
    paths = path.rglob("*") if path.is_dir() else []
    return [p for p in paths if p.is_file() and p.stat().st_size > 0 and
            p.name.lower().startswith(("license", "licence", "copying", "copyright", "notice"))]


def audit(project: Path) -> dict:
    main = project / "app/src/main"
    errors, missing = [], []
    source_versions = {}
    checked = 0
    def error(kind, **details):
        errors.append({"kind": kind, **details})
    install = main / "assets/install"
    checked_install = 0
    try:
        manifest = json.loads((install / "install-manifest.json").read_text(encoding="utf-8"))
        if manifest.get("format") != 1 or manifest.get("pins") != INSTALL_PINS:
            error("invalid_installer_format_or_pins")
        entries = manifest["files"]
        names = [item["path"] for item in entries]
        if len(names) != len(INSTALL_FILES) or set(names) != INSTALL_FILES:
            error("invalid_installer_file_set", files=names)
        for item in entries:
            if item["path"] not in INSTALL_FILES:
                continue
            path = install / item["path"]
            if not path.is_file():
                error("missing_installer_file", file=item["path"])
            elif path.stat().st_size != item["bytes"] or hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
                error("installer_hash_or_size_mismatch", file=item["path"])
            else:
                checked_install += 1
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as exc:
        error("invalid_installer_manifest", detail=str(exc))
    for abi in ABIS:
        notices = main / "assets/licenses/native" / abi
        try:
            manifest = json.loads((notices / "libraries.json").read_text(encoding="utf-8"))
            packages = {p["package"]: p for p in json.loads((notices / "termux-packages.json").read_text(encoding="utf-8"))}
            pins = json.loads((notices / "upstream-deps.lock.json").read_text(encoding="utf-8"))
            libraries = manifest["libraries"]
        except (OSError, ValueError, KeyError, TypeError) as exc:
            error("invalid_manifest", abi=abi, detail=str(exc))
            continue
        if manifest.get("abi") != abi or "libanhyprland.so" not in libraries:
            error("invalid_abi_or_compositor", abi=abi)
        expected = set()
        for name, item in libraries.items():
            if name == "libc++_shared.so":
                continue  # AGP supplies this from the configured NDK.
            output = item.get("name_in_apk", "")
            if not output.startswith("lib") or not output.endswith(".so") or Path(output).name != output:
                error("invalid_library_name", abi=abi, library=name)
                continue
            path = main / ("prebuilt" if name == "libanhyprland.so" else "jniLibs") / abi / output
            expected.add(path)
            if not path.is_file():
                error("missing_library", abi=abi, library=name, path=str(path.relative_to(project)))
            elif hashlib.sha256(path.read_bytes()).hexdigest() != item.get("sha256_apk"):
                error("hash_mismatch", abi=abi, library=name)
            else:
                checked += 1
            for dependency in item.get("needed", []):
                if dependency not in SYSTEM and dependency not in libraries:
                    error("missing_dependency", abi=abi, library=name, dependency=dependency)
            stem = name.removeprefix("lib").split(".so")[0]
            package = version = None
            if name == "libanhyprland.so":
                component, notice = "Hyprland", notices / "Hyprland-BSD-3-Clause.txt"
                version = manifest.get("commit")
            elif item.get("source", "").startswith("ndk-prefix/") and stem in TERMUX:
                package, component = TERMUX[stem]
                version = packages.get(package, {}).get("version")
                if package in SOURCE_PACKAGES:
                    source_versions.setdefault((package, version), []).append(abi)
                notice = notices / "termux/doc" / component
                if version is None:
                    error("missing_package_provenance", abi=abi, library=name, package=package)
            elif stem in BUILT:
                component = BUILT[stem]
                version = pins.get(component.split("/")[0], {}).get("commit")
                notice = notices / "built" / component
            else:
                error("unmapped_license", abi=abi, library=name)
                continue
            for candidate in notice.rglob("*") if notice.is_dir() else [notice]:
                if candidate.is_symlink() and not candidate.exists():
                    error("dangling_license_symlink", abi=abi, library=name,
                          path=str(candidate.relative_to(project)))
            if not notice_files(notice):
                missing.append({"abi": abi, "library": name, "component": component,
                                "package": package, "version": version,
                                "expectedNotice": str(notice.relative_to(project))})
        for directory in (main / "jniLibs" / abi, main / "prebuilt" / abi):
            for path in directory.glob("*.so"):
                if path not in expected:
                    error("unrecorded_staged_library", abi=abi, path=str(path.relative_to(project)))
    for relative in ("assets/native/magic.mgc", "assets/licenses/ANDROID-NDK-NOTICE.txt"):
        path = main / relative
        if not path.is_file() or path.stat().st_size == 0:
            error("missing_asset_or_notice", path=str(path.relative_to(project)))
    # These seven distributed components have unresolved corresponding-source
    # mappings. Restoring their shared license texts does not supply those inputs.
    source_path = main / "assets/licenses/native/corresponding-sources.json"
    try:
        sources = json.loads(source_path.read_text(encoding="utf-8")) if source_path.exists() else {"packages": []}
        records = {(item["package"], item["version"]): item for item in sources["packages"]}
    except (OSError, ValueError, KeyError, TypeError) as exc:
        records = {}
        error("invalid_corresponding_sources_manifest", detail=str(exc))
    source_gaps = []
    for (package, version), abis in sorted(source_versions.items()):
        record = records.get((package, version), {})
        required = ("upstreamSourceUrl", "upstreamSourceSha256", "termuxRecipeCommit",
                    "termuxRecipePath", "sourceBundleUrl", "sourceBundleSha256")
        absent = [field for field in required if not isinstance(record.get(field), str) or not record[field].strip()]
        for field in ("upstreamSourceSha256", "sourceBundleSha256", "termuxRecipeCommit"):
            if field not in absent and not re.fullmatch(r"[0-9a-f]{40}" if field.endswith("Commit") else r"[0-9a-f]{64}", record[field]):
                absent.append(field)
        if absent:
            source_gaps.append({"package": package, "version": version, "abis": sorted(set(abis)),
                                "missingFields": absent})
    passed = not errors and not missing
    return {"passed": passed, "readyForPublicBinaryRelease": passed and not source_gaps,
            "checkedLibraries": checked,
            "checkedInstallerFiles": checked_install,
            "errors": errors, "missingLicenses": missing, "sourceMaterialGaps": source_gaps,
            "scope": "Mechanical staged files/hashes/pins/closure/notices and known corresponding-source mappings; not certification of license compliance or source completeness."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--mechanical-only", action="store_true", help="Exit on file/hash/notice checks only; does not clear binary release holds")
    args = parser.parse_args()
    result = audit(args.project.resolve())
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result["passed" if args.mechanical_only else "readyForPublicBinaryRelease"] else 1)
