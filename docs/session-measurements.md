# Session resource measurements

`tools/measure-session-resources.py` is an operator-driven, emulator-only
sampler. It does not start, stop, hide, or reopen a session. The operator first
puts the prepared emulator into one phase—`visible-idle` or `hidden`—using the
existing normal controls, then supplies the live Android app PID and guest
process PIDs.

The sampler takes a finite number of samples and records raw `/proc` CPU ticks,
RSS bytes, PID start times, elapsed monotonic time, collection elapsed time,
and actual positive Hyprland monitor geometry for every requested graphical
profile. A PID
that keeps its number but changes its `/proc` start time is rejected as reuse.
Every claimed sample therefore includes a health check; the output does not
claim battery use, thermal behavior, or whole-device resource totals.

Example shape (the process identities are chosen from the prepared emulator):

```powershell
python tools/measure-session-resources.py --serial emulator-5556 `
  --expected-avd Omamobile_Adapter_Test_API35 `
  --phase visible-idle --samples 5 --interval 1 `
  --android-process app=7269 `
  --android-thread renderer=7269:7310 `
  --guest-process phone:quickshell=1234 `
  --guest-process desktop:quickshell=2345
```

The command accepts only `emulator-*` serials and requires the exact expected AVD
name. `--phase hidden` is a measurement label, not an action; a stopped/zero-process
audit remains an existing shutdown check outside this sampler. The operator must
record how the phase was established, including which output was hidden, and
preserve the prepared data and grants. Compare repeated runs under the same
ABI, API level, display topology, sample count, interval, and
foreground/background conditions. This tool is not a battery benchmark and
does not use root, Shizuku, a private-device scan, or synthetic `/proc` data.

Optional `--android-thread NAME=PID:TID` samples a specific embedded native
compositor thread instead of mistaking a guest launch wrapper for the renderer.
Choose the live identities from the app's `/proc/PID/task` inventory. Each read
checks the app UID, thread-group membership, thread start time, and containing
process start time. Parent identity is also checked before and after collection.
Thread RSS is omitted because its address space belongs to the process.

Every process and thread observation records its own host monotonic read start
and finish times. The report records the emulator's `getconf CLK_TCK` value;
the sampling pause alone must never be used as the CPU delta's elapsed time.
These observations do not establish frame presentation cadence.

Android process samples are read through `adb exec-out run-as dev.omamobile.host`
and require the `/proc` status UID to match the app UID. Guest processes are
also app-owned processes in the current guest architecture, so guest samples
require that same app UID; they do not assume a virtual root UID. The interval
is a pause between collection attempts, not the complete sampling cadence:
each sample records observer collection time.
CPU is reported as raw `utime+stime` ticks and deltas, with no CPU percentage
claim because no per-process elapsed/clock-rate normalization is performed.
