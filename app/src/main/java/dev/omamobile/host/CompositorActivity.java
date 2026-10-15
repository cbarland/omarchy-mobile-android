package dev.omamobile.host;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.function.Consumer;

/** Actual Linux-compositor frames on an Android Surface, with per-display Android input. */
public abstract class CompositorActivity extends Activity {
    private CompositorController controller;
    private LinuxSurface surface;
    private boolean captureReleaseKeyDown;
    private TextView status;
    private final Consumer<String> observe = value -> { status.setText(value); status.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE); };
    protected abstract boolean phone();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        controller = CompositorController.get(this, phone());
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        surface = new LinuxSurface(this);
        layout.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setTextSize(18); status.setPadding(28, 60, 28, 28);
        layout.addView(status, new FrameLayout.LayoutParams(-1, -2));
        setContentView(layout);
        controller.observe(observe);
    }
    @Override protected void onResume() {
        super.onResume();
        surface.restoreCapture();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        surface.queueCapture();
    }
    @Override protected void onDestroy() {
        controller.unobserve(observe);
        super.onDestroy();
    }

    private final class LinuxSurface extends SurfaceView implements SurfaceHolder.Callback {
        private float cursorX, cursorY;
        private int mouseButtons;
        private boolean captureAllowed = true;
        private int captureAttempts;
        private final Runnable capture = () -> tryCapture();
        LinuxSurface(Context context) {
            super(context);
            setFocusable(true); setFocusableInTouchMode(true); requestFocus();
            setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW));
            getHolder().addCallback(this);
        }
        @Override public void surfaceCreated(SurfaceHolder holder) { }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            controller.attach(this, holder.getSurface(), width, height, getDisplay().getDisplayId());
            queueCapture();
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            removeCallbacks(capture);
            releasePointerCapture();
            controller.detach(this);
        }
        private void restoreCapture() {
            captureAllowed = true;
            queueCapture();
        }
        private void queueCapture() {
            captureAttempts = 0;
            removeCallbacks(capture);
            post(capture);
        }
        private void tryCapture() {
            if (phone() || !NativeCompositor.AVAILABLE || !captureAllowed || !hasWindowFocus()
                    || !isShown() || !isAttachedToWindow() || hasPointerCapture()) return;
            requestFocus();
            requestPointerCapture();
            // Focus and the replacement input channel do not always become
            // ready in the same frame. Retry briefly, never while unfocused.
            if (++captureAttempts < 10) postDelayed(capture, 100);
        }
        private void releaseCapture() {
            captureAllowed = false;
            removeCallbacks(capture);
            releasePointerCapture();
            android.util.Log.d("OmamobilePointer", "Desktop capture released by shortcut");
        }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            if (oldWidth == 0 || oldHeight == 0) { cursorX = width / 2f; cursorY = height / 2f; }
            cursorX = Math.max(0, Math.min(width - 1, cursorX));
            cursorY = Math.max(0, Math.min(height - 1, cursorY));
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (focused && !phone() && NativeCompositor.AVAILABLE) {
                queueCapture();
            } else if (!focused) {
                removeCallbacks(capture);
                releasePointerCapture();
                if (NativeCompositor.AVAILABLE) updateButtons(0);
            }
        }
        @Override public boolean onCapturedPointerEvent(MotionEvent event) {
            if (!NativeCompositor.AVAILABLE) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < event.getHistorySize(); i++) {
                    cursorX += event.getHistoricalX(i); cursorY += event.getHistoricalY(i);
                }
                cursorX += event.getX(); cursorY += event.getY();
                cursorX = Math.max(0, Math.min(getWidth() - 1, cursorX));
                cursorY = Math.max(0, Math.min(getHeight() - 1, cursorY));
            }
            return pointer(event, cursorX, cursorY);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!NativeCompositor.AVAILABLE) return true;
            requestFocus();
            int action = event.getActionMasked(), index = event.getActionIndex();
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                // After the release shortcut, an explicit desktop click can
                // opt back in. Focus/resize events alone must not undo release.
                if (action == MotionEvent.ACTION_DOWN) restoreCapture();
                return pointer(event);
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                NativeCompositor.touch(event.getPointerId(index), 0, event.getX(index), event.getY(index));
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                NativeCompositor.touch(event.getPointerId(index), 1, event.getX(index), event.getY(index));
            else if (action == MotionEvent.ACTION_MOVE)
                for (int i = 0; i < event.getPointerCount(); i++) NativeCompositor.touch(event.getPointerId(i), 2, event.getX(i), event.getY(i));
            else if (action == MotionEvent.ACTION_CANCEL) NativeCompositor.touch(0, 3, 0, 0);
            return true;
        }
        private boolean pointer(MotionEvent event) {
            cursorX = event.getX(); cursorY = event.getY();
            return pointer(event, cursorX, cursorY);
        }
        private boolean pointer(MotionEvent event, float x, float y) {
            int action = event.getActionMasked();
            NativeCompositor.pointer(x, y, 0, false);
            int buttons = event.getButtonState();
            if (action == MotionEvent.ACTION_DOWN && buttons == 0) buttons = MotionEvent.BUTTON_PRIMARY;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) buttons = 0;
            updateButtons(buttons);
            if (action == MotionEvent.ACTION_SCROLL) NativeCompositor.axis(-event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 15, -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 15);
            return true;
        }
        private void updateButtons(int buttons) {
            int changed = mouseButtons ^ buttons;
            for (int bit : new int[]{MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY}) {
                if ((changed & bit) == 0) continue;
                int evdev = bit == MotionEvent.BUTTON_SECONDARY ? 273 : bit == MotionEvent.BUTTON_TERTIARY ? 274 : 272;
                NativeCompositor.pointer(cursorX, cursorY, evdev, (buttons & bit) != 0);
            }
            mouseButtons = buttons;
        }
        @Override public boolean onGenericMotionEvent(MotionEvent event) { return NativeCompositor.AVAILABLE && pointer(event); }
        @Override public boolean onHoverEvent(MotionEvent event) { return NativeCompositor.AVAILABLE && pointer(event); }
        @Override public boolean onCheckIsTextEditor() { return true; }
        @Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
            // Android IMEs can consume Escape DOWN before Activity dispatch,
            // leaving a Wayland application in insert mode with only the UP.
            // Consume this key here after forwarding both edges; the event
            // then cannot reach Activity dispatch and be forwarded twice.
            if (handleEscape(event)) return true;
            return super.dispatchKeyEventPreIme(event);
        }
        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            info.inputType = android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new BaseInputConnection(this, false) {
                @Override public boolean commitText(CharSequence text, int cursor) {
                    text.toString().codePoints().forEach(NativeCompositor::unicode); return true;
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < before; i++) { NativeCompositor.key(14, true); NativeCompositor.key(14, false); }
                    return true;
                }
            };
        }
    }
    private boolean handleCaptureRelease(KeyEvent event) {
        if (!NativeCompositor.AVAILABLE || phone() || event.getKeyCode() != KeyEvent.KEYCODE_ESCAPE) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.isCtrlPressed() && event.isAltPressed()) {
            captureReleaseKeyDown = true;
            surface.releaseCapture();
            return true;
        }
        // Consume the matching UP even if the user released a modifier first.
        if (event.getAction() == KeyEvent.ACTION_UP && captureReleaseKeyDown) {
            captureReleaseKeyDown = false;
            return true;
        }
        return false;
    }
    private boolean handleEscape(KeyEvent event) {
        if (handleCaptureRelease(event)) return true;
        if (!NativeCompositor.AVAILABLE || event.getKeyCode() != KeyEvent.KEYCODE_ESCAPE
                || (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)) return false;
        int code = event.getScanCode();
        NativeCompositor.key(code != 0 ? code : 1, event.getAction() == KeyEvent.ACTION_DOWN);
        return true;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Also support callers that dispatch directly to the Activity. Normal
        // ViewRoot delivery consumes Escape in LinuxSurface before the IME.
        if (handleEscape(event)) return true;
        if (!NativeCompositor.AVAILABLE || event.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        int code = event.getScanCode();
        if (code == 0) code = switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_A -> 30; case KeyEvent.KEYCODE_B -> 48; case KeyEvent.KEYCODE_C -> 46;
            case KeyEvent.KEYCODE_D -> 32; case KeyEvent.KEYCODE_E -> 18; case KeyEvent.KEYCODE_F -> 33;
            case KeyEvent.KEYCODE_G -> 34; case KeyEvent.KEYCODE_H -> 35; case KeyEvent.KEYCODE_I -> 23;
            case KeyEvent.KEYCODE_J -> 36; case KeyEvent.KEYCODE_K -> 37; case KeyEvent.KEYCODE_L -> 38;
            case KeyEvent.KEYCODE_M -> 50; case KeyEvent.KEYCODE_N -> 49; case KeyEvent.KEYCODE_O -> 24;
            case KeyEvent.KEYCODE_P -> 25; case KeyEvent.KEYCODE_Q -> 16; case KeyEvent.KEYCODE_R -> 19;
            case KeyEvent.KEYCODE_S -> 31; case KeyEvent.KEYCODE_T -> 20; case KeyEvent.KEYCODE_U -> 22;
            case KeyEvent.KEYCODE_V -> 47; case KeyEvent.KEYCODE_W -> 17; case KeyEvent.KEYCODE_X -> 45;
            case KeyEvent.KEYCODE_Y -> 21; case KeyEvent.KEYCODE_Z -> 44;
            case KeyEvent.KEYCODE_1 -> 2; case KeyEvent.KEYCODE_2 -> 3; case KeyEvent.KEYCODE_3 -> 4;
            case KeyEvent.KEYCODE_4 -> 5; case KeyEvent.KEYCODE_5 -> 6; case KeyEvent.KEYCODE_6 -> 7;
            case KeyEvent.KEYCODE_7 -> 8; case KeyEvent.KEYCODE_8 -> 9; case KeyEvent.KEYCODE_9 -> 10; case KeyEvent.KEYCODE_0 -> 11;
            case KeyEvent.KEYCODE_ENTER -> 28; case KeyEvent.KEYCODE_DEL -> 14; case KeyEvent.KEYCODE_TAB -> 15;
            case KeyEvent.KEYCODE_ESCAPE -> 1; case KeyEvent.KEYCODE_SPACE -> 57;
            case KeyEvent.KEYCODE_CTRL_LEFT -> 29; case KeyEvent.KEYCODE_CTRL_RIGHT -> 97;
            case KeyEvent.KEYCODE_ALT_LEFT -> 56; case KeyEvent.KEYCODE_ALT_RIGHT -> 100;
            case KeyEvent.KEYCODE_SHIFT_LEFT -> 42; case KeyEvent.KEYCODE_SHIFT_RIGHT -> 54;
            case KeyEvent.KEYCODE_META_LEFT -> 125; case KeyEvent.KEYCODE_META_RIGHT -> 126;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 105; case KeyEvent.KEYCODE_DPAD_RIGHT -> 106;
            case KeyEvent.KEYCODE_DPAD_UP -> 103; case KeyEvent.KEYCODE_DPAD_DOWN -> 108;
            default -> 0;
        };
        if (code != 0) { NativeCompositor.key(code, event.getAction() == KeyEvent.ACTION_DOWN); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getUnicodeChar() > 0) { NativeCompositor.unicode(event.getUnicodeChar()); return true; }
        return super.dispatchKeyEvent(event);
    }
}
