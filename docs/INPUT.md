# Input and keyboard quick reference

How touch, hardware keys and the native phone keyboard reach Linux, so a
new user can get a Linux window focused and typed into without reading the
graphics implementation notes. For rendering, displays and sessions see
[GRAPHICS.md](GRAPHICS.md); for Android app windows see
[ANDROID-APPS.md](ANDROID-APPS.md).

The Android input adapter is
`app/src/main/java/dev/omamobile/host/CompositorActivity.java`; native keyboard
startup is in `tools/linux/start-shell`, using Mobile's pinned keyboard helpers.
See `docs/VERIFICATION.md` for what was actually verified.

## Focusing a Linux window

In the desktop session (one eligible external Android display), the host asks
for focus and pointer capture as soon as the window has focus, is shown and is
attached. The capture retries briefly because focus and the replacement input
channel do not always become ready in the same frame. Captured mouse movement
and buttons go to the Linux compositor; touchscreen gestures are forwarded as
Linux touch events.

The phone session (Omarchy Mobile on the phone display) does not take pointer
capture, but its touchscreen gestures are still forwarded to the Linux touch
shell.

## Mouse capture and Ctrl+Alt+Escape

While the desktop window is focused, an external mouse's relative movement and
buttons are captured by the host, so they act on the Linux cursor instead of
the Android UI. Touchscreen gestures remain Linux touch events. To hand the
mouse back to Android without leaving the session, press
**Ctrl + Alt + Escape**. The host releases pointer capture on that key and stops
retrying until the window regains focus. The matching key-up is consumed even
if a modifier is released first.

Ctrl+Alt+Escape is checked before the plain Escape handling below, so the
release shortcut never reaches the Linux session.

## Escape is Linux, Android Back is Android

**Escape** is forwarded using Android's scan code, falling back to scan code 1
when none is supplied. The host consumes it before the IME sees it, so it closes dialogs and
cancels modes inside the session rather than moving Android focus.

**Android Back** (the system Back gesture or key) is deliberately *not*
forwarded: the host hands it back to Android, so it keeps its Android meaning,
such as dismissing the keyboard or navigating according to Android. It does not
become a Linux Escape event. Use Escape for Linux-side cancel and Back for
Android-side navigation.

## Native phone keyboard

The phone uses Omarchy Mobile's Squeekboard keyboard inside Wayland. Supported
text fields request it automatically; the **Keyboard** tile in expanded Mobile
quick settings opens it on demand. The tile may be on the second page. Mobile's
small hide button above the keyboard dismisses it. Android Back retains its
Android meaning; use the native hide button to fold Squeekboard.

Mobile's terminal layout includes Esc, Tab, Ctrl, Alt and arrows. The Android
adapter preserves its views and key sizes, and sends terminal characters and
shortcuts through Squeekboard's native virtual keyboard. This avoids Foot 1.28
discarding text-input edits with older state serials. Other text fields retain
normal Wayland text-input composition and automatic keyboard activation.
Ctrl and Alt toggle: tap a modifier again to release it after the shortcut.
The keyboard reserves Linux workspace space without resizing the Android
Surface. The phone and shared profiles start one keyboard with their shell;
the separate desktop profile does not start another. Neither root nor Shizuku
is needed.

The graphics preparation step installs the signed `squeekboard` package. For
an existing installation, use **Update Linux integration**, install that
package if missing, then reopen the phone/shared session. Existing custom profiles are
preserved: enable the `omamobile.keyboard` helper in the phone/shared plugin
configuration if it is absent. User keyboard settings and terminal layouts
are preserved. An unset screen-keyboard preference is enabled on session entry;
an explicit disabled preference remains disabled.

Automatic opening depends on the application's Wayland text-input support.
An application without that support can still use the Keyboard tile. Other
languages, applications and physical devices need their own verification;
see [VERIFICATION.md](VERIFICATION.md) for the tested paths.

## Android input connection compatibility

The host retains its Android InputConnection for input compatibility, but the
phone starts with Android's software keyboard hidden so it does not overlap
Squeekboard. The following records the separate Android IME transport contract.

Text typed with the Android keyboard reaches the focused Linux client as
committed Unicode: the IME keeps only a small local pending draft, and on
commit (or when composition finishes) the draft is sent to the client as
Unicode input. When no local draft is active, IME Backspace is sent as the
Backspace key. With a draft present, deletion is delegated to Android's local
editor and sends no Linux key; it does not provide Linux text-history editing.

Verified limits (see the "Direct IME composition contract" record in
[VERIFICATION.md](VERIFICATION.md)): the host does not claim full IME history,
selection, preedit protocol or universal Unicode toolkit compatibility.
Non-ASCII transport uses the pinned US-keyboard Ctrl+Shift+U route and stays
client/toolkit dependent.

## Key mapping

Keys that Android delivers with a scan code (most hardware and Bluetooth
keyboards) are forwarded as-is. Otherwise the host maps common Android key codes
to Linux scan codes, including letters, digits, Enter, Tab, Backspace, Space,
Ctrl, Alt, Shift, Meta and the D-pad arrows. A key with neither a scan code nor
a known mapping is forwarded as its Unicode character when it has one.

For normal Omarchy bindings (Super, workspace switching, screenshots and the
rest), use the pinned upstream Omarchy
[default binding directory](https://github.com/omacom/omarchy/tree/e71a816e9032f040225e086c7256f73e8080ae45/default/hypr/bindings)
rather than a second list maintained here. The host forwards input; Hyprland
and the focused application decide how to handle it.

## Verification limits

The direct IME composition contract above was exercised on an API 35 emulator
(`emulator-5556`). A separate physical Nubia run confirmed phone touch/swipes
and Bluetooth keyboard input plus mouse movement/clicks. Broad hardware-key
coverage, the Bluetooth pairing flow, and composition/Unicode behavior beyond
the recorded IME contract remain unverified; see [VERIFICATION.md](VERIFICATION.md)
for the recorded runs and their limits.
