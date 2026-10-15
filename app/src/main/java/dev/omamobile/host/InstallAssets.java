package dev.omamobile.host;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Set;
import org.json.JSONObject;

/** Pinned UI sources and setup scripts authenticated by the installed APK. */
final class InstallAssets {
    private static final Set<String> FILES = Set.of("linux-shell.tgz", "prepare-arch-graphics", "configure-omarchy-repository");

    static boolean available(Context context) {
        try (var in = context.getAssets().open("install/install-manifest.json")) { return in.read() != -1; }
        catch (IOException unavailable) { return false; }
    }

    static void stage(Context context, Path destination) throws Exception {
        JSONObject manifest;
        try (var in = context.getAssets().open("install/install-manifest.json")) {
            var bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
                if (bytes.size() > 1024 * 1024) throw new IOException("Installer manifest too large");
            }
            manifest = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }
        if (manifest.getInt("format") != 1) throw new IOException("Unsupported installer payload");
        var entries = manifest.getJSONArray("files");
        var remaining = new java.util.HashSet<>(FILES);
        for (int i = 0; i < entries.length(); i++) {
            var entry = entries.getJSONObject(i);
            String name = entry.getString("path");
            if (!remaining.remove(name)) throw new IOException("Unexpected installer file: " + name);
            long expectedSize = entry.getLong("bytes");
            if (expectedSize < 1 || expectedSize > 128L * 1024 * 1024) throw new IOException("Invalid installer file size");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var in = context.getAssets().open("install/" + name);
                    var out = Files.newOutputStream(destination.resolve(name))) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Installation cancelled");
                    size += count;
                    if (size > expectedSize) throw new IOException("Installer file exceeds manifest size");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
            }
            String hash = hex(digest.digest());
            if (size != expectedSize || !hash.equals(entry.getString("sha256")))
                throw new IOException("Installer file checksum mismatch: " + name);
        }
        if (!remaining.isEmpty()) throw new IOException("Incomplete installer payload");
        Files.write(destination.resolve("install-manifest.json"), (manifest.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
