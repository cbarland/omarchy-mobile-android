import json
from pathlib import Path
import re
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
from shell.terminal_keyboard import terminal_keycodes


LAYOUT = '''# SPDX-License-Identifier: GPL-3.0-or-later
---
outlines:
    narrow: { width: 20, height: 50 }
views:
    base:
        - "a A ? α space --thin \\\\ Return Ctrl ↑"
buttons:
    space:
        outline: "narrow"
        text: " "
    --thin:
        outline: "narrow"
        text: "-"
    "\\\\":
        outline: "narrow"
    Return:
        keysym: "Return"
    Ctrl:
        modifier: "Control"
    "↑":
        keysym: "Up"
'''


class TerminalKeyboardTests(unittest.TestCase):
    def setUp(self):
        self.output = terminal_keycodes(LAYOUT.encode()).decode()
        self.buttons = {}
        for block in re.split(r"(?=^    \S.*:\n)", self.output.split("\nbuttons:\n")[1], flags=re.M):
            if not block:
                continue
            name, body = block.split(":\n", 1)
            name = name.strip()
            name = json.loads(name) if name.startswith('"') else name
            self.assertNotIn(name, self.buttons)
            self.buttons[name] = body

    def test_text_and_symbols_use_keycodes_without_ime_commits(self):
        for name, character in (("a", "a"), ("A", "A"), ("?", "?"), ("α", "α"),
                                ("space", " "), ("--thin", "-"), ("\\", "\\")):
            self.assertIn(f'keysym: "U{ord(character):04X}"', self.buttons[name])
            self.assertNotIn("text:", self.buttons[name])

    def test_native_views_geometry_and_controls_are_preserved(self):
        self.assertIn(LAYOUT.split("\nbuttons:\n")[0], self.output)
        for name in ("space", "--thin", "\\"):
            self.assertIn('outline: "narrow"', self.buttons[name])
        self.assertIn('label: "-"', self.buttons["--thin"])
        self.assertEqual(self.buttons["Return"], '        keysym: "Return"\n')
        self.assertEqual(self.buttons["Ctrl"], '        modifier: "Control"\n')
        self.assertEqual(self.buttons["↑"], '        keysym: "Up"\n')


if __name__ == "__main__":
    unittest.main()
