package dev.omamobile.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/** Caller holds EXCLUSIVE_INSTALL. Only authenticated changed stock files are replaced. */
final class ShellPayloadUpdate {
    static final String MANIFEST = "usr/share/omamobile/android/install-manifest.json";
    static final String TRANSACTION = "payload-update";
    private static final long MAX_FILE = 128L * 1024 * 1024;
    record Member(String hash, long bytes, int mode) {
        Member {
            if (hash == null || !hash.matches("[0-9a-f]{64}") || bytes < 0 || bytes > MAX_FILE
                    || mode < -1 || mode > 0777) throw new IllegalArgumentException("Invalid payload member");
        }
    }
    interface Filesystem {
        int mode(Path path) throws IOException;
        void mode(Path path, int mode) throws IOException;
        void replace(Path source, Path target) throws IOException;
    }
    private record Change(String path, Member before, Member after) { }
    private static void running() throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Payload update cancelled");
    }
    private static Path target(Path root, String name) throws IOException {
        Path result = GuestPaths.archivePath(root, name);
        if (name.isEmpty() || !root.relativize(result).toString().replace(root.getFileSystem().getSeparator(), "/").equals(name)
                || result.equals(root) || name.equals(MANIFEST)) throw new IOException("Ambiguous payload path: " + name);
        GuestPaths.noSymlinkParents(root, result);
        return result;
    }
    static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                running();
                if ((total += count) > MAX_FILE) throw new IOException("Payload file too large");
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value & 255) >>> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
    private static Member observed(Path path, Filesystem fs) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Customized non-file preserved: " + path);
        return new Member(hash(path), Files.size(path), fs.mode(path));
    }
    private static boolean matches(Member actual, Member expected) {
        return actual == null ? expected == null : expected != null
                && actual.hash.equals(expected.hash) && actual.bytes == expected.bytes
                && (expected.mode == -1 || actual.mode == expected.mode);
    }
    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    private static void save(Properties journal, Path path) throws IOException {
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) { journal.store(output, null); }
        if (Files.size(path) > 4 * 1024 * 1024) throw new IOException("Payload update journal too large");
        force(path);
    }
    private static void finish(Path transaction, Filesystem fs) throws IOException {
        // A crash during garbage collection must not leave an active, half-deleted journal.
        Path finished = transaction.resolveSibling("payload-finished-" + java.util.UUID.randomUUID());
        fs.replace(transaction, finished);
        GuestPaths.deleteTree(finished);
    }
    private static void put(Properties properties, String key, Member member) {
        properties.setProperty(key + ".hash", member == null ? "-" : member.hash);
        if (member != null) {
            properties.setProperty(key + ".bytes", Long.toString(member.bytes));
            properties.setProperty(key + ".mode", Integer.toString(member.mode));
        }
    }
    private static Member get(Properties properties, String key) throws IOException {
        String hash = properties.getProperty(key + ".hash");
        if ("-".equals(hash)) return null;
        try {
            int mode = Integer.parseInt(properties.getProperty(key + ".mode"));
            if (mode < 0) throw new IllegalArgumentException("Missing journal file mode");
            return new Member(hash, Long.parseLong(properties.getProperty(key + ".bytes")), mode);
        }
        catch (RuntimeException invalid) { throw new IOException("Invalid payload update journal", invalid); }
    }
    private static void copy(Path source, Path destination, Member expected, Filesystem fs) throws Exception {
        Files.copy(source, destination);
        fs.mode(destination, expected.mode);
        force(destination);
        if (!matches(observed(destination, fs), expected)) throw new IOException("Payload changed while staging: " + source);
    }
    private static Path manifest(Path root) throws IOException {
        Path path = root.resolve(MANIFEST);
        GuestPaths.noSymlinkParents(root, path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024)
            throw new IOException("Installed payload ownership manifest missing or customized");
        return path;
    }
    static int apply(Path root, Path staged, Map<String, Member> installed,
                     Map<String, Member> desired, byte[] newManifest, Filesystem fs) throws Exception {
        if (newManifest.length == 0 || newManifest.length > 1024 * 1024
                || installed.size() > 100000 || desired.size() > 100000)
            throw new IOException("Invalid payload manifest size");
        recover(root, fs);
        Path metadata = manifest(root);
        ArrayList<Change> changes = new ArrayList<>();
        TreeSet<String> paths = new TreeSet<>(installed.keySet()); paths.addAll(desired.keySet());
        for (String name : paths) {
            running();
            Path live = target(root, name);
            Member old = installed.get(name), next = desired.get(name);
            if (next != null && next.mode < 0) throw new IOException("APK payload lacks file modes; rebuild its assets");
            if (matches(next, old) && matches(old, next)) continue;
            // Older installed manifests omitted modes. Equal content is unchanged.
            if (old != null && next != null && old.mode == -1 && old.hash.equals(next.hash) && old.bytes == next.bytes) continue;
            Member current = observed(live, fs);
            if (matches(current, next)) continue;
            if (!matches(current, old)) throw new IOException("Customized payload file preserved; update aborted: " + name);
            if (old != null && old.mode == -1 && next != null && current.mode != next.mode)
                throw new IOException("Unverified legacy permissions preserved: " + name);
            if (next != null && !matches(observed(target(staged, name), fs), next))
                throw new IOException("APK payload member mismatch: " + name);
            changes.add(new Change(name, current, next));
        }
        if (changes.isEmpty() && java.util.Arrays.equals(Files.readAllBytes(metadata), newManifest)) return 0;
        Path linux = root.getParent(), transaction = linux.resolve(TRANSACTION);
        Path preparation = Files.createTempDirectory(linux, "payload-stage-");
        boolean published = false;
        try {
            Properties journal = new Properties(); journal.setProperty("format", "1");
            journal.setProperty("count", Integer.toString(changes.size()));
            journal.setProperty("oldManifest", hash(metadata));
            Files.write(preparation.resolve("manifest.new"), newManifest); force(preparation.resolve("manifest.new"));
            fs.mode(preparation.resolve("manifest.new"), fs.mode(metadata));
            journal.setProperty("newManifest", hash(preparation.resolve("manifest.new")));
            for (int i = 0; i < changes.size(); i++) {
                running();
                Change change = changes.get(i);
                journal.setProperty(i + ".path", change.path);
                put(journal, i + ".old", change.before); put(journal, i + ".new", change.after);
                if (change.before != null) copy(target(root, change.path), preparation.resolve(i + ".old"), change.before, fs);
                if (change.after != null) copy(target(staged, change.path), preparation.resolve(i + ".new"), change.after, fs);
            }
            save(journal, preparation.resolve("journal.properties"));
            fs.replace(preparation, transaction); published = true;
            for (int i = 0; i < changes.size(); i++) {
                running();
                Change change = changes.get(i); Path live = target(root, change.path);
                if (!matches(observed(live, fs), change.before)) throw new IOException("Payload changed during update: " + change.path);
                if (change.after == null) Files.delete(live);
                else { Files.createDirectories(live.getParent()); fs.replace(transaction.resolve(i + ".new"), live); }
            }
            running();
            if (!hash(metadata).equals(journal.getProperty("oldManifest"))) throw new IOException("Installed manifest changed during update");
            fs.replace(transaction.resolve("manifest.new"), metadata);
            finish(transaction, fs);
            return changes.size();
        } catch (Exception failure) {
            boolean interrupted = Thread.interrupted();
            try {
                if (published) try { recover(root, fs); } catch (Exception recovery) { failure.addSuppressed(recovery); }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw failure;
        } finally { if (!published) GuestPaths.deleteTree(preparation); }
    }
    static void recover(Path root, Filesystem fs) throws Exception {
        Path transaction = root.getParent().resolve(TRANSACTION);
        if (!Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(transaction, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid payload update transaction");
        Path record = transaction.resolve("journal.properties");
        if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS) || Files.size(record) > 4 * 1024 * 1024)
            throw new IOException("Invalid payload update journal");
        Properties journal = new Properties(); try (var input = Files.newInputStream(record)) { journal.load(input); }
        if (!"1".equals(journal.getProperty("format"))) throw new IOException("Unsupported payload update journal");
        String current = hash(manifest(root));
        if (!current.equals(journal.getProperty("oldManifest")) && current.equals(journal.getProperty("newManifest"))) {
            finish(transaction, fs); return;
        }
        if (!current.equals(journal.getProperty("oldManifest"))) throw new IOException("Newer installed manifest preserved; recovery blocked");
        int count;
        try { count = Integer.parseInt(journal.getProperty("count")); }
        catch (RuntimeException invalid) { throw new IOException("Invalid payload update journal", invalid); }
        if (count < 0 || count > 100000) throw new IOException("Invalid payload update count");
        ArrayList<Change> changes = new ArrayList<>();
        TreeSet<String> unique = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            String name = journal.getProperty(i + ".path");
            if (name == null || !unique.add(name)) throw new IOException("Invalid payload update path");
            Member old = get(journal, i + ".old"), next = get(journal, i + ".new");
            Member actual = observed(target(root, name), fs);
            if (!matches(actual, old) && !matches(actual, next))
                throw new IOException("Newer payload edit preserved; recovery blocked: " + name);
            if (old != null && !matches(observed(transaction.resolve(i + ".old"), fs), old))
                throw new IOException("Payload rollback backup damaged: " + name);
            changes.add(new Change(name, old, next));
        }
        for (int i = changes.size() - 1; i >= 0; i--) {
            Change change = changes.get(i); Path live = target(root, change.path);
            if (matches(observed(live, fs), change.before)) continue;
            if (!matches(observed(live, fs), change.after)) throw new IOException("Payload changed during recovery: " + change.path);
            if (change.before == null) Files.delete(live);
            else {
                Path pending = transaction.resolve(i + ".restore"); Files.deleteIfExists(pending);
                copy(transaction.resolve(i + ".old"), pending, change.before, fs);
                Files.createDirectories(live.getParent()); fs.replace(pending, live);
            }
        }
        finish(transaction, fs);
    }
}
