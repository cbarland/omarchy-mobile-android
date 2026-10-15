package dev.omamobile.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded transaction for display configuration files.
 *
 * The transaction owns only the paths passed to {@link #apply}. Revert first
 * checks every path against the bytes written by this transaction; if a user
 * or a later session changed any path, it leaves all paths untouched.
 */
final class DisplayConfigTransaction {
    record Preview(Map<Path, String> files) {
        Preview {
            if (files == null || files.isEmpty()) throw new IllegalArgumentException("No display configuration");
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
            for (var entry : files.entrySet()) {
                if (entry.getKey() == null || entry.getKey().getFileName() == null)
                    throw new IllegalArgumentException("Invalid display configuration path");
                if (entry.getValue() == null) throw new IllegalArgumentException("Missing display configuration text");
            }
        }

        static Preview of(Map<Path, String> files) { return new Preview(files); }
        static Preview single(Path path, String text) { return new Preview(Map.of(path, text)); }
    }

    enum RevertResult { REVERTED, CHANGED }

    private record Entry(Path path, byte[] before, byte[] applied) { }

    private final List<Entry> entries;
    private boolean finished;

    private DisplayConfigTransaction(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Validate desired bytes without changing the live configuration. */
    static Preview preview(Map<Path, String> files) {
        return Preview.of(files);
    }

    /** Apply all desired files atomically, rolling back earlier writes on failure. */
    static DisplayConfigTransaction apply(Preview preview) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (var file : preview.files().entrySet()) {
            Path path = file.getKey();
            byte[] before = Files.exists(path) ? Files.readAllBytes(path) : null;
            entries.add(new Entry(path, before, file.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        int written = 0;
        try {
            for (Entry entry : entries) {
                atomicWrite(entry.path(), entry.applied());
                written++;
            }
        } catch (IOException failure) {
            for (int i = written - 1; i >= 0; i--) {
                try {
                    restore(entries.get(i));
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        return new DisplayConfigTransaction(entries);
    }

    /**
     * Revert only if no path has changed since apply. A changed path means the
     * caller must keep the user's newer configuration and start a new review.
     */
    RevertResult revert() throws IOException {
        if (finished) return RevertResult.CHANGED;
        for (Entry entry : entries) {
            if (!same(entry.path(), entry.applied())) return RevertResult.CHANGED;
        }
        for (Entry entry : entries) restore(entry);
        finished = true;
        return RevertResult.REVERTED;
    }

    private static boolean same(Path path, byte[] expected) throws IOException {
        return Files.exists(path) && java.util.Arrays.equals(Files.readAllBytes(path), expected);
    }

    private static void restore(Entry entry) throws IOException {
        if (entry.before() == null) Files.deleteIfExists(entry.path());
        else atomicWrite(entry.path(), entry.before());
    }

    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("Display config has no parent: " + path);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".display-preview-", ".tmp");
        try {
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
