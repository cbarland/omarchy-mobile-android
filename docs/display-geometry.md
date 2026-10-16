# External display geometry verification

`tools/verify-display-geometry.py` runs a bounded four-phase protocol on the
disposable API 35 emulator. It adds slot 1 at `1920×1080@160`, `1280×720@160`,
`720×1280@240`, and `1920×1080@160`, launches the focused Android probe for
each new display ID, and checks the actual Hyprland monitor geometry returned by
`hyprctl -j monitors`. Each phase records the desktop display ID, owner PID and
Hyprland signature. The script stops the screens service and removes slot 1 in
its cleanup path.

Run it only against the prepared disposable emulator:

```powershell
python tools/verify-display-geometry.py --serial emulator-5556
```

The result is written to `app/build/verification/display-geometry.json`.
