package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.IOException;
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
    @Test public void guestCommandDiagnosticsAreBoundedAndActionable() throws Exception {
        Assume.assumeTrue("Explicit -e diagnostics true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("diagnostics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path ready = context.getFilesDir().toPath().resolve("graphics/desktop/ready.properties");
        assertTrue("Desktop graphics session is required", Files.isRegularFile(ready));
        Properties properties = new Properties();
        try (var in = Files.newInputStream(ready)) { properties.load(in); }
        assertEquals("ok", runGuest(context, "desktop", properties,
                List.of("/bin/sh", "-c", "printf ok")));
        try {
            runGuest(context, "desktop", properties, List.of("/bin/sh", "-c", "printf nonzero; exit 7"));
            fail("Nonzero guest command unexpectedly succeeded");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("profile=desktop"));
            assertTrue(expected.getMessage().contains("exited 7"));
            assertTrue(expected.getMessage().contains("nonzero"));
        }
        try {
            runGuest(context, "desktop", properties, List.of("/usr/bin/python3", "-c", "import time; time.sleep(6)"));
            fail("Timed guest command unexpectedly succeeded");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("timed out after 5s"));
            assertTrue(expected.getMessage().contains("profile=desktop"));
        }
        try {
            runGuest(context, "desktop", properties,
                    List.of("/usr/bin/python3", "-c", "print('x' * 200000)"));
            fail("Oversized guest output unexpectedly succeeded");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("output exceeded 65536 bytes"));
            assertTrue(expected.getMessage().contains("outputTail="));
        }
    }

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
            String startupFailure = "";
            String expected = profile.equals("phone") ? "omamobile-home" : "omarchy-bar";
            while (System.nanoTime() < deadline) {
                // Another integration test may already have started these
                // compositors. Also tolerate a ready file from an older PID
                // while the replacement session is starting.
                try (var in = Files.newInputStream(ready)) { properties.load(in); }
                try {
                    layers = ipc(context, profile, properties, "layers");
                    if (layers.contains(expected) && layers.contains("omarchy-background")) break;
                } catch (IllegalStateException starting) {
                    // A stale ready file can name the previous session until
                    // the service replaces it. Keep the existing startup bound.
                    startupFailure = starting.getMessage();
                }
                Thread.sleep(500);
            }
            Files.write(home.resolve("verified-layers.json"), layers.getBytes(StandardCharsets.UTF_8));
            assertTrue(profile + " missing upstream surfaces: " + layers + "\n" + startupFailure + "\n" + logs(home), layers.contains(expected));
            assertTrue(profile + " missing upstream wallpaper: " + layers, layers.contains("omarchy-background"));
            verifyDbusActivationEnvironment(context, profile, properties);
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
            assertEquals(profile + " runtime symlink probe failed", "ok", runGuest(context, profile, properties,
                    List.of("/usr/bin/python3", "-c", RUNTIME_SYMLINK_PROBE)).trim());
            String instances = runGuest(context, profile, properties,
                    List.of("/usr/bin/quickshell", "list", "-a", "-j")).trim();
            var discovered = new org.json.JSONArray(instances);
            assertTrue(profile + " quickshell discovery returned no live instances: " + instances,
                    discovered.length() >= 1);
        }
        awaitWidgetGuard(files);
    }

    /**
     * Destructive lifecycle check for the installation lease.  It is opt-in
     * because it starts and stops both real compositor owners on the prepared
     * emulator; the ordinary graphics smoke tests remain read-only at the
     * test-runner level.
     */
    @Test public void installationLeaseTracksBothGraphicalOwners() throws Exception {
        Assume.assumeTrue("Explicit -e leaseGraphics true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("leaseGraphics")));
        Assume.assumeTrue("Explicit -e graphics true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path linux = context.getFilesDir().toPath().resolve("linux");
        try {
            // Establish a clean lifecycle baseline without touching app data.
            context.startService(new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS));
            awaitOwnerState(false, false);
            clearReadyFiles(context);
            // Use the reviewed normal startup/readiness path so the lease
            // assertions begin only after both owners have acquired SHARED_USE.
            upstreamShellsMapOnBothNativeHyprlandSessions();
            awaitOwnerState(true, true);
            String initialPhoneSignature = readySignature(context, "phone");
            String initialDesktopSignature = readySignature(context, "desktop");
            try (InstallationLease ignored = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
                fail("Exclusive installation lease acquired while both graphical owners were live");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("busy"));
            }

            // The profile-specific broadcast is the same control path used by
            // the display-removal handler; it must leave the phone owner live.
            context.sendBroadcast(new Intent(DesktopService.STOP_SCREENS)
                    .setPackage(context.getPackageName()).putExtra("profile", "desktop"));
            awaitOwnerState(true, false);
            try (InstallationLease ignored = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
                fail("Exclusive installation lease acquired while phone owner was live");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("busy"));
            }

            context.startService(new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS));
            awaitOwnerState(false, false);
            String phoneSignatureBeforeBusyStart = readySignature(context, "phone");
            String desktopSignatureBeforeBusyStart = readySignature(context, "desktop");
            try (InstallationLease lease = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
                // A normal start while an installer owns the tree must fail
                // closed and must not create a fresh ready owner.
                startNormalOwners(context);
                SystemClock.sleep(2500);
                assertEquals("Phone compositor created a new ready signature while installation was exclusive",
                        phoneSignatureBeforeBusyStart, readySignature(context, "phone"));
                assertEquals("Desktop compositor created a new ready signature while installation was exclusive",
                        desktopSignatureBeforeBusyStart, readySignature(context, "desktop"));
            }
            // The rejected start may still have a bound Android service
            // process. Stop that failed attempt before testing a real restart;
            // otherwise a second start can merely reuse the old service.
            context.startService(new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS));
            awaitOwnerState(false, false);
            clearReadyFiles(context);
            upstreamShellsMapOnBothNativeHyprlandSessions();
            awaitOwnerState(true, true);
            String restartedPhoneSignature = readySignature(context, "phone");
            String restartedDesktopSignature = readySignature(context, "desktop");
            assertNotEquals("Phone compositor did not restart with a new signature; expected old="
                    + initialPhoneSignature + " actual=" + restartedPhoneSignature,
                    initialPhoneSignature, restartedPhoneSignature);
            assertNotEquals("Desktop compositor did not restart with a new signature; expected old="
                    + initialDesktopSignature + " actual=" + restartedDesktopSignature,
                    initialDesktopSignature, restartedDesktopSignature);
            assertResponsive(context, "phone");
            assertResponsive(context, "desktop");
        } finally {
            if (processRunning("dev.omamobile.host:phone") || processRunning("dev.omamobile.host:desktop"))
                context.startService(new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS));
        }
    }

    private static void startNormalOwners(Context context) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> context.startActivity(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> context.startForegroundService(
                new Intent(context, DesktopService.class)));
    }

    private static void awaitOwnerState(boolean phone, boolean desktop) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (processRunning("dev.omamobile.host:phone") == phone
                    && processRunning("dev.omamobile.host:desktop") == desktop) return;
            SystemClock.sleep(200);
        }
        assertEquals("phone owner state", phone, processRunning("dev.omamobile.host:phone"));
        assertEquals("desktop owner state", desktop, processRunning("dev.omamobile.host:desktop"));
    }

    private static boolean processRunning(String process) throws Exception {
        try (var parcel = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("pidof " + process);
             var in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel)) {
            return !new String(in.readAllBytes(), StandardCharsets.UTF_8).trim().isEmpty();
        }
    }

    private static String readySignature(Context context, String profile) throws Exception {
        Path ready = context.getFilesDir().toPath().resolve("graphics/" + profile + "/ready.properties");
        if (!Files.isRegularFile(ready)) return "";
        Properties properties = new Properties();
        try (var in = Files.newInputStream(ready)) { properties.load(in); }
        return properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE", "");
    }

    private static void clearReadyFiles(Context context) throws Exception {
        Path graphics = context.getFilesDir().toPath().resolve("graphics");
        Files.deleteIfExists(graphics.resolve("phone/ready.properties"));
        Files.deleteIfExists(graphics.resolve("desktop/ready.properties"));
    }

    private static void assertResponsive(Context context, String profile) throws Exception {
        Path ready = context.getFilesDir().toPath().resolve("graphics/" + profile + "/ready.properties");
        Properties properties = new Properties();
        try (var in = Files.newInputStream(ready)) { properties.load(in); }
        assertTrue(profile + " compositor did not answer after lease release",
                ipc(context, profile, properties, "monitors").contains("width"));
    }

    @Test public void coveredPhoneStaysResponsiveWhileMainServiceOwnsBindings() throws Exception {
        Assume.assumeTrue("Explicit -e freezer true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("freezer")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Path files = context.getFilesDir().toPath();
        try {
            // Reuse the full startup/readiness path, including stale-ready
            // replacement handling, before beginning the background interval.
            upstreamShellsMapOnBothNativeHyprlandSessions();
            Properties[] properties = new Properties[2];
            for (int i = 0; i < 2; i++) {
                String profile = i == 0 ? "phone" : "desktop";
                Path ready = files.resolve("graphics/" + profile + "/ready.properties");
                properties[i] = new Properties();
                try (var in = Files.newInputStream(ready)) { properties[i].load(in); }
                assertTrue(profile + " compositor was not initially responsive",
                        ipc(context, profile, properties[i], "monitors").contains("width"));
            }
            // Put the controls activity over the phone output while the main
            // FGS owns both cross-process bindings. The external output is
            // intentionally left in its normal presentation state.
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            instrumentation.waitForIdleSync();
            // Leave the app as a user would and check background responsiveness.
            // Android may retain the phone as PREVIOUS; this switch alone does
            // not guarantee cached-process promotion on every test run.
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            instrumentation.waitForIdleSync();
            // Android's cached-process promotion was observed 45-60 seconds
            // after readiness on the failing build; stay beyond that window.
            SystemClock.sleep(65000);
            for (int i = 0; i < 2; i++) {
                String profile = i == 0 ? "phone" : "desktop";
                Properties current = new Properties();
                try (var in = Files.newInputStream(files.resolve("graphics/" + profile + "/ready.properties"))) {
                    current.load(in);
                }
                assertEquals(profile + " compositor was replaced while covered",
                        properties[i].getProperty("HYPRLAND_INSTANCE_SIGNATURE"),
                        current.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
                assertTrue(profile + " compositor stopped responding while covered",
                        ipc(context, profile, current, "monitors").contains("width"));
            }
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
            long stopped = SystemClock.uptimeMillis() + 15000;
            while (outputProcessesRunning() && SystemClock.uptimeMillis() < stopped) SystemClock.sleep(100);
            assertFalse("Stopping the main service left compositor owners running", outputProcessesRunning());
            SystemClock.sleep(1000);
            assertFalse("A released compositor owner was recreated", outputProcessesRunning());
        } finally {
            if (outputProcessesRunning()) instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
        }
    }

    private static boolean outputProcessesRunning() throws Exception {
        var ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (var parcel = ui.executeShellCommand("pidof dev.omamobile.host:phone dev.omamobile.host:desktop");
             var in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel)) {
            return !new String(in.readAllBytes(), StandardCharsets.UTF_8).trim().isEmpty();
        }
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
        var guest = new java.util.ArrayList<>(List.of("/usr/bin/hyprctl", "-j", command));
        guest.addAll(java.util.Arrays.asList(arguments));
        return runGuest(context, profile, properties, guest);
    }

    private static void verifyDbusActivationEnvironment(Context context, String profile, Properties properties) throws Exception {
        Path root = context.getFilesDir().toPath().resolve("linux/rootfs");
        String id = "dev.omamobile.envprobe." + profile + ".p" + java.util.UUID.randomUUID().toString().replace("-", "");
        Path service = root.resolve("usr/share/dbus-1/services/" + id + ".service");
        Path temporary = Files.createTempDirectory(root.resolve("tmp"), "omamobile-env-");
        Path script = temporary.resolve("probe.py");
        Path output = temporary.resolve("environment.json");
        String guestTemporary = "/tmp/" + temporary.getFileName();
        String source = """
                import json, os, sys
                from gi.repository import Gio, GLib
                bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
                result = bus.call_sync('org.freedesktop.DBus', '/org/freedesktop/DBus',
                              'org.freedesktop.DBus', 'RequestName',
                              GLib.Variant('(su)', (sys.argv[1], 4)),
                              GLib.VariantType('(u)'), Gio.DBusCallFlags.NONE, 2000, None)
                assert result.unpack()[0] == 1
                with open(sys.argv[2] + '.tmp', 'x') as out:
                    json.dump({key: os.environ.get(key) for key in (
                        'OMARCHY_PATH', 'OMAMOBILE_SESSION_HOST', 'OMAMOBILE_ACCESS_SHELL', 'OMAMOBILE_PROFILE', 'PATH',
                        'WAYLAND_DISPLAY', 'HYPRLAND_INSTANCE_SIGNATURE',
                        'DBUS_SESSION_BUS_ADDRESS', 'XDG_RUNTIME_DIR')}, out)
                os.replace(sys.argv[2] + '.tmp', sys.argv[2])
                loop = GLib.MainLoop()
                GLib.timeout_add(2000, loop.quit)
                loop.run()
                """;
        boolean serviceCreated = false;
        try {
            Files.createDirectories(service.getParent());
            Files.write(script, source.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.write(service, ("[D-BUS Service]\nName=" + id + "\nExec=/usr/bin/python3 "
                    + guestTemporary + "/probe.py " + id + " " + guestTemporary + "/environment.json\n")
                    .getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
            serviceCreated = true;
            String runtime = "/run/omamobile/" + (profile.equals("phone") ? "p" : "d");
            String reload = runGuest(context, profile, properties, List.of("/usr/bin/gdbus", "call", "--session",
                    "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus",
                    "--method", "org.freedesktop.DBus.ReloadConfig"));
            assertEquals(profile + " D-Bus ReloadConfig failed", "()", reload.trim());
            String start = runGuest(context, profile, properties, List.of("/usr/bin/gdbus", "call", "--session",
                    "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus",
                    "--method", "org.freedesktop.DBus.StartServiceByName", id, "0"));
            assertEquals(profile + " D-Bus activation did not start a new service", "(uint32 1,)", start.trim());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(output) && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(profile + " activated service did not write environment", Files.isRegularFile(output));
            var env = new org.json.JSONObject(new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
            assertEquals(profile + " runtime", runtime, env.optString("XDG_RUNTIME_DIR", null));
            assertEquals(properties.getProperty("WAYLAND_DISPLAY"), env.optString("WAYLAND_DISPLAY", null));
            assertEquals(properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE"), env.optString("HYPRLAND_INSTANCE_SIGNATURE", null));
            assertEquals(profile, env.optString("OMAMOBILE_PROFILE", null));
            assertEquals("android", env.optString("OMAMOBILE_SESSION_HOST", null));
            assertEquals("/root/.local/share/omamobile/omamobile-access-shell",
                    env.optString("OMAMOBILE_ACCESS_SHELL", null));
            assertEquals("/root/.local/share/omarchy", env.optString("OMARCHY_PATH", null));
            String busAddress = env.optString("DBUS_SESSION_BUS_ADDRESS", "");
            assertTrue(profile + " activated service used another bus: " + busAddress,
                    busAddress.matches(java.util.regex.Pattern.quote("unix:path=" + runtime + "/bus")
                            + "(?:,guid=[0-9a-f]{32})?"));
            assertTrue(List.of(env.getString("PATH").split(":")).contains("/usr/lib/omamobile/session-bin"));
        } finally {
            if (serviceCreated) Files.deleteIfExists(service);
            GuestPaths.deleteTree(temporary);
        }
    }

    private static String runGuest(Context context, String profile, Properties properties, List<String> guest) throws Exception {
        Path files = context.getFilesDir().toPath();
        String runtime = "/run/omamobile/" + (profile.equals("phone") ? "p" : "d");
        var argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(),
                files.resolve("linux/rootfs"), List.of(new RuntimeSpec.Bind("/dev", "/dev", false),
                        new RuntimeSpec.Bind("/proc", "/proc", false)),
                guest);
        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(GuestPaths.resolve(files.resolve("linux/rootfs"), "/root").toFile())
                .redirectErrorStream(true);
        builder.environment().clear(); builder.environment().putAll(RuntimeSpec.environment(false));
        builder.environment().put("XDG_RUNTIME_DIR", runtime);
        builder.environment().put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + runtime + "/bus");
        builder.environment().put("WAYLAND_DISPLAY", properties.getProperty("WAYLAND_DISPLAY"));
        builder.environment().put("HYPRLAND_INSTANCE_SIGNATURE", properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
        final String command = guest.toString();
        final int maxOutput = 64 * 1024;
        Path capture = Files.createTempFile(files, ".guest-ipc-", ".log");
        Process process;
        try {
            builder.redirectOutput(capture.toFile());
            process = builder.start();
            try { process.getOutputStream().close(); }
            catch (IOException error) { process.destroyForcibly(); throw error; }
        } catch (IOException error) {
            Files.deleteIfExists(capture);
            throw new IOException("Guest command could not start: profile=" + profile + " command=" + command
                    + diagnostics(context, profile, properties), error);
        }
        boolean completed = false;
        try {
            completed = process.waitFor(5, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                throw new IllegalStateException("Guest command timed out after 5s: profile=" + profile
                        + " command=" + command + " outputTail=" + readTail(capture, maxOutput)
                        + diagnostics(context, profile, properties));
            }
            int exit = process.exitValue();
            if (exit != 0) throw new IllegalStateException("Guest command exited " + exit + ": profile="
                    + profile + " command=" + command + " outputTail=" + readTail(capture, maxOutput)
                    + diagnostics(context, profile, properties));
            long size = Files.size(capture);
            if (size > maxOutput) throw new IllegalStateException("Guest command output exceeded " + maxOutput
                    + " bytes: profile=" + profile + " command=" + command + " outputTail=" + readTail(capture, maxOutput)
                    + diagnostics(context, profile, properties));
            return new String(Files.readAllBytes(capture), StandardCharsets.UTF_8);
        } finally {
            if (!completed && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(capture);
        }
    }

    private static String readTail(Path path, int limit) throws IOException {
        if (!Files.exists(path)) return "<no capture>";
        long size = Files.size(path);
        int wanted = (int) Math.min(size, limit);
        try (var input = Files.newInputStream(path)) {
            long skip = size - wanted;
            while (skip > 0) {
                long skipped = input.skip(skip);
                if (skipped <= 0) break;
                skip -= skipped;
            }
            byte[] bytes = new byte[wanted];
            int offset = 0;
            while (offset < wanted) {
                int count = input.read(bytes, offset, wanted - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != wanted) bytes = java.util.Arrays.copyOf(bytes, offset);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String diagnostics(Context context, String profile, Properties properties) {
        Path home = context.getFilesDir().toPath().resolve("graphics/" + profile);
        StringBuilder value = new StringBuilder("\nGuest context: profile=").append(profile)
                .append(" requestedDisplay=").append(properties.getProperty("ANDROID_DISPLAY_ID"))
                .append(" requestedWayland=").append(properties.getProperty("WAYLAND_DISPLAY"))
                .append(" requestedSignature=").append(properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
        for (String name : List.of("ready.properties", "linux-shell.log", "dbus.log", "hyprland-native.log"))
            value.append("\n").append(name).append(" tail:\n").append(safeTail(home.resolve(name), 4000));
        return value.toString();
    }

    private static String safeTail(Path path, int limit) {
        try { return readTail(path, limit); }
        catch (IOException error) { return "<unavailable: " + error.getMessage() + ">"; }
    }

    private static final String RUNTIME_SYMLINK_PROBE = """
            import os, pathlib, socket, tempfile
            with tempfile.TemporaryDirectory(prefix='.omamobile-probe-', dir=os.environ['XDG_RUNTIME_DIR']) as directory:
                root = pathlib.Path(directory)
                by_id = root / 'by-id' / 'fixture'
                by_pid = root / 'by-pid'
                by_id.mkdir(parents=True)
                by_pid.mkdir()
                lock = by_id / 'instance.lock'
                lock.write_bytes(b'fixture')
                absolute = by_pid / 'absolute'
                relative = by_pid / 'relative'
                os.symlink(str(by_id), absolute)
                os.symlink('../by-id/fixture', relative)
                assert (absolute / 'instance.lock').read_bytes() == b'fixture'
                assert (relative / 'instance.lock').read_bytes() == b'fixture'
                assert (absolute / 'instance.lock').stat().st_ino == lock.stat().st_ino
                assert os.readlink(absolute) == str(by_id)
                parent = os.open(by_pid, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    fd = os.open('absolute/instance.lock', os.O_RDONLY, dir_fd=parent)
                    try: assert os.fstat(fd).st_size == 7
                    finally: os.close(fd)
                finally: os.close(parent)
                (absolute / 'created').write_bytes(b'new')
                assert (relative / 'created').read_bytes() == b'new'
                (absolute / 'created').unlink()
                assert not (by_id / 'created').exists()
            for name in ('.socket.sock', '.socket2.sock'):
                endpoint = pathlib.Path(os.environ['XDG_RUNTIME_DIR']) / 'hypr' / os.environ['HYPRLAND_INSTANCE_SIGNATURE'] / name
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                    connection.settimeout(2)
                    connection.connect(str(endpoint))
            print('ok', end='')
            """;

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
