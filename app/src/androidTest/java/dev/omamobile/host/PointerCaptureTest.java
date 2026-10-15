package dev.omamobile.host;

import android.content.Intent;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import java.nio.file.Files;
import java.util.Properties;
import static org.junit.Assert.*;

/** Real Android relative input through the external Surface into Wayland. */
public class PointerCaptureTest {
    @Test public void relativeMouseMovesAndCaptureCanBeReleased() throws Exception {
        Assume.assumeTrue("Prepared graphics and explicit -e graphics true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        try { exercise(); }
        finally { GraphicsSmokeTest.awaitWidgetGuard(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir().toPath()); }
    }

    private void exercise() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var ui = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var context = instrumentation.getTargetContext();
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, DesktopService.class)));
        var ready = context.getFilesDir().toPath().resolve("graphics/desktop/ready.properties");
        Properties properties = new Properties();
        JSONObject monitor = null;
        long deadline = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Files.exists(ready)) {
                try (var in = Files.newInputStream(ready)) { properties.load(in); }
                try {
                    monitor = new JSONArray(GraphicsSmokeTest.ipc(context, "desktop", properties, "monitors")).getJSONObject(0);
                    break;
                } catch (Exception ignored) { }
            }
            SystemClock.sleep(100);
        }
        assertNotNull("External compositor unavailable", monitor);
        int display = Integer.parseInt(properties.getProperty("ANDROID_DISPLAY_ID"));
        assertNotEquals("Never inject this test into the phone", 0, display);
        var before = new JSONObject(GraphicsSmokeTest.ipc(context, "desktop", properties, "cursorpos"));
        double scale = monitor.getDouble("scale");
        float dx = before.getDouble("x") < monitor.getInt("width") / scale - 100 ? 37 : -37;
        float dy = before.getDouble("y") < monitor.getInt("height") / scale - 100 ? 19 : -19;
        long now = SystemClock.uptimeMillis();
        MotionEvent motion = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, dx, dy, 0);
        motion.setSource(InputDevice.SOURCE_MOUSE_RELATIVE);
        try { inject(ui, motion, display); }
        finally { motion.recycle(); }
        JSONObject after = before;
        deadline = SystemClock.uptimeMillis() + 3000;
        while (SystemClock.uptimeMillis() < deadline) {
            after = new JSONObject(GraphicsSmokeTest.ipc(context, "desktop", properties, "cursorpos"));
            if (Math.abs(after.getDouble("x") - before.getDouble("x")) > 1) break;
            SystemClock.sleep(50);
        }
        assertEquals("Relative X motion did not reach Wayland", dx / scale,
                after.getDouble("x") - before.getDouble("x"), 2);
        assertEquals("Relative Y motion did not reach Wayland", dy / scale,
                after.getDouble("y") - before.getDouble("y"), 2);
        assertTrue("Desktop was not capturing before the release shortcut", desktopCapturing(ui));
        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        int alt = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        inject(ui, new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, 0, ctrl), display);
        inject(ui, new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, 0, ctrl | alt), display);
        inject(ui, new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0, ctrl | alt), display);
        // Releasing the modifiers first must still consume the Escape UP.
        inject(ui, new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0, ctrl), display);
        inject(ui, new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0, 0), display);
        inject(ui, new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0, 0), display);
        // Capture changes cross the input-dispatcher boundary asynchronously.
        // Wait for the observed release, rather than assuming a 200ms frame.
        deadline = SystemClock.uptimeMillis() + 3000;
        while (desktopCapturing(ui) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        assertFalse("Release shortcut left the desktop capturing the mouse", desktopCapturing(ui));
        SystemClock.sleep(1200);
        assertFalse("Desktop recaptured immediately after the release shortcut", desktopCapturing(ui));
        Files.write(context.getFilesDir().toPath().resolve("graphics/verified-pointer.json"),
                new JSONObject().put("display", display).put("before", before).put("after", after)
                        .put("releaseShortcut", true).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean desktopCapturing(android.app.UiAutomation ui) throws Exception {
        try (var parcel = ui.executeShellCommand("dumpsys input");
             var in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel)) {
            String state = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : state.split("\\n")) {
                // The first field is live. A later historical ANR dump can
                // describe a capture owner that no longer exists.
                if (line.contains("Current Window with Pointer Capture:"))
                    return line.contains("dev.omamobile.host.DesktopActivity");
            }
            throw new AssertionError("Android did not report live pointer capture state");
        }
    }

    private static void inject(android.app.UiAutomation ui, InputEvent event, int display) throws Exception {
        if (event instanceof KeyEvent key) key.setSource(InputDevice.SOURCE_KEYBOARD);
        event.getClass().getMethod("setDisplayId", int.class).invoke(event, display);
        assertTrue("Android rejected the external-display test event", ui.injectInputEvent(event, true));
    }
}
