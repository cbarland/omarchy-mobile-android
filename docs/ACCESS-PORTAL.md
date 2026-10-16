# Rootless Android Access portal

The fresh shell payload stages Omarchy Mobile's MIT-licensed
`omamobile-portal-access` backend and its D-Bus activation metadata from the
pinned Mobile checkout (`953bcb3058347459cdf7faaa1e149f16e0f7cfa6`). The
payload enables only `org.freedesktop.impl.portal.Access=omamobile;gtk`.
FileChooser and all other portal interfaces remain on distro defaults.

The signed Arch package setup installs the required `xdg-desktop-portal`,
`python-dbus`, and Python GLib packages. An APK update does not rewrite an
existing rootfs payload. Refresh and reapply the packaged payload, then run
the normal authorized shell setup for an existing installation; running setup
alone cannot create a helper that is absent from the payload. A missing
backend, failed lock-state query, or unavailable phone sheet fails the request
closed.

Run `verify-access-portal allow` or `verify-access-portal deny` inside a
prepared graphical session. The harness first proves that the Access plugin
has no pending request, submits one unique request, prints its exact ID, and
waits for the human to tap the visible phone sheet. It never calls the
backend's Answer method. On failure it closes the harness-owned D-Bus request,
then dismisses only that exact request ID if the plugin still reports it as
current; an unrelated current request is left untouched. A successful run is
reported only after the real UI reply and an empty post-reply snapshot.
