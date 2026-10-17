#!/usr/bin/env python3
"""Generate twice from pinned Git objects, without trusting a dirty native cache."""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

from patch_port import apply, apply_renderer

FILES = (
    "src/config/supplementary/executor/Executor.cpp",
    "src/render/OpenGL.cpp",
    "src/helpers/AsyncDialogBox.hpp",
    "src/helpers/AsyncDialogBox.cpp",
    "src/Compositor.cpp",
    "src/managers/ANRManager.hpp",
    "src/managers/ANRManager.cpp",
    "src/managers/permissions/DynamicPermissionManager.cpp",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port-repository", type=Path, required=True, help="Read-only Git object source; no fetch or checkout")
    parser.add_argument("--output", type=Path, required=True, help="Task-owned proof directory")
    parser.add_argument("--check-anr-owner", action="store_true", help="Compile/run the emitted ANR callback with ASan/UBSan")
    parser.add_argument("--hyprutils-include", type=Path, help="Use the real Hyprutils pointer headers in the consumer check")
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
    if args.check_anr_owner:
        compiler = shutil.which("c++")
        if not compiler:
            raise RuntimeError("ANR consumer check requires a Linux C++ compiler")

        def callback(text):
            start = text.index("->then(", text.index("void CANRManager::SANRData::runDialog(")) + len("->then(")
            end = text.index("\n    });", start)
            return text[start:end] + "\n    }\n"

        include = args.output / "anr-dialog-callback.inc"
        binary = args.output / "anr-dialog-owner-test"
        test = Path(__file__).parents[1] / "tests/anr_dialog_owner_host.cpp"
        command = [compiler, "-std=c++20", "-Wall", "-Wextra", "-Werror", "-g", "-fsanitize=address,undefined",
                   "-fno-omit-frame-pointer", "-I", str(args.output), str(test), "-o", str(binary)]
        if args.hyprutils_include:
            command += ["-DOMAMOBILE_TEST_HYPRUTILS", "-I", str(args.hyprutils_include)]
        include.write_text(callback((args.output / "src/managers/ANRManager.cpp").read_text()))
        subprocess.run(command, check=True, timeout=60)
        positive = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
        (args.output / "anr-owner-positive.log").write_text(positive.stdout + positive.stderr)
        positive.check_returncode()
        # The exact original callback is a negative control, not an invented bug fixture.
        include.write_text(callback(pristine["src/managers/ANRManager.cpp"].decode()))
        subprocess.run(command, check=True, timeout=60)
        negative = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
        (args.output / "anr-owner-original-negative.log").write_text(negative.stdout + negative.stderr)
        if negative.returncode == 0 or "heap-use-after-free" not in negative.stderr:
            raise RuntimeError("Original ANR callback did not reproduce the expected ASan lifetime failure")
        # Leave the generated callback/binary as the passing version for review/reruns.
        include.write_text(callback((args.output / "src/managers/ANRManager.cpp").read_text()))
        subprocess.run(command, check=True, timeout=60)
        receipt["anrOwnerConsumer"] = {"passed": True, "originalAsanUseAfterFree": True,
                                      "pointerTypes": "Hyprutils" if args.hyprutils_include else "std"}
    (args.output / "source-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"pin": pin, "applyTwiceConverged": True, "executorReaperAlreadyInPin": receipt["executorReaperAlreadyInPin"]}))


if __name__ == "__main__":
    main()
