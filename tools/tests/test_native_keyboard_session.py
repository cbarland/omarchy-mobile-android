"""Session ownership checks without Wayland, Android, or installed GTK packages."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).parents[2]


@unittest.skipUnless(os.name == "posix", "requires POSIX process signals and Bash")
class NativeKeyboardSessionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.bin = self.home / "bin"
        self.bin.mkdir()
        self.events = self.home / "events"
        self.release = self.home / "release"
        program = f'''#!{sys.executable}
import json, os, pathlib, signal, sys, time
name = pathlib.Path(sys.argv[0]).name
with open(os.environ['EVENTS'], 'a') as stream:
    stream.write(json.dumps({{'name': name, 'pid': os.getpid(), 'profile': os.environ['OMAMOBILE_PROFILE']}}) + '\\n')
signal.signal(signal.SIGTERM, lambda *args: sys.exit(0))
while not (name == 'quickshell' and pathlib.Path(os.environ['RELEASE']).exists()):
    time.sleep(.02)
'''
        for name in ("squeekboard", "quickshell"):
            path = self.bin / name
            path.write_text(program)
            path.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), OMARCHY_PATH=str(self.home),
                        EVENTS=str(self.events), RELEASE=str(self.release))

    def started(self, count):
        end = time.monotonic() + 5
        while time.monotonic() < end:
            events = [json.loads(line) for line in self.events.read_text().splitlines()] if self.events.exists() else []
            if len(events) >= count:
                return events
            time.sleep(.02)
        self.fail("session children did not start")

    def session(self, profile):
        process = subprocess.Popen(["bash", str(ROOT / "tools/linux/start-shell"), profile],
                                   env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def stop():
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
            process.communicate()
        self.addCleanup(stop)
        return process

    def assert_exited(self, events):
        for event in events:
            with self.assertRaises(ProcessLookupError):
                os.kill(event["pid"], 0)

    def test_phone_and_shared_own_one_keyboard_and_reap_it_on_shell_exit(self):
        for profile in ("phone", "unified"):
            with self.subTest(profile=profile):
                self.events.unlink(missing_ok=True)
                self.release.unlink(missing_ok=True)
                process = self.session(profile)
                events = self.started(2)
                self.assertCountEqual([event["name"] for event in events], ["quickshell", "squeekboard"])
                self.assertTrue(all(event["profile"] == profile for event in events))
                self.release.touch()
                self.assertEqual(process.wait(timeout=5), 0)
                self.assert_exited(events)
                process.communicate()

    def test_stopping_session_reaps_both_children(self):
        process = self.session("phone")
        events = self.started(2)
        process.send_signal(signal.SIGTERM)
        self.assertEqual(process.wait(timeout=5), 143)
        self.assert_exited(events)
        process.communicate()

    def test_separate_desktop_does_not_start_a_phone_keyboard(self):
        process = self.session("desktop")
        events = self.started(1)
        self.assertEqual([event["name"] for event in events], ["quickshell"])
        self.release.touch()
        self.assertEqual(process.wait(timeout=5), 0)
        self.assert_exited(events)
        process.communicate()


if __name__ == "__main__":
    unittest.main()
