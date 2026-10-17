# Input and keyboard quick reference

What the Android host does with touch, hardware keys and the phone IME, so a
new user can get a Linux window focused and typed into without reading the
graphics implementation notes. For rendering, displays and sessions see
[GRAPHICS.md](GRAPHICS.md); for Android app windows see
[ANDROID-APPS.md](ANDROID-APPS.md).

Source of truth: the activity in
`app/src/main/java/dev/omamobile/host/CompositorActivity.java` (pointer capture,
key mapping, IME) and `docs/VERIFICATION.md` for what was actually verified.

## Focusing a Linux window

In the desktop session (one eligible external Android display), the host asks
for focus and pointer capture as soon as the window has focus, is shown and is
attached. The capture retries briefly because focus and the replacement input
channel do not always become ready in the same frame. Touch moves the Linux
cursor; taps and clicks go to the focused client.

The phone session (Omarchy Mobile on the phone display) does not take pointer
capture and never sends this input path.

## Mouse capture and Ctrl+Alt+Escape

While the desktop window is focused, the pointer is captured by the host, so
taps and drags act on the Linux cursor instead of the Android UI. To hand the
pointer back to Android without leaving the session, press
**Ctrl + Alt + Escape**. The host releases pointer capture on that key and stops
retrying until the window regains focus. The matching key-up is consumed even
if a modifier is released first.

Ctrl+Alt+Escape is checked before the plain Escape handling below, so the
release shortcut never reaches the Linux session.

## Escape is Linux, Android Back is Android

**Escape** is forwarded to the focused Linux client as the Escape key (scan
code 1); the host consumes it before the IME sees it, so it closes dialogs and
cancels modes inside the session rather than moving Android focus.

**Android Back** (the system Back gesture or key) is deliberately *not*
forwarded: the host hands it back to Android, so it keeps its Android meaning
(navigating away from the app, opening the app switcher). Use Escape for
Linux-side cancel and Back for Android-side navigation.

## Phone IME

Text typed with the Android keyboard reaches the focused Linux client as
committed Unicode: the IME keeps only a small local pending draft, and on
commit (or when composition finishes) the draft is sent to the client as
Unicode input. Backspace from the IME is sent as the Backspace key.

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
rest), use the upstream Omarchy shortcut documentation rather than a second
list maintained here; this host only forwards what Omarchy itself binds.

## Verification limits

The input contract above was exercised on an API 35 emulator
(`emulator-5556`), not on a physical device. Touch feel, hardware-key scan
codes, Bluetooth pairing and IME behavior on real phones remain unverified;
see [VERIFICATION.md](VERIFICATION.md) for the recorded runs and what they do
not cover.