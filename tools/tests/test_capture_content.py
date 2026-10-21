import contextlib
import io
import itertools
import json
from pathlib import Path
import runpy
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from capture_content import raw_frame, frame_content, frame_png


class CaptureContentTests(unittest.TestCase):
    def test_blank_cursor_only_and_uniform_frames_are_rejected(self):
        for pixel in (b'\0\0\0\xff', b'\x04\x04\x04\xff', b'\xff' * 4):
            self.assertFalse(frame_content((64, 64, 4, pixel * 4096))['hasContent'])
        cursor = bytearray(b'\0\0\0\xff' * 4096)
        cursor[:16] = b'\xff' * 16
        self.assertFalse(frame_content((64, 64, 4, cursor))['hasContent'])

    def test_actual_rgb_components_survive_header_formats_and_png_encoding(self):
        for format, source, expected in [(1, b'\x10\x20\x30\xff', b'\x10\x20\x30\xff'),
                                         (2, b'\x10\x20\x30\0', b'\x10\x20\x30\xff'),
                                         (3, b'\x10\x20\x30', b'\x10\x20\x30'),
                                         (5, b'\x30\x20\x10\xff', b'\x10\x20\x30\xff')]:
            for color_header in (b'', struct.pack('<I', 1)):
                with self.subTest(format=format, header=len(color_header)):
                    frame = raw_frame(struct.pack('<III', 1, 1, format) + color_header + source)
                    png = frame_png(frame)
                    self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
                    offset, compressed = 8, b''
                    while offset < len(png):
                        size = struct.unpack_from('>I', png, offset)[0]
                        name, value = png[offset + 4:offset + 8], png[offset + 8:offset + 8 + size]
                        self.assertEqual(struct.unpack_from('>I', png, offset + 8 + size)[0], zlib.crc32(name + value))
                        if name == b'IDAT':
                            compressed += value
                        offset += size + 12
                    self.assertEqual(zlib.decompress(compressed), b'\0' + expected)

    def test_nonblank_content_and_invalid_buffers(self):
        pixels = (b'\x10\x20\x30\xff' + b'\xc0\x80\x40\xff') * 2048
        self.assertTrue(frame_content((64, 64, 4, pixels))['hasContent'])
        for data in (b'', struct.pack('<III', 0, 1, 1), struct.pack('<III', 2, 2, 1) + b'x',
                     struct.pack('<III', 1, 1, 22) + b'\0' * 8):
            with self.assertRaises(ValueError):
                raw_frame(data)

    def test_blank_deadline_keeps_failed_frame_and_diagnostics(self):
        ticks = itertools.count()
        raw = struct.pack('<IIII', 64, 64, 1, 1) + b'\0\0\0\xff' * 4096
        def output(command, **kwargs):
            if 'screencap' in command:
                return raw
            return 'Display 1 (HWC display 0):\nDisplay 2 (HWC display 1):\n'
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(sys, 'argv', [str(TOOLS / 'capture-emulator.py'), '--adb', 'fake-adb',
                                           '--output', temporary, '--wait-content', '1']), \
                 patch('subprocess.check_output', side_effect=output), \
                 patch('time.monotonic', side_effect=lambda: next(ticks) * .25), patch('time.sleep'), \
                 contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(TimeoutError):
                    runpy.run_path(str(TOOLS / 'capture-emulator.py'), run_name='__main__')
            report = json.loads((Path(temporary) / 'phone.json').read_text())
            self.assertFalse(report['passed'])
            self.assertIn('deadline', report['error'])
            self.assertFalse(report['frames'][-1]['hasContent'])
            self.assertTrue((Path(temporary) / 'phone.png').is_file())


if __name__ == '__main__':
    unittest.main()
