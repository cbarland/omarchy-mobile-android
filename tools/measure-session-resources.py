#!/usr/bin/env python3
"""Measure bounded Android/guest session resources for one operator-selected phase.

This command samples already-running processes; it does not start, stop, or
reset a session. The operator supplies the phase and process identities.
"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

APP = "dev.omamobile.host"
PROFILES = ("phone", "desktop")


def parse_proc_stat(text):
    line = text.strip()
    closing = line.rfind(") ")
    if closing < 0:
        raise ValueError("malformed /proc stat: missing command terminator")
    pid_text, rest = line[:closing], line[closing + 2:]
    try:
        pid = int(pid_text.split(" ", 1)[0])
        fields = rest.split()
        # rest starts at field 3 (state); field N is fields[N - 3].
        return {"pid": pid, "state": fields[0], "utime": int(fields[11]),
                "stime": int(fields[12]), "starttime": int(fields[19]),
                "rssPages": int(fields[21])}
    except (IndexError, ValueError) as error:
        raise ValueError("malformed /proc stat fields") from error


def parse_proc_uid(text):
    for line in text.splitlines():
        if line.startswith("Uid:"):
            fields = line.split()
            if len(fields) >= 2:
                return int(fields[1])
    raise ValueError("/proc status has no Uid field")


def identity_matches(before, after):
    return (before["pid"] == after["pid"] and
            before["starttime"] == after["starttime"] and
            before.get("ownerPid") == after.get("ownerPid") and
            before.get("ownerStarttime") == after.get("ownerStarttime"))


def parse_positive_health(text):
    try:
        monitors = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError("health command did not return JSON") from error
    if not isinstance(monitors, list) or not monitors:
        raise ValueError("health JSON has no monitors")
    normalized = []
    for monitor in monitors:
        if not isinstance(monitor, dict):
            raise ValueError("health JSON contains a malformed monitor")
        width, height = monitor.get("width"), monitor.get("height")
        if not isinstance(width, int) or isinstance(width, bool) or width <= 0:
            raise ValueError("health monitor width is not positive")
        if not isinstance(height, int) or isinstance(height, bool) or height <= 0:
            raise ValueError("health monitor height is not positive")
        normalized.append({"width": width, "height": height})
    return normalized


def parse_assignment(value, separator="="):
    name, found, payload = value.partition(separator)
    if not found or not name or not payload:
        raise argparse.ArgumentTypeError(f"expected NAME{separator}VALUE")
    return name, payload


def run(command, timeout):
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"command failed: {command!r}: {result.stderr[-400:]}")
    return result.stdout


class Sampler:
    def __init__(self, args):
        self.args = args
        self.adb = [args.adb, "-s", args.serial]
        self.root = Path(__file__).resolve().parents[1]
        self.avd = run(self.adb + ["emu", "avd", "name"], args.command_timeout).splitlines()[0].strip()
        if self.avd != args.expected_avd:
            raise RuntimeError(f"refusing AVD {self.avd!r}; expected {args.expected_avd!r}")
        self.app_uid = int(run(self.adb + ["exec-out", "run-as", APP, "id", "-u"], args.command_timeout).strip())
        self.page_size = int(run(self.adb + ["shell", "getconf", "PAGESIZE"], args.command_timeout).strip())
        self.clock_ticks = int(run(self.adb + ["shell", "getconf", "CLK_TCK"], args.command_timeout).strip())
        if self.page_size <= 0 or self.clock_ticks <= 0:
            raise RuntimeError("getconf returned a nonpositive page size or clock tick rate")

    def android_stat(self, pid):
        stat = parse_proc_stat(run(self.adb + ["exec-out", "run-as", APP, "cat", f"/proc/{pid}/stat"], self.args.command_timeout))
        uid = parse_proc_uid(run(self.adb + ["exec-out", "run-as", APP, "cat", f"/proc/{pid}/status"], self.args.command_timeout))
        if uid != self.app_uid:
            raise RuntimeError(f"Android PID {pid} is not owned by {APP}")
        if stat["pid"] != pid:
            raise RuntimeError(f"Android PID {pid} stat identity does not match")
        return stat

    def android_thread_stat(self, pid, tid):
        owner = self.android_stat(pid)
        base = f"/proc/{pid}/task/{tid}"
        stat = parse_proc_stat(run(self.adb + ["exec-out", "run-as", APP, "cat", base + "/stat"], self.args.command_timeout))
        status = run(self.adb + ["exec-out", "run-as", APP, "cat", base + "/status"], self.args.command_timeout)
        tgid = re.search(r"^Tgid:\s*(\d+)\s*$", status, re.MULTILINE)
        if stat["pid"] != tid or parse_proc_uid(status) != self.app_uid or not tgid or int(tgid[1]) != pid:
            raise RuntimeError(f"Android TID {tid} does not belong to app PID {pid}")
        if not identity_matches(owner, self.android_stat(pid)):
            raise RuntimeError(f"Android thread owner PID {pid} changed during collection")
        # Threads share the process address space; never sum their RSS as
        # independently owned memory.
        del stat["rssPages"]
        return dict(stat, ownerPid=pid, ownerStarttime=owner["starttime"])

    def guest_stat(self, profile, pid):
        command = [sys.executable, str(self.root / "tools/emulator-linux.py"), "--adb", self.args.adb,
                   "--serial", self.args.serial, "session", profile, f"cat /proc/{pid}/stat"]
        stat = parse_proc_stat(run(command, self.args.command_timeout))
        uid_command = [sys.executable, str(self.root / "tools/emulator-linux.py"), "--adb", self.args.adb,
                        "--serial", self.args.serial, "session", profile, f"cat /proc/{pid}/status"]
        if parse_proc_uid(run(uid_command, self.args.command_timeout)) != self.app_uid:
            raise RuntimeError(f"guest PID {pid} is not owned by the app UID")
        return stat

    def health(self, profile):
        command = [sys.executable, str(self.root / "tools/emulator-linux.py"), "--adb", self.args.adb,
                   "--serial", self.args.serial, "session", profile, "hyprctl -j monitors"]
        return parse_positive_health(run(command, self.args.command_timeout))


def timed_stat(read):
    started = time.monotonic()
    sample = read()
    return dict(sample, readStartedMonotonicSeconds=started,
                readFinishedMonotonicSeconds=time.monotonic())


def measured(sample, previous, key, page_size):
    current = dict(sample)
    current["cpuTicks"] = sample["utime"] + sample["stime"]
    if "rssPages" in sample:
        current["rssBytes"] = sample["rssPages"] * page_size
    prior = previous.get(key)
    if prior is not None:
        current["cpuDeltaTicks"] = current["cpuTicks"] - prior["cpuTicks"]
        if "rssBytes" in current:
            current["rssDeltaBytes"] = current["rssBytes"] - prior["rssBytes"]
    previous[key] = current
    return current


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-avd", required=True)
    parser.add_argument("--adb", default=shutil.which("adb") or
                        str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--phase", choices=("visible-idle", "hidden"), required=True)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--interval", type=float, default=1.0)
    parser.add_argument("--command-timeout", type=float, default=20.0)
    parser.add_argument("--output", type=Path, default=Path("app/build/verification/session-resources.json"))
    parser.add_argument("--android-process", action="append", default=[], metavar="NAME=PID")
    parser.add_argument("--android-thread", action="append", default=[], metavar="NAME=PID:TID",
                        help="sample an app-owned thread, validating its containing process identity")
    parser.add_argument("--guest-process", action="append", default=[], metavar="PROFILE:NAME=PID")
    parser.add_argument("--health-profile", action="append", choices=PROFILES)
    args = parser.parse_args(argv)
    if not args.serial.startswith("emulator-"):
        parser.error("only named emulator serials are supported")
    if args.samples < 2 or args.samples > 60:
        parser.error("--samples must be between 2 and 60")
    if not math.isfinite(args.interval) or args.interval <= 0 or args.interval > 60:
        parser.error("--interval must be in (0, 60]")
    if not math.isfinite(args.command_timeout) or args.command_timeout <= 0 or args.command_timeout > 120:
        parser.error("--command-timeout must be in (0, 120]")
    if not args.android_process or not args.guest_process:
        parser.error("supply at least one --android-process and one --guest-process")
    return args


def main(argv=None):
    args = parse_args(argv)
    android = [parse_assignment(item) for item in args.android_process]
    if any(int(pid) <= 0 for _, pid in android) or len({name for name, _ in android}) != len(android):
        raise SystemExit("Android process labels must be unique and PIDs must be positive")
    guest = []
    threads = []
    for item in args.android_thread:
        name, spec = parse_assignment(item)
        pid, tid = parse_assignment(spec, ":")
        if int(pid) <= 0 or int(tid) <= 0:
            raise SystemExit("thread PIDs and TIDs must be positive")
        threads.append((name, int(pid), int(tid)))
    for item in args.guest_process:
        profile, spec = parse_assignment(item, ":")
        if profile not in PROFILES:
            raise SystemExit(f"unsupported guest profile: {profile}")
        name, pid = parse_assignment(spec)
        if int(pid) <= 0:
            raise SystemExit("guest PIDs must be positive")
        guest.append((profile, name, int(pid)))
    labels = ([("android", name) for name, _ in android] +
              [("android-thread", name) for name, _, _ in threads] +
              [(f"guest:{profile}", name) for profile, name, _ in guest])
    if len(set(labels)) != len(labels):
        raise SystemExit("process labels must be unique within each source")
    health_profiles = args.health_profile or list(PROFILES)
    if len(set(health_profiles)) != len(health_profiles):
        raise SystemExit("health profiles must be unique")
    sampler = Sampler(args)
    samples = []
    identities = {}
    started = time.monotonic()
    previous = {}
    for index in range(args.samples):
        collection_started = time.monotonic()
        observed = {"index": index, "monotonicSeconds": collection_started - started,
                    "processes": {}, "health": {}}
        for name, pid_text in android:
            sample = timed_stat(lambda: sampler.android_stat(int(pid_text)))
            key = f"android:{name}"
            if key in identities and not identity_matches(identities[key], sample):
                raise RuntimeError(f"PID identity changed for {key}")
            identities.setdefault(key, sample)
            observed["processes"][key] = measured(sample, previous, key, sampler.page_size)
        for name, pid, tid in threads:
            sample = timed_stat(lambda: sampler.android_thread_stat(pid, tid))
            key = f"android-thread:{name}"
            if key in identities and not identity_matches(identities[key], sample):
                raise RuntimeError(f"thread or owner identity changed for {key}")
            identities.setdefault(key, sample)
            observed["processes"][key] = measured(sample, previous, key, sampler.page_size)
        for profile, name, pid in guest:
            sample = timed_stat(lambda: sampler.guest_stat(profile, pid))
            key = f"guest:{profile}:{name}"
            if key in identities and not identity_matches(identities[key], sample):
                raise RuntimeError(f"PID identity changed for {key}")
            identities.setdefault(key, sample)
            observed["processes"][key] = measured(sample, previous, key, sampler.page_size)
        for profile in health_profiles:
            observed["health"][profile] = sampler.health(profile)
        observed["collectionElapsedSeconds"] = time.monotonic() - collection_started
        samples.append(observed)
        if index + 1 < args.samples:
            time.sleep(args.interval)
    variation = {}
    for key in samples[0]["processes"]:
        values = [sample["processes"][key] for sample in samples]
        variation[key] = {"cpuDeltaTicks": [item["cpuDeltaTicks"] for item in values[1:]]}
        if "rssBytes" in values[0]:
            variation[key].update(rssBytesMin=min(item["rssBytes"] for item in values),
                                  rssBytesMax=max(item["rssBytes"] for item in values))
    result = {"phase": args.phase, "serial": args.serial, "avd": sampler.avd,
              "avdGuard": args.expected_avd,
              "units": {"cpu": "proc clock ticks (utime+stime)", "rss": "bytes",
                        "elapsed": "monotonic seconds"},
              "pageSize": sampler.page_size, "clockTicksPerSecond": sampler.clock_ticks,
              "sampleIntervalSeconds": args.interval,
              "samples": samples, "variation": variation, "identity": identities, "conditions": {
                  "healthRequiredEverySample": True, "batteryMeasured": False,
                  "operatorSelectedPhase": True, "sampleCount": args.samples,
                  "healthProfiles": health_profiles,
                  "intervalIsPauseSeconds": True,
                  "samplingCadenceIncludesObserverCost": True,
                  "observerCostRecorded": True,
                  "threadRssReported": False,
                  "identityRule": "pid plus proc starttime; threads also validate owner pid and starttime"}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"samples": len(samples), "phase": args.phase, "output": str(args.output)}))


if __name__ == "__main__":
    main()
