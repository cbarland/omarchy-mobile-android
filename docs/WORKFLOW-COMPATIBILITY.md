# Workflow compatibility evidence

This note records the session contract audited for issue #1. It is scoped to
normal Omarchy application discovery and launch; it is not a claim of complete
desktop-entry or plugin compatibility.

## Session identity

The pinned Omarchy source is commit
`e71a816e9032f040225e086c7256f73e8080ae45` from
[basecamp/omarchy](https://github.com/basecamp/omarchy), under its
[MIT license](https://github.com/basecamp/omarchy/blob/e71a816e9032f040225e086c7256f73e8080ae45/LICENSE).
At [`shell/services/AppLibrary.qml`](https://github.com/basecamp/omarchy/blob/e71a816e9032f040225e086c7256f73e8080ae45/shell/services/AppLibrary.qml#L150-L151), the upstream app library builds its
hidden-entry scan input from `XDG_CURRENT_DESKTOP`, `XDG_SESSION_DESKTOP`, and
`DESKTOP_SESSION`. At [`shell/services/hidden-entries.sh`](https://github.com/basecamp/omarchy/blob/e71a816e9032f040225e086c7256f73e8080ae45/shell/services/hidden-entries.sh#L59-L67), entries are
filtered according to `Hidden`, `NoDisplay`, `OnlyShowIn`, and `NotShowIn`.

`RuntimeSpec.graphicalEnvironment` supplies `XDG_SESSION_TYPE` and
`XDG_CURRENT_DESKTOP` plus the Android session marker at
`app/src/main/java/dev/omamobile/host/RuntimeSpec.java:52-61`. Both graphical
profiles use the same map: `GraphicalSession` clears the child environment,
adds that map, and starts the packaged `start-shell` at
`app/src/main/java/dev/omamobile/host/GraphicalSession.java:169-208`.
The shell adapter remains intentionally small. It does not need to duplicate
those values: the compositor launch path already supplies the required
`XDG_CURRENT_DESKTOP` and `XDG_SESSION_TYPE` values to both profiles.

## Reproducible proof

Run from the repository root in Linux bash:

```text
python3 tools/tests/test_session_exec.py
```

Observed on WSL Ubuntu on 2026-10-15: the session adapter regression passed
(8 tests). The decisive emulator check was also performed: both live quickshell
processes already had `XDG_CURRENT_DESKTOP=Hyprland` and
`XDG_SESSION_TYPE=wayland`. A direct fixture audit of the upstream
`OnlyShowIn`/`NotShowIn` matcher shows that `XDG_CURRENT_DESKTOP=Hyprland`
alone makes the normal Hyprland entries visible; no missing-entry failure was
reproduced. `XDG_SESSION_DESKTOP`, `DESKTOP_SESSION`, and `TERMINAL` therefore
remain unmodified pending a consumer failure.

## XERJ retrieval record

The focused `ax-omamobile-shell-*` index returned the upstream launch contract
in `plugins/README.md`: app launches use `uwsm-app -- gtk-launch`, and the
desktop-entry filtering path is part of the normal app library. The focused
index was live and returned bounded passages. No upstream source was copied;
the audit confirmed the current environment already satisfies the relevant
desktop filtering contract.

## HTTPS theme transport check

The harness keeps its reviewed generated two-file data fixture and can exercise
the same upstream installer over an ephemeral local HTTPS Git server:

```text
python3 /root/.local/share/omamobile/verify-theme-download-persistence prepare /tmp/omamobile-theme-download-untrusted --transport loopback-https-git
python3 /root/.local/share/omamobile/verify-theme-download-persistence install /tmp/omamobile-theme-download-untrusted --untrusted-cert
python3 /root/.local/share/omamobile/verify-theme-download-persistence prepare /tmp/omamobile-theme-download-trusted --transport loopback-https-git
python3 /root/.local/share/omamobile/verify-theme-download-persistence install /tmp/omamobile-theme-download-trusted
```

The certificate has a `127.0.0.1` SAN and is trusted only through the fixture
subprocess's `GIT_SSL_CAINFO`; no global TLS override is made. The negative
phase proves an untrusted certificate fails before a theme checkout or
selection change, after which the trusted phase observes the generated commit,
peer acknowledgments, persistence, and existing restore checks. Public
third-party theme compatibility remains open and is deliberately not executed
by this harness.
