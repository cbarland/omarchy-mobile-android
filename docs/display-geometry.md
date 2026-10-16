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

## Absolute pointer routing

`tools/verify-pointer-geometry.py` adds a first-party fullscreen GTK fixture to
the same four-phase audit. It waits for the actual SurfaceFlinger frame/crop
and GTK allocation to match the mode before sending two absolute taps to the
external Android display. Each phase must record two new events in the expected
cells, within one logical pixel of the coordinates implied by Hyprland's scale.
Old matching events cannot satisfy a later phase. Nonzero transforms and
ambiguous or cropped surfaces fail the audit.

```powershell
python tools/verify-pointer-geometry.py --serial emulator-5558
```

The driver accepts only the named Adapter Test or Install Trust disposable AVD,
checks live owners before staging its unique fixture, and removes only its own
process and files afterward. The geometry audit restores the original mode on
failure; restoration or fixture cleanup failure makes the result fail. The
default report is `app/build/verification/pointer-geometry.json`.

The lead independently ran all four phases on the API 35 Install Trust emulator
against graphical main `5c5e6f8`. All eight taps matched, both owner PIDs and
Hyprland signatures remained stable, and the original `1920×1080@160` mode was
restored. All observed Hyprland scales were 1.0. This proves absolute ADB input
routing through Android and the real Wayland client under those mode changes;
fractional scaling, relative Bluetooth mouse input, nonzero transforms and
physical monitors remain separate checks.
