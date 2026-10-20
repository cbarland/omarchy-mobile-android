#!/usr/bin/env python3
"""Build/test a Mobile branch or exact commit without changing reviewed pins or APK assets."""
import argparse
import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

from shell.sources import DEFAULT_LOCK, load_sources

ROOT = Path(__file__).resolve().parents[1]


def resolve_mobile(url, reference):
    if re.fullmatch('[0-9a-f]{40}', reference):
        return reference
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._/-]*', reference):
        raise ValueError('Expected a branch name or full lowercase commit')
    ref = reference if reference.startswith('refs/heads/') else 'refs/heads/' + reference
    output = subprocess.check_output(['git', 'ls-remote', '--exit-code', '--refs', url, ref],
                                     text=True, timeout=60).splitlines()
    if len(output) != 1:
        raise ValueError('Branch did not resolve to exactly one commit')
    commit, actual_ref = output[0].split()
    if actual_ref != ref or not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Unexpected upstream ref response')
    return commit


def payload_diff(old, new):
    before = {x['path']: (x['sha256'], x['mode']) for x in old['payloadMembers']}
    after = {x['path']: (x['sha256'], x['mode']) for x in new['payloadMembers']}
    return {'added': sorted(after.keys() - before.keys()),
            'removed': sorted(before.keys() - after.keys()),
            'changed': sorted(name for name in before.keys() & after.keys() if before[name] != after[name])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mobile-ref', default='main', help='Branch or full commit; resolved once per run')
    parser.add_argument('--cache', type=Path, default=ROOT / '.build-tools/sources')
    parser.add_argument('--output', type=Path, help='New directory for candidate, logs and report')
    args = parser.parse_args()
    output = (args.output or ROOT / 'app/build/upstream' / uuid.uuid4().hex[:12]).resolve()
    output.mkdir(parents=True, exist_ok=False)
    pins = load_sources()
    original_lock = DEFAULT_LOCK.read_bytes()
    report = {'format': 1, 'requestedMobileRef': args.mobile_ref, 'baselinePins': pins,
              'payloadCompatible': False, 'runtimeVerification': 'required; not performed by this command',
              'productionPinsChanged': False, 'steps': []}

    def step(name, command):
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=600)
        (output / (name + '.stdout')).write_text(result.stdout, encoding='utf-8', newline='\n')
        (output / (name + '.stderr')).write_text(result.stderr, encoding='utf-8', newline='\n')
        report['steps'].append({'name': name, 'passed': result.returncode == 0})
        if result.returncode:
            raise RuntimeError(f'{name} failed; inspect {name}.stderr and {name}.stdout')
        return result.stdout

    try:
        commit = resolve_mobile(pins['mobile']['url'], args.mobile_ref)
        candidate = copy.deepcopy(pins)
        candidate['mobile']['commit'] = commit
        report.update(resolvedMobileCommit=commit, candidatePins=candidate)
        lock = output / 'candidate.lock.json'
        lock.write_text(json.dumps({'format': 1, 'sources': candidate}, indent=2) + '\n', encoding='utf-8', newline='\n')
        prepare = [sys.executable, str(ROOT / 'tools/prepare-install-assets.py'), '--cache', str(args.cache.resolve())]
        step('baseline-package', prepare + ['--output', str(output / 'baseline')])
        packaged = json.loads(step('candidate-package', prepare + ['--sources-lock', str(lock), '--output', str(output / 'candidate')]))
        payload = output / 'candidate/linux-shell.tgz'
        step('shared-shell-contract', [sys.executable, str(ROOT / 'tools/tests/test_unified_shell_payload.py'), str(payload)])
        command = [sys.executable, str(ROOT / 'tools/tests/test_prepare_linux_shell.py'), '--sources-lock', str(lock),
                   '--work-directory', str(output / 'canonical')]
        for name, checkout in packaged['checkouts'].items():
            command.extend(['--' + name, checkout])
        step('canonical-source-contract', command)
        before = json.loads((output / 'baseline/install-manifest.json').read_text())
        after = json.loads((output / 'candidate/install-manifest.json').read_text())
        report.update(payloadCompatible=True, payloadDiff=payload_diff(before, after))
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        report['error'] = str(error)
    finally:
        report['productionPinsChanged'] = DEFAULT_LOCK.read_bytes() != original_lock
        if report['productionPinsChanged']:
            report['payloadCompatible'] = False
            report['error'] = 'Reviewed source lock changed while this check was running; candidate must be rerun'
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'report': str(output / 'report.json'), 'payloadCompatible': report['payloadCompatible'],
                      'runtimeVerification': report['runtimeVerification']}))
    return 0 if report['payloadCompatible'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
