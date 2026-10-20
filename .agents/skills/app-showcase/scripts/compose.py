"""Compose whole PNG captures into self-contained showcase HTML."""
import argparse
import base64
import hashlib
import html
import json
from pathlib import Path
import re
import struct


def compose(spec_path, destination):
    spec = json.loads(spec_path.read_text(encoding='utf-8'))
    if not 1 <= len(spec['captures']) <= 3:
        raise ValueError('Use one to three captures')
    escape = lambda value: html.escape(str(value), quote=True)
    cards, manifest = [], []
    for capture in spec['captures']:
        path = spec_path.parent / capture['path']
        data = path.read_bytes()
        if data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
            raise ValueError('Capture is not PNG: ' + path.name)
        width, height = struct.unpack('>II', data[16:24])
        if not width or not height:
            raise ValueError('Invalid capture dimensions')
        manifest.append({'file': path.name, 'label': capture['label'], 'width': width,
                         'height': height, 'sha256': hashlib.sha256(data).hexdigest()})
        image = base64.b64encode(data).decode('ascii')
        cards.append(f'''<figure style="--ratio:{width / height}">
          <figcaption data-fit>{escape(capture['label'])}<small>{escape(capture.get('note', ''))}</small></figcaption>
          <div class="screen"><img src="data:image/png;base64,{image}" width="{width}" height="{height}" alt="{escape(capture['label'])}"></div>
        </figure>''')
    def items(key):
        values = spec[key]
        if not 1 <= len(values) <= 3:
            raise ValueError(key + ': use one to three short lines')
        return ''.join('<li>' + escape(value) + '</li>' for value in values)
    template = '''<!doctype html><html><head><meta charset="utf-8">
<meta name="showcase-size" content="2000x1250"><title>@@TITLE@@</title><style>
*{box-sizing:border-box}html,body{margin:0;width:2000px;height:1250px;background:#10131a;color:#f1f3ed;font-family:Arial,sans-serif}
body{padding:48px 64px 32px;display:flex;flex-direction:column;gap:24px}
header{display:flex;justify-content:space-between;align-items:start;height:148px;flex:none}
h1{font-size:62px;letter-spacing:-2.2px;margin:0 0 12px;line-height:1.03;font-weight:750}header p{font-size:26px;color:#c2c9d4;margin:0;line-height:1.4}
.context{max-width:420px;text-align:right;font-size:20px;line-height:1.55;color:#b3d8c1;white-space:pre-line}
.scene{height:705px;display:flex;justify-content:center;align-items:start;gap:40px;padding:0 32px;flex:none}
figure{margin:0;width:calc(650px * var(--ratio) + 16px);max-width:100%;flex-shrink:1}
figcaption{font-size:21px;letter-spacing:.2px;height:48px;display:flex;justify-content:space-between;align-items:start;gap:16px}
figcaption small{font-size:17px;color:#aeb8c9;text-align:right;line-height:1.2}
.screen{background:#050609;border:8px solid #303641;padding:0}
img{display:block;width:100%;height:auto;object-fit:contain}
.details{display:grid;grid-template-columns:1fr 1fr .9fr;gap:42px;padding-top:25px;border-top:1px solid #3b4350;height:173px;flex:none}
h2{font-size:17px;letter-spacing:2.5px;text-transform:uppercase;color:#b3d8c1;margin:0 0 16px}
ul{margin:0;padding:0;list-style:none}li{font-size:21px;line-height:1.55;color:#e1e5ee}
.cta{font-size:30px;font-weight:700;margin-bottom:14px;line-height:1.05}.url{font-size:19px;color:#b3d8c1;overflow-wrap:anywhere;line-height:1.35}
footer{font-size:17px;color:#aeb8c9;line-height:1.5;display:flex;justify-content:space-between;gap:35px}
</style></head><body>
<header><div data-fit><h1>@@TITLE@@</h1><p>@@SUBTITLE@@</p></div><div class="context" data-fit>@@CONTEXT@@</div></header>
<section class="scene">@@CARDS@@</section>
<section class="details"><div data-fit><h2>Demonstrated</h2><ul>@@DEMONSTRATED@@</ul></div><div data-fit><h2>Project goals</h2><ul>@@GOALS@@</ul></div><div data-fit><div class="cta">@@CTA@@</div><div class="url">@@URL@@</div></div></section>
<footer><span data-fit>@@CREDITS@@</span><span>Actual app captures · No simulated UI</span></footer>
</body></html>'''
    values = {key.upper(): escape(spec[key]) for key in ['title', 'subtitle', 'context', 'cta', 'url', 'credits']}
    values.update(CARDS=''.join(cards), DEMONSTRATED=items('demonstrated'), GOALS=items('goals'))
    document = re.sub(r'@@([A-Z]+)@@', lambda match: values[match.group(1)], template)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(document, encoding='utf-8', newline='\n')
    receipt = {'captures': manifest, 'htmlSha256': hashlib.sha256(document.encode('utf-8')).hexdigest(),
               'screenshotEdits': 'none; inputs embedded verbatim, proportionally scaled by CSS'}
    destination.with_suffix('.captures.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('spec', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(compose(args.spec, args.output), indent=2))
