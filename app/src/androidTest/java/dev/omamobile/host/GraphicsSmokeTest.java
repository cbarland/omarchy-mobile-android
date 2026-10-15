package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Opt-in end-to-end check against a prepared Linux installation and two Android displays. */
@RunWith(AndroidJUnit4.class)
public class GraphicsSmokeTest {
    @Test public void upstreamShellsMapOnBothNativeHyprlandSessions() throws Exception {
        Assume.assumeTrue("Explicit -e graphics true required", "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Path files = context.getFilesDir().toPath();
        assertTrue("Stage the ABI's native compositor first", new File(context.getApplicationInfo().nativeLibraryDir, "libomamobile_hyprland.so").isFile());
        assertTrue("Attach an external presentation display", context.getSystemService(DisplayManager.class)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length > 0);
        assertTrue(Files.isRegularFile(files.resolve("linux/rootfs/root/.local/share/omamobile/start-shell")));
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, DesktopService.class)));
        for (String profile : List.of("phone", "desktop")) {
            Path home = files.resolve("graphics/" + profile);
            Path ready = home.resolve("ready.properties");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(200);
            assertTrue(profile + " native compositor did not become ready: " + logs(home), Files.exists(ready));
            Properties properties = new Properties();
            try (var in = Files.newInputStream(ready)) { properties.load(in); }
            assertEquals(files.resolve("linux/rootfs").toString(), properties.getProperty("ROOTFS"));
            if (profile.equals("phone")) assertEquals("0", properties.getProperty("ANDROID_DISPLAY_ID"));
            else assertNotEquals("0", properties.getProperty("ANDROID_DISPLAY_ID"));
            String layers = "";
            String expected = profile.equals("phone") ? "omamobile-home" : "omarchy-bar";
            while (System.nanoTime() < deadline) {
                // Another integration test may already have started these
                // compositors. Also tolerate a ready file from an older PID
                // while the replacement session is starting.
                try (var in = Files.newInputStream(ready)) { properties.load(in); }
                layers = ipc(context, profile, properties, "layers");
                if (layers.contains(expected) && layers.contains("omarchy-background")) break;
                Thread.sleep(500);
            }
            Files.write(home.resolve("verified-layers.json"), layers.getBytes(StandardCharsets.UTF_8));
            assertTrue(profile + " missing upstream surfaces: " + layers + "\n" + logs(home), layers.contains(expected));
            assertTrue(profile + " missing upstream wallpaper: " + layers, layers.contains("omarchy-background"));
            String monitors = ipc(context, profile, properties, "monitors");
            Files.write(home.resolve("verified-monitors.json"), monitors.getBytes(StandardCharsets.UTF_8));
            assertTrue(monitors, monitors.contains("width") && monitors.contains("height"));
            String errors = ipc(context, profile, properties, "configerrors");
            // This Hyprland revision serializes an empty error string as [""].
            var errorLines = new org.json.JSONArray(errors);
            for (int i = 0; i < errorLines.length(); i++)
                assertTrue(profile + " config errors: " + errors, errorLines.getString(i).trim().isEmpty());
            var scale = new org.json.JSONObject(ipc(context, profile, properties, "getoption", "debug:disable_scale_checks"));
            assertTrue(profile + " scale option was not applied: " + scale, scale.getBoolean("bool") && scale.getBoolean("set"));
        }
        awaitWidgetGuard(files);
    }

    static void awaitWidgetGuard(Path files) throws Exception {
        // Instrumentation teardown can stop the shell. Let upstream's widget
        // startup guard settle first so our test doesn't label that stop a crash.
        Path guard = files.resolve("linux/rootfs/root/.config/omamobile/widget-guard.json");
        long settle = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        long quiet = System.nanoTime();
        while (Files.exists(guard)) {
            var state = new org.json.JSONObject(new String(Files.readAllBytes(guard), StandardCharsets.UTF_8));
            if ("true".equals(InstrumentationRegistry.getArguments().getString("owl")))
                assertFalse("Reviewed todo widget was marked as crashed", state.getJSONObject("crashed").has("simonschubert.todo"));
            if (state.getJSONObject("loading").length() != 0) quiet = System.nanoTime();
            else if (System.nanoTime() - quiet >= TimeUnit.MILLISECONDS.toNanos(3250)) break;
            // Empty before the QML widget mounts is not a settled startup.
            assertTrue("Phone widgets did not finish loading", System.nanoTime() < settle);
            Thread.sleep(100);
        }
    }

    static String ipc(Context context, String profile, Properties properties, String command, String... arguments) throws Exception {
        Path files = context.getFilesDir().toPath();
        String runtime = "/run/omamobile/" + (profile.equals("phone") ? "p" : "d");
        var guest = new java.util.ArrayList<>(List.of("/usr/bin/hyprctl", "-j", command));
        guest.addAll(java.util.Arrays.asList(arguments));
        var argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(),
                files.resolve("linux/rootfs"), List.of(new RuntimeSpec.Bind("/dev", "/dev", false),
                        new RuntimeSpec.Bind("/proc", "/proc", false), new RuntimeSpec.Bind(files.resolve("r").toString(), "/run/omamobile", false)),
                guest);
        ProcessBuilder builder = new ProcessBuilder(argv).directory(files.toFile()).redirectErrorStream(true);
        builder.environment().clear(); builder.environment().putAll(RuntimeSpec.environment(false));
        builder.environment().put("XDG_RUNTIME_DIR", runtime);
        builder.environment().put("HYPRLAND_INSTANCE_SIGNATURE", properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
        Process process = builder.start(); process.getOutputStream().close();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return "Hyprland IPC timed out"; }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String logs(Path home) throws Exception {
        StringBuilder logs = new StringBuilder();
        for (String name : List.of("linux-shell.log", "dbus.log", "hyprland-native.log")) {
            Path path = home.resolve(name);
            if (!Files.exists(path)) continue;
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            logs.append(name).append(":\n").append(text.substring(Math.max(0, text.length() - 6000))).append('\n');
        }
        return logs.toString();
    }
}
