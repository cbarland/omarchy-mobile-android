package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.content.IntentFilter;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class AndroidAppPortalTest {
    private byte[] lastImage = new byte[0];
    private LocalSocket connect() throws Exception { return connect(true); }
    private LocalSocket connect(boolean startMainActivity) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeTrue("Prepared Linux installation required", HostRuntime.get(context).installed());
        if (startMainActivity)
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        if (startMainActivity)
            context.startForegroundService(new Intent(context, DesktopService.class));
        String path = new File(context.getFilesDir(), SessionRuntime.HOST_ALIAS + "/android.sock").toString();
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                LocalSocket socket = new LocalSocket();
                socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                socket.setSoTimeout(10000); return socket;
            } catch (Exception e) { if (attempt == 99) throw e; SystemClock.sleep(40); }
        }
        throw new AssertionError();
    }
    private JSONObject request(LocalSocket socket, JSONObject json) throws Exception {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(bytes.length); out.write(bytes); out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] reply = new byte[in.readInt()]; in.readFully(reply);
        int image = in.readInt(); lastImage = in.readNBytes(image);
        return new JSONObject(new String(reply, StandardCharsets.UTF_8));
    }
    @Test public void protocolRejectsUnownedInputAndBadSizes() throws Exception {
        try (LocalSocket socket = connect()) {
            assertFalse(request(socket, new JSONObject().put("op", "gesture").put("display", 0)).getBoolean("ok"));
            assertFalse(request(socket, new JSONObject().put("op", "open").put("package", "com.android.settings").put("width", 20000).put("height", 600)).getBoolean("ok"));
            JSONObject list = request(socket, new JSONObject().put("op", "list"));
            assertTrue(list.toString(), list.getBoolean("ok"));
            assertTrue(list.getJSONArray("apps").length() > 0);
            assertFalse(request(socket, new JSONObject().put("op", "unknown-device-state-operation")).getBoolean("ok"));
        }
    }
    @Test public void deviceStateMatchesAndroidApisAndLinuxCli() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e graphics true and prepared Linux required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject host;
        try (LocalSocket socket = connect()) {
            host = request(socket, new JSONObject().put("op", "device-state"));
        }
        assertTrue(host.toString(), host.getBoolean("ok"));
        var keyguard = context.getSystemService(android.app.KeyguardManager.class);
        var power = context.getSystemService(android.os.PowerManager.class);
        assertNotNull(keyguard);
        assertNotNull(power);
        assertEquals(keyguard.isKeyguardLocked(), host.getBoolean("keyguardLocked"));
        assertEquals(keyguard.isDeviceLocked(), host.getBoolean("deviceLocked"));
        assertEquals(power.isInteractive(), host.getBoolean("interactive"));

        var root = context.getFilesDir().toPath().resolve("linux/rootfs");
        JSONObject cli = new JSONObject(runLinuxClient(context, root, "device-state", "device-state-"));
        assertEquals(host.getBoolean("keyguardLocked"), cli.getBoolean("keyguardLocked"));
        assertEquals(host.getBoolean("deviceLocked"), cli.getBoolean("deviceLocked"));
        assertEquals(host.getBoolean("interactive"), cli.getBoolean("interactive"));
    }
    @Test public void batteryStateMatchesAndroidStickyApiAndLinuxCli() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e graphics true and prepared Linux required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject host;
        try (LocalSocket socket = connect()) {
            host = request(socket, new JSONObject().put("op", "battery-state"));
        }
        assertTrue(host.toString(), host.getBoolean("ok"));
        BatterySnapshot expected = BatterySnapshot.fromIntent(context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        assertNullableEquals(expected.present(), host, "present");
        assertNullableEquals(expected.percentage(), host, "percentage");
        assertNullableEquals(expected.plugged(), host, "plugged");
        assertEquals(expected.state(), host.getString("state"));
        var root = context.getFilesDir().toPath().resolve("linux/rootfs");
        JSONObject cli = new JSONObject(runLinuxClient(context, root, "battery-state", "battery-state-"));
        assertNullableEquals(host, cli, "present");
        assertNullableEquals(host, cli, "percentage");
        assertNullableEquals(host, cli, "plugged");
        assertEquals(host.getString("state"), cli.getString("state"));
    }
    private static void assertNullableEquals(Object expected, JSONObject actual, String key) throws Exception {
        if (expected == null) assertTrue(key + " should be JSON null", actual.isNull(key));
        else if (expected instanceof Number && actual.get(key) instanceof Number)
            assertEquals(((Number) expected).doubleValue(), ((Number) actual.get(key)).doubleValue(), 0.000001);
        else assertEquals(expected, actual.get(key));
    }
    private static void assertNullableEquals(JSONObject expected, JSONObject actual, String key) throws Exception {
        if (expected.isNull(key)) assertTrue(key + " should be JSON null", actual.isNull(key));
        else if (expected.get(key) instanceof Number && actual.get(key) instanceof Number)
            assertEquals(((Number) expected.get(key)).doubleValue(), ((Number) actual.get(key)).doubleValue(), 0.000001);
        else assertEquals(expected.get(key), actual.get(key));
    }
    @Test public void batterySnapshotHandlesSyntheticMissingAndMalformedIntents() {
        BatterySnapshot missing = BatterySnapshot.fromIntent(new Intent());
        assertNull(missing.present());
        assertNull(missing.percentage());
        assertNull(missing.plugged());
        assertEquals(BatterySnapshot.UNKNOWN, missing.state());
        Intent malformed = new Intent()
                .putExtra(android.os.BatteryManager.EXTRA_PRESENT, "yes")
                .putExtra(android.os.BatteryManager.EXTRA_LEVEL, "half")
                .putExtra(android.os.BatteryManager.EXTRA_SCALE, "whole")
                .putExtra(android.os.BatteryManager.EXTRA_STATUS, "charging")
                .putExtra(android.os.BatteryManager.EXTRA_PLUGGED, "usb");
        BatterySnapshot value = BatterySnapshot.fromIntent(malformed);
        assertNull(value.present());
        assertNull(value.percentage());
        assertNull(value.plugged());
        assertEquals(BatterySnapshot.UNKNOWN, value.state());
    }
    @Test public void linuxDisplaySettingsRequestShowsColdAndReusedPhonePanel() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e graphics true and prepared Linux required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        // Reuse the repository's real two-session readiness probe before
        // testing the Linux request, so this path is exercised with both
        // graphical peers alive.
        new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
        List<MainActivity> stale = finishMainActivities(instrumentation);
        awaitDestroyed(instrumentation, stale);
        MainActivity first = null;
        try (LocalSocket socket = connect(false)) {
            JSONObject reply = request(socket, new JSONObject().put("op", "display-settings"));
            assertTrue(reply.toString(), reply.getBoolean("ok"));
            assertTrue(reply.toString(), reply.getBoolean("requested"));
            first = awaitMainActivity(instrumentation);
            assertEquals(android.view.Display.DEFAULT_DISPLAY, first.getDisplay().getDisplayId());
            awaitDialogShowing(instrumentation, first);
            dismissDialog(instrumentation, first);

            String cli = runLinuxClient(context, context.getFilesDir().toPath().resolve("linux/rootfs"),
                    "display-settings", "portal-display-settings-");
            assertEquals("Android display settings requested", cli);
            MainActivity second = awaitMainActivity(instrumentation);
            assertSame("Existing phone activity should receive the host action", first, second);
            assertEquals(android.view.Display.DEFAULT_DISPLAY, second.getDisplay().getDisplayId());
            awaitDialogShowing(instrumentation, second);
            dismissDialog(instrumentation, second);
        } finally {
            if (first != null) dismissDialog(instrumentation, first);
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
        }
    }
    private static List<MainActivity> finishMainActivities(android.app.Instrumentation instrumentation) {
        List<MainActivity> found = new ArrayList<>();
        instrumentation.runOnMainSync(() -> {
            Set<android.app.Activity> unique = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var stage : androidx.test.runner.lifecycle.Stage.values())
                if (stage == androidx.test.runner.lifecycle.Stage.PRE_ON_CREATE
                        || stage == androidx.test.runner.lifecycle.Stage.DESTROYED) continue;
                else
                for (var activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(stage))
                    if (activity instanceof MainActivity && unique.add(activity)) {
                        found.add((MainActivity) activity);
                        activity.finish();
                    }
        });
        return found;
    }
    private static void awaitDestroyed(android.app.Instrumentation instrumentation, List<MainActivity> activities) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean alive = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                for (MainActivity activity : activities) if (!activity.isDestroyed()) alive.set(true);
            });
            if (!alive.get()) return;
            SystemClock.sleep(50);
        }
        fail("Cold display-settings request requires every prior MainActivity to be destroyed first");
    }
    private static MainActivity awaitMainActivity(android.app.Instrumentation instrumentation) {
        AtomicReference<MainActivity> found = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> {
                for (var activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if (activity instanceof MainActivity) found.set((MainActivity) activity);
            });
            if (found.get() != null) return found.get();
            SystemClock.sleep(50);
        }
        AtomicReference<String> state = new AtomicReference<>("unknown");
        instrumentation.runOnMainSync(() -> {
            StringBuilder value = new StringBuilder();
            for (var stage : androidx.test.runner.lifecycle.Stage.values()) {
                int count = 0;
                for (var activity : androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(stage)) {
                    count++;
                    if (activity instanceof MainActivity)
                        value.append(" MainActivity@").append(activity.getDisplay().getDisplayId());
                }
                if (count != 0) value.append(" ").append(stage).append("=").append(count);
            }
            state.set(value.toString());
        });
        fail("Display request must resume MainActivity on the default display; lifecycle:" + state.get());
        return null;
    }
    private static boolean dialogShowing(android.app.Instrumentation instrumentation, MainActivity activity) {
        AtomicBoolean showing = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> {
            android.app.Dialog dialog = displayDialogUnchecked(activity);
            showing.set(dialog != null && dialog.isShowing());
        });
        return showing.get();
    }
    private static void awaitDialogShowing(android.app.Instrumentation instrumentation, MainActivity activity) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (dialogShowing(instrumentation, activity)) return;
            SystemClock.sleep(50);
        }
        fail("Display request did not show the existing Android display dialog");
    }
    private static void dismissDialog(android.app.Instrumentation instrumentation, MainActivity activity) {
        instrumentation.runOnMainSync(() -> {
            android.app.Dialog dialog = displayDialogUnchecked(activity);
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        });
    }
    private static android.app.Dialog displayDialogUnchecked(MainActivity activity) {
        try {
            var owner = MainActivity.class.getDeclaredField("displaySettings");
            owner.setAccessible(true);
            var dialog = DisplaySettings.class.getDeclaredField("dialog");
            dialog.setAccessible(true);
            return (android.app.Dialog) dialog.get(owner.get(activity));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
    @Test public void linuxClientReachesTheSamePortal() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e graphics true and prepared Linux required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.json.JSONArray hostApps;
        try (LocalSocket socket = connect()) {
            JSONObject reply = request(socket, new JSONObject().put("op", "list"));
            assertTrue(reply.toString(), reply.getBoolean("ok"));
            hostApps = reply.getJSONArray("apps");
        }
        var root = context.getFilesDir().toPath().resolve("linux/rootfs");
        var guestApps = new org.json.JSONArray(runLinuxClient(context, root, "list", "portal-list-"));
        assertTrue("Expected discoverable Android applications", guestApps.length() > 0);
        assertEquals(packages(hostApps), packages(guestApps));
    }
    private static String runLinuxClient(Context context, java.nio.file.Path root, String action, String prefix) throws Exception {
        var argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(),
                root, List.of(new RuntimeSpec.Bind("/dev", "/dev", false),
                        new RuntimeSpec.Bind("/proc", "/proc", false)),
                List.of("/usr/bin/omamobile-android", action));
        var captured = Files.createTempFile(context.getCacheDir().toPath(), prefix, ".json");
        Process process = null;
        try {
            var builder = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(captured.toFile());
            builder.environment().clear();
            builder.environment().putAll(RuntimeSpec.environment(false));
            process = builder.start();
            process.getOutputStream().close();
            assertTrue("Linux portal client timed out", process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(Files.readAllBytes(captured), StandardCharsets.UTF_8).trim();
            assertEquals(output, 0, process.exitValue());
            return output;
        } finally {
            if (process != null) process.destroyForcibly();
            Files.deleteIfExists(captured);
        }
    }
    private static java.util.Set<String> packages(org.json.JSONArray apps) throws Exception {
        var packages = new HashSet<String>();
        for (int i = 0; i < apps.length(); i++) packages.add(apps.getJSONObject(i).getString("package"));
        return packages;
    }
    @Test public void settingsHasLiveFramesAndDisplayDiesWithClient() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e androidWindows true and device Shizuku authorization required",
                "true".equals(InstrumentationRegistry.getArguments().getString("androidWindows")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int id;
        try (LocalSocket socket = connect()) {
            JSONObject opened = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                opened = request(socket, new JSONObject().put("op", "open").put("package", "com.android.settings").put("width", 720).put("height", 800));
                if (opened.optBoolean("ok") || !opened.optString("error").contains("Connecting Android windows")) break;
                SystemClock.sleep(100);
            }
            assertTrue(opened.toString(), opened.getBoolean("ok"));
            id = opened.getInt("display");
            assertNotEquals(0, id);
            JSONObject frame = null;
            for (int i = 0; i < 100; i++) {
                frame = request(socket, new JSONObject().put("op", "frame"));
                if (frame.optLong("sequence") > 0 && hasContent(lastImage)) break;
                SystemClock.sleep(50);
            }
            assertTrue(String.valueOf(frame), frame != null && frame.getLong("sequence") > 0);
            assertTrue("Expected actual Android Settings content, not an empty display", hasContent(lastImage));
            IAndroidShell helper = AndroidShell.get(context).require();
            assertEquals(2000, helper.uid());
            assertNotEquals(0, android.os.Process.myUid());
            try {
                helper.key(0, android.view.KeyEvent.KEYCODE_A, "");
                fail("The shell adapter must reject input to the phone display");
            } catch (SecurityException expected) { }
        }
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        for (int i = 0; i < 100 && displays.getDisplay(id) != null; i++) SystemClock.sleep(30);
        assertNull("Android display must be released after the Linux client disconnects", displays.getDisplay(id));
    }
    private static boolean hasContent(byte[] image) {
        if (image.length == 0) return false;
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length);
        if (bitmap == null) return false;
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 40))
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 40)) colors.add(bitmap.getPixel(x, y) & 0x00f8f8f8);
        bitmap.recycle();
        return colors.size() > 8;
    }
}
