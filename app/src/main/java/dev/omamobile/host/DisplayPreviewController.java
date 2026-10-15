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
    enum RevertResult { NONE, REVERTED, CHANGED }
    private record Pending(byte[] before, byte[] applied, long deadline) { }
    private final Path monitor, state, boundary;

    DisplayPreviewController(Context context) {
        this(context.getFilesDir().toPath().resolve("linux/rootfs/root/.local/share/omamobile/profiles/desktop/hypr/monitors.lua"),
                context.getFilesDir().toPath().resolve("display-preview"));
    }
    DisplayPreviewController(Path monitor, Path stateDirectory) {
        this.monitor = monitor.toAbsolutePath().normalize();
        state = stateDirectory.toAbsolutePath().normalize().resolve("pending.properties");
        boundary = state.getParent().getParent();
    }
    boolean hasPending() { return Files.exists(state, LinkOption.NOFOLLOW_LINKS); }
    long deadline() throws IOException { return pending().deadline(); }

    void preview(int scale, long now) throws IOException {
        if (Arrays.stream(SCALES).noneMatch(value -> value == scale)) throw new IOException("Unsupported desktop scale");
        if (hasPending()) throw new IOException("Keep or revert the current preview first");
        byte[] original = readRegular(monitor);
        String text = new String(original, StandardCharsets.UTF_8);
        var match = GENERATED.matcher(text);
        if (!match.matches()) throw new IOException("Custom desktop monitor config preserved");
        String selected = String.format(Locale.ROOT, "%.2f", scale / 100.0);
        if (Double.parseDouble(match.group(1)) == scale / 100.0) throw new IOException("Desktop already uses that scale");
        byte[] applied = (text.substring(0, match.start(1)) + selected + text.substring(match.end(1)))
                .getBytes(StandardCharsets.UTF_8);
        Pending preview = new Pending(original, applied, Math.addExact(now, TIMEOUT_MS));
        writePending(preview);
        try {
            atomicWrite(monitor, applied);
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
        if (!Arrays.equals(readRegular(monitor), preview.applied()))
            throw new IOException("Desktop config changed during preview; revert preserves the newer edit");
        Files.delete(state);
        return true;
    }
    RevertResult revert() throws IOException {
        if (!hasPending()) return RevertResult.NONE;
        Pending preview = pending();
        byte[] current;
        try { current = readRegular(monitor); }
        catch (IOException missingOrCustom) {
            Files.delete(state);
            return RevertResult.CHANGED;
        }
        RevertResult result = RevertResult.NONE;
        if (Arrays.equals(current, preview.applied())) {
            atomicWrite(monitor, preview.before());
            result = RevertResult.REVERTED;
        } else if (!Arrays.equals(current, preview.before())) result = RevertResult.CHANGED;
        Files.delete(state);
        return result;
    }
    private Pending pending() throws IOException {
        Properties values = new Properties();
        try {
            values.load(new ByteArrayInputStream(readRegular(state)));
            if (!"1".equals(values.getProperty("version"))) throw new IOException("Unknown preview journal format");
            byte[] before = Base64.getDecoder().decode(values.getProperty("before"));
            byte[] applied = Base64.getDecoder().decode(values.getProperty("applied"));
            long deadline = Long.parseLong(values.getProperty("deadline"));
            if (deadline <= 0 || !GENERATED.matcher(new String(before, StandardCharsets.UTF_8)).matches()
                    || !GENERATED.matcher(new String(applied, StandardCharsets.UTF_8)).matches())
                throw new IOException("Invalid preview journal");
            return new Pending(before, applied, deadline);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new IOException("Malformed preview journal", malformed);
        }
    }
    private void writePending(Pending preview) throws IOException {
        Properties values = new Properties();
        values.setProperty("version", "1");
        values.setProperty("before", Base64.getEncoder().encodeToString(preview.before()));
        values.setProperty("applied", Base64.getEncoder().encodeToString(preview.applied()));
        values.setProperty("deadline", Long.toString(preview.deadline()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, "Unconfirmed desktop scale preview");
        atomicWrite(state, bytes.toByteArray());
    }
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
