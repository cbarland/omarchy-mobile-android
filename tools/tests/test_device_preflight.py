import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('device_preflight', Path(__file__).parents[1] / 'device-preflight.py')
P = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(P)


class DevicePreflightTests(unittest.TestCase):
    def fixtures(self):
        apk = {'signerSha256': ['a' * 64], 'minimumSdk': 29, 'graphicsAbis': ['arm64-v8a'], 'pins': {'mobile': 'fixture'}}
        outputs = [{'id': 0, 'state': 'ON'}, {'id': 8, 'presentation': True, 'state': 'ON'}]
        return apk, outputs

    def test_exact_signer_abi_sdk_and_active_display_required(self):
        apk, outputs = self.fixtures()
        self.assertEqual(P.blockers(apk, apk, 'arm64-v8a', 35, outputs), [])
        self.assertEqual(P.blockers(apk, None, 'arm64-v8a', 35, outputs), [])
        self.assertEqual(P.blockers(apk, dict(apk, pins=None, graphicsAbis=[]), 'arm64-v8a', 35, outputs), [])
        self.assertTrue(P.blockers(dict(apk, pins=None), apk, 'arm64-v8a', 35, outputs))
        self.assertIn('ABI', P.blockers(apk, apk, 'x86_64', 35, outputs)[0])
        self.assertIn('newer Android', P.blockers(apk, apk, 'arm64-v8a', 28, outputs)[0])
        changed = dict(apk, signerSha256=['b' * 64])
        self.assertIn('do not uninstall', P.blockers(apk, changed, 'arm64-v8a', 35, outputs)[0])
        self.assertTrue(P.blockers(apk, apk, 'arm64-v8a', 35, outputs[:1]))
        self.assertTrue(P.blockers(apk, apk, 'arm64-v8a', 35, [dict(x, state='OFF') for x in outputs]))

    def test_display_parser_omits_names_and_unique_identifiers(self):
        values = P.displays('Displays:\nDisplay id 0: DisplayInfo{"Phone", displayId 0, real 1080 x 2424, state ON, type INTERNAL}\n'
                            'Display id 8: DisplayInfo{"Monitor", FLAG_PRESENTATION, real 1920 x 1080, state ON, uniqueId "private"}\n')
        self.assertEqual(values[1], {'id': 8, 'width': 1920, 'height': 1080, 'state': 'ON', 'presentation': True})
        with self.assertRaises(ValueError):
            P.displays('unrecognized display output')

    def test_apk_metadata_requires_verified_signer_and_graphics_pair(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/install/install-manifest.json', json.dumps({'pins': {'mobile': 'fixture'}}))
                archive.writestr('lib/arm64-v8a/libomamobile_hyprland.so', b'fixture')
                archive.writestr('lib/arm64-v8a/libtawcroot.so', b'fixture')
                archive.writestr('lib/x86_64/libomamobile_hyprland.so', b'no runtime')
            responses = ['Signer #1 certificate SHA-256 digest: ' + 'a' * 64,
                         "package: name='dev.omamobile.host' versionCode='1'\nsdkVersion:'29'"]
            with patch.object(P, 'run', side_effect=responses):
                info = P.apk_info(apk, Path(temporary))
            self.assertEqual(info['graphicsAbis'], ['arm64-v8a'])
            self.assertEqual(len(info['sha256']), 64)
            with patch.object(P, 'run', return_value='No verified signer'):
                with self.assertRaises(ValueError):
                    P.apk_info(apk, Path(temporary))

    def test_preflight_error_is_saved_without_installing_or_requesting_input(self):
        calls = []
        def fail(command):
            calls.append(command)
            raise ValueError('signature verification failed')
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary) / 'new-output'
            with patch.object(sys, 'argv', ['preflight', '--serial', 'explicit-device', '--candidate', 'candidate.apk', '--output', str(out)]), \
                 patch.object(P, 'run', side_effect=fail), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(P.main(), 1)
            report = json.loads((out / 'preflight.json').read_text())
            self.assertFalse(report['readyForDeviceTest'])
            self.assertIn('signature', report['error'])
            self.assertEqual(len(calls), 1)
            self.assertIn('verify', calls[0])

    def test_installed_and_fresh_device_paths_only_observe_state(self):
        apk, _ = self.fixtures()
        for installed in (True, False):
            with self.subTest(installed=installed), tempfile.TemporaryDirectory() as temporary:
                calls = []
                def observe(command):
                    calls.append(command)
                    if command[-1] == 'ro.product.cpu.abi': return 'arm64-v8a'
                    if command[-1] == 'ro.build.version.sdk': return '35'
                    if command[-1] == 'get-displays':
                        return ('Display id 0: DisplayInfo{real 1080 x 2424, state ON}\n'
                                'Display id 8: DisplayInfo{FLAG_PRESENTATION, real 1920 x 1080, state ON}')
                    if 'path' in command:
                        if not installed: raise subprocess.CalledProcessError(1, command, output='')
                        return 'package:/data/app/host/base.apk'
                    if 'pull' in command: return ''
                    self.fail(str(command))
                out = Path(temporary) / 'report'
                with patch.object(sys, 'argv', ['preflight', '--adb', 'adb', '--serial', 'explicit-device',
                                               '--candidate', 'candidate.apk', '--output', str(out)]), \
                     patch.object(P, 'apk_info', return_value=apk), patch.object(P, 'run', side_effect=observe), \
                     patch.object(P.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '10210\n141413\n', '')), \
                     contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(P.main(), 0)
                report = json.loads((out / 'preflight.json').read_text())
                self.assertEqual(report['installed'] is not None, installed)
                self.assertEqual(report['linuxIdentityAndHashes'], ['10210', '141413'])
                for command in calls:
                    self.assertEqual(command[:3], ['adb', '-s', 'explicit-device'])
                    self.assertFalse({'install', 'uninstall', 'root', 'grant', 'revoke', 'input', 'start', 'clear'} & set(command))


if __name__ == '__main__':
    unittest.main()
