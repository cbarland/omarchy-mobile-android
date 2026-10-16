package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
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

/** One-shot geometry probe used by verify-display-geometry.py during hotplug. */
public class DisplayGeometryProbeTest {
    @Test public void externalDisplayGeometryReachesHyprland() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit -e geometry true required", "true".equals(arguments.getString("geometry")));
        int expectedWidth = Integer.parseInt(arguments.getString("expectedWidth"));
        int expectedHeight = Integer.parseInt(arguments.getString("expectedHeight"));
        String expectedDisplayId = arguments.getString("expectedDisplayId");
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path files = context.getFilesDir().toPath();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            context.startForegroundService(new Intent(context, DesktopService.class));
        });
        Properties ready = awaitDesktop(files, expectedDisplayId);
        long deadline = android.os.SystemClock.uptimeMillis() + 30000;
        JSONObject monitor = null;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                JSONArray monitors = new JSONArray(GraphicsSmokeTest.ipc(context, "desktop", ready, "monitors"));
                if (monitors.length() == 1) {
                    JSONObject candidate = monitors.getJSONObject(0);
                    if (candidate.optInt("width") == expectedWidth && candidate.optInt("height") == expectedHeight) {
                        monitor = candidate;
                        break;
                    }
                    monitor = candidate;
                }
            } catch (Exception ignored) { }
            Thread.sleep(200);
            try (var in = Files.newInputStream(files.resolve("graphics/desktop/ready.properties"))) { ready.load(in); }
        }
        assertNotNull("Desktop Hyprland monitor did not become available", monitor);
        assertEquals("Hyprland width did not follow external display", expectedWidth, monitor.getInt("width"));
        assertEquals("Hyprland height did not follow external display", expectedHeight, monitor.getInt("height"));
        JSONObject report = new JSONObject().put("expectedWidth", expectedWidth).put("expectedHeight", expectedHeight)
                .put("actualWidth", monitor.getInt("width")).put("actualHeight", monitor.getInt("height"))
                .put("desktopDisplayId", ready.getProperty("ANDROID_DISPLAY_ID"))
                .put("desktopSignature", ready.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
        Files.write(files.resolve("graphics/desktop/geometry-probe.json"), report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Properties awaitDesktop(Path files, String expectedDisplayId) throws Exception {
        Path ready = files.resolve("graphics/desktop/ready.properties");
        long deadline = android.os.SystemClock.uptimeMillis() + 75000;
        Exception last = null;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                Properties value = new Properties();
                try (var in = Files.newInputStream(ready)) { value.load(in); }
                if (value.getProperty("ANDROID_DISPLAY_ID") != null
                        && !"0".equals(value.getProperty("ANDROID_DISPLAY_ID"))
                        && (expectedDisplayId == null || expectedDisplayId.equals(value.getProperty("ANDROID_DISPLAY_ID")))) return value;
            } catch (Exception error) { last = error; }
            Thread.sleep(200);
        }
        throw new AssertionError("Desktop session did not become ready: " + last);
    }
}
