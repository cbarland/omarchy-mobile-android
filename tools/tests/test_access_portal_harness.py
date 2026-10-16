import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HARNESS = ROOT / "tools/linux/verify-access-portal"
PREPARE = ROOT / "tools/prepare-linux-shell.py"
if os.name == "nt":
    BASH = Path(r"C:\Program Files\Git\bin\bash.exe")
else:
    BASH = Path(shutil.which("bash") or "bash")


class AccessPortalHarnessTest(unittest.TestCase):
    def run_fixture(self, mode, initial):
        if not BASH.exists():
            self.skipTest("POSIX bash is unavailable")
        try:
            probe = subprocess.run([str(BASH), "-c", "exit 0"], capture_output=True, timeout=5)
        except OSError:
            self.skipTest("POSIX bash cannot be started")
        if probe.returncode != 0:
            self.skipTest("POSIX bash is blocked by the host")
        with tempfile.TemporaryDirectory(prefix="access-portal-fixture-") as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            state = root / "state.json"
            log = root / "calls.log"
            state.write_text(json.dumps(initial), encoding="utf-8")
            (bin_dir / "timeout").write_text("#!/bin/bash\nshift\nexec \"$@\"\n", encoding="utf-8")
            (bin_dir / "omarchy-shell").write_text(
                "#!/bin/bash\n"
                "if [[ \"$*\" == *' snapshot '* ]]; then cat \"${FAKE_STATE}\"; exit 0; fi\n"
                "if [[ \"$*\" == *' dismiss '* ]]; then printf 'dismiss %s\\n' \"${FAKE_REQUEST_ID}\" >> \"${FAKE_LOG}\"; printf '%s' '{\"opened\":false,\"pending\":0,\"current\":null}' > \"${FAKE_STATE}\"; exit 0; fi\n"
                "exit 69\n", encoding="utf-8")
            (bin_dir / "gdbus").write_text(
                "#!/bin/bash\n"
                "if [[ \"$*\" == *' AccessDialog '* ]]; then\n"
                "  printf 'dialog\\n' >> \"${FAKE_LOG}\"\n"
                "  python3 - \"$@\" <<'PY'\n"
                "import json, os, sys, time\n"
                "args=sys.argv[1:]; i=args.index('org.example.OmamobileProbe'); title=args[i+2]; body=args[i+4]\n"
                "mode=os.environ['FAKE_MODE']; current={'id':'a1b2c3d4','title':title,'body':body,'appId':'org.example.OmamobileProbe'}\n"
                "if mode == 'existing': current={'id':'deadbeef','title':'other','body':'other','appId':'other'}\n"
                "with open(os.environ['FAKE_STATE'],'w') as f: json.dump({'opened':True,'pending':1,'current':current}, f)\n"
                "if mode == 'fail' or mode == 'existing': sys.exit(1)\n"
                "time.sleep(.2)\n"
                "with open(os.environ['FAKE_STATE'],'w') as f: json.dump({'opened':False,'pending':0,'current':None}, f)\n"
                "print('(uint32 0, @a{sv} {})')\n"
                "PY\n"
                "  exit 0\n"
                "fi\n"
                "if [[ \"$*\" == *' Close '* ]]; then printf 'close\\n' >> \"${FAKE_LOG}\"; exit 0; fi\n"
                "exit 69\n", encoding="utf-8")
            for script in bin_dir.iterdir():
                os.chmod(script, 0o755)
            environment = os.environ.copy()
            environment.update({"PATH": str(bin_dir) + os.pathsep + environment["PATH"],
                                "FAKE_STATE": str(state), "FAKE_LOG": str(log), "FAKE_MODE": mode,
                                "FAKE_REQUEST_ID": "a1b2c3d4"})
            result = subprocess.run([str(BASH), str(HARNESS), "allow"], env=environment,
                                    text=True, capture_output=True, timeout=20)
            calls = log.read_text(encoding="utf-8") if log.exists() else ""
            return result, calls

    def test_refuses_existing_request_before_dialog(self):
        result, calls = self.run_fixture("initial", {"opened": True, "pending": 1,
                                                       "current": {"id": "existing"}})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("dialog", calls)

    def test_success_requires_typed_reply_and_empty_snapshot(self):
        result, calls = self.run_fixture("success", {"opened": False, "pending": 0, "current": None})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("dialog", calls)
        self.assertIn("uint32 0,", result.stdout)
        self.assertNotIn("dismiss", calls)

    def test_failure_closes_and_dismisses_only_owned_request(self):
        result, calls = self.run_fixture("fail", {"opened": False, "pending": 0, "current": None})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("close", calls)
        self.assertIn("dismiss a1b2c3d4", calls)

    def test_failure_leaves_unrelated_request_untouched(self):
        result, calls = self.run_fixture("existing", {"opened": False, "pending": 0, "current": None})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("close", calls)
        self.assertNotIn("dismiss", calls)

    def test_payload_selects_access_only(self):
        source = PREPARE.read_text(encoding="utf-8")
        self.assertIn("default=*", source)
        self.assertIn("org.freedesktop.impl.portal.Access=omamobile", source)
        self.assertNotIn("org.freedesktop.impl.portal.FileChooser=gnome", source)
        self.assertNotIn("usr/lib/systemd/user/omamobile-portal-access.service", source)


if __name__ == "__main__":
    unittest.main()
