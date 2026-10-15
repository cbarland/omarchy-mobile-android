# Workflow compatibility evidence

This note records the session contract audited for issue #1. It is scoped to
normal Omarchy application discovery and launch; it is not a claim of complete
desktop-entry or plugin compatibility.

## Session identity

The pinned Omarchy source checkout is `e71a816e9032f040225e086c7256f73e8080ae45`
(MIT license, `C:/Users/daneb/AndroidStudioProjects/_omarchy_upstream_20261011/LICENSE`).
At `shell/services/AppLibrary.qml:150-151`, the upstream app library builds its
hidden-entry scan input from `XDG_CURRENT_DESKTOP`, `XDG_SESSION_DESKTOP`, and
`DESKTOP_SESSION`. At `shell/services/hidden-entries.sh:59-67`, entries are
filtered according to `Hidden`, `NoDisplay`, `OnlyShowIn`, and `NotShowIn`.

`RuntimeSpec.graphicalEnvironment` supplies `XDG_SESSION_TYPE` and
`XDG_CURRENT_DESKTOP` plus the Android session marker at
`app/src/main/java/dev/omamobile/host/RuntimeSpec.java:52-61`. Both graphical
profiles use the same map: `GraphicalSession` clears the child environment,
adds that map, and starts the packaged `start-shell` at
`app/src/main/java/dev/omamobile/host/GraphicalSession.java:169-208`.
The shell adapter now fills the two remaining desktop identity variables and
`TERMINAL`, while preserving any deliberately inherited value.

## Reproducible proof

Run from the repository root in Linux bash:

```text
python3 tools/tests/test_start_shell.py
python3 tools/tests/test_session_exec.py
```

Observed on WSL Ubuntu on 2026-10-15: both checks passed (`2` and `8` tests,
respectively). The test uses a fake `quickshell` executable, so it observes the
actual environment exported by `start-shell` without requiring an emulator or
mutating Linux user data. The Android launch path is covered by the source
inspection above; the decisive emulator check is to inspect the launcher state
and open a desktop entry marked with `OnlyShowIn=Hyprland` or
`NotShowIn=GNOME` from both phone and desktop profiles.

## XERJ retrieval record

The focused `ax-omamobile-shell-*` index returned the upstream launch contract
in `plugins/README.md`: app launches use `uwsm-app -- gtk-launch`, and the
desktop-entry filtering path is part of the normal app library. The focused
index was live and returned bounded passages. No upstream source was copied;
the adapter change supplies the environment expected by that MIT-licensed
source.
