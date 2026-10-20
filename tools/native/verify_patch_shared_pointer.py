"""Verify the shared-pointer patch against a retained, pinned lifecycle source tree."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import patch_shared_pointer as patch


def verify(source: Path):
    expected = json.loads((patch.HERE / 'output_lifecycle_hashes.json').read_text())
    with tempfile.TemporaryDirectory(prefix='shared-pointer-patch-') as temporary:
        root = Path(temporary)
        for name, hashes in expected.items():
            data = (source / name).read_bytes().replace(b'\r\n', b'\n')
            if hashlib.sha256(data).hexdigest() != hashes['phase2']:
                raise ValueError('Expected pinned lifecycle input: ' + name)
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        patch.prepare(root)
        first = {name: (root / name).read_bytes() for name in expected}
        assert patch.is_applied(root)
        patch.prepare(root)
        assert first == {name: (root / name).read_bytes() for name in expected}, 'Second application changed files'
        for name in expected:
            (root / name).write_bytes((source / name).read_bytes())
        modified = root / 'src/android/Embed.cpp'
        modified.write_bytes(modified.read_bytes() + b'\n// custom input\n')
        before = {name: (root / name).read_bytes() for name in expected}
        try:
            patch.apply(root)
        except RuntimeError as error:
            assert 'differs from reviewed lifecycle source' in str(error)
        else:
            raise AssertionError('Customized input was accepted')
        assert before == {name: (root / name).read_bytes() for name in expected}, 'Rejected input was modified'
    return {'pinnedInput': True, 'expectedOutput': True, 'idempotent': True, 'customInputPreserved': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    print(json.dumps(verify(parser.parse_args().source), indent=2))
