"""Locked native package regressions with fake HTTP and dpkg; no real stage is used."""
import gzip
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import platform
import runpy
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import urllib.error


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/native/fetch_termux.py"
SPEC = importlib.util.spec_from_file_location("locked_termux", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class LockedTermuxTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="termux-lock-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.base = self.root / "stage/x86_64"
        self.lock = self.root / "lock.json"
        self.data = b"fake deb bytes; package scripts must never be run"
        self.entry = {"package": "libfixture", "version": "1.2-3", "homepage": "https://example.invalid",
                      "url": "https://packages.termux.dev/apt/termux-main/pool/main/f/fixture/libfixture_1.2-3_x86_64.deb",
                      "sha256": hashlib.sha256(self.data).hexdigest()}
        self.write_lock([self.entry])

    def write_lock(self, entries):
        self.lock.write_text(json.dumps(entries), encoding="utf-8")

    def fake_extract(self, command, **kwargs):
        self.assertEqual(command[:2], ["dpkg-deb", "-x"])
        prefix = Path(command[3]) / "data/data/com.termux/files/usr"
        (prefix / "lib/pkgconfig").mkdir(parents=True)
        (prefix / "lib/libfixture.so").write_bytes(b"fake library")
        (prefix / "lib/pkgconfig/fixture.pc").write_text("prefix=/data/data/com.termux/files/usr\n")
        return subprocess.CompletedProcess(command, 0)

    def run_locked(self):
        # fcntl is Linux-only. Emulate only the advisory lock while exercising
        # the actual CLI selection/stage paths on either Windows or Linux.
        fake_fcntl = types.SimpleNamespace(flock=lambda *args: None, LOCK_EX=1)
        with patch.object(sys, "platform", "linux"), patch.dict(sys.modules, {"fcntl": fake_fcntl}):
            MODULE.main(["--abi", "x86_64", "--directory", str(self.base), "--lock", str(self.lock)])

    def test_locked_cli_never_requests_indexes_and_reuses_only_matching_stage(self):
        with patch.object(MODULE, "read_https", return_value=self.data) as http, \
                patch.object(MODULE, "resolve_latest", side_effect=AssertionError("must not resolve latest")), \
                patch.object(MODULE.subprocess, "check_output", return_value="Package: libfixture\nVersion: 1.2-3\nArchitecture: x86_64\n"), \
                patch.object(MODULE.subprocess, "run", side_effect=self.fake_extract) as extract:
            self.run_locked()
            http.assert_called_once_with(self.entry["url"])
            extract.assert_called_once()
            self.assertTrue((self.base / "ndk-prefix/lib/libfixture.so").is_file())
            self.assertIn(str(self.base / "ndk-prefix"), (self.base / "ndk-prefix/lib/pkgconfig/fixture.pc").read_text())
            self.run_locked()
            self.assertEqual(http.call_count, 1)
            self.assertEqual(extract.call_count, 1)
            self.assertEqual(json.loads((self.base / "termux-packages.json").read_text()), [self.entry])
            self.write_lock([{**self.entry, "sha256": "0" * 64}])
            with self.assertRaisesRegex(ValueError, "Existing native stage differs"):
                self.run_locked()

    def test_changed_download_hash_fails_before_extraction_or_stage_publication(self):
        with patch.object(MODULE, "read_https", return_value=b"changed upstream bytes"), \
                patch.object(MODULE.subprocess, "run") as extract:
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                self.run_locked()
            extract.assert_not_called()
        self.assertFalse((self.base / "ndk-prefix").exists())
        self.assertFalse((self.base / "termux-packages.json").exists())

    def test_missing_locked_version_reports_http_failure_without_index_fallback(self):
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError(self.entry["url"], 404, "not found", None, None)
        with patch.object(MODULE.urllib.request, "build_opener", return_value=opener), \
                patch.object(MODULE, "resolve_latest", side_effect=AssertionError("no fallback")):
            with self.assertRaisesRegex(ValueError, "HTTP 404.*no latest-version fallback"):
                self.run_locked()
        self.assertEqual(opener.open.call_count, 1)
        self.assertFalse((self.base / "termux-packages.json").exists())

    def test_invalid_lock_is_rejected_before_creating_stage(self):
        cases = [[], [self.entry, self.entry], [{key: value for key, value in self.entry.items() if key != "version"}],
                 [{**self.entry, "url": self.entry["url"].replace("https:", "http:")}],
                 [{**self.entry, "url": self.entry["url"].replace("/pool/main/", "/pool/../main/")}],
                 [{**self.entry, "url": self.entry["url"].replace("/pool/main/", "/pool/%2e%2e/main/")}],
                 [{**self.entry, "url": self.entry["url"].replace("x86_64.deb", "aarch64.deb")}],
                 [{**self.entry, "url": self.entry["url"].replace(".deb", ".deb?latest=1")}]]
        for entries in cases:
            with self.subTest(entries=entries):
                self.write_lock(entries)
                with self.assertRaises(ValueError), patch.object(MODULE, "read_https", side_effect=AssertionError("no HTTP")):
                    self.run_locked()
                self.assertFalse(self.base.exists())

    def test_missing_version_field_has_a_clear_error(self):
        del self.entry["version"]
        with self.assertRaisesRegex(ValueError, "missing version"):
            MODULE.validate_lock([self.entry], "x86_64")

    def test_refresh_explicitly_resolves_current_indexes(self):
        fields = ("Package: libfixture\nVersion: 2.0\nArchitecture: x86_64\n"
                  "Filename: pool/main/f/fixture/libfixture_2.0_x86_64.deb\nSHA256: " + self.entry["sha256"] + "\n\n")
        with patch.object(MODULE, "REQUESTED", ["libfixture"]), \
                patch.object(MODULE, "read_https", return_value=gzip.compress(fields.encode())) as http:
            resolved = MODULE.resolve_latest("x86_64")
        self.assertEqual(resolved[0]["version"], "2.0")
        self.assertEqual(http.call_count, 2)
        self.assertTrue(all(call.args[0].endswith("binary-x86_64/Packages.gz") for call in http.call_args_list))

    def test_package_control_architecture_must_match_lock(self):
        with patch.object(MODULE, "read_https", return_value=self.data), \
                patch.object(MODULE.subprocess, "check_output", return_value="Package: libfixture\nVersion: 1.2-3\nArchitecture: aarch64\n"), \
                patch.object(MODULE.subprocess, "run") as extract:
            with self.assertRaisesRegex(ValueError, "control metadata differs"):
                self.run_locked()
            extract.assert_not_called()

    def test_committed_locks_validate_for_their_abis(self):
        for abi in ("x86_64", "aarch64"):
            self.assertTrue(MODULE.load_lock(MODULE.default_lock(abi), abi))

    def test_build_rejects_missing_ndk_before_writing_cache(self):
        cache = self.root / "must-not-exist"
        script = ROOT / "tools/native/build_port.py"
        with patch.object(sys, "argv", [str(script), "--abi", "x86_64", "--cache", str(cache), "--ndk", str(self.root / "missing-ndk")]), \
                patch.object(sys, "platform", "linux"), patch.object(platform, "machine", return_value="x86_64"), \
                patch.dict(sys.modules, {"fetch_termux": MODULE}), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error:
                runpy.run_path(str(script), run_name="__main__")
        self.assertEqual(error.exception.code, 2)
        self.assertFalse(cache.exists())

    def test_build_rejects_ndk_missing_requested_architecture_before_writes(self):
        cache, ndk = self.root / "uncreated-cache", self.root / "ndk"
        ndk.mkdir()
        (ndk / "source.properties").write_text("Pkg.Revision = 30.0.14904198-beta1\n")
        script = ROOT / "tools/native/build_port.py"
        error_text = io.StringIO()
        with patch.object(sys, "argv", [str(script), "--abi", "arm64-v8a", "--cache", str(cache), "--ndk", str(ndk)]), \
                patch.object(sys, "platform", "linux"), patch.object(platform, "machine", return_value="x86_64"), \
                patch.dict(sys.modules, {"fetch_termux": MODULE}), contextlib.redirect_stderr(error_text):
            with self.assertRaises(SystemExit):
                runpy.run_path(str(script), run_name="__main__")
        self.assertIn("lacks the Linux compiler for arm64-v8a", error_text.getvalue())
        self.assertFalse(cache.exists())

    def test_build_defaults_to_lock_and_refresh_is_explicit(self):
        ndk = self.root / "valid-ndk"
        compiler = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
        compiler.parent.mkdir(parents=True)
        compiler.touch()
        (ndk / "source.properties").write_text("Pkg.Revision = 30.0.14904198-beta1\n")
        script = ROOT / "tools/native/build_port.py"
        class StopAfterFirstCommand(Exception):
            pass
        for refresh in (False, True):
            with self.subTest(refresh=refresh):
                arguments = [str(script), "--abi", "arm64-v8a", "--cache", str(self.root / str(refresh)), "--ndk", str(ndk)]
                if refresh:
                    arguments.append("--refresh-termux")
                with patch.object(sys, "argv", arguments), patch.object(sys, "platform", "linux"), \
                        patch.object(platform, "machine", return_value="x86_64"), \
                        patch.dict(sys.modules, {"fetch_termux": MODULE}), \
                        patch.object(MODULE.subprocess, "run", side_effect=StopAfterFirstCommand) as command, \
                        contextlib.redirect_stdout(io.StringIO()):
                    with self.assertRaises(StopAfterFirstCommand):
                        runpy.run_path(str(script), run_name="__main__")
                actual = command.call_args.args[0]
                self.assertTrue(actual[1].endswith("fetch_termux.py"))
                self.assertEqual(actual[2:4], ["--abi", "aarch64"])
                if refresh:
                    self.assertIn("--refresh", actual)
                    self.assertNotIn("--lock", actual)
                else:
                    self.assertNotIn("--refresh", actual)
                    self.assertEqual(actual[-2:], ["--lock", str(MODULE.default_lock("aarch64"))])


if __name__ == "__main__":
    unittest.main()
