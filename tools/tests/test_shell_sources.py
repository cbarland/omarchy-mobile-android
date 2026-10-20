import copy
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from shell.sources import load_sources


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, TOOLS / file)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


PACKAGE = module('prepare_install_sources', 'prepare-install-assets.py')
UPSTREAM = module('upstream_check', 'check-upstream.py')


class ShellSourcesTests(unittest.TestCase):
    def test_invalid_locks_reject_moving_refs_and_credentials(self):
        original = {'format': 1, 'sources': load_sources()}
        variants = []
        for value in ['main', 'a'*39, 'A'*40]:
            candidate = copy.deepcopy(original)
            candidate['sources']['mobile']['commit'] = value
            variants.append(candidate)
        for value in ['http://github.com/repo.git', 'https://user:password@github.com/repo.git', 'https://github.com/repo.git?token=value']:
            candidate = copy.deepcopy(original)
            candidate['sources']['mobile']['url'] = value
            variants.append(candidate)
        variants.extend([{'format': 2, 'sources': original['sources']}, {'format': 1, 'sources': {}}])
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'lock.json'
            for candidate in variants:
                with self.subTest(candidate=candidate):
                    path.write_text(json.dumps(candidate))
                    with self.assertRaises(ValueError):
                        load_sources(path)

    def test_candidate_cannot_publish_into_normal_apk_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            lock = Path(temporary) / 'candidate.json'
            lock.write_text(json.dumps({'format': 1, 'sources': load_sources()}))
            with mock.patch.object(sys, 'argv', ['prepare-install-assets.py', '--sources-lock', str(lock)]), \
                 mock.patch.object(PACKAGE, 'checkout') as checkout, \
                 self.assertRaises(SystemExit) as error:
                PACKAGE.main()
            self.assertEqual(error.exception.code, 2)
            checkout.assert_not_called()

    def test_mobile_ref_resolves_once_to_full_commit(self):
        sha = 'a'*40
        with mock.patch.object(UPSTREAM.subprocess, 'check_output', return_value=sha+'\trefs/heads/main\n') as remote:
            self.assertEqual(UPSTREAM.resolve_mobile('https://github.com/example/mobile.git', 'main'), sha)
            remote.assert_called_once()
        with mock.patch.object(UPSTREAM.subprocess, 'check_output') as remote:
            self.assertEqual(UPSTREAM.resolve_mobile('https://github.com/example/mobile.git', sha), sha)
            with self.assertRaises(ValueError):
                UPSTREAM.resolve_mobile('https://github.com/example/mobile.git', '--upload-pack=command')
            remote.assert_not_called()

    def test_payload_diff_includes_mode_changes_additions_and_removals(self):
        def item(name, digest='a', mode=0o644):
            return {'path': name, 'sha256': digest, 'mode': mode}
        before = {'payloadMembers': [item('same'), item('mode'), item('removed')]}
        after = {'payloadMembers': [item('same'), item('mode', mode=0o755), item('added')]}
        self.assertEqual(UPSTREAM.payload_diff(before, after), {'added': ['added'], 'removed': ['removed'], 'changed': ['mode']})

    def test_concurrent_cache_population_and_distinct_pins_preserve_sources(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'source'
            source.mkdir()
            def git(*args):
                return subprocess.check_output(['git', '-C', str(source), *args], text=True, stderr=subprocess.DEVNULL).strip()
            git('init', '--quiet')
            commits = []
            for text in ['first\n', 'second\n']:
                (source / 'value').write_text(text, newline='\n')
                git('add', 'value')
                git('-c', 'user.name=Source fixture', '-c', 'user.email=fixture@example.invalid',
                    '-c', 'commit.gpgsign=false', '-c', 'core.hooksPath=' + str(root / 'no-hooks'), 'commit', '--quiet', '-m', text.strip())
                commits.append(git('rev-parse', 'HEAD'))
            pins = {'mobile': (source.as_uri(), commits[0])}
            with ThreadPoolExecutor(max_workers=2) as workers:
                futures = [workers.submit(PACKAGE.checkout, 'mobile', None, root / 'cache', pins) for _ in range(2)]
                paths = [future.result() for future in futures]
            self.assertEqual(paths[0], paths[1])
            self.assertEqual((paths[0] / 'value').read_text(), 'first\n')
            second = PACKAGE.checkout('mobile', None, root / 'cache', {'mobile': (source.as_uri(), commits[1])})
            self.assertNotEqual(paths[0], second)
            self.assertEqual((paths[0] / 'value').read_text(), 'first\n')
            self.assertEqual((second / 'value').read_text(), 'second\n')
            self.assertEqual(git('status', '--porcelain'), '')


if __name__ == '__main__':
    unittest.main()
