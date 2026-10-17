#!/usr/bin/env python3
"""Generate twice from pinned Git objects, without trusting a dirty native cache."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import subprocess

from patch_port import apply, apply_renderer

FILES = (
    "src/config/supplementary/executor/Executor.cpp",
    "src/render/OpenGL.cpp",
    "src/helpers/AsyncDialogBox.hpp",
    "src/helpers/AsyncDialogBox.cpp",
    "src/Compositor.cpp",
    "src/managers/ANRManager.cpp",
    "src/managers/permissions/DynamicPermissionManager.cpp",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port-repository", type=Path, required=True, help="Read-only Git object source; no fetch or checkout")
    parser.add_argument("--output", type=Path, required=True, help="Task-owned proof directory")
    args = parser.parse_args()
    recipe = ast.parse(Path(__file__).with_name("build_port.py").read_text())
    pin = next(ast.literal_eval(node.value) for node in recipe.body if isinstance(node, ast.Assign)
               and any(isinstance(target, ast.Name) and target.id == "PIN" for target in node.targets))
    pristine = {name: subprocess.check_output(["git", "-C", str(args.port_repository), "show", f"{pin}:{name}"]) for name in FILES}
    args.output.mkdir(parents=True, exist_ok=True)
    for name, content in pristine.items():
        path = args.output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    generated = (*FILES, "src/helpers/AndroidGuestDialog.hpp")

    def fingerprint():
        return {name: hashlib.sha256((args.output / name).read_bytes()).hexdigest() for name in generated}

    apply(args.output)
    apply_renderer(args.output)
    first = fingerprint()
    apply(args.output)
    apply_renderer(args.output)
    second = fingerprint()
    if first != second:
        raise RuntimeError("Repeated guest-dialog patch generation changed the output")
    receipt = {"pin": pin, "pristineSha256": {name: hashlib.sha256(data).hexdigest() for name, data in pristine.items()},
               "generatedSha256": second, "applyTwiceConverged": True,
               "executorReaperAlreadyInPin": b"waitpid(child, &status, 0)" in pristine[FILES[0]]}
    (args.output / "source-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"pin": pin, "applyTwiceConverged": True, "executorReaperAlreadyInPin": receipt["executorReaperAlreadyInPin"]}))


if __name__ == "__main__":
    main()
