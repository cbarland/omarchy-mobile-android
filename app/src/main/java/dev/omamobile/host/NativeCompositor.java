package dev.omamobile.host;

import android.view.Surface;

/** JNI boundary to the pinned Hyprland Android Surface backend. */
final class NativeCompositor {
    interface Ready { void ready(String display, String signature); }
    static Ready listener;
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
    static native int run(Surface surface, int width, int height, String home, String runtime,
                          String config, String rootfs, String wrapper, String libraries, String magic, String xkb);
    static native int window(Surface surface, int width, int height);
    static native int pointer(float x, float y, int button, boolean pressed);
    static native int touch(int id, int action, float x, float y);
    static native int axis(float dx, float dy);
    static native int key(int evdev, boolean pressed);
    static native int unicode(int codepoint);
    static native int stop();
}
