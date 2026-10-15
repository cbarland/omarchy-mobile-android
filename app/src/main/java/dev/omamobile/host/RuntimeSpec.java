package dev.omamobile.host;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Explicit argv, no host shell interpolation. Every display refers to this same rootfs. */
final class RuntimeSpec {
    record Bind(String host, String guest, boolean readOnly) {
        Bind {
            if (!host.startsWith("/") || !guest.startsWith("/") || host.contains(":")
                    || guest.contains(":") || host.indexOf('\0') >= 0 || guest.indexOf('\0') >= 0
                    || guest.equals("/") || guest.endsWith("/") || guest.contains("//")
                    || java.util.Arrays.stream(guest.substring(1).split("/")).anyMatch(part -> part.equals(".") || part.equals(".."))) {
                throw new IllegalArgumentException("Bind paths must be absolute and unambiguous");
            }
        }
        String argument() { return host + ":" + guest + (readOnly ? ":ro" : ""); }
    }

    static List<String> command(Path binary, Path root, List<Bind> binds, List<String> guestCommand) {
        if (guestCommand.isEmpty() || !guestCommand.get(0).startsWith("/")) throw new IllegalArgumentException("Absolute guest executable required");
        List<String> result = new ArrayList<>(List.of(binary.toString(), "-s", "-r", root.toString()));
        for (Bind bind : binds) { result.add("-b"); result.add(bind.argument()); }
        result.add("--");
        result.addAll(guestCommand);
        for (String arg : result) if (arg.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in argument");
        return result;
    }

    static Map<String, String> environment(boolean ando) {
        var env = new java.util.LinkedHashMap<String, String>();
        env.put("HOME", "/root");
        env.put("USER", "root");
        env.put("LOGNAME", "root");
        env.put("PATH", "/opt/omamobile/bin:/usr/local/sbin:/usr/local/bin:/usr/bin:/bin");
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "dumb"); // Pipe console, not a PTY.
        env.put("TMPDIR", "/tmp");
        if (ando) env.put("OMALAUNCHER_ANDO_SOCKET", "/run/omamobile-ando/ando.sock");
        return env;
    }

    /** Shared by the initial shell and every client launched by the compositor. */
    static Map<String, String> graphicalEnvironment(String profile, String runtime, String timeZone) {
        var env = new java.util.LinkedHashMap<>(environment(true));
        env.put("PATH", "/usr/lib/omamobile/session-bin:/root/.local/share/omarchy/bin:" + env.get("PATH"));
        env.put("TZ", timeZone);
        env.put("GSETTINGS_BACKEND", "keyfile");
        env.put("XDG_RUNTIME_DIR", runtime);
        env.put("XDG_SESSION_TYPE", "wayland");
        env.put("XDG_CURRENT_DESKTOP", "Hyprland");
        env.put("QT_QPA_PLATFORM", "wayland");
        env.put("QT_QUICK_BACKEND", "software");
        env.put("QSG_RENDER_LOOP", "basic");
        env.put("LIBGL_ALWAYS_SOFTWARE", "1");
        env.put("LP_NUM_THREADS", "2");
        env.put("OMARCHY_PATH", "/root/.local/share/omarchy");
        env.put("OMAMOBILE_PROFILE", profile);
        env.put("OMAMOBILE_SESSION_HOST", "android");
        env.put("NO_AT_BRIDGE", "1");
        env.put("DBUS_SESSION_BUS_ADDRESS", "unix:path=" + runtime + "/bus");
        return env;
    }
}
