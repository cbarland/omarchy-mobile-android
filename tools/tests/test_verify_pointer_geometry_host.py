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
        app_calls = []
        def app(text):
            app_calls.append(text)
            if len(app_calls) == 2:
                raise subprocess.CalledProcessError(1, text)
        fixture._app = app
        with self.assertRaises(subprocess.CalledProcessError):
            fixture.start()
        fixture.stop()
        self.assertTrue(any(command[-2:] == ["rm", fixture.remote_script] for command in calls))
        self.assertIn("files/linux/rootfs/root/.", app_calls[0])
        self.assertTrue(all("session" not in text for text in app_calls))

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

    def test_probe_counts_new_events_after_the_viewport_converges(self):
        phase = {"mode": (1920, 1080, 160), "monitor": {"scale": 1, "transform": 0},
                 "owners": {"desktop": {"displayId": 2}}}
        surface = {"frame": (0, 0, 1920, 1080), "crop": (0.0, 0.0, 1920.0, 1080.0)}
        allocation = {"width": 1920, "height": 1080}
        class RecordingFixture:
            def __init__(self):
                self.events = [{"cell": 0, "x": 480, "y": 270},
                               {"cell": 3, "x": 1440, "y": 810}]
                self.taps = []
            def read(self):
                return {"allocation": allocation, "events": list(self.events)}
            def tap(self, display, x, y):
                self.taps.append((display, x, y))
                self.events.append({"cell": 0 if x < 960 else 3, "x": x, "y": y})
        fixture = RecordingFixture()
        with patch.object(M, "wait_phase_viewport", return_value=(surface, allocation)):
            result = M.probe_phase([], "emulator-5558", M.ROOT, phase, fixture)
        self.assertEqual(fixture.taps, [(2, 480, 270), (2, 1440, 810)])
        self.assertEqual(len(fixture.events), 4)
        self.assertEqual(len(result["events"]), 2)
        self.assertEqual(phase["fixtureAllocation"], allocation)

    def test_probe_rejects_old_matching_events_when_new_taps_are_lost(self):
        phase = {"mode": (1920, 1080, 160), "monitor": {"scale": 1, "transform": 0},
                 "owners": {"desktop": {"displayId": 2}}}
        surface = {"frame": (0, 0, 1920, 1080), "crop": (0.0, 0.0, 1920.0, 1080.0)}
        fixture = unittest.mock.Mock()
        fixture.read.return_value = {"events": [{"cell": 0, "x": 480, "y": 270},
                                                 {"cell": 3, "x": 1440, "y": 810}]}
        with patch.object(M, "wait_phase_viewport", return_value=(surface, {"width": 1920, "height": 1080})), \
                patch.object(M.time, "monotonic", side_effect=[0, 0, 6]), patch.object(M.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "exactly two new events"):
                M.probe_phase([], "emulator-5558", M.ROOT, phase, fixture)

    def test_surface_and_gtk_poll_until_both_converge(self):
        phase = {"mode": (1920, 1080, 160), "monitor": {"scale": 1, "transform": 0}}
        old = {"frame": (0, 0, 1280, 720), "crop": (0.0, 0.0, 1280.0, 720.0)}
        new = {"frame": (0, 0, 1920, 1080), "crop": (0.0, 0.0, 1920.0, 1080.0)}
        surfaces = iter([old, new, new])
        allocations = iter([{"width": 1280, "height": 720}, {"width": 1920, "height": 1080}])
        self.assertEqual(M.wait_phase_viewport(phase, lambda: next(surfaces), lambda: next(allocations), timeout=1)[0], new)

    def test_viewport_never_converging_fails_closed(self):
        phase = {"mode": (1920, 1080, 160), "monitor": {"scale": 1, "transform": 0}}
        stale = {"frame": (0, 0, 1280, 720), "crop": (0.0, 0.0, 1280.0, 720.0)}
        with self.assertRaisesRegex(RuntimeError, "did not converge"):
            M.wait_phase_viewport(phase, lambda: stale,
                                  lambda: {"width": 1280, "height": 720}, timeout=.01)


if __name__ == "__main__":
    unittest.main()
