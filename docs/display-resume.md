# Display sleep/resume verification

`tools/verify-display-resume.py` is a bounded audit for the prepared disposable
AVD `Omamobile_Adapter_Test_API35` on `emulator-5556`. It records the initial
power and keyguard state, phone and desktop owner/readiness metadata, and actual
Hyprland monitor IPC. It then sends one sleep event, waits for `Asleep`, wakes the
emulator, dismisses the emulator keyguard, and repeats the checks. The `finally`
path restores the exact initial power state. It never installs, uninstalls,
resets, revokes grants, changes Shizuku state, or edits emulator data.

```powershell
python tools/verify-display-resume.py --serial emulator-5556
```

The audit requires an explicitly `Awake` and known-unlocked baseline. After wake
it polls for up to 15 seconds and requires both sessions to expose positive
monitor geometry, with the same owner PIDs, Hyprland signatures, Android display
IDs, and monitor geometry as the baseline. A baseline with dead sockets is refused
before sending sleep. An earlier manual attempt began without a recorded healthy
baseline and found dead sockets after wake; that result is inconclusive and is not
claimed as a reproduced defect. A later bounded run after restarting the prepared
screens passed and restored `Awake`. No production fix is claimed by this audit.
