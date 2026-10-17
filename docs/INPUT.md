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

## Android IME

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
