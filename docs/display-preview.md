# Desktop display scale preview

The Android displays panel offers adapter-owned desktop scale previews at
1.00×, 1.25×, 1.50×, 2.00× and 3.00×. A preview edits only the generated
desktop profile file under `root/.local/share/omamobile/profiles/desktop/hypr/`
and restarts the existing foreground screen owner. The phone profile remains
separate and is restarted by the same owner so the phone remains available
after the preview cycle.

The pending preview stores the exact original and applied bytes plus a
30-second deadline under the app files directory. Keep removes that pending
state. Revert checks that the file still contains the preview bytes before
restoring the original. If a user edits the file, revert reports a changed
configuration and leaves it untouched. A missing, symbolic-link, or custom
monitor file is rejected. Session construction also expires a pending preview,
so an Activity or host process restart cannot leave an expired preview active.

## Emulator evidence procedure

On a disposable prepared two-display emulator, start screens, open **Android
displays**, choose a scale preview, and verify that both sessions return after
the owner restart. Confirm the desktop profile's `monitors.lua` contains the
preview scale, then select **Revert desktop scale preview** and confirm the
exact original bytes return. Repeat with **Keep desktop scale preview** and
reopen the app. For timeout/restart evidence, leave the preview pending for
30 seconds or force-stop/reopen the app after the deadline; the original bytes
must return before the desktop session starts. Edit the monitor file while a
preview is pending and verify that revert leaves the edit intact.

This procedure does not establish Android hardware mode, rotation, virtual
resolution, or multi-external-display support.
