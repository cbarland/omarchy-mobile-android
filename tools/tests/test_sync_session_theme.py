"""Isolated Linux contract tests; no Android device or real session is touched."""
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ThemeSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="theme-sync-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.home = self.root / "home"
        self.runtime = self.root / "run"
        self.theme = self.home / ".local/state/omarchy/current/theme"
        self.theme.mkdir(parents=True)
        self.colors = 'background = "#112233"\nforeground = "#ffffff"\n'
        (self.theme / "colors.toml").write_text(self.colors)
        self.calls_file = self.root / "calls.jsonl"
        # Relocate only the fixed Linux runtime root. Keep all production IPC,
        # discovery, exit-status, payload and timeout logic unchanged.
        self.script = self.root / "sync-session-theme"
        self.script.write_text((ROOT / "tools/linux/sync-session-theme").read_text().replace(
            "/run/omamobile", str(self.runtime)))
        mock = self.root / "omarchy-shell"
        mock.write_text('''#!/usr/bin/python3
import json, os, sys, time
with open(os.environ["CALLS"], "a") as f:
    f.write(json.dumps({"args": sys.argv[1:], "profile": os.environ["OMAMOBILE_PROFILE"],
        "display": os.environ["WAYLAND_DISPLAY"], "runtime": os.environ["XDG_RUNTIME_DIR"],
        "bus": os.environ["DBUS_SESSION_BUS_ADDRESS"]}) + "\\n")
method = sys.argv[2]
if method == os.environ.get("FAIL_METHOD"):
    if os.environ.get("HANG") == "1": time.sleep(30)
    print(os.environ.get("FAIL_REPLY", "error"))
    sys.exit(int(os.environ.get("FAIL_STATUS", "0")))
if method in ("ping", "applyTheme"): print("ok")
''')
        mock.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), PATH=str(self.root) + ":/usr/bin:/bin",
                        XDG_STATE_HOME=str(self.home / ".local/state"),
                        OMAMOBILE_SESSION_HOST="android", OMAMOBILE_PROFILE="phone",
                        OMARCHY_PATH=str(self.home / ".local/share/omarchy"),
                        XDG_RUNTIME_DIR=str(self.runtime / "p"), WAYLAND_DISPLAY="wayland-1",
                        CALLS=str(self.calls_file))

    def active_peer(self, peer="d", display="wayland-9"):
        directory = self.runtime / peer
        directory.mkdir(parents=True, exist_ok=True)
        identity = hashlib.md5((self.env["OMARCHY_PATH"] + "/shell\n" + display).encode()).hexdigest()[:16]
        for name in (display, "omarchy-shell-" + identity + ".sock"):
            sock = socket.socket(socket.AF_UNIX)
            sock.bind(str(directory / name))
            self.addCleanup(sock.close)

    def run_sync(self, **env):
        result = subprocess.run(["bash", str(self.script)], env=self.env | env,
                                capture_output=True, text=True, timeout=8)
        self.assertEqual(result.stderr, "")
        return result

    def calls(self):
        return [json.loads(line) for line in self.calls_file.read_text().splitlines()] if self.calls_file.exists() else []

    def test_optional_shell_and_background_with_peer_display_discovery(self):
        self.active_peer()
        self.active_peer("p", "wayland-1")
        self.assertEqual(self.run_sync().returncode, 0)
        calls = self.calls()
        self.assertEqual([c["args"][:2] for c in calls], [["shell", "ping"], ["shell", "applyTheme"]])
        self.assertEqual(calls[1]["args"][2:], [base64.b64encode(self.colors.encode()).decode(), ""])
        self.assertTrue(all(c["profile"] == "desktop" and c["display"] == "wayland-9" for c in calls))
        self.assertEqual(calls[1]["bus"], "unix:path=" + str(self.runtime / "d/bus"))

    def test_shell_payload_background_and_reverse_direction(self):
        self.active_peer("p")
        (self.theme / "shell.toml").write_text('radius = 12\n')
        background = self.home / "wall paper.png"
        background.write_bytes(b"fixture")
        (self.theme.parent / "background").symlink_to(background)
        self.assertEqual(self.run_sync(OMAMOBILE_PROFILE="desktop").returncode, 0)
        calls = self.calls()
        self.assertEqual(calls[1]["args"][3], base64.b64encode(b'radius = 12\n').decode())
        self.assertEqual(calls[2]["args"], ["background", "setInstant", str(background)])
        self.assertTrue(all(c["profile"] == "phone" for c in calls))

    def test_runtime_without_active_shell_is_skipped_without_ipc(self):
        (self.runtime / "d").mkdir(parents=True)
        for name in ("wayland-9", "omarchy-shell-unrelated.sock"):
            sock = socket.socket(socket.AF_UNIX)
            sock.bind(str(self.runtime / "d" / name))
            self.addCleanup(sock.close)
        self.assertEqual(self.run_sync().returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_wrong_acknowledgments_and_failed_calls_return_failure(self):
        self.active_peer()
        for method in ("ping", "applyTheme"):
            for status in ("0", "7"):
                with self.subTest(method=method, status=status):
                    self.assertEqual(self.run_sync(FAIL_METHOD=method, FAIL_STATUS=status).returncode, 1)
        log = (self.home / ".local/state/omamobile/theme-sync.log").read_text()
        self.assertIn("desktop ping failed: error", log)
        self.assertIn("desktop applyTheme failed: error", log)

    def test_background_failure_is_reported(self):
        self.active_peer()
        background = self.theme.parent / "background"
        background.write_bytes(b"fixture")
        self.assertEqual(self.run_sync(FAIL_METHOD="setInstant").returncode, 1)

    def test_unresponsive_active_peer_is_bounded_and_fails(self):
        self.active_peer()
        self.assertEqual(self.run_sync(FAIL_METHOD="ping", HANG="1").returncode, 1)
        self.assertEqual(len(self.calls()), 1)

    def test_missing_colors_fails(self):
        (self.theme / "colors.toml").unlink()
        self.assertEqual(self.run_sync().returncode, 1)
        self.assertEqual(self.calls(), [])

    def test_unknown_origin_fails_and_runtime_fallback_works(self):
        self.active_peer()
        self.assertEqual(self.run_sync(OMAMOBILE_PROFILE="", XDG_RUNTIME_DIR="/unrelated").returncode, 1)
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.run_sync(OMAMOBILE_PROFILE="").returncode, 0)

    def test_non_android_host_does_nothing(self):
        self.active_peer()
        self.assertEqual(self.run_sync(OMAMOBILE_SESSION_HOST="other").returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_hook_seeding_is_idempotent_and_preserves_user_hooks(self):
        # Run the setup script's actual hook installation block in an isolated
        # HOME; the rest of setup needs the installed Linux payload and dbus.
        setup = (ROOT / "tools/linux/setup-linux-shell").read_text()
        block = setup[setup.index("# Preserve both"):setup.index("for plugin in")]
        hooks = self.home / ".config/omarchy/hooks"
        hook = hooks / "theme-set.d/omamobile-sync-session-theme"
        hook.parent.mkdir(parents=True)
        legacy = hooks / "theme-set"
        legacy.write_text("user legacy hook\n")
        sibling = hook.parent / "user-theme-hook"
        sibling.write_text("user directory hook\n")

        def seed():
            subprocess.run(["bash", "-eu", "-c", block], env=self.env, check=True)
            self.assertEqual(legacy.read_text(), "user legacy hook\n")
            self.assertEqual(sibling.read_text(), "user directory hook\n")

        seed()
        self.assertEqual(os.readlink(hook), str(self.home / ".local/share/omamobile/sync-session-theme"))
        seed()
        hook.unlink()
        hook.write_text("user owns this name\n")
        seed()
        self.assertEqual(hook.read_text(), "user owns this name\n")
        hook.unlink()
        hook.symlink_to("/missing-user-hook")
        seed()
        self.assertEqual(os.readlink(hook), "/missing-user-hook")


if __name__ == "__main__":
    unittest.main()
