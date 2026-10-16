# Desktop display scale preview

The Android displays panel offers desktop scale previews at 1.00x, 1.25x,
1.50x, 2.00x and 3.00x. Start Omarchy screens before previewing. Only the
adapter-generated desktop monitors.lua is eligible. Custom configuration and
symlink paths are preserved. The existing Hyprland file watcher applies the
atomic replacement live; neither display session needs to restart.

DesktopService is the single mutation and timeout owner. Before publishing a
preview, it atomically records the exact original/applied bytes and a deadline
in one private journal. Keep confirms the change; Revert or a 30-second service
timeout restores the original only if the file still contains preview bytes.
Later user edits are left intact and logged. The service also rolls back an
unconfirmed preview when stopped or recreated after process death. No Activity
or open dialog is needed to own recovery. A malformed journal is reported in
Android logs and is preserved rather than guessed at.

The dialog receives a package-scoped change notification after service actions,
so Keep/Revert and scale buttons reflect completed actions. Android modes and
rotation remain read-only. The upstream desktop Display panel remains available
for normal Omarchy controls; this is a narrow phone-accessible recovery control.

## Verification - 2026-10-15

On disposable API35 emulator-5556, DisplayPreviewTest passed in 58.286 seconds.
It clicked the real controls and observed native Hyprland scale: 1 to 1.25,
explicit revert to 1, confirm 1.25, then preview 1.5 and close the dialog. The
service timeout restored 1.25. Both compositor signatures stayed unchanged and
the phone remained at scale 3. Original monitor bytes were restored afterward.

A separate real preview/force-stop/reopen check restored the exact original
monitor bytes and removed the pending journal before fresh sessions became
ready. No prepared-emulator data, physical device or Shizuku grant was changed.

Run the focused Android test explicitly, avoiding Gradle connected-test uninstall
cleanup:

```sh
adb -s emulator-5556 shell am instrument -w -e graphics true -e displayPreview true \
  -e class dev.omamobile.host.DisplayPreviewTest \
  dev.omamobile.host.test/androidx.test.runner.AndroidJUnitRunner
```

The test requires an existing two-display installation with pristine desktop
scale 1 and phone scale 3. JVM checks cover confirmation, deadline, journal
recreation, unpublished-write recovery, conflicting user edits and malformed
state; symlink behavior is checked where supported by the test filesystem.
Broader rotation, resolution/density, sleep/resume and hardware mode controls
remain issue #2 work. This does not join the two compositor sessions.
