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

The first observed run on 2026-10-15 reproduced a lifecycle defect: Android
returned to `Awake`, but both `phone` and `desktop` Hyprland sockets rejected the
`hyprctl -j monitors` probe (`Couldn't connect ... socket.sock. (4)`). Readiness
files and owner metadata remained present, so those files are not sufficient
session health evidence. The script reports this as failed and still restores the
initial power state. No production fix is claimed by this audit.
