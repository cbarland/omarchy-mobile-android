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

## Quickshell discovery and reload boundary

The pinned Omarchy Mobile documentation shows `quickshell list --all` as a
diagnostic for selecting the nested shell's Wayland display, while the
upstream Omarchy CLI sends normal operations through the shell's own IPC
socket. On hosted Android sessions, the former can omit a live instance even
when `omarchy-shell shell ping` succeeds. `omamobile-shell status` keeps these
signals separate: discovery is diagnostic, and IPC ping is the readiness
proof. `omamobile-shell reload` forwards `reloadConfig` and preserves its exit
status. `omamobile-shell list` remains the raw Quickshell discovery command.
The helper is Android-session-only and does not create a daemon or systemd
scope.

The source reference is Omarchy Mobile commit
`953bcb3058347459cdf7faaa1e149f16e0f7cfa6`,
[`packages/omamobile-shell/plugins/README.md`](https://github.com/derekross/omarchy-mobile/blob/953bcb3058347459cdf7faaa1e149f16e0f7cfa6/packages/omamobile-shell/plugins/README.md#testing-in-the-desktop-harness),
under its MIT license. The adapter does not copy upstream code; it preserves
the documented CLI boundary and adds only the Android-session lifecycle
wrapper.

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

## Emulator integration evidence - 2026-10-15

On disposable API35 emulator-5556, the unchanged pinned Omarchy installer
rejected the fixture's untrusted certificate with an actual certificate trust
diagnostic, without creating a checkout or changing the selected theme. The
same prepared snapshot then accepted a trusted retry with CA trust scoped to
that child operation. The generated commit, palette and wallpaper hashes and
peer shell acknowledgements passed on both profiles.

Both owners were stopped normally and reopened. New compositor signatures,
retained theme hashes and live shell state passed for both profiles. Direct
SurfaceFlinger screenshots before and after reopening showed the cyan fixture
wallpaper on both screens. The original theme/cache/GTK data was restored by
exact snapshot comparison; the fixture checkout was removed. The harness's
renderedPaletteVerified field remains false because visual inspection is a
separate lead check, not an assertion made by the script itself.

During this run, a scrolled host console exposed a developer-helper failure:
off-screen controls are absent from Android's accessibility dump. The helper
now performs bounded Tab navigation within the host window to reveal the
control, and refuses to navigate an obscuring system window. Actual reopening
from that scrolled state passed; focused host regressions cover both outcomes.

This establishes generated-fixture TLS and restoration behavior. Public
third-party theme compatibility, broader menus/plugins and full Linux service
support remain open in issue #1. No physical device or prepared-emulator data
was used or modified by these checks.
