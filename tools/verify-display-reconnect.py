#!/usr/bin/env python3
"""Run the disposable-emulator display reconnect instrumentation protocol."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
from pathlib import Path
from typing import Any


AVD_NAME = "Omamobile_Adapter_Test_API35"
TEMP_SLOT = "2"
TEMP_ADD_ARGS = [TEMP_SLOT, "1280", "720", "160", "0"]
APP_PACKAGE = "dev.omamobile.host"
TEST_PACKAGE = f"{APP_PACKAGE}.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
HANDSHAKE = "files/graphics/display-selection-handshake.json"
MAX_RUNTIME = 240.0


class AuditError(RuntimeError):
    pass


class Adb:
    def __init__(self, path: str, serial: str, log: list[dict[str, Any]]) -> None:
        self.path = path
        self.serial = serial
        self.log = log

    def run(self, args: list[str], timeout: float = 15.0, *, check: bool = True) -> str:
        command = [self.path, "-s", self.serial, *args]
        entry: dict[str, Any] = {"command": command, "timeout": timeout}
        try:
            result = subprocess.run(
                command, capture_output=True, text=True, timeout=timeout, check=False
            )
        except subprocess.TimeoutExpired as exc:
            entry.update({"timeoutExpired": True, "stdout": exc.stdout or "", "stderr": exc.stderr or ""})
            self.log.append(entry)
            raise AuditError(f"adb timeout: {' '.join(command)}") from exc
        stdout = result.stdout or ""
        stderr = result.stderr or ""
        entry.update({"returncode": result.returncode, "stdout": stdout, "stderr": stderr})
        self.log.append(entry)
        if args[:1] == ["emu"] and re.search(r"\bKO\b", stdout, re.IGNORECASE):
            raise AuditError(f"emulator console rejected command: {' '.join(command)}: {stdout.strip()}")
        if check and result.returncode:
            raise AuditError(f"adb failed ({result.returncode}): {' '.join(command)}: {stderr.strip()}")
        return stdout


def display_ids(text: str) -> set[int]:
    ids: set[int] = set()
    for line in text.splitlines():
        if "mBaseDisplayInfo" not in line:
            continue
        match = re.search(r"mBaseDisplayInfo=DisplayInfo\{[^\n]*?displayId\s*[=:]?\s*(\d+)", line)
        if match:
            ids.add(int(match.group(1)))
    return ids


def has_1280x720_output(text: str) -> bool:
    return bool(
        re.search(r"\b1280\s*[x×]\s*720\b", text, re.IGNORECASE)
        or re.search(r"width\s*[=:]\s*1280[^\n]*height\s*[=:]\s*720", text, re.IGNORECASE)
        or re.search(r"size\s*[=:]\s*1280\s*[x×]\s*720", text, re.IGNORECASE)
    )


def parse_handshake(raw: str | None) -> dict[str, Any] | None:
    if not raw:
        return None
    try:
        value = json.loads(raw)
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


def remaining(deadline: float) -> float:
    value = deadline - time.monotonic()
    if value <= 0:
        raise AuditError("240 second runtime limit exceeded")
    return value


def wait_until(predicate, deadline: float, interval: float = 0.5) -> Any:
    while True:
        result = predicate()
        if result:
            return result
        remaining(deadline)
        time.sleep(max(0.2, interval))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="disposable emulator serial")
    parser.add_argument(
        "--adb",
        default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"),
    )
    parser.add_argument(
        "--output", type=Path, default=Path("app/build/verification/display-reconnect.json")
    )
    parser.add_argument(
        "--rapid",
        action="store_true",
        help="after the ordinary reconnect, exercise the immediate delete/add path",
    )
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        raise AuditError("refusing non-emulator serial")

    adb_log: list[dict[str, Any]] = []
    adb = Adb(args.adb, args.serial, adb_log)
    started = time.monotonic()
    deadline = started + MAX_RUNTIME
    result: dict[str, Any] = {
        "serial": args.serial,
        "approvedAvd": AVD_NAME,
        "temporarySlot": TEMP_SLOT,
        "temporaryAddArgs": TEMP_ADD_ARGS,
        "rapidRequested": args.rapid,
        "instrumentation": {"package": TEST_PACKAGE, "runner": RUNNER},
        "phases": [],
        "adb": adb_log,
        "passed": False,
    }
    process: subprocess.Popen[str] | None = None
    created = False
    disconnected = False
    reconnected = False

    try:
        if adb.run(["get-state"], timeout=10).strip() != "device":
            raise AuditError("emulator is not in device state")
        avd_lines = [line.strip() for line in adb.run(["emu", "avd", "name"], timeout=10).splitlines()]
        avd = next((line for line in avd_lines if line and line.upper() != "OK"), "")
        result["avdName"] = avd
        if avd != AVD_NAME:
            raise AuditError(f"refusing unapproved AVD: {avd!r}")
        if adb.run(["shell", "getprop", "ro.kernel.qemu"], timeout=10).strip() != "1":
            raise AuditError("refusing a non-emulator kernel")
        hardware = adb.run(["shell", "getprop", "ro.hardware"], timeout=10).strip()
        result["hardware"] = hardware
        if hardware not in {"ranchu", "goldfish"}:
            raise AuditError(f"unsupported emulator hardware: {hardware!r}")

        before_dump = adb.run(["shell", "dumpsys", "display"], timeout=20)
        if "virtual:com.android.emulator.multidisplay:1234563" in before_dump:
            raise AuditError("required temporary display unique ID is already active")
        if has_1280x720_output(before_dump):
            raise AuditError("another 1280x720 output is already active")
        before_ids = display_ids(before_dump)
        result["displayIdsBefore"] = sorted(before_ids)
        old_handshake_raw = None
        try:
            old_handshake_raw = adb.run(
                ["exec-out", "run-as", APP_PACKAGE, "cat", HANDSHAKE], timeout=10, check=False
            )
        except AuditError:
            old_handshake_raw = None
        result["previousHandshake"] = parse_handshake(old_handshake_raw)
        previous_run = (parse_handshake(old_handshake_raw) or {}).get("run")

        adb.run(["emu", "multidisplay", "add", *TEMP_ADD_ARGS], timeout=15)
        created = True
        result["temporarySlotCreated"] = True

        def find_new_id() -> int | None:
            ids = display_ids(adb.run(["shell", "dumpsys", "display"], timeout=20))
            new = ids - before_ids
            return min(new) if len(new) == 1 else None

        first_display_id = wait_until(find_new_id, deadline)
        result["firstDisplayId"] = first_display_id
        command = [
            adb.path,
            "-s",
            args.serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-e",
            "graphics",
            "true",
        ]
        command.extend(["-e", "firstDisplayId", str(first_display_id)])
        if args.rapid:
            command.extend(["-e", "rapidReconnect", "true"])
        command.extend([
            "-e",
            "class",
            "dev.omamobile.host.DisplaySelectionPersistenceTest",
            f"{TEST_PACKAGE}/{RUNNER}",
        ])
        result["instrumentation"]["command"] = command
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

        def handshake() -> tuple[str | None, dict[str, Any] | None, str | None]:
            raw = adb.run(
                ["exec-out", "run-as", APP_PACKAGE, "cat", HANDSHAKE], timeout=10, check=False
            )
            value = parse_handshake(raw)
            return (value.get("phase") if value else None, value, raw)

        def wait_phase(expected: str) -> dict[str, Any]:
            def ready() -> dict[str, Any] | None:
                phase, value, raw = handshake()
                if phase and phase not in result["phases"]:
                    result["phases"].append(phase)
                fresh = value is not None and (
                    previous_run is None or value.get("run") != previous_run
                )
                if fresh and phase == "complete" and value.get("passed") is not True:
                    raise AuditError("fresh instrumentation run reported complete with passed=false")
                if phase == expected and fresh and (old_handshake_raw is None or raw != old_handshake_raw):
                    return value
                if process is not None and process.poll() is not None:
                    raise AuditError(f"instrumentation exited before phase {expected} (code {process.returncode})")
                return None

            return wait_until(ready, deadline)

        wait_phase("ready-for-disconnect")
        adb.run(["emu", "multidisplay", "del", TEMP_SLOT], timeout=15)
        disconnected = True
        wait_phase("ready-for-reconnect")
        adb.run(["emu", "multidisplay", "add", *TEMP_ADD_ARGS], timeout=15)
        reconnected = True
        if args.rapid:
            wait_phase("ready-for-rapid-reconnect")
            # Deliberately issue the pair back-to-back. The emulator console
            # command completion is the only synchronization between them.
            adb.run(["emu", "multidisplay", "del", TEMP_SLOT], timeout=15)
            disconnected = True
            reconnected = False
            adb.run(["emu", "multidisplay", "add", *TEMP_ADD_ARGS], timeout=15)
            reconnected = True
        complete = wait_phase("complete")
        result["completeHandshake"] = complete
        remaining(deadline)
        stdout, stderr = process.communicate(timeout=max(1.0, remaining(deadline)))
        result["instrumentation"].update({"returncode": process.returncode, "stdout": stdout, "stderr": stderr})
        if process.returncode != 0 or "OK (1 test)" not in stdout:
            raise AuditError("instrumentation did not report OK (1 test)")
        if complete.get("passed") is not True or complete.get("preferencesRestored") is not True:
            raise AuditError("instrumentation did not report passed=true and preferencesRestored=true")
        if args.rapid and complete.get("rapidReconnectVerified") is not True:
            raise AuditError("rapid reconnect was requested but the final handshake did not verify it")
        result["passed"] = True
    except (AuditError, OSError) as exc:
        result["error"] = str(exc)
    finally:
        # Finish the test before touching the temporary display. If reconnect failed,
        # restore slot 2 first so the app can complete its bounded cleanup path.
        if process is not None and process.poll() is None:
            try:
                process.communicate(timeout=max(1.0, min(20.0, remaining(deadline))))
            except (subprocess.TimeoutExpired, AuditError):
                pass
        if created:
            if disconnected and not reconnected:
                try:
                    adb.run(["emu", "multidisplay", "add", *TEMP_ADD_ARGS], timeout=15)
                    reconnected = True
                except AuditError as exc:
                    result.setdefault("cleanupErrors", []).append(str(exc))
            try:
                adb.run(["emu", "multidisplay", "del", TEMP_SLOT], timeout=15)
            except AuditError as exc:
                result.setdefault("cleanupErrors", []).append(str(exc))
        if process is not None:
            if process.poll() is None:
                try:
                    process.terminate()
                    process.communicate(timeout=5)
                except (OSError, subprocess.TimeoutExpired):
                    pass
            if "stdout" not in result["instrumentation"]:
                stdout, stderr = process.communicate(timeout=5)
                result["instrumentation"].update(
                    {"returncode": process.returncode, "stdout": stdout, "stderr": stderr}
                )
        result["elapsedSeconds"] = round(time.monotonic() - started, 3)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")

    print(json.dumps({"passed": result["passed"], "output": str(args.output), "error": result.get("error")}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AuditError as exc:
        print(json.dumps({"passed": False, "error": str(exc)}))
        raise SystemExit(1)
