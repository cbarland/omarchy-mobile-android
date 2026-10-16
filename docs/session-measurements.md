# Session resource measurements

`tools/measure-session-resources.py` is an operator-driven, emulator-only
sampler. It does not start, stop, hide, or reopen a session. The operator first
puts the prepared emulator into one phase—`visible-idle` or `hidden`—using the existing normal controls, then supplies the live Android
app PID and guest process PIDs.

The sampler takes a finite number of samples and records raw `/proc` CPU ticks,
RSS bytes, PID start times, process counts, elapsed monotonic time, collection
elapsed time, and actual
positive Hyprland monitor geometry for every requested graphical profile. A PID
that keeps its number but changes its `/proc` start time is rejected as reuse.
Every claimed sample therefore includes a health check; the output does not
claim battery use, thermal behavior, or whole-device resource totals.

Example shape (the process/count commands are chosen from the prepared
emulator and recorded with the run):

```powershell
python tools/measure-session-resources.py --serial emulator-5556 `
  --expected-avd Omamobile_Adapter_Test_API35 `
  --phase visible-idle --samples 5 --interval 1 `
  --android-process app=7269 `
  --guest-process phone:quickshell=1234 `
  --guest-process desktop:quickshell=2345 `
  --android-count-command "ps -A | grep -c dev.omamobile.host" `
  --guest-count phone="pgrep -c -f quickshell" `
  --guest-count desktop="pgrep -c -f quickshell"
```

The command accepts only `emulator-*` serials and requires the exact expected AVD
name. `--phase hidden` is a measurement label, not an action; a stopped/zero-process
audit remains an existing shutdown check outside this sampler. The operator must
record how the phase was established and preserve the prepared data and grants. Compare repeated runs
under the same ABI, API level, display topology, sample count, interval, and
foreground/background conditions. This tool is not a battery benchmark and
does not use root, Shizuku, a private-device scan, or synthetic `/proc` data.

Android process samples are read through `adb exec-out run-as dev.omamobile.host`
and require the `/proc` status UID to match the app UID. Guest samples require
the guest session's root UID. The supplied process count commands are operator
evidence only; their command text is recorded and their provenance is not
independently verified. The interval is a pause between collection attempts,
not the complete sampling cadence: each sample records observer collection time.
CPU is reported as raw `utime+stime` ticks and deltas, with no CPU percentage
claim because no per-process elapsed/clock-rate normalization is performed.
