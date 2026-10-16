package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Properties;
import org.json.JSONArray;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in proof of the real controls, service deadline and native monitor reload. */
public class DisplayPreviewTest {
    private Context context;
    private Path files;
    @Test public void controlsConfirmRevertAndTimeoutWithoutRestartingPhone() throws Exception {
        Assume.assumeTrue("Explicit graphics/displayPreview opt-in required", "true".equals(
                InstrumentationRegistry.getArguments().getString("graphics")) && "true".equals(
                InstrumentationRegistry.getArguments().getString("displayPreview")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        files = context.getFilesDir().toPath();
        Path monitor = files.resolve("linux/rootfs/root/.local/share/omamobile/profiles/desktop/hypr/monitors.lua");
        byte[] original = Files.readAllBytes(monitor);
        var preview = new DisplayPreviewController(context);
        assertFalse("Do not overwrite a pre-existing preview", preview.hasPending());
        try {
            new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
            double initial = scale("desktop");
            assertEquals("Pristine desktop scale required", 1.0, initial, 0.001);
            String phoneOwner = ready("phone").getProperty("HYPRLAND_INSTANCE_SIGNATURE");
            String desktopOwner = ready("desktop").getProperty("HYPRLAND_INSTANCE_SIGNATURE");
            var instrumentation = InstrumentationRegistry.getInstrumentation();
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            click("Android displays");
            click("Preview desktop scale 1.25×");
            awaitControl("Keep desktop scale preview");
            awaitScale(1.25);
            click("Revert desktop scale preview");
            awaitControl("Preview desktop scale 1.25×");
            awaitScale(initial);
            assertArrayEquals(original, Files.readAllBytes(monitor));
            click("Preview desktop scale 1.25×");
            awaitControl("Keep desktop scale preview");
            awaitScale(1.25);
            click("Keep desktop scale preview");
            awaitControl("Preview desktop scale 1.5×");
            assertFalse(preview.hasPending());
            click("Preview desktop scale 1.5×");
            awaitControl("Keep desktop scale preview");
            awaitScale(1.5);
            click("Done"); // No dialog owns the timeout.
            long deadline = SystemClock.uptimeMillis() + DisplayPreviewController.TIMEOUT_MS + 8000;
            while (preview.hasPending() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            assertFalse("Service did not expire preview after dialog closed", preview.hasPending());
            awaitScale(1.25);
            click("Android displays");
            awaitControl("Preview desktop scale 1.5×");
            assertEquals(phoneOwner, ready("phone").getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
            assertEquals(desktopOwner, ready("desktop").getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
            assertEquals("Phone scale changed", 3.0, scale("phone"), 0.001);
        } finally {
            try { preview.revert(); }
            finally {
                GraphicsSmokeTest.awaitWidgetGuard(files);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> context.startService(
                        new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS)));
                SystemClock.sleep(1500);
                Files.write(monitor, original);
            }
        }
    }
    private Properties ready(String profile) throws Exception {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(files.resolve("graphics/" + profile + "/ready.properties"))) { properties.load(input); }
        return properties;
    }
    private double scale(String profile) throws Exception {
        String response = GraphicsSmokeTest.ipc(context, profile, ready(profile), "monitors");
        return new JSONArray(response).getJSONObject(0).getDouble("scale");
    }
    private void awaitScale(double expected) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (Math.abs(scale("desktop") - expected) > 0.001 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        assertEquals("Native compositor scale", expected, scale("desktop"), 0.001);
    }
    private AccessibilityNodeInfo find(String text) {
        var root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        var pending = new ArrayDeque<AccessibilityNodeInfo>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (node.getText() != null && node.getText().toString().toLowerCase(Locale.ROOT).equals(text.toLowerCase(Locale.ROOT))) return node;
            for (int i = 0; i < node.getChildCount(); i++) { var child = node.getChild(i); if (child != null) pending.add(child); }
        }
        return null;
    }
    private void awaitControl(String text) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (find(text) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        assertNotNull("Missing visible control: " + text, find(text));
    }
    private void click(String text) {
        awaitControl(text);
        assertTrue("Control click failed: " + text, find(text).performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }
}
