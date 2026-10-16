#!/usr/bin/env python3
"""Verify canonical payloads against real pinned local checkouts, without editing them.

Example (PowerShell):
  python tools/tests/test_prepare_linux_shell.py --omarchy <checkout> --mobile <checkout> --widgets <checkout>

Disposable shared clones and the JSON report are retained beneath .build-tools.
This is an explicit integration check, not part of automatic unit discovery.
"""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import uuid


ROOT = Path(__file__).resolve().parents[2]
PREPARE = ROOT / "tools/prepare-linux-shell.py"


def run(*arguments):
    environment = os.environ.copy()
    environment["GIT_OPTIONAL_LOCKS"] = "0"
    return subprocess.check_output(arguments, text=True, encoding="utf-8", env=environment, timeout=60).strip()


def pins():
    return {
        node.targets[0].id.lower(): ast.literal_eval(node.value)
        for node in ast.parse(PREPARE.read_text(encoding="utf-8")).body
        if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
        and node.targets[0].id in {"OMARCHY", "MOBILE", "WIDGETS"}
    }


def members(path):
    result = {}
    with tarfile.open(path, "r:gz") as archive:
        for item in archive.getmembers():
            if item.name in result:
                raise AssertionError(f"Duplicate payload member: {item.name}")
            if not item.isfile():
                raise AssertionError(f"Unexpected non-file payload member: {item.name}")
            data = archive.extractfile(item).read()
            result[item.name] = {"sha256": hashlib.sha256(data).hexdigest(), "mode": item.mode, "size": len(data)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("omarchy", "mobile", "widgets"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--work-directory", type=Path,
                        help="New disposable directory; defaults to a unique workspace .build-tools child")
    arguments = parser.parse_args()
    sources = {name: getattr(arguments, name).resolve() for name in ("omarchy", "mobile", "widgets")}
    revisions = pins()
    original_status = {}
    for name, source in sources.items():
        assert run("git", "-C", str(source), "rev-parse", "HEAD") == revisions[name], f"Wrong {name} reference revision"
        original_status[name] = run("git", "-C", str(source), "status", "--porcelain=v1", "--untracked-files=all")
    directory = (arguments.work_directory or ROOT.parent / ".build-tools" / ("payload-canonical-" + uuid.uuid4().hex[:12])).resolve()
    assert all(not directory.is_relative_to(source) for source in sources.values()), "Disposable output must be outside reference checkouts"
    directory.mkdir(parents=True, exist_ok=False)
    clones = {}
    for name, source in sources.items():
        destination = directory / name
        run("git", "clone", "--quiet", "--shared", "--no-checkout", str(source), str(destination))
        run("git", "-C", str(destination), "-c", "core.autocrlf=false", "-c", "core.eol=lf",
            "checkout", "--quiet", "--detach", revisions[name])
        clones[name] = destination

    def payload(name):
        archive = directory / (name + ".tar.gz")
        run(sys.executable, str(PREPARE), "--omarchy", str(clones["omarchy"]), "--mobile", str(clones["mobile"]),
            "--widgets", str(clones["widgets"]), "--output", str(archive))
        return members(archive)

    before = payload("clean")
    assert before, "Preparation produced an empty payload"
    required_portal = {
        "usr/bin/omamobile-portal-access",
        "usr/share/xdg-desktop-portal/portals/omamobile.portal",
        "etc/xdg/xdg-desktop-portal/hyprland-portals.conf",
        "usr/share/dbus-1/services/org.freedesktop.impl.portal.desktop.omamobile.service",
        "root/.local/share/omamobile/verify-access-portal",
    }
    assert required_portal <= before, f"Access portal payload members missing: {required_portal - before}"
    # Invalid JSON makes accidental worktree reads fail loudly. License changes
    # cover both explicit put() calls and trees; widget bytes cover hash checks.
    dirty_paths = {
        "omarchy": ["config/omarchy/shell.json", "LICENSE"],
        "mobile": ["packages/omamobile-shell/profile/shell.json", "packages/omamobile-shell/LICENSE",
                   "packages/omamobile-shell/widgets/LICENSE-owl-guide", "packages/omamobile-owl-widgets/index.json"],
        "widgets": ["LICENSE", "order.txt"],
    }
    index_path = clones["mobile"] / "packages/omamobile-owl-widgets/index.json"
    widget = next(item for item in json.loads(index_path.read_text(encoding="utf-8"))["widgets"]
                  if item.get("status") in {"ok", "partial"})
    dirty_paths["widgets"].append("widgets/" + widget["id"] + "/widget.owl")
    for name, paths in dirty_paths.items():
        for relative in paths:
            (clones[name] / relative).write_bytes(b"deliberately dirty worktree; never ship this\r\n")
    untracked = clones["omarchy"] / "shell/.canonical-payload-untracked"
    untracked.write_bytes(b"untracked file must not enter payload\n")
    mode_path = "bin/omarchy-theme-set"
    mode = run("git", "-C", str(clones["omarchy"]), "ls-tree", revisions["omarchy"], "--", mode_path).split()[0]
    assert mode in {"100644", "100755"}, "Expected a regular tracked launcher"
    run("git", "-C", str(clones["omarchy"]), "update-index", "--chmod=" + ("-x" if mode == "100755" else "+x"), mode_path)
    after = payload("dirty")
    changes = sorted(name for name in before.keys() | after.keys() if before.get(name) != after.get(name))
    assert not changes, f"Dirty references changed payload members: {changes[:20]}"
    for name, source in sources.items():
        assert run("git", "-C", str(source), "rev-parse", "HEAD") == revisions[name]
        assert run("git", "-C", str(source), "status", "--porcelain=v1", "--untracked-files=all") == original_status[name], f"Original {name} checkout changed"
    report = {"passed": True, "pins": revisions, "referenceSources": {key: str(value) for key, value in sources.items()},
              "membersCompared": len(before), "dirtyPaths": dirty_paths,
              "indexModeFlipped": mode_path, "untrackedExcluded": str(untracked.relative_to(clones["omarchy"])),
              "originalCheckoutStatusUnchanged": True, "comparison": "tar member SHA-256, mode and size; gzip headers excluded"}
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": True, "membersCompared": len(before), "report": str(directory / "report.json")}))


if __name__ == "__main__":
    main()
