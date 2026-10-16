import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("pointer_geometry", Path(__file__).parents[1] / "verify-pointer-geometry.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)


class PointerGeometryTests(unittest.TestCase):
    def test_fixture_constructor_is_side_effect_free_and_failed_install_cleans_remote(self):
        with patch.object(M.subprocess, "run") as run:
            fixture = M.Fixture("adb", "emulator-5558")
            run.assert_not_called()
        calls = []
        fixture._call = lambda command: calls.append(command)
        fixture._session = lambda text: (_ for _ in ()).throw(subprocess.CalledProcessError(1, text))
        with self.assertRaises(subprocess.CalledProcessError):
            fixture.start()
        fixture.stop()
        self.assertTrue(any(command[-2:] == ["rm", fixture.remote_script] for command in calls))

    def test_surface_geometry_requires_one_unambiguous_desktop_layer(self):
        dump = "Output Layer 1 (SurfaceView[dev.omamobile.host/dev.omamobile.host.DesktopActivity](BLAST))\n"
        dump += "displayFrame=[ 0 0 1920 1080 ] sourceCrop=[ 0.0 0.0 1920.0 1080.0 ]\n"
        self.assertEqual(M.surface_geometry(dump), {"frame": (0, 0, 1920, 1080),
                                                    "crop": (0.0, 0.0, 1920.0, 1080.0)})
        with self.assertRaisesRegex(RuntimeError, "ambiguous"):
            M.surface_geometry(dump + dump)
        with self.assertRaisesRegex(RuntimeError, "crop"):
            M.surface_geometry(dump.replace("sourceCrop=[ 0.0 0.0 1920.0 1080.0 ]",
                                             "sourceCrop=[ 4.0 0.0 1920.0 1080.0 ]"))

    def test_phase_comparison_requires_exact_cells_and_one_pixel_rounding(self):
        phase = {"mode": (1920, 1080, 160), "monitor": {"scale": 1, "transform": 0},
                 "fixtureAllocation": {"width": 1920, "height": 1080}}
        surface = {"frame": (0, 0, 1920, 1080), "crop": (0.0, 0.0, 1920.0, 1080.0)}
        events = [{"cell": 0, "x": 480.5, "y": 270.0}, {"cell": 3, "x": 1440.0, "y": 810.5}]
        result = M.compare_phase(phase, surface, events, [(480, 270), (1440, 810)])
        self.assertEqual(result["scale"], 1)
        bad = list(events)
        bad[1] = dict(bad[1], cell=2)
        with self.assertRaisesRegex(RuntimeError, "Wrong cell"):
            M.compare_phase(phase, surface, bad, [(480, 270), (1440, 810)])

    def test_phase_comparison_rejects_transform_and_surface_mismatch(self):
        phase = {"mode": (1920, 1080, 160), "monitor": {"scale": 1, "transform": 1},
                 "fixtureAllocation": {"width": 1920, "height": 1080}}
        surface = {"frame": (0, 0, 1920, 1080), "crop": (0.0, 0.0, 1920.0, 1080.0)}
        with self.assertRaisesRegex(RuntimeError, "transform"):
            M.compare_phase(phase, surface, [], [])
        phase["monitor"]["transform"] = 0
        with self.assertRaisesRegex(RuntimeError, "Non-finite"):
            M.compare_phase(phase, surface, [{"cell": 0, "x": float("nan"), "y": 270},
                                             {"cell": 3, "x": 1440, "y": 810}],
                            [(480, 270), (1440, 810)])

    def test_stale_events_are_not_accepted_as_new_phase_events(self):
        phase = {"mode": (1920, 1080, 160), "monitor": {"scale": 1, "transform": 0},
                 "fixtureAllocation": {"width": 1920, "height": 1080}}
        surface = {"frame": (0, 0, 1920, 1080), "crop": (0.0, 0.0, 1920.0, 1080.0)}
        with self.assertRaisesRegex(RuntimeError, "expected"):
            M.compare_phase(phase, surface, [{"cell": 0, "x": 480, "y": 270}],
                            [(480, 270), (1440, 810)])


if __name__ == "__main__":
    unittest.main()
