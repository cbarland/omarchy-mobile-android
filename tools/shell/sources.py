"""One reviewed source lock for packaging, compatibility checks and release gates."""
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

DEFAULT_LOCK = Path(__file__).with_name('sources.lock.json')


def load_sources(path=DEFAULT_LOCK):
    value = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(value, dict) or type(value.get('format')) is not int or value['format'] != 1:
        raise ValueError('Expected source lock format 1')
    sources = value.get('sources')
    if not isinstance(sources, dict) or set(sources) != {'omarchy', 'mobile', 'widgets'}:
        raise ValueError('Source lock must contain omarchy, mobile and widgets')
    for name, source in sources.items():
        if not isinstance(source, dict) or set(source) != {'url', 'commit'}:
            raise ValueError(f'{name}: expected url and commit')
        url, commit = source['url'], source['commit']
        if not isinstance(commit, str) or not re.fullmatch('[0-9a-f]{40}', commit):
            raise ValueError(f'{name}: expected a full lowercase Git commit')
        if not isinstance(url, str) or any(c.isspace() for c in url):
            raise ValueError(f'{name}: expected an HTTPS Git URL')
        parsed = urlsplit(url)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment:
            raise ValueError(f'{name}: expected an uncredentialed HTTPS Git URL')
    return sources
