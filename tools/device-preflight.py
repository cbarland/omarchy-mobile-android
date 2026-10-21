#!/usr/bin/env python3
"""Read-only candidate/device checks before a data-preserving APK update."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

APP = 'dev.omamobile.host'


def run(command):
    return subprocess.check_output([str(x) for x in command], text=True, timeout=45).strip()


def apk_info(path, tools):
    suffix = '.bat' if os.name == 'nt' else ''
    signed = run([tools / ('apksigner' + suffix), 'verify', '--print-certs', path])
    certificates = sorted(set(re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', signed, re.M)))
    if not certificates:
        raise ValueError('apksigner did not report a verified signer certificate')
    badging = run([tools / ('aapt.exe' if os.name == 'nt' else 'aapt'), 'dump', 'badging', path])
    package = re.search(r"^package: name='([^']+)'", badging, re.M)
    minimum = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or package.group(1) != APP or not minimum:
        raise ValueError('Expected the Omarchy Mobile host APK with a numeric minimum SDK')
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        abis = sorted({name.split('/')[1] for name in names if name.startswith('lib/') and len(name.split('/')) == 3
                       and name.endswith('/libomamobile_hyprland.so')
                       and 'lib/' + name.split('/')[1] + '/libtawcroot.so' in names})
        manifest = 'assets/install/install-manifest.json'
        pins = json.loads(archive.read(manifest))['pins'] if manifest in names else None
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'sha256': digest,
            'signerSha256': [value.lower() for value in certificates], 'minimumSdk': int(minimum.group(1)),
            'graphicsAbis': abis, 'pins': pins}


def displays(text):
    values = []
    for line in text.splitlines():
        match = re.match(r'Display id (\d+): .*\breal (\d+) x (\d+),', line)
        if match:
            state = re.search(r'\bstate (\w+)', line)
            values.append({'id': int(match.group(1)), 'width': int(match.group(2)), 'height': int(match.group(3)),
                           'state': state.group(1) if state else 'unknown', 'presentation': 'FLAG_PRESENTATION' in line})
    if not any(value['id'] == 0 for value in values):
        raise ValueError('Android did not expose its default display')
    return values


def blockers(candidate, installed, abi, sdk, outputs):
    reasons = []
    if candidate['pins'] is None:
        reasons.append('Candidate has no packaged Linux installer manifest')
    if abi not in candidate['graphicsAbis']:
        reasons.append('Candidate lacks the compositor/runtime library pair for device ABI ' + abi)
    if candidate['minimumSdk'] > sdk:
        reasons.append('Candidate requires a newer Android SDK')
    if installed is not None and candidate['signerSha256'] != installed['signerSha256']:
        reasons.append('Signing certificates differ; do not uninstall to bypass this. Plan a data-preserving migration')
    if not any(value['id'] == 0 and value['state'] == 'ON' for value in outputs):
        reasons.append('The phone display is not ON')
    if not any(value['id'] > 0 and value['presentation'] and value['state'] == 'ON' for value in outputs):
        reasons.append('No active Android presentation display; attach/unlock the monitor before two-display testing')
    return reasons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='Explicit ADB target; no default device')
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='New private evidence directory')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or
               str(Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk'))
    parser.add_argument('--build-tools', type=Path, default=sdk / 'build-tools/36.0.0')
    parser.add_argument('--adb', default=shutil.which('adb') or str(sdk / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    report = {'readyForDeviceTest': False, 'serial': args.serial,
              'scope': 'Read-only APK identity, exact signer, ABI and display preflight. No install, input, grants or Linux changes; not a public release gate.'}
    adb = [args.adb, '-s', args.serial]
    try:
        candidate = apk_info(args.candidate.resolve(), args.build_tools.resolve())
        abi = run(adb + ['shell', 'getprop', 'ro.product.cpu.abi'])
        android_sdk = int(run(adb + ['shell', 'getprop', 'ro.build.version.sdk']))
        outputs = displays(run(adb + ['shell', 'cmd', 'display', 'get-displays']))
        try:
            paths = run(adb + ['shell', 'pm', 'path', APP]).splitlines()
        except subprocess.CalledProcessError as error:
            if error.returncode != 1 or error.output.strip():
                raise
            paths = []  # Package Manager's ordinary absent-package response.
        installed = None
        if paths:
            if len(paths) != 1 or not paths[0].startswith('package:/'):
                raise ValueError('Expected one installed base APK; split/ambiguous installs need manual review')
            with tempfile.TemporaryDirectory(prefix='installed-apk-', dir=args.output) as temporary:
                saved = Path(temporary) / 'installed.apk'
                run(adb + ['pull', paths[0].removeprefix('package:'), str(saved)])
                installed = apk_info(saved, args.build_tools.resolve())
        # Only fixed identity/hash commands, never read Linux user-file contents.
        identity = subprocess.run(adb + ['shell', 'run-as', APP, 'sh', '-c',
            "'id -u; stat -c %i files/linux/rootfs; sha256sum files/linux/rootfs/root/.config/omamobile/home.json files/linux/rootfs/root/.local/share/omamobile/profiles/phone.json files/linux/rootfs/root/.local/share/omamobile/profiles/desktop.json'"],
            capture_output=True, text=True, timeout=30)
        reasons = blockers(candidate, installed, abi, android_sdk, outputs)
        report.update(candidate=candidate, installed=installed, deviceAbi=abi, androidSdk=android_sdk,
                      displays=outputs, linuxIdentityAndHashes=identity.stdout.strip().splitlines(),
                      linuxIdentityAvailable=identity.returncode == 0, blockers=reasons, readyForDeviceTest=not reasons)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        report['error'] = str(error)
    finally:
        (args.output / 'preflight.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'readyForDeviceTest': report['readyForDeviceTest'], 'report': str(args.output / 'preflight.json'),
                      'blockers': report.get('blockers'), 'error': report.get('error')}))
    return 0 if report['readyForDeviceTest'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
