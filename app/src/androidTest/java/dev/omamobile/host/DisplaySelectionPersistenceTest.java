package dev.omamobile.host;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real settings and service routing; host orchestration reconnects an actual emulator output. */
public class DisplaySelectionPersistenceTest {
    private record Owner(int pid, String start) { }
    private record Session(Owner owner, String signature) { }

    @Test public void savedOutputSurvivesActivityRecreationAndReconnectWithoutFallback() throws Exception {
        Assume.assumeTrue("Explicit prepared graphics test required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        Assume.assumeTrue("Routing fixture is restricted to emulators",
                android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish"));
        String displayArgument = InstrumentationRegistry.getArguments().getString("firstDisplayId");
        assertNotNull("Host orchestration must supply -e firstDisplayId <Android display ID>", displayArgument);
        int firstId = Integer.parseInt(displayArgument);
        assertTrue("Select an external Android display", firstId > Display.DEFAULT_DISPLAY);
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Path files = context.getFilesDir().toPath();
        SharedPreferences preferences = context.getSharedPreferences("display-routing", Context.MODE_PRIVATE);
        Map<String, ?> originalPreferences = new HashMap<>(preferences.getAll());
        MainActivity activity = null;
        JSONObject report = new JSONObject().put("run", SystemClock.uptimeMillis()).put("passed", false)
                .put("firstDisplayId", firstId);
        // Static test-owned filename, atomic updates, and a fresh run token let the
        // host distinguish this invocation from any retained report.
        phase(files, report, "starting");
        try {
            stopScreens(context);
            awaitNoOwner(context, "phone");
            awaitNoOwner(context, "desktop");
            String oldPhoneSignature = signature(files, "phone");
            String oldDesktopSignature = signature(files, "desktop");
            var outputs = DisplayCatalog.read(context);
            var output = outputs.stream().filter(item -> item.candidate().id() == firstId).findFirst().orElseThrow();
            assertTrue("Prepared target must accept DesktopActivity and have a unique signature",
                    DisplaySelection.canRemember(DisplayCatalog.candidates(outputs), output.candidate()));
            // Keep a different usable output attached throughout, so refusal to
            // fall back after unplugging the saved target is actually exercised.
            assertTrue("Prepare a second distinct eligible output for the missing-target check",
                    outputs.stream().anyMatch(item -> item.candidate().eligible()
                            && !item.candidate().signature().equals(output.candidate().signature())));
            String name = output.name();

            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            activity = awaitControls(null);
            MainActivity firstActivity = activity;
            instrumentation.runOnMainSync(() -> {
                openSettings(firstActivity);
                RadioButton target = choice(firstActivity, name, firstId);
                assertTrue("Target must be selectable", target.isEnabled());
                assertTrue(target.performClick());
                assertTrue("The saved choice must be visibly checked", choice(firstActivity, name, firstId).isChecked());
            });
            assertEquals(output.candidate().signature(), DisplayCatalog.savedSignature(context));
            assertEquals(firstId, DisplayCatalog.selection(context, DisplayCatalog.read(context)).id());

            instrumentation.runOnMainSync(firstActivity::recreate);
            activity = awaitControls(firstActivity);
            MainActivity recreated = activity;
            instrumentation.runOnMainSync(() -> {
                openSettings(recreated);
                assertTrue("Saved selection was lost after Activity recreation", choice(recreated, name, firstId).isChecked());
                settings(recreated).dismiss();
                context.startForegroundService(new Intent(context, DesktopService.class));
            });
            assertEquals(output.candidate().signature(), DisplayCatalog.savedSignature(context));
            Session phone = awaitSession(context, "phone", Display.DEFAULT_DISPLAY, oldPhoneSignature, null);
            Session desktop = awaitSession(context, "desktop", firstId, oldDesktopSignature, null);
            GraphicsSmokeTest.awaitWidgetGuard(files);
            report.put("firstDesktopPid", desktop.owner().pid()).put("savedSignature", DisplayCatalog.savedSignature(context))
                    .put("targetName", name);
            phase(files, report, "ready-for-disconnect");

            long deadline = SystemClock.uptimeMillis() + 45000;
            while (DisplayCatalog.read(context).stream().anyMatch(item -> item.candidate().id() == firstId)
                    && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            assertFalse("Host did not disconnect the selected output",
                    DisplayCatalog.read(context).stream().anyMatch(item -> item.candidate().id() == firstId));
            awaitNoOwner(context, "desktop");
            assertEquals("Removing the desktop must retain the phone owner", phone.owner(), owner(context, "phone"));
            var missing = DisplayCatalog.selection(context, DisplayCatalog.read(context));
            assertEquals(DisplaySelection.Reason.UNAVAILABLE, missing.reason());
            assertEquals(-1, missing.id());
            // Exercise the normal Start action with another usable output present.
            instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, DesktopService.class)));
            instrumentation.waitForIdleSync();
            assertNoDesktopFor(context, 3000);
            assertEquals(phone.owner(), owner(context, "phone"));

            phase(files, report, "ready-for-reconnect");
            deadline = SystemClock.uptimeMillis() + 45000;
            DisplaySelection.Result restored;
            do {
                restored = DisplayCatalog.selection(context, DisplayCatalog.read(context));
                if (restored.reason() == DisplaySelection.Reason.SAVED && restored.id() != firstId) break;
                SystemClock.sleep(100);
            } while (SystemClock.uptimeMillis() < deadline);
            assertEquals("Host did not reconnect the saved name/capability signature", DisplaySelection.Reason.SAVED, restored.reason());
            int secondId = restored.id();
            assertNotEquals("Reconnection must really change the Android display ID", firstId, secondId);
            // No new Start action: actual service display-added listener restores it.
            Session replacement = awaitSession(context, "desktop", secondId, desktop.signature(), desktop.owner());
            assertNotEquals("Reconnection must create a new desktop owner PID", desktop.owner().pid(), replacement.owner().pid());
            assertEquals("Reconnection restarted the phone owner", phone.owner(), owner(context, "phone"));
            report.put("secondDisplayId", secondId).put("secondDesktopPid", replacement.owner().pid())
                    .put("phonePid", phone.owner().pid()).put("restoredByHotplug", true);
            GraphicsSmokeTest.awaitWidgetGuard(files);
            if ("true".equals(InstrumentationRegistry.getArguments().getString("rapidReconnect"))) {
                // The host removes and re-adds its same emulator slot immediately.
                // Do not wait for the old owner here: exercise the overlap between
                // display-added handling and the removed controller's shutdown.
                long rapidDeadline = SystemClock.uptimeMillis() + 75000;
                report.put("rapidReconnectRequested", true);
                phase(files, report, "ready-for-rapid-reconnect");
                DisplaySelection.Result rapid;
                do {
                    rapid = DisplayCatalog.selection(context, DisplayCatalog.read(context));
                    if (rapid.reason() == DisplaySelection.Reason.SAVED && rapid.id() != secondId) break;
                    SystemClock.sleep(50);
                } while (SystemClock.uptimeMillis() < rapidDeadline);
                assertEquals("Host did not rapidly reconnect the saved target", DisplaySelection.Reason.SAVED, rapid.reason());
                int thirdId = rapid.id();
                assertNotEquals("Rapid reconnection must change the Android display ID", secondId, thirdId);
                report.put("rapidDisplayId", thirdId);
                Session rapidReplacement = awaitSession(context, "desktop", thirdId, replacement.signature(),
                        replacement.owner(), rapidDeadline);
                assertNotEquals("Rapid reconnection reused the old desktop owner PID",
                        replacement.owner().pid(), rapidReplacement.owner().pid());
                assertEquals("Rapid reconnection restarted the phone owner", phone.owner(), owner(context, "phone"));
                report.put("rapidDesktopPid", rapidReplacement.owner().pid()).put("rapidReconnectVerified", true);
            }
            report.put("passed", true);
        } catch (Exception | AssertionError failure) {
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            try {
                stopScreens(context);
                awaitNoOwner(context, "desktop");
                awaitNoOwner(context, "phone");
            } finally {
                try {
                    if (activity != null) {
                        MainActivity controls = activity;
                        instrumentation.runOnMainSync(() -> settings(controls).dismiss());
                    }
                } finally {
                    restorePreferences(preferences, originalPreferences);
                    report.put("preferencesRestored", true);
                    phase(files, report, "complete");
                    Files.write(files.resolve("graphics/verified-display-selection.json"),
                            report.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    private static void phase(Path files, JSONObject report, String phase) throws Exception {
        report.put("phase", phase);
        Path directory = Files.createDirectories(files.resolve("graphics"));
        Path temporary = Files.createTempFile(directory, ".display-selection-", ".json");
        try {
            Files.write(temporary, report.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, directory.resolve("display-selection-handshake.json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static MainActivity awaitControls(MainActivity previous) {
        MainActivity[] current = new MainActivity[1];
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof MainActivity && activity != previous) current[0] = (MainActivity) activity;
            });
            if (current[0] != null) return current[0];
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Controls did not resume after launch/recreation");
    }

    private static DisplaySettings settings(MainActivity activity) {
        try {
            var field = MainActivity.class.getDeclaredField("displaySettings");
            field.setAccessible(true);
            return (DisplaySettings) field.get(activity);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void openSettings(MainActivity activity) {
        Button button = find(activity.getWindow().getDecorView(), Button.class, "Android displays", false);
        assertNotNull("Missing Android displays control", button);
        assertTrue(button.performClick());
    }

    private static RadioButton choice(MainActivity activity, String name, int displayId) {
        try {
            var field = DisplaySettings.class.getDeclaredField("dialog");
            field.setAccessible(true);
            AlertDialog dialog = (AlertDialog) field.get(settings(activity));
            assertNotNull("Android displays dialog is not open", dialog);
            RadioButton result = find(dialog.getWindow().getDecorView(), RadioButton.class, name + " · ID " + displayId, false);
            assertNotNull("Fixture radio button is missing", result);
            return result;
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static <T extends android.widget.TextView> T find(View view, Class<T> type, String text, boolean prefix) {
        if (type.isInstance(view)) {
            String actual = ((android.widget.TextView) view).getText().toString();
            if (prefix ? actual.startsWith(text) : actual.equals(text)) return type.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type, text, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Session awaitSession(Context context, String profile, int displayId, String previousSignature, Owner previousOwner) throws Exception {
        return awaitSession(context, profile, displayId, previousSignature, previousOwner, SystemClock.uptimeMillis() + 75000);
    }

    private static Session awaitSession(Context context, String profile, int displayId, String previousSignature, Owner previousOwner,
                                        long deadline) throws Exception {
        Path ready = context.getFilesDir().toPath().resolve("graphics/" + profile + "/ready.properties");
        String last = "No new owner/ready file";
        while (SystemClock.uptimeMillis() < deadline) {
            Owner live = owner(context, profile);
            if (live != null && !live.equals(previousOwner)) {
                try {
                    Properties properties = new Properties();
                    try (var in = Files.newInputStream(ready)) { properties.load(in); }
                    String signature = properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE", "");
                    if (!signature.isEmpty() && !signature.equals(previousSignature)
                            && Integer.toString(displayId).equals(properties.getProperty("ANDROID_DISPLAY_ID"))) {
                        JSONArray monitors = new JSONArray(GraphicsSmokeTest.ipc(context, profile, properties, "monitors"));
                        if (monitors.length() == 1 && monitors.getJSONObject(0).getInt("width") > 0
                                && monitors.getJSONObject(0).getInt("height") > 0 && live.equals(owner(context, profile)))
                            return new Session(live, signature);
                    }
                    last = properties.toString();
                } catch (Exception unavailable) { last = unavailable.toString(); }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError(profile + " did not start a fresh live compositor on display " + displayId + ": " + last);
    }

    private static String signature(Path files, String profile) {
        try (var in = Files.newInputStream(files.resolve("graphics/" + profile + "/ready.properties"))) {
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE", "");
        } catch (java.io.IOException unavailable) { return ""; }
    }

    private static Owner owner(Context context, String profile) {
        var processes = context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes != null) for (var process : processes) {
            if (process.uid != android.os.Process.myUid() || !process.processName.equals(context.getPackageName() + ":" + profile)) continue;
            try {
                String stat = new String(Files.readAllBytes(Path.of("/proc/" + process.pid + "/stat")), StandardCharsets.UTF_8);
                String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
                if (!fields[0].equals("Z")) return new Owner(process.pid, fields[19]);
            } catch (java.io.IOException | SecurityException ignored) { }
        }
        return null;
    }

    private static void awaitNoOwner(Context context, String profile) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (owner(context, profile) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertNull("Previous " + profile + " owner did not stop", owner(context, profile));
    }

    private static void assertNoDesktopFor(Context context, long duration) {
        long deadline = SystemClock.uptimeMillis() + duration;
        do {
            assertNull("Missing saved target fell back to another Android display", owner(context, "desktop"));
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
    }

    private static void stopScreens(Context context) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> context.startService(
                new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS)));
    }

    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> original) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (var entry : original.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set<?>) {
                Set<String> copy = new HashSet<>();
                for (Object item : (Set<?>) value) copy.add((String) item);
                editor.putStringSet(key, copy);
            } else throw new AssertionError("Unexpected preference type for " + key);
        }
        assertTrue("Failed to restore original display preferences", editor.commit());
        assertEquals("Display preferences changed after cleanup", original, preferences.getAll());
    }
}
