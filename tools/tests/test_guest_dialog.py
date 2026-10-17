import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(os.name == "posix" and shutil.which("c++"), "Linux C++ compiler required")
class GuestDialogTest(unittest.TestCase):
    def test_real_spawn_boundary_under_no_fork_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            binary = work / "guest-dialog-test"
            subprocess.run(["c++", "-std=c++20", "-pthread", "-Wall", "-Wextra", "-Werror",
                            "-I", str(ROOT / "tools/native"), str(ROOT / "tools/tests/guest_dialog_host.cpp"),
                            "-Wl,--wrap=posix_spawn", "-o", str(binary)], check=True, timeout=60)
            source = (ROOT / "app/src/main/java/dev/omamobile/host/GraphicalSession.java").read_text()
            match = re.search(r'static String wrapperArguments\(\) \{\s*return ("(?:[^"\\]|\\.)*");', source)
            self.assertIsNotNone(match, "Locate the actual generated wrapper argument protocol")
            protocol = json.loads(match.group(1))
            guest_bin = work / "guest-bin"
            guest_bin.mkdir()
            (guest_bin / "hyprland-dialog").write_text("#!/bin/sh\nexit 0\n")
            (guest_bin / "hyprland-dialog").chmod(0o755)
            wrappers = []
            for name, path in (("available", str(guest_bin)), ("missing", str(work / "missing"))):
                wrapper = work / name
                wrapper.write_text("#!/bin/sh\n" + protocol +
                                   f'exec /usr/bin/env -i PATH="{path}" HL_EXEC_RULE_TOKEN="$HL_EXEC_RULE_TOKEN" "$@"\n')
                wrappers.append(wrapper)
            slow = work / "slow"
            # A shell builtin loop exercises cancellation without launching descendants.
            slow.write_text("#!/bin/sh\ntrap '' TERM\nwhile :; do :; done\n")
            env = os.environ.copy()
            env["PATH"] = "/usr/bin:/bin"  # Native PATH cannot see the guest-only executable.
            result = subprocess.run([str(binary), *map(str, wrappers), str(slow)], cwd=work,
                                    env=env, text=True, capture_output=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("reaping: passed", result.stdout)
            self.assertIn("guest stderr remains visible", result.stderr)
            self.assertFalse((work / "SHOULD_NOT_EXIST").exists())
            # Legacy command strings still receive shell interpretation, using the same protocol.
            legacy = subprocess.run(["/bin/sh", str(wrappers[0]), "printf '%s\\n' 'legacy space'; printf second"],
                                    text=True, capture_output=True, timeout=5)
            self.assertEqual((legacy.returncode, legacy.stdout), (0, "legacy space\nsecond"))
            empty = subprocess.run(["/bin/sh", str(wrappers[0]), "--argv"], text=True, capture_output=True, timeout=5)
            self.assertEqual((empty.returncode, empty.stdout), (64, ""))


if __name__ == "__main__":
    unittest.main()
