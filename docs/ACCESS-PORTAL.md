# Rootless Android Access portal

The fresh shell payload stages Omarchy Mobile's MIT-licensed
`omamobile-portal-access` backend and its D-Bus activation metadata from the
pinned Mobile checkout (`953bcb3058347459cdf7faaa1e149f16e0f7cfa6`). The
payload enables only `org.freedesktop.impl.portal.Access=omamobile;gtk`.
FileChooser and all other portal interfaces remain on distro defaults.

The signed Arch package setup installs the required `xdg-desktop-portal`,
`python-dbus`, and Python GLib packages. An APK update does not rewrite an
existing rootfs payload; rerun the authorized shell setup for an existing
installation. A missing backend, failed lock-state query, or unavailable
phone sheet fails the request closed.

Run `verify-access-portal allow` or `verify-access-portal deny` inside a
prepared graphical session. The harness first proves that the Access plugin
has no pending request, submits one unique request, prints its exact ID, and
waits for the human to tap the visible phone sheet. It never calls the
backend's Answer method or dismisses the sheet directly. Cleanup closes only
the harness-owned D-Bus request ID.
