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
        ProcessBuilder builder = new ProcessBuilder(argv).directory(files.toFile()).redirectErrorStream(true);
        builder.environment().clear(); builder.environment().putAll(RuntimeSpec.environment(false));
        builder.environment().put("XDG_RUNTIME_DIR", runtime);
        builder.environment().put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + runtime + "/bus");
        builder.environment().put("WAYLAND_DISPLAY", properties.getProperty("WAYLAND_DISPLAY"));
        builder.environment().put("HYPRLAND_INSTANCE_SIGNATURE", properties.getProperty("HYPRLAND_INSTANCE_SIGNATURE"));
        Process process = builder.start(); process.getOutputStream().close();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return "Hyprland IPC timed out"; }
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
