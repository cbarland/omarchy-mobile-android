from contextlib import redirect_stdout
from importlib.machinery import SourceFileLoader
import io
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).parents[1] / "linux" / "omamobile-access-shell"
fixture = SourceFileLoader("omamobile_access_shell_fixture", str(SCRIPT)).load_module()


class AccessShellTest(unittest.TestCase):
    def run_lock(self, payload):
        executed = []
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "omarchy"
            (base / "bin").mkdir(parents=True)
            (base / "bin" / "omarchy-shell").touch()
            with redirect_stdout(output):
                result = fixture.main(["lock", "isLocked"], {"OMARCHY_PATH": str(base)},
                                      query=lambda: payload, execute=lambda path, argv: executed.append((path, argv)))
        return result, output.getvalue().strip(), executed

    def test_lock_mapping_fails_closed(self):
        for payload, expected in [
                ('{"keyguardLocked":true,"deviceLocked":false,"interactive":true}', "true"),
                ('{"keyguardLocked":false,"deviceLocked":true,"interactive":true}', "true"),
                ('{"keyguardLocked":false,"deviceLocked":false,"interactive":false}', "true"),
                ('{"keyguardLocked":false,"deviceLocked":false,"interactive":true}', "false")]:
            with self.subTest(payload=payload):
                result, output, executed = self.run_lock(payload)
                self.assertEqual(0, result)
                self.assertEqual(expected, output)
                self.assertEqual([], executed)
        for payload in ('{}', '{"keyguardLocked":"false","deviceLocked":false,"interactive":true}', 'not json'):
            with self.subTest(payload=payload):
                result, output, executed = self.run_lock(payload)
                self.assertEqual(1, result)
                self.assertEqual("", output)
                self.assertEqual([], executed)

    def test_other_argv_delegates_exactly(self):
        executed = []
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "omarchy"
            (base / "bin").mkdir(parents=True)
            (base / "bin" / "omarchy-shell").touch()
            result = fixture.main(["shell", "ping", "extra"], {"OMARCHY_PATH": str(base)},
                                  execute=lambda path, argv: executed.append((path, argv)))
        self.assertEqual(1, result)
        self.assertEqual(1, len(executed))
        self.assertEqual(executed[0][0], executed[0][1][0])
        self.assertEqual(["shell", "ping", "extra"], executed[0][1][1:])

    def test_adapter_refuses_missing_target(self):
        executed = []
        self.assertEqual(1, fixture.main(["shell"], {"OMARCHY_PATH": str(SCRIPT.parent)},
                                         execute=lambda path, argv: executed.append((path, argv))))
        self.assertEqual([], executed)

    def test_query_error_fails_closed(self):
        executed = []
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "omarchy"
            (base / "bin").mkdir(parents=True)
            (base / "bin" / "omarchy-shell").touch()
            result = fixture.main(["lock", "isLocked"], {"OMARCHY_PATH": str(base)},
                                  query=lambda: (_ for _ in ()).throw(OSError("portal unavailable")),
                                  execute=lambda path, argv: executed.append((path, argv)))
        self.assertEqual(1, result)
        self.assertEqual([], executed)


if __name__ == "__main__":
    unittest.main()
