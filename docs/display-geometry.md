# External display geometry verification

`tools/verify-display-geometry.py` runs the existing Linux session probe against
an already-present external display on the disposable API 35 emulator. It asks
the emulator to resize slot 1 in place at `1920×1080@160`, `1280×720@160`,
`720×1280@240`, and `1920×1080@160`, and checks actual Hyprland monitor geometry
through `hyprctl -j monitors`. Each phase requires the same phone and desktop
owner PIDs. The original geometry is restored; the script refuses to add or
delete a missing display and does not launch instrumentation.

Run it only against the prepared disposable emulator:

```powershell
python tools/verify-display-geometry.py --serial emulator-5556
```

The result is written to `app/build/verification/display-geometry.json`.
