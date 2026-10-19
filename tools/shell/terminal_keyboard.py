"""Select native key events for the pinned Squeekboard terminal layout."""
import json
import re


def terminal_keycodes(data: bytes) -> bytes:
    # The caller verifies the complete upstream blob before this transformation.
    # Its double-quoted view rows use the JSON-compatible YAML string subset.
    text = data.decode("utf-8").replace("\r\n", "\n")
    head, separator, buttons = text.partition("\nbuttons:\n")
    if not separator:
        raise ValueError("Expected the pinned terminal layout's buttons section")
    characters = {key for row in re.findall(r"^        - (.+)$", head, re.M)
                  for key in json.loads(row).split() if len(key) == 1}
    aliases = {"space": " ", "--thin": "-", "/-thin": "/", "|-thin": "|", ".-thin": "."}
    existing = set()
    result = []
    for block in re.split(r"(?=^    \S.*:\n)", buttons, flags=re.M):
        if not block:
            continue
        name, body = block.split(":\n", 1)
        name = name.strip()
        name = json.loads(name) if name.startswith('"') else name
        existing.add(name)
        character = aliases.get(name, name if name in characters else None)
        if character is not None and not re.search(r"^        (keysym|action|modifier):", body, re.M):
            # Keep the upstream outlines/labels; replace only text submission.
            body = re.sub(r"^        text:.*\n", "", body, flags=re.M).rstrip() + "\n"
            if name in aliases:
                body += "        label: " + json.dumps(character, ensure_ascii=False) + "\n"
            body += f'        keysym: "U{ord(character):04X}"\n'
            result.append("    " + json.dumps(name, ensure_ascii=False) + ":\n" + body)
        else:
            result.append(block)
    for character in sorted(characters - existing):
        result.append("    " + json.dumps(character, ensure_ascii=False)
                      + f':\n        keysym: "U{ord(character):04X}"\n')
    notice = ("# Android adapter: terminal text uses native virtual-keyboard events,\n"
              "# alongside terminal controls. Foot 1.28 drops text-input-v3 edits\n"
              "# with older state serials. View geometry and other layouts are unchanged.\n")
    return (notice + head + separator + "".join(result)).encode("utf-8")
