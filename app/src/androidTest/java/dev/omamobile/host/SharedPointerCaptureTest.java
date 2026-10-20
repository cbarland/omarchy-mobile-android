package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Android phone capture must control one Wayland seat across both outputs. */
public class SharedPointerCaptureTest {
    private Context context;
    private android.app.UiAutomation ui;
    private final Properties ready = new Properties();

    @Test public void phoneCaptureCrossesOutputsAndKeyboardFollowsTheSeat() throws Exception {
        Assume.assumeTrue("Prepared disposable two-display emulator and -e sharedPointer true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("sharedPointer")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        ui = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, DesktopService.class)
                .setAction(DesktopService.START_UNIFIED)));
        Path state = context.getFilesDir().toPath().resolve("graphics/unified/ready.properties");
        JSONArray monitors = null;
        long deadline = SystemClock.uptimeMillis() + 45000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Files.isRegularFile(state)) {
                try {
                    try (var in = Files.newInputStream(state)) { ready.load(in); }
                    JSONArray value = new JSONArray(ipc("monitors"));
                    if (value.length() == 2) { monitors = value; break; }
                } catch (Exception unavailable) { }
            }
            SystemClock.sleep(100);
        }
        assertNotNull("Shared compositor must expose both actual outputs", monitors);
        deadline = SystemClock.uptimeMillis() + 30000;
        boolean painted = false;
        while (SystemClock.uptimeMillis() < deadline) {
            JSONObject layers = new JSONObject(ipc("layers"));
            painted = hasLayer(layers, "ANDROID-1", "1", "omamobile-home")
                    && hasLayer(layers, "ANDROID-2", "0", "omarchy-background")
                    && hasLayer(layers, "ANDROID-2", "2", "omarchy-bar");
            if (painted) break;
            SystemClock.sleep(100);
        }
        assertTrue("Shell must finish its startup overlay before input testing", painted);
        JSONObject phone = monitor(monitors, "ANDROID-1"), desktop = monitor(monitors, "ANDROID-2");
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, UnifiedPhoneActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        deadline = SystemClock.uptimeMillis() + 5000;
        while (!phoneCapturing() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertTrue("Shared phone window must capture the mouse", phoneCapturing());

        double startX = phone.getDouble("x") + width(phone) / 2;
        double overlapY = Math.max(phone.getDouble("y"), desktop.getDouble("y")) + 120;
        lua("hl.dsp.cursor.move({x=" + startX + ",y=" + overlapY + "})");
        JSONObject before = position();
        assertOn(phone, before);
        double targetX = desktop.getDouble("x") + width(desktop) / 2;
        motion(MotionEvent.ACTION_MOVE, (float)(targetX - before.getDouble("x")), 0, 0);
        JSONObject onDesktop = awaitOn(desktop);

        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0; properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, -1);
        long now = SystemClock.uptimeMillis();
        MotionEvent scroll = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE_RELATIVE, 0);
        try { inject(scroll); } finally { scroll.recycle(); }
        SystemClock.sleep(150);
        JSONObject afterScroll = position();
        assertEquals("Scroll warped to the Android capture output", onDesktop.getDouble("x"), afterScroll.getDouble("x"), 1);
        assertOn(desktop, afterScroll);

        Path fixture = Files.createTempDirectory(context.getFilesDir().toPath().resolve("linux/rootfs/tmp"), "shared-pointer-");
        String guest = "/tmp/" + fixture.getFileName();
        try {
            String script = """
                    from pathlib import Path
                    import os, re, select, sys, termios, time, tty
                    root = Path('%s')
                    fd = sys.stdin.fileno()
                    old = termios.tcgetattr(fd)
                    data = bytearray()
                    try:
                        tty.setraw(fd)
                        os.write(1,b'\\x1b[?1000h\\x1b[?1006hClick this terminal.\\r\\n')
                        (root/'mouse-ready').write_bytes(b'ready')
                        deadline = time.monotonic()+8
                        while time.monotonic()<deadline:
                            if select.select([fd],[],[],.2)[0]: data.extend(os.read(fd,1024))
                            if re.search(rb'\\x1b\\[<0;[0-9]+;[0-9]+m',data): break
                        (root/'mouse').write_bytes(data)
                    finally:
                        termios.tcsetattr(fd,termios.TCSADRAIN,old)
                        os.write(1,b'\\x1b[?1000l\\x1b[?1006l')
                    (root/'keys-ready').write_bytes(b'ready')
                    (root/'typed').write_text(input('Type x then Enter: '),encoding='utf-8')
                    """.replace("%s", guest);
            Files.write(fixture.resolve("read.py"), script.getBytes(StandardCharsets.UTF_8));
            String command = "foot --app-id=shared-pointer-proof python3 " + guest + "/read.py";
            String literal = "\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            lua("hl.dsp.exec_cmd(" + literal + ",{monitor='ANDROID-2'})");
            deadline = SystemClock.uptimeMillis() + 10000;
            JSONObject window = null;
            while (SystemClock.uptimeMillis() < deadline) {
                JSONObject active = new JSONObject(ipc("activewindow"));
                if (active.optString("class").equals("shared-pointer-proof")) { window = active; break; }
                SystemClock.sleep(50);
            }
            assertNotNull("External test terminal must be focused", window);
            assertEquals(desktop.getInt("id"), window.getInt("monitor"));
            assertTrue("Android capture must remain on the phone", phoneCapturing());
            deadline = SystemClock.uptimeMillis() + 5000;
            while (!Files.exists(fixture.resolve("mouse-ready")) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertTrue("Terminal mouse reporter did not start", Files.exists(fixture.resolve("mouse-ready")));
            JSONObject cursor = position();
            JSONArray at = window.getJSONArray("at"), size = window.getJSONArray("size");
            motion(MotionEvent.ACTION_MOVE, (float)(at.getDouble(0) + size.getDouble(0)/2 - cursor.getDouble("x")),
                    (float)(at.getDouble(1) + size.getDouble(1)/2 - cursor.getDouble("y")), 0);
            SystemClock.sleep(200);
            JSONObject clickState = new JSONObject().put("window", window).put("cursor", position())
                    .put("layers", new JSONObject(ipc("layers")));
            motion(MotionEvent.ACTION_DOWN, 0, 0, MotionEvent.BUTTON_PRIMARY);
            motion(MotionEvent.ACTION_UP, 0, 0, 0);
            deadline = SystemClock.uptimeMillis() + 9000;
            while (!Files.exists(fixture.resolve("keys-ready")) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertTrue("Terminal mouse reporter did not finish", Files.exists(fixture.resolve("keys-ready")));
            String mouse = new String(Files.readAllBytes(fixture.resolve("mouse")), StandardCharsets.UTF_8);
            clickState.put("mouse", mouse);
            Files.write(context.getFilesDir().toPath().resolve("graphics/shared-pointer-click-state.json"),
                    clickState.toString(2).getBytes(StandardCharsets.UTF_8));
            assertTrue("External terminal did not receive the captured click: " + mouse,
                    mouse.matches("(?s).*\\x1b\\[<0;[0-9]+;[0-9]+M.*\\x1b\\[<0;[0-9]+;[0-9]+m.*"));
            // Visiting Android controls must not redirect the Linux seat back to
            // the phone merely because that window recaptures the physical mouse.
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            instrumentation.waitForIdleSync();
            deadline = SystemClock.uptimeMillis() + 3000;
            while (phoneCapturing() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertFalse("Android controls never received focus", phoneCapturing());
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, UnifiedPhoneActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            deadline = SystemClock.uptimeMillis() + 3000;
            while (!phoneCapturing() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertTrue("Phone did not recapture after returning from controls", phoneCapturing());
            assertEquals("Android focus return stole the external Linux client", "shared-pointer-proof",
                    new JSONObject(ipc("activewindow")).optString("class"));
            key(KeyEvent.KEYCODE_X, 0); key(KeyEvent.KEYCODE_ENTER, 0);
            deadline = SystemClock.uptimeMillis() + 3000;
            while (!Files.exists(fixture.resolve("typed")) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertTrue("Keys returned to the phone instead of the focused external client", Files.exists(fixture.resolve("typed")));
            assertEquals("x", new String(Files.readAllBytes(fixture.resolve("typed")), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(fixture.resolve("read.py"));
            Files.deleteIfExists(fixture.resolve("typed"));
            for (String name : new String[]{"mouse", "mouse-ready", "keys-ready"}) Files.deleteIfExists(fixture.resolve(name));
            Files.deleteIfExists(fixture);
        }
        JSONObject backStart = position();
        motion(MotionEvent.ACTION_MOVE, (float)(startX - backStart.getDouble("x")), 0, 0);
        JSONObject back = awaitOn(phone);

        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        int alt = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        keyEvent(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, ctrl);
        keyEvent(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.ACTION_DOWN, ctrl | alt);
        keyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_DOWN, ctrl | alt);
        keyEvent(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.ACTION_UP, ctrl);
        keyEvent(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0);
        keyEvent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_UP, 0);
        deadline = SystemClock.uptimeMillis() + 3000;
        while (phoneCapturing() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertFalse("Phone capture release failed", phoneCapturing());
        SystemClock.sleep(1200);
        assertFalse("Phone recaptured after explicit release", phoneCapturing());
        captureOutputs();
        Files.write(context.getFilesDir().toPath().resolve("graphics/verified-shared-pointer.json"),
                new JSONObject().put("monitors", monitors).put("before", before).put("external", onDesktop)
                        .put("afterScroll", afterScroll).put("returned", back).put("typed", "x")
                        .put("phoneCaptureReleased", true).put("terminalClick", true).put("androidFocusReturn", true)
                        .put("signature", ready.getProperty("HYPRLAND_INSTANCE_SIGNATURE"))
                        .put("fixture", fixture.getFileName().toString()).toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private String ipc(String command, String... args) throws Exception {
        return GraphicsSmokeTest.ipc(context, "unified", ready, command, args);
    }
    private void captureOutputs() throws Exception {
        String physical = new String(shellBytes("dumpsys SurfaceFlinger --display-id"), StandardCharsets.UTF_8);
        var phone = java.util.regex.Pattern.compile("(?m)^Display ([0-9]+) \\(HWC display 0\\):").matcher(physical);
        assertTrue("Cannot identify emulator phone output", phone.find());
        String displays = new String(shellBytes("dumpsys SurfaceFlinger"), StandardCharsets.UTF_8);
        var desktop = java.util.regex.Pattern.compile("(?m)^Display ([0-9]+) \\(virtual, \\\"Emulator 2D Display\\\"\\)").matcher(displays);
        assertTrue("Capture gate requires the disposable emulator external output", desktop.find());
        String[] ids = {phone.group(1), desktop.group(1)};
        String[] names = {"phone", "desktop"};
        for (int i = 0; i < ids.length; i++) {
            byte[] png = shellBytes("screencap -p -d " + ids[i]);
            assertTrue("Capture was not PNG", png.length > 8 && png[0] == (byte)137 && png[1] == 'P');
            Files.write(context.getFilesDir().toPath().resolve("graphics/shared-pointer-" + names[i] + ".png"), png);
        }
    }
    private byte[] shellBytes(String command) throws Exception {
        try (var descriptor = ui.executeShellCommand(command);
             var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { return input.readAllBytes(); }
    }
    private void lua(String expression) throws Exception { assertEquals("ok", ipc("dispatch_lua_expression", expression).trim()); }
    private JSONObject position() throws Exception { return new JSONObject(ipc("cursorpos")); }
    private static boolean hasLayer(JSONObject layers, String output, String level, String namespace) throws Exception {
        JSONArray list = layers.getJSONObject(output).getJSONObject("levels").getJSONArray(level);
        for (int i = 0; i < list.length(); i++) if (list.getJSONObject(i).optString("namespace").equals(namespace)
                && list.getJSONObject(i).optDouble("alpha", 0) >= .9) return true;
        return false;
    }
    private static double width(JSONObject monitor) throws Exception { return monitor.getDouble("width") / monitor.getDouble("scale"); }
    private static JSONObject monitor(JSONArray monitors, String name) throws Exception {
        for (int i = 0; i < monitors.length(); i++) if (monitors.getJSONObject(i).getString("name").equals(name)) return monitors.getJSONObject(i);
        throw new AssertionError("Missing " + name);
    }
    private static void assertOn(JSONObject monitor, JSONObject position) throws Exception {
        assertTrue(position.toString(), position.getDouble("x") >= monitor.getDouble("x")
                && position.getDouble("x") < monitor.getDouble("x") + width(monitor)
                && position.getDouble("y") >= monitor.getDouble("y")
                && position.getDouble("y") < monitor.getDouble("y") + monitor.getDouble("height") / monitor.getDouble("scale"));
    }
    private JSONObject awaitOn(JSONObject monitor) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 3000;
        JSONObject last;
        do {
            last = position();
            try { assertOn(monitor, last); return last; } catch (AssertionError notYet) { SystemClock.sleep(50); }
        } while (SystemClock.uptimeMillis() < deadline);
        assertOn(monitor, last); return last;
    }
    private void motion(int action, float x, float y, int buttons) throws Exception {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords(); coordinates.x = x; coordinates.y = y;
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 1, new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coordinates}, 0, buttons, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE_RELATIVE, 0);
        try { inject(event); } finally { event.recycle(); }
    }
    private void key(int code, int meta) throws Exception { keyEvent(code, KeyEvent.ACTION_DOWN, meta); keyEvent(code, KeyEvent.ACTION_UP, meta); }
    private void keyEvent(int code, int action, int meta) throws Exception {
        long now = SystemClock.uptimeMillis();
        KeyEvent key = new KeyEvent(now, now, action, code, 0, meta); key.setSource(InputDevice.SOURCE_KEYBOARD); inject(key);
    }
    private void inject(InputEvent event) throws Exception {
        event.getClass().getMethod("setDisplayId", int.class).invoke(event, 0);
        assertTrue("Android rejected the phone-capture event", ui.injectInputEvent(event, true));
    }
    private boolean phoneCapturing() throws Exception {
        try (var descriptor = ui.executeShellCommand("dumpsys input");
             var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) if (line.contains("Current Window with Pointer Capture:"))
                return line.contains("dev.omamobile.host.UnifiedPhoneActivity");
            throw new AssertionError("Missing Android pointer-capture state");
        }
    }
}
