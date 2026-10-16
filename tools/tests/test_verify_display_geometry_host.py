import importlib.util
import json
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "verify-display-geometry.py"
SPEC = importlib.util.spec_from_file_location("verify_display_geometry", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class GeometryHostTests(unittest.TestCase):
    def test_existing_display_geometry_is_scoped_to_display_id(self):
        text = "DisplayInfo{displayId=0, real 1080 x 2400}\nDisplayInfo{displayId=12, real 1280 x 720}"
        self.assertEqual(MODULE.geometry_for_display(text, 12), (1280, 720))
        self.assertIsNone(MODULE.geometry_for_display(text, 4))

    def test_handshake_requires_an_object(self):
        self.assertEqual(json.loads(json.dumps({"phase": 1}))["phase"], 1)


if __name__ == "__main__":
    unittest.main()
