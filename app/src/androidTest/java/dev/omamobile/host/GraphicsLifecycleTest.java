package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.json.JSONArray;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reproduce repeated Start, closing the single Recents card, and a fresh start. */
public class GraphicsLifecycleTest {
    @Test public void repeatedStartAndCloseReopenKeepOneSessionPerOutput() throws Exception {
        Assume.assumeTrue("Prepared two-display Linux installation required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        var guard = context.getFilesDir().toPath().resolve("linux/rootfs/root/.config/omamobile/widget-guard.json");
        String sentinel = "omamobile.test.planned-stop";
        boolean seededCrash = false;
        try {
            // Earlier checks may have left a settled session running. Start
            // from a genuine cold shell so the OWL startup interval is tested.
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
            long stopped = SystemClock.uptimeMillis() + 15000;
            while (screenProcessesRunning() && SystemClock.uptimeMillis() < stopped) SystemClock.sleep(100);
            assertFalse("Previous screen processes did not stop", screenProcessesRunning());
            start(context);
            Map<String, String> initial = new HashMap<>();
            boolean owl = "true".equals(InstrumentationRegistry.getArguments().getString("owl"));
            String recordedCrashes = null;
            if (owl) {
                // Cold Linux/Qt startup on a busy emulator can take tens of
                // seconds. Still require the real three-second guard itself.
                long loading = SystemClock.uptimeMillis() + 45000;
                boolean activeGuard = false;
                while (SystemClock.uptimeMillis() < loading) {
                    if (Files.exists(guard)) {
                        var state = new org.json.JSONObject(new String(Files.readAllBytes(guard), java.nio.charset.StandardCharsets.UTF_8));
                        assertFalse(state.getJSONObject("crashed").has("simonschubert.todo"));
                        activeGuard = state.getJSONObject("loading").has("simonschubert.todo");
                        if (activeGuard) break;
                    }
                    SystemClock.sleep(50);
                }
                assertTrue("The test did not exercise closing during the real OWL startup guard", activeGuard);
                // Do not wait for the desktop: it can start after the phone's
                // three-second widget guard has already expired.
                Properties phone = new Properties();
                try (var in = Files.newInputStream(context.getFilesDir().toPath().resolve("graphics/phone/ready.properties"))) {
                    phone.load(in);
                }
                initial.put("phone", phone.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
                byte[] original = Files.readAllBytes(guard);
                Files.write(context.getFilesDir().toPath().resolve("graphics/widget-guard-before-planned-stop.json"), original);
                var state = new org.json.JSONObject(new String(original, java.nio.charset.StandardCharsets.UTF_8));
                assertTrue("OWL guard expired before the planned stop", state.getJSONObject("loading").has("simonschubert.todo"));
                assertFalse("Test sentinel collides with an existing record", state.getJSONObject("crashed").has(sentinel));
                state.getJSONObject("crashed").put(sentinel, "test-only recorded crash");
                recordedCrashes = state.getJSONObject("crashed").toString();
                writeGuard(guard, state);
                seededCrash = true;
            } else {
                initial = sessions(context);
                GraphicsSmokeTest.awaitWidgetGuard(context.getFilesDir().toPath());
            }
            var tasks = context.getSystemService(ActivityManager.class).getAppTasks();
            var controls = tasks.stream().filter(task -> {
                var component = task.getTaskInfo().baseIntent.getComponent();
                return component != null && component.getClassName().equals(MainActivity.class.getName());
            }).findFirst().orElseThrow();
            controls.finishAndRemoveTask();
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (screenProcessesRunning() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            assertFalse("Closing the app left its hidden screen processes running", screenProcessesRunning());
            if (owl) {
                var state = new org.json.JSONObject(new String(Files.readAllBytes(guard), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("Planned shutdown left a widget startup marker", 0, state.getJSONObject("loading").length());
                assertFalse(state.getJSONObject("crashed").has("simonschubert.todo"));
                assertEquals("Planned shutdown changed recorded widget crashes", recordedCrashes, state.getJSONObject("crashed").toString());
                Files.write(context.getFilesDir().toPath().resolve("graphics/widget-guard-after-planned-stop.json"), Files.readAllBytes(guard));
                state.getJSONObject("crashed").remove(sentinel);
                writeGuard(guard, state);
                seededCrash = false;
            }
            start(context);
            Map<String, String> fresh = sessions(context);
            for (String profile : initial.keySet())
                assertNotEquals("Closed " + profile + " compositor was reused", initial.get(profile), fresh.get(profile));
            assertTasks(context);
            for (int i = 0; i < 3; i++) {
                start(context);
                assertEquals("Start created another native compositor", fresh, sessions(context));
                assertTasks(context);
            }
            if ("true".equals(InstrumentationRegistry.getArguments().getString("androidWindows"))) {
                long connected = SystemClock.uptimeMillis() + 5000;
                boolean authorized = false;
                while (SystemClock.uptimeMillis() < connected) {
                    try { authorized = AndroidShell.get(context).require().uid() == 2000; }
                    catch (java.io.IOException ignored) { }
                    if (authorized) break;
                    SystemClock.sleep(100);
                }
                assertTrue("Previously authorized Android windows did not reconnect after reopening", authorized);
            }
            // Exercise capture and real Wayland motion after the fresh start,
            // including the explicit release shortcut.
            new PointerCaptureTest().relativeMouseMovesAndCaptureCanBeReleased();
        } finally {
            if (seededCrash && Files.exists(guard)) {
                // Remove only our synthetic record; never replace the guard
                // with a backup that could erase a real intervening crash.
                var state = new org.json.JSONObject(new String(Files.readAllBytes(guard), java.nio.charset.StandardCharsets.UTF_8));
                state.getJSONObject("crashed").remove(sentinel);
                writeGuard(guard, state);
            }
            if (screenProcessesRunning()) GraphicsSmokeTest.awaitWidgetGuard(context.getFilesDir().toPath());
        }
    }

    private static void writeGuard(java.nio.file.Path guard, org.json.JSONObject state) throws Exception {
        var temporary = Files.createTempFile(guard.getParent(), ".lifecycle-test-", ".json");
        try {
            Files.write(temporary, (state.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.move(temporary, guard, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void start(Context context) {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, DesktopService.class)));
        instrumentation.waitForIdleSync();
        SystemClock.sleep(300);
    }

    private static Map<String, String> sessions(Context context) throws Exception {
        Map<String, String> signatures = new HashMap<>();
        for (String profile : new String[]{"phone", "desktop"}) {
            var ready = context.getFilesDir().toPath().resolve("graphics/" + profile + "/ready.properties");
            long deadline = SystemClock.uptimeMillis() + 25000;
            Exception last = null;
            while (SystemClock.uptimeMillis() < deadline) {
                try {
                    Properties properties = new Properties();
                    try (var in = Files.newInputStream(ready)) { properties.load(in); }
                    var monitors = new JSONArray(GraphicsSmokeTest.ipc(context, profile, properties, "monitors"));
                    assertEquals("More than one native output", 1, monitors.length());
                    assertTrue(monitors.getJSONObject(0).getInt("width") > 0);
                    signatures.put(profile, properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
                    break;
                } catch (Exception e) { last = e; }
                SystemClock.sleep(100);
            }
            assertTrue(profile + " session unavailable: " + last, signatures.containsKey(profile));
        }
        return signatures;
    }

    private static void assertTasks(Context context) {
        Map<String, Integer> counts = new HashMap<>();
        int visibleCards = 0;
        for (var task : context.getSystemService(ActivityManager.class).getAppTasks()) {
            var intent = task.getTaskInfo().baseIntent;
            var component = intent.getComponent();
            if (component == null) continue;
            String name = component.getClassName();
            if (!name.equals(MainActivity.class.getName()) && !name.equals(PhoneActivity.class.getName())
                    && !name.equals(DesktopActivity.class.getName())) continue;
            counts.merge(name, 1, Integer::sum);
            if ((intent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0) visibleCards++;
        }
        assertEquals("More than one user-facing app card", 1, visibleCards);
        for (Class<?> screen : new Class<?>[]{MainActivity.class, PhoneActivity.class, DesktopActivity.class})
            assertEquals("Missing or duplicated " + screen.getSimpleName(), Integer.valueOf(1), counts.get(screen.getName()));
    }

    private static boolean screenProcessesRunning() throws Exception {
        var ui = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try (var parcel = ui.executeShellCommand("pidof dev.omamobile.host:phone dev.omamobile.host:desktop");
             var in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel)) {
            return !new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim().isEmpty();
        }
    }
}
