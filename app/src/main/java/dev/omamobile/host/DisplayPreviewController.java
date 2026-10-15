package dev.omamobile.host;

import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/** Owns the single adapter-generated desktop monitor file during a preview. */
final class DisplayPreviewController {
    static final long TIMEOUT_MS = 30_000L;
    private static final String MARKER = "-- Per-profile Wayland output scale; GTK text scale is separate.";
    private final Path root;
    private final Path monitor;
    private final Path state;
    private final Path before;
    private final Path applied;

    DisplayPreviewController(Context context) {
        Path files = context.getApplicationContext().getFilesDir().toPath();
        root = files.resolve("linux/rootfs");
        monitor = root.resolve("root/.local/share/omamobile/profiles/desktop/hypr/monitors.lua");
        Path directory = files.resolve("display-preview");
        state = directory.resolve("state.properties");
        before = directory.resolve("before.lua");
        applied = directory.resolve("applied.lua");
    }

    DisplayPreviewController(Path monitor, Path stateDirectory) {
        root = monitor.getParent();
        this.monitor = monitor;
        state = stateDirectory.resolve("state.properties");
        before = stateDirectory.resolve("before.lua");
        applied = stateDirectory.resolve("applied.lua");
    }

    static void recoverExpired(Context context) {
        DisplayPreviewController controller = new DisplayPreviewController(context);
        try {
            controller.recoverExpiredAt(System.currentTimeMillis());
        } catch (IOException ignored) {
            // Session startup must not replace a user file when recovery cannot
            // prove that it still contains the preview bytes.
        }
    }

    void recoverExpiredAt(long now) throws IOException {
        if (hasPending() && deadline() <= now) {
            if (java.util.Arrays.equals(Files.readAllBytes(monitor), Files.readAllBytes(before))) clear();
            else revert();
        }
    }

    boolean hasPending() { return Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS); }
    long deadline() throws IOException { return readState().getProperty("deadline", "0").isEmpty() ? 0 : Long.parseLong(readState().getProperty("deadline")); }
    int pendingScale() throws IOException { return Integer.parseInt(readState().getProperty("scale")); }

    void preview(int scale) throws IOException {
        if (scale < 100 || scale > 300) throw new IllegalArgumentException("Unsupported desktop scale");
        if (hasPending()) revert();
        if (!Files.isRegularFile(monitor, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(monitor) || hasSymlinkAncestor())
            throw new IOException("Desktop monitor config is missing or custom");
        String original = new String(Files.readAllBytes(monitor), StandardCharsets.UTF_8);
        if (!original.startsWith(MARKER + "\n")) throw new IOException("Custom desktop monitor config preserved");
        String text = original.replaceFirst("(?m)^local omarchy_monitor_scale = [0-9]+(?:\\.[0-9]+)?$",
                "local omarchy_monitor_scale = " + String.format(Locale.ROOT, "%.2f", scale / 100.0));
        if (text.equals(original)) throw new IOException("Desktop scale marker missing");
        Files.createDirectories(state.getParent());
        Files.write(before, original.getBytes(StandardCharsets.UTF_8));
        try {
            Files.write(applied, text.getBytes(StandardCharsets.UTF_8));
            Properties properties = new Properties();
            properties.setProperty("scale", Integer.toString(scale));
            properties.setProperty("deadline", Long.toString(System.currentTimeMillis() + TIMEOUT_MS));
            try (var output = Files.newOutputStream(state)) { properties.store(output, "display preview"); }
            DisplayConfigTransaction.apply(DisplayConfigTransaction.Preview.single(monitor, text));
        } catch (IOException failure) {
            Files.deleteIfExists(before); Files.deleteIfExists(applied); Files.deleteIfExists(state);
            throw failure;
        }
    }

    DisplayConfigTransaction.RevertResult revert() throws IOException {
        if (!hasPending()) return DisplayConfigTransaction.RevertResult.CHANGED;
        byte[] expected = Files.readAllBytes(applied);
        byte[] original = Files.readAllBytes(before);
        if (!java.util.Arrays.equals(Files.readAllBytes(monitor), expected))
            return DisplayConfigTransaction.RevertResult.CHANGED;
        DisplayConfigTransaction.restore(monitor, expected, original);
        clear();
        return DisplayConfigTransaction.RevertResult.REVERTED;
    }

    void keep() throws IOException { clear(); }

    private Properties readState() throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(state)) { properties.load(input); }
        return properties;
    }
    private boolean hasSymlinkAncestor() {
        for (Path path = monitor.getParent(); path != null && path.startsWith(root); path = path.getParent())
            if (Files.isSymbolicLink(path)) return true;
        return false;
    }
    private void clear() throws IOException {
        Files.deleteIfExists(state); Files.deleteIfExists(before); Files.deleteIfExists(applied);
    }
}
