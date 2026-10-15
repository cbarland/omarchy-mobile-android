"""Host-level TLS behavior for the generated two-file theme fixture."""
import http.server
from importlib.machinery import SourceFileLoader
import os
from pathlib import Path
import ssl
import subprocess
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/linux/verify-theme-download-persistence"
fixture = SourceFileLoader("theme_fixture", str(SCRIPT)).load_module()


class ThemeTlsTest(unittest.TestCase):
    def test_certificate_failure_classifier_requires_trust_diagnostic(self):
        log = self.root / "diagnostic.log"
        for message in ("fatal: unable to get local issuer certificate", "SSL certificate verify failed",
                        "server certificate verification failed: self-signed certificate"):
            log.write_text(message, encoding="utf-8")
            self.assertTrue(fixture.certificate_verification_failed(log))
        for message in ("Could not resolve host", "Connection timed out", "URL rejected by policy"):
            log.write_text(message, encoding="utf-8")
            self.assertFalse(fixture.certificate_verification_failed(log))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="theme-tls-")
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        (self.source / "backgrounds").mkdir(parents=True)
        (self.source / "colors.toml").write_text('accent = "#12abef"\n', encoding="utf-8")
        (self.source / "backgrounds/fixture.png").write_bytes(b"two-file-data-fixture")
        self.env = dict(os.environ, GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL="/dev/null",
                        GIT_TERMINAL_PROMPT="0", GIT_CONFIG_COUNT="1",
                        GIT_CONFIG_KEY_0="core.hooksPath", GIT_CONFIG_VALUE_0="/dev/null")
        subprocess.run(["git", "init", "-q", str(self.source)], env=self.env, check=True)
        subprocess.run(["git", "-C", str(self.source), "add", "colors.toml", "backgrounds/fixture.png"], env=self.env, check=True)
        subprocess.run(["git", "-C", str(self.source), "-c", "user.name=fixture", "-c",
                        "user.email=fixture@example.invalid", "commit", "-qm", "data-only fixture"],
                       env=self.env, check=True)
        self.commit = subprocess.check_output(["git", "-C", str(self.source), "rev-parse", "HEAD"],
                                               env=self.env, text=True).strip()
        self.www = self.root / "www"
        self.www.mkdir()
        self.repository = self.www / "fixture.git"
        subprocess.run(["git", "clone", "--bare", str(self.source), str(self.repository)], env=self.env, check=True,
                       stdout=subprocess.DEVNULL)
        subprocess.run(["git", "--git-dir=" + str(self.repository), "update-server-info"], env=self.env, check=True)
        self.cert, self.key = fixture.tls_material(self.root)

        outer = self
        class Handler(http.server.SimpleHTTPRequestHandler):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, directory=str(outer.www), **kwargs)
            def log_message(self, fmt, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(certfile=str(self.cert), keyfile=str(self.key))
        self.server.socket = context.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = "https://127.0.0.1:%d/fixture.git" % self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.temp.cleanup()

    def test_valid_ca_clones_exact_two_file_fixture(self):
        destination = self.root / "trusted-clone"
        trusted = dict(self.env, GIT_SSL_CAINFO=str(self.cert))
        result = subprocess.run(["git", "clone", "--", self.url, str(destination)], env=trusted,
                                text=True, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(subprocess.check_output(["git", "-C", str(destination), "rev-parse", "HEAD"],
                                                 env=trusted, text=True).strip(), self.commit)
        tree = subprocess.check_output(["git", "-C", str(destination), "ls-tree", "-r", "--name-only", "HEAD"],
                                       env=trusted, text=True).splitlines()
        self.assertEqual(tree, ["backgrounds/fixture.png", "colors.toml"])

    def test_untrusted_certificate_fails_before_checkout(self):
        destination = self.root / "untrusted-clone"
        result = subprocess.run(["git", "clone", "--", self.url, str(destination)], env=self.env,
                                text=True, capture_output=True, timeout=30)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(destination.exists())
        self.assertRegex(result.stderr + result.stdout, "certificate|CAfile|TLS|SSL")


if __name__ == "__main__":
    unittest.main()
