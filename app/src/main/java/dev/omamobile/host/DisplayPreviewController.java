package dev.omamobile.host;

import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

/** One service owns one desktop monitor preview; its journal precedes the live write. */
final class DisplayPreviewController {
    static final long TIMEOUT_MS = 30_000L;
    static final int[] SCALES = {100, 125, 150, 200, 300};
    private static final int MAX_BYTES = 8192;
    private static final Pattern GENERATED = Pattern.compile(
            "\\Q-- Per-profile Wayland output scale; GTK text scale is separate.\\E\\n"
            + "local omarchy_monitor_scale = ([0-9]+(?:\\.[0-9]+)?)\\n"
            + "\\Qhl.monitor({ output = \"\", mode = \"preferred\", position = \"auto\", scale = omarchy_monitor_scale })\\E\\n");
    private static final Pattern UNIFIED_GENERATED = Pattern.compile(
            "\\Qhl.monitor({ output = \"ANDROID-1\", mode = \"preferred\", position = \"0x0\", scale = 3 })\\E\\n"
            + "\\Qhl.monitor({ output = \"ANDROID-2\", mode = \"preferred\", position = \"auto\", scale = \\E"
            + "([0-9]+(?:\\.[0-9]+)?)\\Q })\\E\\n");
    enum RevertResult { NONE, REVERTED, CHANGED }
    private record Pending(String profile, Path target, byte[] before, byte[] applied, long deadline) { }
    private final Path monitor, unifiedMonitor, state, boundary;
    private String profile;

    DisplayPreviewController(Context context) {
        this(context.getFilesDir().toPath().resolve("linux/rootfs/root/.local/share/omamobile/profiles/desktop/hypr/monitors.lua"),
                context.getFilesDir().toPath().resolve("display-preview"), "desktop",
                context.getFilesDir().toPath().resolve("linux/rootfs/root/.local/share/omamobile/profiles/unified/hypr/monitors.lua"));
    }
    DisplayPreviewController(Path monitor, Path stateDirectory) {
        this(monitor, stateDirectory, "desktop", null);
    }
    DisplayPreviewController(Path monitor, Path stateDirectory, String profile) {
        this(monitor, stateDirectory, profile, null);
    }
    private DisplayPreviewController(Path monitor, Path stateDirectory, String profile, Path unifiedMonitor) {
        if (!profile.equals("desktop") && !profile.equals("unified")) throw new IllegalArgumentException("Unknown display profile");
        this.monitor = monitor.toAbsolutePath().normalize();
        this.unifiedMonitor = (unifiedMonitor == null ? monitor : unifiedMonitor).toAbsolutePath().normalize();
        this.profile = profile;
        state = stateDirectory.toAbsolutePath().normalize().resolve("pending.properties");
        boundary = state.getParent().getParent();
    }
    void selectProfile(String selected) throws IOException {
        if (!selected.equals("desktop") && !selected.equals("unified")) throw new IOException("Unknown display profile");
        if (hasPending()) throw new IOException("Keep or revert the current preview first");
        profile = selected;
    }
    boolean hasPending() { return Files.exists(state, LinkOption.NOFOLLOW_LINKS); }
    long deadline() throws IOException { return pending().deadline(); }

    void preview(int scale, long now) throws IOException {
        if (Arrays.stream(SCALES).noneMatch(value -> value == scale)) throw new IOException("Unsupported desktop scale");
        if (hasPending()) throw new IOException("Keep or revert the current preview first");
        Path target = target();
        byte[] original = readRegular(target);
        String text = new String(original, StandardCharsets.UTF_8);
        var match = pattern().matcher(text);
        if (!match.matches()) throw new IOException("Custom desktop monitor config preserved");
        String selected = String.format(Locale.ROOT, "%.2f", scale / 100.0);
        if (Double.parseDouble(match.group(1)) == scale / 100.0) throw new IOException("Desktop already uses that scale");
        byte[] applied = (text.substring(0, match.start(1)) + selected + text.substring(match.end(1)))
                .getBytes(StandardCharsets.UTF_8);
        Pending preview = new Pending(profile, target, original, applied, Math.addExact(now, TIMEOUT_MS));
        writePending(preview);
        try {
            atomicWrite(target, applied);
        } catch (IOException failure) {
            // If the rename never happened, recovery clears the journal without changing the original.
            try { revert(); } catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }
    boolean keep(long now) throws IOException {
        if (!hasPending()) return false;
        Pending preview = pending();
        if (now >= preview.deadline()) { revert(); return false; }
        if (!Arrays.equals(readRegular(preview.target()), preview.applied()))
            throw new IOException("Desktop config changed during preview; revert preserves the newer edit");
        Files.delete(state);
        return true;
    }
    RevertResult revert() throws IOException {
        if (!hasPending()) return RevertResult.NONE;
        Pending preview = pending();
        byte[] current;
        try { current = readRegular(preview.target()); }
        catch (IOException missingOrCustom) {
            Files.delete(state);
            return RevertResult.CHANGED;
        }
        RevertResult result = RevertResult.NONE;
        if (Arrays.equals(current, preview.applied())) {
            atomicWrite(preview.target(), preview.before());
            result = RevertResult.REVERTED;
        } else if (!Arrays.equals(current, preview.before())) result = RevertResult.CHANGED;
        Files.delete(state);
        return result;
    }
    private Pending pending() throws IOException {
        Properties values = new Properties();
        try {
            values.load(new ByteArrayInputStream(readRegular(state)));
            String version = values.getProperty("version");
            if (!"1".equals(version) && !"2".equals(version)) throw new IOException("Unknown preview journal format");
            byte[] before = Base64.getDecoder().decode(values.getProperty("before"));
            byte[] applied = Base64.getDecoder().decode(values.getProperty("applied"));
            long deadline = Long.parseLong(values.getProperty("deadline"));
            String savedProfile = values.getProperty("profile", "desktop");
            if (!savedProfile.equals("desktop") && !savedProfile.equals("unified")) throw new IOException("Invalid preview profile");
            Path savedTarget = targetFor(savedProfile);
            String targetValue = values.getProperty("target", savedTarget.toString());
            if (!savedTarget.toString().equals(targetValue)) throw new IOException("Preview target changed");
            if (deadline <= 0 || !patternFor(savedProfile).matcher(new String(before, StandardCharsets.UTF_8)).matches()
                    || !patternFor(savedProfile).matcher(new String(applied, StandardCharsets.UTF_8)).matches())
                throw new IOException("Invalid preview journal");
            return new Pending(savedProfile, savedTarget, before, applied, deadline);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new IOException("Malformed preview journal", malformed);
        }
    }
    private void writePending(Pending preview) throws IOException {
        Properties values = new Properties();
        values.setProperty("version", "2");
        values.setProperty("profile", preview.profile());
        values.setProperty("target", preview.target().toString());
        values.setProperty("before", Base64.getEncoder().encodeToString(preview.before()));
        values.setProperty("applied", Base64.getEncoder().encodeToString(preview.applied()));
        values.setProperty("deadline", Long.toString(preview.deadline()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, "Unconfirmed desktop scale preview");
        atomicWrite(state, bytes.toByteArray());
    }
    private Path target() { return targetFor(profile); }
    private Path targetFor(String selected) { return selected.equals("unified") ? unifiedMonitor : monitor; }
    private Pattern pattern() { return patternFor(profile); }
    private static Pattern patternFor(String selected) { return selected.equals("unified") ? UNIFIED_GENERATED : GENERATED; }
    private byte[] readRegular(Path path) throws IOException {
        noSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES)
            throw new IOException("Missing or custom display configuration: " + path.getFileName());
        return Files.readAllBytes(path);
    }
    private void noSymlinks(Path path) throws IOException {
        if (!path.startsWith(boundary)) throw new IOException("Display path is outside app storage");
        // Android owns aliases above filesDir; only app-owned descendants are configurable.
        for (Path part = path; part != null && part.startsWith(boundary); part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Custom symlink path preserved: " + part.getFileName());
    }
    private void atomicWrite(Path path, byte[] bytes) throws IOException {
        noSymlinks(path);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".display-preview-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
