package dev.omamobile.host;

import android.view.Surface;
import java.util.function.Consumer;

/** One Activity's output attachment and input, independent of compositor ownership. */
interface CompositorEndpoint {
    void observe(Consumer<String> listener);
    void unobserve(Consumer<String> listener);
    void attach(Object owner, Surface surface, int width, int height, int displayId);
    void detach(Object owner);
    void pointer(float x, float y, int button, boolean pressed);
    default boolean sharedSeat() { return false; }
    /** True consumes motion, including input from a retired shared-output lease. */
    default boolean relativePointer(float dx, float dy, int button, boolean pressed) { return false; }
    void touch(int id, int action, float x, float y);
    void axis(float dx, float dy);
    void key(int code, boolean pressed);
    void unicode(int codepoint);
    void focus(boolean focused);
}
