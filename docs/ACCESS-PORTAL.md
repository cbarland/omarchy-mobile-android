# Rootless Android Access portal

The fresh shell payload stages Omarchy Mobile's MIT-licensed
`omamobile-portal-access` backend and its D-Bus activation metadata from the
pinned Mobile checkout (`953bcb3058347459cdf7faaa1e149f16e0f7cfa6`). The
payload enables only `org.freedesktop.impl.portal.Access=omamobile` and sets
`default=*` so each other interface follows the first installed implementation
selected by the portal resolver. FileChooser, PipeWire, and every other
interface remain unverified and unsupported by this payload.

The signed Arch package setup installs the required `xdg-desktop-portal`,
`python-dbus`, and Python GLib packages. An APK update does not rewrite an
existing rootfs payload. Refresh and reapply the packaged payload, then run
the normal authorized shell setup for an existing installation; running setup
alone cannot create a helper that is absent from the payload. A missing
backend, failed lock-state query, or unavailable visible sheet fails the
request closed.

Run `verify-access-portal allow` or `verify-access-portal deny` inside a
prepared phone or desktop graphical session. The harness first proves that
the Access plugin has no pending request, submits one unique request, prints
its exact ID, and waits up to 60 seconds for the human to tap the visible
sheet in that profile. It never calls the backend's Answer method. On failure
it closes the harness-owned D-Bus request, then dismisses only that exact
request ID if the plugin still reports it as current; an unrelated current
request is left untouched. A successful run is reported only after the real
UI reply and an empty post-reply snapshot.

The reviewed fresh-install run exercised the direct canonical backend in both
profiles. Phone Allow and Deny returned portal `uint32 0` and `uint32 1`;
desktop Allow and Deny returned the same values, and each queue was empty
after the reply. This proves the Access backend round trip only. It does not
establish Camera frontend selection, Flatpak, FUSE, other portal interfaces,
or Android hardware permission behavior.
