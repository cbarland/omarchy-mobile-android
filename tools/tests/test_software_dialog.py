import json
import os
from pathlib import Path
import re
import runpy
import socket
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/runtime-dialog"
ADAPTER = ASSETS / "hyprland-dialog"
QML = ASSETS / "dialog.qml"
PARSE = runpy.run_path(str(ADAPTER))["parse"]


class DialogCliTest(unittest.TestCase):
    def test_defaults_and_guiutils_normalization(self):
        self.assertEqual(PARSE([]), dict(title="", apptitle="", text="", buttons=[]))
        spec = PARSE(["--title", "old", "--title", "new", "--apptitle", "window",
                      "--text", "a<br/>b\\nc", "--buttons", ";Wait;  Deny \t;; ;",
                      "--buttons", "Allow once;Terminate"])
        self.assertEqual(spec, dict(title="new", apptitle="window", text="a\nb\nc",
                                    buttons=["Wait", "Deny", "", "Allow once", "Terminate"]))

    def test_values_are_data_and_unknown_or_missing_flags_fail(self):
        value = 'spaces "quotes" $(touch BAD)\nUnicode 雪 <b>plain</b>'
        self.assertEqual(PARSE(["--title", value, "--text", value])["title"], value)
        self.assertEqual(PARSE(["--title", "--help"])["title"], "--help")
        for argv in (["unknown"], ["--buttons"], ["--text"], ["--apptitle"], ["--title"]):
            with self.subTest(argv=argv), self.assertRaises(ValueError):
                PARSE(argv)
        self.assertIsNone(PARSE(["--help", "ignored"]))
        self.assertIsNone(PARSE(["-h"]))


@unittest.skipUnless(os.name == "posix", "Linux descriptor/process semantics required")
class DialogBoundaryTest(unittest.TestCase):
    def command(self, *, close=(), failure=False, choice=0, drop_reply=False):
        # Execute the actual fixed QML command with its dynamic label argument.
        match = re.search(r'command:\s*\[(.*?)root\.selectedLabel\s*\]', QML.read_text(), re.S)
        self.assertIsNotNone(match)
        prefix = json.loads("[" + match.group(1).rstrip().rstrip(",") + "]")
        fixture = f'''import json,os,subprocess,sys
spec=json.loads(os.environ['OMAMOBILE_DIALOG_SPEC'])
assert os.environ['QS_APP_ID']=='hyprland-dialog'
assert os.environ['QS_DISABLE_CRASH_HANDLER']=='1'
assert os.environ['QS_DISABLE_FILE_WATCHER']=='1'
assert os.environ['HL_EXEC_RULE_TOKEN']=='owned-token'
os.write(1,b'qs diagnostic\\n')
os.write(2,('PID:'+str(os.getpid())+'\\n').encode())
if {choice!r} is None:sys.exit(0)
if {drop_reply!r}:os.close(3)
result=subprocess.run({prefix!r}+[spec['buttons'][{choice!r}]],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,close_fds=False,timeout=5)
sys.exit(result.returncode)
'''
        launcher = f'''import errno,os,runpy,sys
module=runpy.run_path(sys.argv[1])
real_exec=os.execve
def fake_qs(path,argv,env):
 assert path=='/usr/bin/qs'
 assert argv==['qs','--path',{str(QML)!r}]
 if {failure!r}:raise FileNotFoundError(errno.ENOENT,'fixture missing qs')
 real_exec(sys.executable,[sys.executable,'-c',{fixture!r}],env)
os.execve=fake_qs
for fd in {close!r}:
 try:os.close(fd)
 except OSError:pass
sys.exit(module['main'](sys.argv[2:]))
'''
        return [sys.executable, "-c", launcher, str(ADAPTER)]

    def environment(self):
        return dict(os.environ, HL_EXEC_RULE_TOKEN="owned-token")

    def test_pipe_response_exact_and_exec_keeps_pid(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "should-not-exist"
            label = f'Wait "$(touch {marker})"\n雪'
            proc = subprocess.Popen(self.command() + ["--title", "untrusted <b>heading</b>", "--buttons", label],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.environment())
            out, err = proc.communicate(timeout=10)
            self.assertEqual(proc.returncode, 0, err)
            self.assertEqual(out, (label + "\n").encode())
            self.assertFalse(marker.exists())
            self.assertIn(b"qs diagnostic\n", err)
            self.assertIn(("PID:" + str(proc.pid) + "\n").encode(), err)

    def test_independent_invocations_do_not_mix_responses(self):
        children = [(label, subprocess.Popen(self.command() + ["--buttons", label],
                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.environment()))
                    for label in ("Wait on phone", "Deny on desktop")]
        self.assertNotEqual(children[0][1].pid, children[1][1].pid)
        for label, proc in children:
            out, err = proc.communicate(timeout=10)
            self.assertEqual((proc.returncode, out), (0, (label + "\n").encode()), err)

    def test_socket_and_regular_file_keep_response_only_and_offset(self):
        reader, writer = socket.socketpair()
        with reader, writer:
            proc = subprocess.Popen(self.command() + ["--buttons", "Wait"], stdout=writer,
                                    stderr=subprocess.PIPE, env=self.environment())
            writer.close()
            reader.settimeout(10)
            data = b""
            while chunk := reader.recv(4096):
                data += chunk
            _, err = proc.communicate(timeout=10)
            self.assertEqual((proc.returncode, data), (0, b"Wait\n"), err)
        with tempfile.TemporaryFile() as out:
            out.write(b"preserved prefix\n")
            out.flush()
            result = subprocess.run(self.command() + ["--buttons", "Deny"], stdout=out,
                                    stderr=subprocess.PIPE, env=self.environment(), timeout=10)
            out.seek(0)
            self.assertEqual((result.returncode, out.read()), (0, b"preserved prefix\nDeny\n"), result.stderr)

    def test_absent_standard_descriptors_do_not_leak_diagnostics_into_reply(self):
        for close in ((0,), (2,), (0, 2)):
            with self.subTest(close=close):
                result = subprocess.run(self.command(close=close) + ["--buttons", "Wait"], capture_output=True,
                                        env=self.environment(), timeout=10)
                self.assertEqual((result.returncode, result.stdout), (0, b"Wait\n"), result.stderr)
        for close in ((1,), (1, 2), (0, 1, 2)):
            with self.subTest(close=close):
                result = subprocess.run(self.command(close=close) + ["--buttons", "Wait"], capture_output=True,
                                        env=self.environment(), timeout=10)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, b"")

    def test_close_exec_failure_invalid_argv_and_reply_failure_do_not_choose(self):
        for options, args in ((dict(choice=None), ["--buttons", "Allow"]),
                              (dict(failure=True), ["--buttons", "Allow"]),
                              (dict(drop_reply=True), ["--buttons", "Allow"]),
                              ({}, ["--unknown"])):
            with self.subTest(options=options, args=args):
                result = subprocess.run(self.command(**options) + args, capture_output=True,
                                        env=self.environment(), timeout=10)
                self.assertEqual(result.stdout, b"")
                if options.get("choice", 0) is not None:
                    self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
