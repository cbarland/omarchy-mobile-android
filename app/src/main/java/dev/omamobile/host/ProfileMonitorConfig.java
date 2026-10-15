package dev.omamobile.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Narrow per-output persistence adapter for untouched host-generated configs. */
final class ProfileMonitorConfig {
    record Result(Path directory, String report) {
        boolean enabled() { return directory != null; }
    }

    static Result prepare(Path root, Path nativeConfig, String profile, List<String> generatedSeeds, int scale) throws IOException {
        if (!profile.equals("phone") && !profile.equals("desktop")) throw new IllegalArgumentException("Unknown display profile");
        Path directory = root.resolve("root/.local/share/omamobile/profiles/" + profile + "/hypr");
        Path monitor = directory.resolve("monitors.lua");
        if (Files.isSymbolicLink(nativeConfig)) return new Result(null, "runtime-only: custom native config symlink preserved");
        String config = new String(Files.readAllBytes(nativeConfig), StandardCharsets.UTF_8);
        String matchedSeed = null;
        String managedConfig = null;
        for (String seed : generatedSeeds) {
            String managed = managedConfig(seed, monitor);
            if (config.equals(seed) || config.equals(managed)) {
                matchedSeed = seed;
                managedConfig = managed;
                break;
            }
        }
        if (matchedSeed == null) return new Result(null, "runtime-only: custom native Hyprland config preserved");

        // Inspect the final component without following it: even an empty
        // user-selected symlink must not be hidden by a session bind.
        Path shared = root.resolve("root/.config/hypr");
        String blocked = null;
        if (hasCustomAncestor(root, shared)) blocked = "custom shared config directory path preserved";
        if (Files.exists(shared, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(shared) || !Files.isDirectory(shared, LinkOption.NOFOLLOW_LINKS)) {
                blocked = "custom shared Hyprland path preserved";
            } else {
                try (var entries = Files.newDirectoryStream(shared)) {
                    if (entries.iterator().hasNext()) blocked = "existing shared Hyprland files preserved";
                }
            }
        }
        if (hasCustomAncestor(root, monitor)) blocked = "custom profile directory path preserved";
        if (Files.exists(monitor, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(monitor, LinkOption.NOFOLLOW_LINKS))
            blocked = "custom profile monitor path preserved";
        if (blocked != null) {
            // Revert only our byte-exact managed native config if the user has
            // since populated the shared directory. Never change their files.
            if (config.equals(managedConfig)) atomicWrite(nativeConfig, matchedSeed);
            return new Result(null, "runtime-only: " + blocked);
        }

        Files.createDirectories(directory);
        if (!Files.exists(monitor, LinkOption.NOFOLLOW_LINKS)) {
            // This is the generic form recognized by the unchanged upstream
            // scaling helper. Its sed -i writes stay inside this bound directory.
            // Scope is Wayland output scale; GTK text scaling remains separate.
            String text = "-- Per-profile Wayland output scale; GTK text scale is separate.\n"
                    + "local omarchy_monitor_scale = " + scale + "\n"
                    + "hl.monitor({ output = \"\", mode = \"preferred\", position = \"auto\", scale = omarchy_monitor_scale })\n";
            Files.write(monitor, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        }
        if (!config.equals(managedConfig)) atomicWrite(nativeConfig, managedConfig);
        return new Result(directory, "per-profile Wayland scale persists in " + monitor + "; GTK text scaling is separate");
    }

    private static boolean hasCustomAncestor(Path root, Path path) {
        for (Path parent = path.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent()) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return true;
        }
        return false;
    }

    private static String managedConfig(String seed, Path monitor) {
        int firstLine = seed.indexOf('\n');
        if (firstLine < 0 || !seed.startsWith("hl.monitor(")) throw new IllegalArgumentException("Expected generated monitor seed");
        String path = monitor.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        // Hyprland tracks explicit require() paths for reload, including the
        // atomic rename performed by upstream sed -i. JNI sees the real path.
        return "require(\"" + path + "\")\n" + seed.substring(firstLine + 1);
    }

    private static void atomicWrite(Path path, String text) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".display-config-", ".lua");
        try {
            Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
