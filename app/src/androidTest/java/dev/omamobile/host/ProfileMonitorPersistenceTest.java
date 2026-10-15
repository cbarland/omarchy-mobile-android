package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in pristine fixture check: stock desktop Display panel and both profiles' scale persistence. */
@RunWith(AndroidJUnit4.class)
public class ProfileMonitorPersistenceTest {
    private static final List<String> PROFILES = List.of("phone", "desktop");
    private Context context;
    private Path files, root, scratch, evidence;
    private String guest;
    private int commandNumber;

    @Test public void desktopDisplayPanelAndProfileCliScalesSurviveOwnerRestart() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit -e graphics true -e monitorPersistence true required",
                "true".equals(arguments.getString("graphics"))
                        && "true".equals(arguments.getString("monitorPersistence")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        files = context.getFilesDir().toPath();
        root = files.resolve("linux/rootfs");
        // A failed fixture precondition must be a failure, never a skip after mutation.
        // In particular this test must never hide or rewrite user Hyprland files.
        assertPristineFixture();
        scratch = Files.createTempDirectory(GuestPaths.resolve(root, "/tmp"), "monitor-verification-");
        guest = "/tmp/" + scratch.getFileName();
        evidence = Files.createDirectories(files.resolve("graphics/monitor-verification/" + scratch.getFileName()));
        Map<String, Double> originals = new LinkedHashMap<>();
        Map<String, byte[]> originalFiles = new LinkedHashMap<>();
        boolean changed = false;
        Throwable failure = null;
        try {
            assertPinnedUpstream();
            startScreens();
            for (String profile : PROFILES) {
                JSONObject monitor = monitor(profile, "initial");
                double scale = monitor.getDouble("scale");
                assertEquals("Use the default scale on the pristine " + profile + " fixture",
                        profile.equals("phone") ? 3 : 1, scale, 0.001);
                double requested = profile.equals("phone") ? 2 : 1.25;
                int units = (int) Math.round(requested * 120);
                assertTrue("Fixture mode cannot represent the requested scale exactly: " + monitor,
                        monitor.getInt("width") * 120 % units == 0 && monitor.getInt("height") * 120 % units == 0);
                originals.put(profile, scale);
                originalFiles.put(profile, Files.readAllBytes(monitorFile(profile)));
                verifyProfilePanelCapability(profile, scale, "initial");
            }

            changed = true; // Cleanup must run even when a CLI launch or assertion fails.
            setScale("phone", 2, "phone-change");
            assertArrayEquals("Phone CLI touched the desktop's persistent config", originalFiles.get("desktop"),
                    Files.readAllBytes(monitorFile("desktop")));
            assertScale("desktop", originals.get("desktop"), "desktop-after-phone");
            byte[] selectedPhone = Files.readAllBytes(monitorFile("phone"));
            setScale("desktop", 1.25, "desktop-change");
            assertArrayEquals("Desktop CLI touched the phone's persistent config", selectedPhone,
                    Files.readAllBytes(monitorFile("phone")));
            assertScale("phone", 2, "phone-after-desktop");

            // The CLI also evals a runtime scale. Prove the file watcher separately:
            // reset only runtime, then perform the same sed -i atomic replacement
            // used by upstream. No explicit reload may be used to make this pass.
            for (String profile : PROFILES) {
                double selected = profile.equals("phone") ? 2 : 1.25;
                String output = monitor(profile, "before-runtime-reset").getString("name");
                assertTrue("Unexpected output name", output.matches("[A-Za-z0-9._-]+"));
                String reply = ipc(profile, "eval", "hl.monitor({ output = \"" + output + "\", mode = \"preferred\", position = \"auto\", scale = "
                        + originals.get(profile) + " })");
                write(profile + "-runtime-reset.txt", reply);
                awaitScale(profile, originals.get(profile), "runtime-reset");
                run(profile, "sed -i -E " + quote("s|^local omarchy_monitor_scale = .*|local omarchy_monitor_scale = "
                        + selected + "|") + " \"$HOME/.config/hypr/monitors.lua\"", "atomic-watch");
                awaitScale(profile, selected, "atomic-watch");
                verifyProfilePanelCapability(profile, selected, "selected");
            }

            Map<String, String> signatures = new LinkedHashMap<>();
            for (String profile : PROFILES) signatures.put(profile, ready(profile).getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
            stopScreens();
            startScreens();
            for (String profile : PROFILES) {
                assertNotEquals("The old compositor was reused instead of restarting " + profile,
                        signatures.get(profile), ready(profile).getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
                double selected = profile.equals("phone") ? 2 : 1.25;
                assertScale(profile, selected, "reopened");
                assertPersistedScale(profile, selected);
                verifyProfilePanelCapability(profile, selected, "reopened");
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (changed) {
                try { startScreens(); } catch (Throwable error) { failure = append(failure, error); }
                for (String profile : PROFILES) {
                    try {
                        try { setScale(profile, originals.get(profile), "restore"); }
                        finally {
                            // Retain original bytes even if a dead compositor
                            // makes CLI cleanup fail. Next startup reads them.
                            Files.write(monitorFile(profile), originalFiles.get(profile));
                        }
                        awaitScale(profile, originals.get(profile), "restored");
                    } catch (Throwable error) { failure = append(failure, error); }
                }
            }
            try { stopScreens(); } catch (Throwable error) { failure = append(failure, error); }
            try { GuestPaths.deleteTree(scratch); } catch (Throwable error) { failure = append(failure, error); }
            if (failure != null) write("failure.txt", android.util.Log.getStackTraceString(failure));
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        write("result.json", new JSONObject().put("verified", true).put("phoneScale", 2)
                .put("desktopScale", 1.25).put("ownerRestart", true).put("atomicFileWatch", true)
                .put("desktopStockDisplayPanel", true).put("phoneStockDisplayPanel", false)
                .put("originalScalesRestored", true).put("ownersStopped", true).toString(2));
    }

    private void assertPristineFixture() throws Exception {
        Path shared = root.resolve("root/.config/hypr");
        assertDirectoryPath(root, shared);
        if (Files.exists(shared, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.newDirectoryStream(shared)) {
                assertFalse("Pristine fixture required: existing shared Hyprland files are preserved", entries.iterator().hasNext());
            }
        }
        for (String profile : PROFILES) {
            Path monitor = monitorFile(profile);
            assertDirectoryPath(root, monitor.getParent());
            if (Files.exists(monitor.getParent(), LinkOption.NOFOLLOW_LINKS)) {
                try (var entries = Files.newDirectoryStream(monitor.getParent())) {
                    for (Path entry : entries) assertEquals("Custom per-profile files are preserved", monitor, entry);
                }
            }
            if (Files.exists(monitor, LinkOption.NOFOLLOW_LINKS)) {
                assertTrue("Custom profile monitor paths are preserved", Files.isRegularFile(monitor, LinkOption.NOFOLLOW_LINKS));
                assertEquals("Pristine generated monitor file required", genericMonitor(profile.equals("phone") ? "3" : "1"), read(monitor));
            }
            Path nativeConfig = files.resolve("graphics/" + profile + "/hyprland.lua");
            assertDirectoryPath(files, nativeConfig.getParent());
            if (!Files.exists(nativeConfig, LinkOption.NOFOLLOW_LINKS)) continue;
            assertTrue("Custom native config paths are preserved", Files.isRegularFile(nativeConfig, LinkOption.NOFOLLOW_LINKS));
            String seed = GraphicalSession.previousSeedConfig(profile.equals("phone"));
            String shortcut = "hl.bind(\"SUPER + CTRL + D\", hl.dsp.exec_cmd(\"omarchy-shell shell toggle omarchy.monitor\"))\n";
            String managed = "require(\"" + monitor.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\")\n" + seed.substring(seed.indexOf('\n') + 1) + shortcut;
            assertTrue("Pristine generated native config required; custom content is preserved",
                    List.of(seed, seed + shortcut, managed).contains(read(nativeConfig)));
        }
    }

    private void assertPinnedUpstream() throws Exception {
        // github.com/basecamp/omarchy, MIT, e71a816e9032f040225e086c7256f73e8080ae45:
        // shell/plugins/panels/monitor/Panel.qml:211 (stateIpc), :226 (open), :314 (setScale),
        // bin/omarchy-hyprland-monitor-scaling:74 (set_scale), :100 (sed persistence).
        JSONObject sources = new JSONObject(read(root.resolve("usr/share/omamobile/android/sources.json")));
        assertEquals("Use the pinned upstream fixture", "e71a816e9032f040225e086c7256f73e8080ae45", sources.getString("omarchy"));
        assertEquals("Use the pinned upstream Mobile fixture", "953bcb3058347459cdf7faaa1e149f16e0f7cfa6", sources.getString("omarchy-mobile"));
        JSONObject hashes = new JSONObject();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("root/.local/share/omarchy/shell/plugins/panels/monitor/Panel.qml", "c506301076e7c9df75c1492d2349b45d4c71b96e939ad01465a6b62a4ee6606e");
        expected.put("root/.local/share/omarchy/bin/omarchy-hyprland-monitor-scaling", "6ef14d1224da38f7a9a4780e2b1c7d049411248082f4617004f44d85273fc4b9");
        // derekross/omarchy-mobile, MIT, the pin above: phone bar's explicit
        // no-widget-panel host API at packages/omamobile-shell/plugins/omamobile.bar/Bar.qml:105-109.
        expected.put("usr/share/omamobile/shell/plugins/omamobile.bar/Bar.qml", "de1f04dd367758032b0d4e8ad09b3aeadcb1d44dde3c910c674b8c3476f849c0");
        for (var entry : expected.entrySet()) {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(root.resolve(entry.getKey())));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            hashes.put(entry.getKey(), hex.toString());
            assertEquals("Upstream fixture file was modified: " + entry.getKey(), entry.getValue(), hex.toString());
        }
        write("upstream-sources.json", sources.put("verifiedSha256", hashes).toString(2));
    }

    private static void assertDirectoryPath(Path base, Path directory) {
        for (Path path = directory; path != null && path.startsWith(base); path = path.getParent())
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                assertTrue("Pristine fixture requires real directories: " + path, Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
    }

    private static String genericMonitor(String scale) {
        return "-- Per-profile Wayland output scale; GTK text scale is separate.\nlocal omarchy_monitor_scale = " + scale
                + "\nhl.monitor({ output = \"\", mode = \"preferred\", position = \"auto\", scale = omarchy_monitor_scale })\n";
    }

    private Path monitorFile(String profile) { return root.resolve("root/.local/share/omamobile/profiles/" + profile + "/hypr/monitors.lua"); }

    private void startScreens() throws Exception { new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions(); }

    private void stopScreens() throws Exception {
        Throwable guardFailure = null;
        try { GraphicsSmokeTest.awaitWidgetGuard(files); } catch (Throwable error) { guardFailure = error; }
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS)));
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (ownersRunning() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        assertFalse("Display owner processes did not stop", ownersRunning());
        if (guardFailure instanceof Exception) throw (Exception) guardFailure;
        if (guardFailure instanceof Error) throw (Error) guardFailure;
    }

    private boolean ownersRunning() {
        var processes = context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes == null) return false;
        return processes.stream().anyMatch(process -> process.uid == android.os.Process.myUid()
                && (process.processName.equals(context.getPackageName() + ":phone")
                || process.processName.equals(context.getPackageName() + ":desktop")));
    }

    private Properties ready(String profile) throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(files.resolve("graphics/" + profile + "/ready.properties"))) { properties.load(in); }
        return properties;
    }

    private String ipc(String profile, String command, String... arguments) throws Exception {
        return GraphicsSmokeTest.ipc(context, profile, ready(profile), command, arguments);
    }

    private JSONObject monitor(String profile, String stage) throws Exception {
        String json = ipc(profile, "monitors");
        write(profile + "-" + stage + "-monitors.json", json);
        JSONArray monitors = new JSONArray(json);
        assertEquals("Each profile must own exactly one Wayland output: " + json, 1, monitors.length());
        JSONObject monitor = monitors.getJSONObject(0);
        assertTrue("The upstream CLI needs a focused output", monitor.getBoolean("focused"));
        return monitor;
    }

    private void assertScale(String profile, double expected, String stage) throws Exception {
        assertEquals(profile + " scale at " + stage, expected, monitor(profile, stage).getDouble("scale"), 0.001);
        JSONArray errors = new JSONArray(ipc(profile, "configerrors"));
        write(profile + "-" + stage + "-configerrors.json", errors.toString());
        for (int i = 0; i < errors.length(); i++) assertTrue("Config errors: " + errors, errors.getString(i).trim().isEmpty());
    }

    private void awaitScale(String profile, double expected, String stage) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (Math.abs(monitor(profile, stage).getDouble("scale") - expected) > 0.001
                && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(150);
        assertScale(profile, expected, stage);
    }

    private void assertPersistedScale(String profile, double expected) throws Exception {
        String text = read(monitorFile(profile));
        write(profile + "-persisted-monitors.lua", text);
        var matcher = java.util.regex.Pattern.compile("(?m)^local omarchy_monitor_scale = ([0-9.]+)$").matcher(text);
        assertTrue("Stock CLI did not persist a monitor scale: " + text, matcher.find());
        assertEquals(expected, Double.parseDouble(matcher.group(1)), 0.001);
    }

    private void setScale(String profile, double scale, String stage) throws Exception {
        run(profile, "omarchy-hyprland-monitor-scaling " + scale, stage);
        awaitScale(profile, scale, stage);
        assertPersistedScale(profile, scale);
    }

    private void verifyProfilePanelCapability(String profile, double expectedScale, String stage) throws Exception {
        // Stock shell.qml:1173/1213 routes bar-widget panels through the active
        // bar. Calling the per-widget target first assumes that target exists
        // and bypasses the same routing used by SUPER + CTRL + D.
        run(profile, "omarchy-shell shell hide omarchy.monitor", stage + "-panel-reset");
        awaitPanelLayer(profile, false, stage + "-closed");
        String summoned = run(profile, "omarchy-shell shell summon omarchy.monitor", stage + "-panel-open");
        if (profile.equals("phone")) {
            // This is an asserted upstream profile capability, not a skipped
            // panel check. The faithful Mobile bar intentionally hosts no stock
            // bar-widget panels; both profiles still exercise the scaling CLI.
            assertEquals("Upstream Mobile bar must report its absent Display widget", "unknown", summoned.trim());
            awaitPanelLayer(profile, false, stage + "-unsupported");
            write(profile + "-" + stage + "-panel-capability.json", new JSONObject()
                    .put("profile", profile).put("stockDisplayPanelAvailable", false).put("summonReply", summoned.trim())
                    .put("reason", "Pinned upstream omamobile.bar hosts no widget panels; use the scaling CLI for phone output scale.")
                    .put("source", "omarchy-mobile@953bcb3058347459cdf7faaa1e149f16e0f7cfa6 packages/omamobile-shell/plugins/omamobile.bar/Bar.qml:105-109")
                    .toString(2));
            return;
        }
        assertEquals(profile + " active bar does not expose the stock Display panel: " + summoned, "ok", summoned.trim());
        try {
            awaitPanelLayer(profile, true, stage + "-open");
            long deadline = SystemClock.uptimeMillis() + 12000;
            JSONObject state;
            do {
                state = new JSONObject(run(profile, "omarchy-shell omarchy.monitor state", stage + "-panel-state"));
                if (state.getJSONArray("displays").length() == 1 && !state.getString("scale").isEmpty()
                        && Math.abs(Double.parseDouble(state.getString("scale")) - expectedScale) < 0.001) break;
                SystemClock.sleep(150);
            } while (SystemClock.uptimeMillis() < deadline);
            write(profile + "-" + stage + "-panel-state.json", state.toString(2));
            assertEquals("Stock panel state must describe this compositor's output", 1, state.getJSONArray("displays").length());
            assertEquals(expectedScale, Double.parseDouble(state.getString("scale")), 0.001);
            assertEquals(monitor(profile, stage + "-panel").getString("name"), state.getString("focusedMonitor"));
            assertTrue("Panel omitted real brightness availability", state.get("brightnessAvailable") instanceof Boolean);
        } finally { run(profile, "omarchy-shell shell hide omarchy.monitor", stage + "-panel-close"); }
    }

    private void awaitPanelLayer(String profile, boolean open, String stage) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 12000;
        String layers;
        do {
            layers = ipc(profile, "layers");
            if (layers.contains("omarchy-keyboard-panel") == open) break;
            SystemClock.sleep(150);
        } while (SystemClock.uptimeMillis() < deadline);
        write(profile + "-" + stage + "-panel-layers.json", layers);
        assertEquals("Stock Display panel layer at " + stage + ": " + layers, open, layers.contains("omarchy-keyboard-panel"));
    }

    private String run(String profile, String command, String stage) throws Exception {
        String name = String.format(java.util.Locale.ROOT, "%03d-%s-%s", ++commandNumber, profile, stage);
        Path status = scratch.resolve(name + ".status");
        boolean terminal = command.startsWith("omarchy-hyprland-monitor-scaling ");
        String script = "#!/bin/bash\ncd " + quote(guest) + " || exit 1\n"
                + "{ " + (terminal ? "[[ -t 0 ]] && " : "") + "timeout --kill-after=1s 10s /bin/bash -c " + quote(command) + "; } > "
                + quote(name + ".out") + " 2> " + quote(name + ".err") + "\nprintf '%s' \"$?\" > " + quote(name + ".status") + "\n";
        Files.write(scratch.resolve(name + ".sh"), script.getBytes(StandardCharsets.UTF_8));
        // Panel queries use the same exec wrapper without a new window, so a
        // terminal cannot steal the panel's keyboard focus during the probe.
        String launch = (terminal ? "foot --app-id=omamobile-monitor-test " : "")
                + "/bin/bash " + quote(guest + "/" + name + ".sh");
        // Uses the compositor's production Linux exec wrapper and profile binds.
        // A separate developer tawcroot invocation would miss ~/.config/hypr.
        String reply = ipc(profile, "dispatch_lua_expression", "hl.dsp.exec_cmd(\""
                + launch.replace("\\", "\\\\").replace("\"", "\\\"") + "\")");
        assertEquals("Native exec failed: " + reply, "ok", reply.trim());
        long deadline = SystemClock.uptimeMillis() + 18000;
        while ((!Files.exists(status) || Files.size(status) == 0) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        for (String extension : List.of("sh", "out", "err", "status")) {
            Path path = scratch.resolve(name + "." + extension);
            if (Files.exists(path)) Files.copy(path, evidence.resolve(path.getFileName()));
        }
        assertTrue("Graphical command timed out: " + name, Files.exists(status) && Files.size(status) > 0);
        assertEquals("CLI failed: " + command + "\n" + read(scratch.resolve(name + ".err")), "0", read(status));
        return read(scratch.resolve(name + ".out"));
    }

    private void write(String name, String text) throws Exception { Files.write(evidence.resolve(name), text.getBytes(StandardCharsets.UTF_8)); }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; first.addSuppressed(next); return first; }
}
