import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("measure_session_resources", ROOT / "tools/measure-session-resources.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MeasureSessionResourcesTest(unittest.TestCase):
    class FakeSampler:
        def __init__(self, args):
            self.avd = args.expected_avd
            self.page_size = 4096
            self.clock_ticks = 100
            self.android_calls = 0

        def android_stat(self, pid):
            self.android_calls += 1
            return {"pid": pid, "state": "S", "utime": 10 + self.android_calls,
                    "stime": 2, "starttime": 100, "rssPages": 3}

        def guest_stat(self, profile, pid):
            return {"pid": pid, "state": "S", "utime": 4, "stime": 1,
                    "starttime": 200, "rssPages": 2}

        def health(self, profile): return [{"width": 1080, "height": 2424}]

        def android_thread_stat(self, pid, tid):
            return {"pid": tid, "state": "S", "utime": self.android_calls * 2,
                    "stime": 3, "starttime": 300, "ownerPid": pid, "ownerStarttime": 100}

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

    def test_thread_owner_reuse_is_rejected(self):
        before = {"pid": 22, "starttime": 300, "ownerPid": 12, "ownerStarttime": 100}
        self.assertTrue(MODULE.identity_matches(before, dict(before)))
        self.assertFalse(MODULE.identity_matches(before, dict(before, ownerStarttime=101)))
        self.assertFalse(MODULE.identity_matches(before, dict(before, ownerPid=13)))

    def test_thread_membership_checked_and_shared_rss_omitted(self):
        sampler = MODULE.Sampler.__new__(MODULE.Sampler)
        sampler.adb = ["adb", "-s", "emulator-5556"]
        sampler.args = type("Args", (), {"command_timeout": 1.0})()
        sampler.app_uid = 10209
        owner = {"pid": 12, "starttime": 100}
        fields = ["S"] + ["0"] * 21
        fields[11], fields[12], fields[19], fields[21] = "17", "5", "300", "4"
        stat = "22 (native-hyprland) " + " ".join(fields)
        for status, accepted in (("Uid:\t10209\nTgid:\t12\n", True),
                                  ("Uid:\t10209\nTgid:\t13\n", False),
                                  ("Uid:\t1000\nTgid:\t12\n", False)):
            with self.subTest(status=status), patch.object(sampler, "android_stat", return_value=owner), \
                    patch.object(MODULE, "run", side_effect=[stat, status]):
                if accepted:
                    sample = sampler.android_thread_stat(12, 22)
                    self.assertEqual(sample["ownerStarttime"], 100)
                    self.assertEqual(sample["utime"] + sample["stime"], 22)
                    self.assertNotIn("rssPages", sample)
                else:
                    with self.assertRaises(RuntimeError):
                        sampler.android_thread_stat(12, 22)

    def test_thread_read_rejects_owner_replacement_during_collection(self):
        sampler = MODULE.Sampler.__new__(MODULE.Sampler)
        sampler.adb = ["adb"]
        sampler.args = type("Args", (), {"command_timeout": 1.0})()
        sampler.app_uid = 10209
        stat = "22 (worker) S " + "0 " * 21
        with patch.object(sampler, "android_stat", side_effect=[{"pid": 12, "starttime": 100},
                                                               {"pid": 12, "starttime": 101}]), \
                patch.object(MODULE, "run", side_effect=[stat, "Uid:\t10209\nTgid:\t12\n"]):
            with self.assertRaisesRegex(RuntimeError, "changed during collection"):
                sampler.android_thread_stat(12, 22)

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

    def test_avd_mismatch_aborts_before_page_size_probe(self):
        args = type("Args", (), {"adb": "adb", "serial": "emulator-5560",
                                  "expected_avd": "Expected", "command_timeout": 1.0})()
        with patch.object(MODULE, "run", return_value="Wrong\n") as command:
            with self.assertRaises(RuntimeError):
                MODULE.Sampler(args)
        command.assert_called_once()

    def test_guest_uid_mismatch_is_rejected(self):
        sampler = MODULE.Sampler.__new__(MODULE.Sampler)
        sampler.app_uid = 10209
        sampler.root = ROOT
        sampler.args = type("Args", (), {"adb": "adb", "serial": "emulator-5560",
                                          "command_timeout": 1.0})()
        stat = "20 (guest) S " + "0 " * 21
        with patch.object(MODULE, "run", side_effect=[stat, "Uid:\t1000\n"]):
            with self.assertRaises(RuntimeError):
                sampler.guest_stat("phone", 20)

    def test_main_records_valid_bounded_workflow(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "measurements.json"
            argv = ["--serial", "emulator-5556", "--expected-avd", "Fixture",
                    "--phase", "visible-idle", "--samples", "2", "--interval", "0.01",
                    "--android-process", "app=10", "--guest-process", "phone:guest=20",
                    "--android-thread", "renderer=10:11",
                    "--health-profile", "phone", "--output", str(output)]
            with patch.object(MODULE, "Sampler", self.FakeSampler), patch.object(MODULE.time, "sleep"):
                MODULE.main(argv)
            result = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(result["avd"], "Fixture")
            self.assertEqual(len(result["samples"]), 2)
            self.assertGreaterEqual(result["samples"][1]["collectionElapsedSeconds"], 0)
            self.assertEqual(result["variation"]["android:app"]["cpuDeltaTicks"], [1])
            self.assertEqual(result["conditions"]["healthProfiles"], ["phone"])
            self.assertEqual(result["clockTicksPerSecond"], 100)
            thread = result["samples"][1]["processes"]["android-thread:renderer"]
            self.assertEqual(thread["cpuDeltaTicks"], 2)
            self.assertNotIn("rssBytes", thread)
            self.assertNotIn("rssBytesMin", result["variation"]["android-thread:renderer"])
            self.assertGreaterEqual(thread["readFinishedMonotonicSeconds"], thread["readStartedMonotonicSeconds"])

    def test_main_rejects_identity_change(self):
        class ReusedPidSampler(self.FakeSampler):
            def android_stat(self, pid):
                sample = super().android_stat(pid)
                if self.android_calls > 1:
                    sample["starttime"] = 999
                return sample

        with tempfile.TemporaryDirectory() as directory:
            argv = ["--serial", "emulator-5556", "--expected-avd", "Fixture",
                    "--phase", "visible-idle", "--samples", "2", "--interval", "0.01",
                    "--android-process", "app=10", "--guest-process", "phone:guest=20",
                    "--health-profile", "phone", "--output", str(Path(directory) / "out.json")]
            with patch.object(MODULE, "Sampler", ReusedPidSampler), patch.object(MODULE.time, "sleep"):
                with self.assertRaises(RuntimeError):
                    MODULE.main(argv)

    def test_main_rejects_nonpositive_health(self):
        class BadHealthSampler(self.FakeSampler):
            def health(self, profile):
                return MODULE.parse_positive_health('[{"width":0,"height":2424}]')

        with tempfile.TemporaryDirectory() as directory:
            argv = ["--serial", "emulator-5556", "--expected-avd", "Fixture",
                    "--phase", "visible-idle", "--samples", "2", "--interval", "0.01",
                    "--android-process", "app=10", "--guest-process", "phone:guest=20",
                    "--health-profile", "phone", "--output", str(Path(directory) / "out.json")]
            with patch.object(MODULE, "Sampler", BadHealthSampler), patch.object(MODULE.time, "sleep"):
                with self.assertRaises(ValueError):
                    MODULE.main(argv)


if __name__ == "__main__":
    unittest.main()
