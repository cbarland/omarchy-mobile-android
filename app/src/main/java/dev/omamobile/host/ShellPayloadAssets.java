package dev.omamobile.host;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Authenticates and stages an APK payload before touching installed stock files. */
final class ShellPayloadAssets {
    private ShellPayloadAssets() { }
    private static Map<String, ShellPayloadUpdate.Member> members(byte[] encoded, boolean requireModes) throws Exception {
        if (encoded.length == 0 || encoded.length > 1024 * 1024) throw new IOException("Invalid payload ownership manifest size");
        JSONObject manifest = new JSONObject(new String(encoded, StandardCharsets.UTF_8));
        if (manifest.getInt("format") != 1) throw new IOException("Unsupported payload ownership manifest");
        var entries = manifest.getJSONArray("payloadMembers");
        if (entries.length() > 100000) throw new IOException("Too many payload ownership entries");
        Map<String, ShellPayloadUpdate.Member> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject value = entries.getJSONObject(i);
            int mode = value.optInt("mode", -1);
            if (requireModes && mode < 0) throw new IOException("APK payload lacks file modes; rebuild its installer assets");
            var member = new ShellPayloadUpdate.Member(value.getString("sha256"), value.getLong("bytes"), mode);
            if (result.put(value.getString("path"), member) != null) throw new IOException("Duplicate payload ownership entry");
        }
        return result;
    }
    static int update(Context context, RootfsImporter importer, Consumer<String> log) throws Exception {
        Path anchor = context.getFilesDir().toPath().toRealPath();
        Path linux = anchor.resolve("linux"), root = linux.resolve("rootfs");
        GuestPaths.noSymlinkParents(anchor, root.resolve(".update-path-check"));
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Linux is not installed");
        ShellPayloadUpdate.Filesystem fs = new ShellPayloadUpdate.Filesystem() {
            @Override public int mode(Path path) throws IOException {
                try { return android.system.Os.lstat(path.toString()).st_mode & 07777; }
                catch (android.system.ErrnoException error) { throw new IOException(error); }
            }
            @Override public void mode(Path path, int mode) throws IOException { HostRuntime.chmod(path, mode); }
            @Override public void replace(Path from, Path to) throws IOException {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        };
        try (InstallationLease exclusive = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
            ShellPayloadUpdate.recover(root, fs);
            Path installed = root.resolve(ShellPayloadUpdate.MANIFEST);
            GuestPaths.noSymlinkParents(root, installed);
            if (!Files.isRegularFile(installed, LinkOption.NOFOLLOW_LINKS) || Files.size(installed) > 1024 * 1024)
                throw new IOException("Installed payload ownership manifest is missing; keeping Linux unchanged");
            Map<String, ShellPayloadUpdate.Member> before = members(Files.readAllBytes(installed), false);
            Path stage = Files.createTempDirectory(linux, "payload-assets-");
            try {
                log.accept("Verifying the update's integration files…");
                InstallAssets.stage(context, stage);
                byte[] encoded = Files.readAllBytes(stage.resolve("install-manifest.json"));
                Map<String, ShellPayloadUpdate.Member> next = members(encoded, true);
                Path expanded = Files.createDirectory(stage.resolve("root"));
                try (var input = Files.newInputStream(stage.resolve("linux-shell.tgz"))) {
                    importer.overlay(input, expanded, log::accept);
                }
                // These become user configuration after their first seed; never replace an existing entry.
                Map<String, String> seeds = new LinkedHashMap<>();
                for (String profile : java.util.List.of("phone", "desktop", "unified"))
                    seeds.put("root/.local/share/omamobile/profiles/" + profile + ".json",
                            "usr/share/omamobile/android/" + profile + ".json");
                seeds.put("root/.config/omamobile/home.json", "usr/share/omamobile/android/home.json");
                for (var seed : seeds.entrySet()) {
                    Path live = root.resolve(seed.getKey());
                    GuestPaths.noSymlinkParents(root, live);
                    if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) continue;
                    var member = next.get(seed.getValue());
                    if (member == null) throw new IOException("Missing default profile in APK payload");
                    Path copy = expanded.resolve(seed.getKey()); Files.createDirectories(copy.getParent());
                    Path source = expanded.resolve(seed.getValue()); GuestPaths.noSymlinkParents(expanded, source);
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid default profile in APK payload");
                    Files.copy(source, copy); fs.mode(copy, member.mode());
                    next.put(seed.getKey(), member);
                }
                int changed = ShellPayloadUpdate.apply(root, expanded, before, next, encoded, fs);
                log.accept("Updated " + changed + " integration files. Your Linux data and existing profiles were preserved.");
                return changed;
            } finally { GuestPaths.deleteTree(stage); }
        }
    }
}
