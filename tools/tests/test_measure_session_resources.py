import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("measure_session_resources", ROOT / "tools/measure-session-resources.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MeasureSessionResourcesTest(unittest.TestCase):
    def test_proc_stat_handles_parentheses_and_derives_deltas(self):
        fields = ["S"] + ["0"] * 21
        fields[11] = "17"
        fields[12] = "5"
        fields[19] = "9001"
        fields[21] = "4"
        first = MODULE.parse_proc_stat("42 (guest (worker)) " + " ".join(fields))
        previous = {}
        measured = MODULE.measured(first, previous, "guest:phone:app", 4096)
        fields[11] = "20"
        fields[21] = "6"
        second = MODULE.parse_proc_stat("42 (guest (worker)) " + " ".join(fields))
        measured_again = MODULE.measured(second, previous, "guest:phone:app", 4096)
        self.assertEqual(measured["cpuTicks"], 22)
        self.assertEqual(measured["rssBytes"], 16384)
        self.assertEqual(measured_again["cpuDeltaTicks"], 3)
        self.assertEqual(measured_again["rssDeltaBytes"], 8192)

    def test_pid_reuse_is_rejected_by_starttime(self):
        before = {"pid": 12, "starttime": 100}
        self.assertTrue(MODULE.identity_matches(before, {"pid": 12, "starttime": 100}))
        self.assertFalse(MODULE.identity_matches(before, {"pid": 12, "starttime": 101}))
        self.assertFalse(MODULE.identity_matches(before, {"pid": 13, "starttime": 100}))

    def test_health_requires_positive_monitor_records(self):
        self.assertEqual(MODULE.parse_positive_health('[{"width":1080,"height":2424}]'),
                         [{"width": 1080, "height": 2424}])
        for value in ("[]", "{}", "[{\"width\":0,\"height\":100}]", "[{\"width\":100}]"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    MODULE.parse_positive_health(value)

    def test_proc_stat_rejects_truncated_input(self):
        with self.assertRaises(ValueError):
            MODULE.parse_proc_stat("42 (short) S 1 2")

    def test_count_parser_rejects_noncounts(self):
        self.assertEqual(MODULE.parse_count(" 3\n"), 3)
        for value in ("pid1 pid2", "-1", ""):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    MODULE.parse_count(value)


if __name__ == "__main__":
    unittest.main()
