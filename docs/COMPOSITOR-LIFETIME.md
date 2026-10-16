# Compositor process ownership

The main `DesktopService` foreground service owns one nonexported, bound-only
service in each active compositor process. `BIND_IMPORTANT | BIND_AUTO_CREATE`
lets Android account for the background compositor as part of the ongoing
user-started graphical session. The external binding exists only while an
external output is selected. This uses ordinary Android service ownership;
it does not change freezer settings, acquire a wake lock, require Shizuku, or
create another foreground notification.

Removing a binding stops that compositor locally before it can become cached.
Stop and service destruction release both bindings. Per-registration connection
identity and generation prevent a late binding-death callback from restarting
an intentionally stopped or replaced output.

## Emulator evidence

The previous APK twice failed the phone monitor query in a nine-test graphics,
Ando, shutdown, pointer, display-preview and service sequence. A capture before
instrumentation cleanup showed the live phone process cached at adjustment 900,
`isFrozen=true`, and all 28 threads in `do_freezer_trap`. The foreground service
in the main process did not protect the separate phone process.

With bound ownership, the same nine-test sequence passed in 107.222 seconds on
the prepared API35 x86_64 emulator. A separate 65-second background check
preserved both Hyprland signatures and live IPC, then verified both compositor
processes exited after Stop and were not immediately recreated (79.999 seconds
total). During that check Android reported actual important bindings and a
noncached, unfrozen phone process. This standalone check also passes on the old
APK when Android retains the phone as its previous app; it does not force cached
promotion and is additional lifecycle coverage rather than the negative control.

The existing ordinary and rapid external reconnect audit passed with exact
preference restoration. These are emulator lifecycle results, not physical
device, deep-idle, power-consumption, or arbitrary OEM-policy guarantees.
