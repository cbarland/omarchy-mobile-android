package dev.omamobile.host;

import android.view.Surface;

/** JNI boundary to the pinned Hyprland Android Surface backend. */
final class NativeCompositor {
    interface Ready { void ready(String display, String signature); }
    interface OutputState { void changed(int output, long generation, int status); }
    static volatile Ready listener;
    static volatile OutputState outputListener;
    static final boolean AVAILABLE;
    static {
        boolean loaded;
        try { System.loadLibrary("omamobile_hyprland"); loaded = true; }
        catch (UnsatisfiedLinkError error) { android.util.Log.e("OmamobileHyprland", "Native backend unavailable", error); loaded = false; }
        AVAILABLE = loaded;
    }
    static void onReady(String display, String signature) {
        Ready callback = listener;
        if (callback != null) callback.ready(display, signature);
    }
    static void onOutputState(int output, long generation, int status) {
        OutputState callback = outputListener;
        if (callback != null) callback.changed(output, generation, status);
    }
    static native int run(Surface surface, int width, int height, String home, String runtime,
                          String config, String rootfs, String wrapper, String libraries, String magic, String xkb);
    static native int runUnified(Surface surface, int width, int height, long generation, String home, String runtime,
                          String config, String rootfs, String wrapper, String libraries, String magic, String xkb);
    static native int outputSurface(int output, long generation, Surface surface, int width, int height);
    static native int outputResize(int output, long generation, int width, int height);
    static native int outputRemove(int output, long generation);
    static native int pointerAt(int output, long generation, float x, float y, int button, boolean pressed);
    static native int relativePointerAt(int output, long generation, float dx, float dy, int button, boolean pressed);
    static native int touchAt(int output, long generation, int id, int action, float x, float y);
    static native int axisAt(int output, long generation, float dx, float dy);
    static native int keyAt(int output, long generation, int evdev, boolean pressed);
    static native int unicodeAt(int output, long generation, int codepoint);
    static native int focusAt(int output, long generation, boolean focused);
    static native int window(Surface surface, int width, int height);
    static native int pointer(float x, float y, int button, boolean pressed);
    static native int touch(int id, int action, float x, float y);
    static native int axis(float dx, float dy);
    static native int key(int evdev, boolean pressed);
    static native int unicode(int codepoint);
    static native int stop();
}
