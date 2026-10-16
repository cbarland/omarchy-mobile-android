# External display geometry verification

`tools/verify-display-geometry.py` runs the existing Linux session probe against
an already-present external display on the disposable API 35 emulator. It asks
the emulator to resize slot 1 in place at `1920×1080@160`, `1280×720@160`,
`720×1280@240`, and `1920×1080@160`, and checks actual Hyprland monitor geometry
through `hyprctl -j monitors`. Each phase requires the same phone and desktop
owner PIDs and Hyprland signatures. It also checks Android's observed dimensions
and density. The exact original geometry/density is restored and checked in
`finally`; a failed restoration fails the audit. No instrumentation is launched.

Precondition: configure emulator slot 1 and start both shells first. The script
checks the named disposable AVD and an existing external emulator display. The
emulator console has no read-only slot inventory, so the caller must ensure the
desktop uses slot 1. Do not run it with a different slot arrangement.

Run it only against the prepared disposable emulator:

```powershell
python tools/verify-display-geometry.py --serial emulator-5556
```

The result is written to `app/build/verification/display-geometry.json`.
