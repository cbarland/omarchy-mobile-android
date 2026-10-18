package dev.omamobile.host;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One display session, referencing the application's one Linux installation. */
final class GraphicalSession {
    final String profile;
    final int androidDisplayId;
    final Path root, home, runtime, config, wrapper, magic, xkb;
    private final Context context;
    private final Path files, androidBin;
    private final AndroidBroker androidBroker;
    private final ProfileMonitorConfig.Result monitorConfig;
    private Process clients;
    private boolean closed;
    private final List<Process> launched = new ArrayList<>();

    GraphicalSession(Context context, boolean phone, int androidDisplayId) throws IOException {
        this(context, phone ? "phone" : "desktop", androidDisplayId);
    }

    GraphicalSession(Context context, String profile, int androidDisplayId) throws IOException {
        if (!profile.equals("phone") && !profile.equals("desktop") && !profile.equals("unified"))
            throw new IllegalArgumentException("Unknown graphical profile: " + profile);
        this.context = context.getApplicationContext();
        this.androidDisplayId = androidDisplayId;
        this.profile = profile;
        boolean phone = profile.equals("phone");
        boolean unified = profile.equals("unified");
        files = context.getFilesDir().toPath();
        root = files.resolve("linux/rootfs");
        if (!Files.isDirectory(root)) throw new IOException("Import and prepare the shared Linux installation first");
        xkb = GuestPaths.resolve(root, "/usr/share/X11/xkb");
        if (!Files.isRegularFile(xkb.resolve("rules/evdev"))) throw new IOException("Install Linux xkeyboard-config before starting Hyprland");
        if (!Files.isRegularFile(GuestPaths.resolve(root, "/root/.local/share/omamobile/start-shell")))
            throw new IOException("Prepare the upstream Omarchy Linux shell profiles first (see docs/GRAPHICS.md)");
        home = Files.createDirectories(files.resolve("graphics/" + profile));
        // Keep Unix-domain IPC addresses beneath sockaddr_un's 108-byte limit.
        runtime = SessionRuntime.prepare(files, root, profile);
        androidBin = SessionRuntime.prepareAdapterBin(runtime);
        // Each display owns a separate lifetime pipe/socket. A phone teardown
        // cannot revoke the desktop's Android commands (or the batch console's).
        androidBroker = new AndroidBroker(new File(context.getApplicationInfo().nativeLibraryDir, "libando.so"),
                androidBin.getParent().toFile());
        HostRuntime.chmod(androidBroker.directory.toPath(), 0700);
        // tawcroot binds directories, not individual files. Use the same
        // app-owned staging pattern as HostRuntime, scoped to this display.
        Files.copy(new File(context.getApplicationInfo().nativeLibraryDir, "libando.so").toPath(),
                androidBin.resolve("ando"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        HostRuntime.chmod(androidBin.resolve("ando"), 0755);
        for (String asset : List.of("hyprland-dialog", "dialog.qml")) {
            try (var in = context.getAssets().open("runtime-dialog/" + asset)) {
                Files.copy(in, androidBin.resolve(asset), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            HostRuntime.chmod(androidBin.resolve(asset), asset.equals("hyprland-dialog") ? 0755 : 0644);
        }
        Files.createDirectories(GuestPaths.resolve(root, "/opt/omamobile/bin"));
        config = home.resolve("hyprland.lua");
        wrapper = home.resolve("linux-exec.sh");
        magic = files.resolve("graphics/magic.mgc");
        if (!Files.exists(magic)) {
            // Both output processes can start together. Publish a complete copy
            // atomically so neither reads a partially written shared database.
            Path temporary = Files.createTempFile(magic.getParent(), ".magic-", ".mgc");
            try {
                try (var in = context.getAssets().open("native/magic.mgc")) {
                    Files.copy(in, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, magic, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        }
        if (!Files.exists(config, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            String lua = unified ? unifiedSeedConfig() : previousSeedConfig(phone) + DISPLAY_SHORTCUT;
            Files.write(config, lua.getBytes(StandardCharsets.UTF_8));
        } else if (!unified && !Files.isSymbolicLink(config)) {
            // Migrate only the exact settings emitted by the initial developer
            // build; retain the rest of an existing, editable Hyprland config.
            String original = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
            String migrated = original
                    .replace("disable_splash_rendering = true, disable_scale_checks = true",
                            "disable_splash_rendering = true, disable_watchdog_warning = true")
                    .replace("debug = { disable_logs = false }",
                            "debug = { disable_logs = false, disable_scale_checks = true }");
            if (!phone && !migrated.contains("no_hardware_cursors"))
                migrated += "\nhl.config({ cursor = { no_hardware_cursors = 1 } })\n";
            migrated = migrateDisplayShortcut(migrated, phone);
            // Only recognize whole generated configs; a similar fragment in a
            // custom file is not permission to migrate that user's settings.
            if (generatedSeeds(phone).contains(migrated) && !migrated.equals(original))
                Files.write(config, migrated.getBytes(StandardCharsets.UTF_8));
        }
        monitorConfig = unified ? null
                : ProfileMonitorConfig.prepare(root, config, profile, generatedSeeds(phone), phone ? 3 : 1);
        String displayReport = profile + " display config: "
                + (monitorConfig != null ? monitorConfig.report() : "unified output seed; monitor preparation deferred");
        Files.write(home.resolve("linux-shell.log"), (displayReport + "\n").getBytes(StandardCharsets.UTF_8));
        android.util.Log.i("OmamobileHyprland", displayReport);
        String fontConfig = "<?xml version=\"1.0\"?><!DOCTYPE fontconfig SYSTEM \"urn:fontconfig:fonts.dtd\"><fontconfig>"
                + "<dir>/system/fonts</dir><dir>" + root + "/usr/share/fonts</dir><cachedir>" + home + "/fontcache</cachedir></fontconfig>";
        Files.write(home.resolve("fonts.conf"), fontConfig.getBytes(StandardCharsets.UTF_8));
        Files.write(wrapper, wrapperText().getBytes(StandardCharsets.UTF_8));
        // Start before JNI mutates this process's environment for Hyprland.
        // The broker inherits Android's real environment and app UID. Make this
        // the last constructor operation so startup failure cannot orphan it.
        androidBroker.start();
    }

    private List<RuntimeSpec.Bind> binds() throws IOException {
        var binds = new ArrayList<RuntimeSpec.Bind>();
        binds.add(new RuntimeSpec.Bind("/dev", "/dev", false));
        binds.add(new RuntimeSpec.Bind("/proc", "/proc", false));
        binds.add(new RuntimeSpec.Bind("/sys", "/sys", true));
        binds.add(new RuntimeSpec.Bind(androidBroker.directory.toString(), "/run/omamobile-ando", false));
        // Only the app-owned staging directory is mounted; the shared Linux
        // installation and user executables are never overwritten.
        binds.add(new RuntimeSpec.Bind(androidBin.toString(), "/opt/omamobile/bin", true));
        if (monitorConfig != null && monitorConfig.enabled())
            binds.add(new RuntimeSpec.Bind(monitorConfig.directory().toString(), "/root/.config/hypr", false));
        File external = context.getExternalFilesDir(null);
        Path shared = (external != null ? external.toPath() : files.resolve("shared")).resolve("Shared");
        Files.createDirectories(shared);
        binds.add(new RuntimeSpec.Bind(shared.toString(), "/android/shared", false));
        return binds;
    }

    // Stock default/hypr/bindings/utilities.lua names the panel omarchy.monitor.
    private static final String DISPLAY_SHORTCUT = "hl.bind(\"SUPER + CTRL + D\", hl.dsp.exec_cmd(\"omarchy-shell shell toggle omarchy.monitor\"))\n";
    private static final String DESKTOP_CURSOR = "hl.config({ cursor = { no_hardware_cursors = 1 } })\n";

    static String previousSeedConfig(boolean phone) {
        return "hl.monitor({ output = \"\", mode = \"preferred\", position = \"auto\", scale = " + (phone ? "3" : "1") + " })\n"
                + "hl.config({ general = { gaps_in = 5, gaps_out = 5, border_size = 2 }, "
                + "decoration = { rounding = 8, blur = { enabled = false } }, animations = { enabled = false }, "
                + "misc = { disable_hyprland_logo = true, disable_splash_rendering = true, disable_watchdog_warning = true }, "
                + "debug = { disable_logs = false, disable_scale_checks = true } })\n"
                + (phone ? "" : DESKTOP_CURSOR)
                + "hl.bind(\"SUPER + RETURN\", hl.dsp.exec_cmd(\"foot\"))\n"
                + "hl.bind(\"SUPER + W\", hl.dsp.window.close())\n"
                + "hl.bind(\"SUPER + SPACE\", hl.dsp.exec_cmd(\"omarchy-shell shell toggle omarchy.menu\"))\n";
    }

    static String unifiedSeedConfig() {
        String desktop = previousSeedConfig(false);
        return "hl.monitor({ output = \"ANDROID-1\", mode = \"preferred\", position = \"0x0\", scale = 3 })\n"
                + "hl.monitor({ output = \"ANDROID-2\", mode = \"preferred\", position = \"auto\", scale = 1 })\n"
                + desktop.substring(desktop.indexOf('\n') + 1) + DISPLAY_SHORTCUT;
    }

    static String migrateDisplayShortcut(String config, boolean phone) {
        String seed = previousSeedConfig(phone);
        // A previous seed migration appended the cursor setting at the end.
        String migratedDesktopSeed = seed.replace(DESKTOP_CURSOR, "") + "\n" + DESKTOP_CURSOR;
        return config.equals(seed) || (!phone && config.equals(migratedDesktopSeed)) ? config + DISPLAY_SHORTCUT : config;
    }

    private static List<String> generatedSeeds(boolean phone) {
        String seed = previousSeedConfig(phone);
        if (phone) return List.of(seed + DISPLAY_SHORTCUT);
        return List.of(seed + DISPLAY_SHORTCUT, seed.replace(DESKTOP_CURSOR, "") + "\n" + DESKTOP_CURSOR + DISPLAY_SHORTCUT);
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private String wrapperText() throws IOException {
        StringBuilder shell = new StringBuilder("#!/system/bin/sh\ncd ").append(quote(GuestPaths.resolve(root, "/root").toString()))
                .append(" || exit 1\n").append(wrapperArguments()).append("exec ");
        String binary = new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toString();
        shell.append(quote(binary)).append(" -s -r ").append(quote(root.toString()));
        for (RuntimeSpec.Bind bind : binds()) shell.append(" -b ").append(quote(bind.argument()));
        shell.append(" -- /usr/bin/env -i ");
        for (var entry : environment().entrySet()) shell.append(quote(entry.getKey() + "=" + entry.getValue())).append(' ');
        // Native posix_spawn supplies these for this launch; do not bake in a
        // display socket or drop Hyprland's workspace/window-rule tokens.
        shell.append("WAYLAND_DISPLAY=\"$WAYLAND_DISPLAY\" ")
                .append("HYPRLAND_INSTANCE_SIGNATURE=\"$HYPRLAND_INSTANCE_SIGNATURE\" ")
                .append("HL_INITIAL_WORKSPACE_TOKEN=\"$HL_INITIAL_WORKSPACE_TOKEN\" HL_EXEC_RULE_TOKEN=\"$HL_EXEC_RULE_TOKEN\" ")
                .append("\"$@\"\n");
        return shell.toString();
    }

    static String wrapperArguments() {
        return "if [ \"$1\" = --argv ]; then\n  shift\n  [ \"$#\" -gt 0 ] || exit 64\nelse\n  set -- /bin/bash -c \"$1\"\nfi\n";
    }

    String guestRuntime() {
        return "/run/omamobile/" + switch (profile) {
            case "phone" -> "p";
            case "desktop" -> "d";
            case "unified" -> "u";
            default -> throw new IllegalStateException("Unknown graphical profile: " + profile);
        };
    }
    private java.util.Map<String, String> environment() {
        return RuntimeSpec.graphicalEnvironment(profile.equals("unified") ? "desktop" : profile,
                guestRuntime(), java.util.TimeZone.getDefault().getID());
    }

    synchronized void startClients(String display, String signature) throws IOException {
        if (closed) throw new IOException("The graphical session is stopping");
        if (clients != null && clients.isAlive()) return;
        try {
            launchClients(display, signature);
        } catch (IOException | RuntimeException error) {
            stop();
            throw error;
        }
    }

    private void launchClients(String display, String signature) throws IOException {
        // A login shell may reset PATH in /etc/profile; restore the session's
        // bound client after profile loading, without editing user dotfiles.
        String shellProfile = profile.equals("unified") ? "desktop" : profile;
        String script = "export PATH=/opt/omamobile/bin:$PATH; exec /root/.local/share/omamobile/start-shell " + shellProfile;
        var sessionEnv = environment();
        sessionEnv.put("WAYLAND_DISPLAY", display);
        sessionEnv.put("HYPRLAND_INSTANCE_SIGNATURE", signature);
        var busArgs = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(),
                root, binds(), List.of("/usr/bin/dbus-daemon", "--session", "--nofork", "--nopidfile", "--address=unix:path=" + guestRuntime() + "/bus"));
        ProcessBuilder bus = new ProcessBuilder(busArgs).directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(home.resolve("dbus.log").toFile());
        bus.environment().clear(); bus.environment().putAll(sessionEnv);
        Process busProcess = bus.start();
        busProcess.getOutputStream().close();
        launched.add(busProcess);
        for (int i = 0; i < 100 && !Files.exists(runtime.resolve("bus")); i++) {
            if (!busProcess.isAlive()) throw new IOException("Linux session bus failed; see " + home.resolve("dbus.log"));
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
        if (!Files.exists(runtime.resolve("bus"))) throw new IOException("Linux session bus timed out; see " + home.resolve("dbus.log"));
        List<String> argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(),
                root, binds(), List.of("/bin/bash", "-lc", script));
        ProcessBuilder builder = new ProcessBuilder(argv).directory(GuestPaths.resolve(root, "/root").toFile())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("linux-shell.log").toFile()));
        builder.environment().clear();
        builder.environment().putAll(sessionEnv);
        clients = builder.start();
        clients.getOutputStream().close();
        launched.add(clients);
        Files.write(home.resolve("ready.properties"), ("WAYLAND_DISPLAY=" + display + "\nHYPRLAND_INSTANCE_SIGNATURE=" + signature
                + "\nROOTFS=" + root + "\nANDROID_DISPLAY_ID=" + androidDisplayId + "\n").getBytes(StandardCharsets.UTF_8));
        android.util.Log.i("OmamobileHyprland", profile + " clients started; shared rootfs " + root);
    }

    private void requestStop() {
        closed = true;
        androidBroker.requestStop();
        for (Process process : launched) process.destroy();
        launched.clear();
        clients = null;
    }

    synchronized void stop() {
        requestStop();
        androidBroker.close();
    }

    synchronized void stopForUser() {
        Process phoneShell = profile.equals("phone") && clients != null && clients.isAlive() ? clients : null;
        requestStop();
        Path temporary = null;
        try {
            if (phoneShell == null) return;
            if (!phoneShell.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                phoneShell.destroyForcibly();
                if (!phoneShell.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) return;
            }
            // Mobile treats a leftover startup marker as a shell crash. This
            // live shell was explicitly stopped by its Android owner. Expire
            // only its in-progress markers after termination; preserve every
            // recorded crash. Unplanned exits/Android force-stop use stop().
            Path guard = GuestPaths.resolve(root, "/root/.config/omamobile/widget-guard.json");
            if (!Files.isRegularFile(guard)) return;
            var state = new org.json.JSONObject(new String(Files.readAllBytes(guard), StandardCharsets.UTF_8));
            var loading = state.optJSONObject("loading");
            if (loading == null || loading.length() == 0) return;
            state.put("loading", new org.json.JSONObject());
            temporary = Files.createTempFile(guard.getParent(), ".widget-stop-", ".json");
            Files.write(temporary, (state.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, guard, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | org.json.JSONException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            android.util.Log.w("OmamobileHyprland", "Cannot finish planned widget shutdown", error);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            // The controller has a six-second host deadline. A slow broker
            // must not spend that budget before the stopped phone's markers
            // are cleared. Keep the session lock across both phases so the
            // client's awaitClients() cleanup cannot race this planned stop.
            androidBroker.close();
        }
    }

    int awaitClients() throws InterruptedException {
        Process process;
        synchronized (this) { process = clients; }
        try {
            return process != null ? process.waitFor() : -1;
        } finally {
            // A failed shell/owner thread must not leave an Android broker or
            // session bus serving an otherwise dead graphical session.
            stop();
        }
    }
}
