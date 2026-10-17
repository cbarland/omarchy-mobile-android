package dev.omamobile.host;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/** One APK-owned helper, with exact legacy adoption; never overlays the user's Linux tree. */
final class AdapterRefresh {
    static final String MEMBER = "usr/bin/omamobile-android";
    // Exact main-history Git blobs of tools/linux/omamobile-android, all 100755.
    // Enroll each shipped helper here when changing it so the next APK can refresh it too.
    private static final java.util.Map<String, Long> PREIMAGES = java.util.Map.of(
            "961a882261e9e403b1d217024ed3e55cdf1d0bdc9c9cc09546571e3912ca0782", 7894L, // 09b3109
            "f634def420d3de15d0cfb2ecd66ef3e0e4323b3e8ea14f110e123f98704f1534", 8498L, // 636daa9
            "0c5a7c0ec3320d6dfc73cfb0b654b005aaac464fd821eeb70aa10c0ebb11049d", 12686L, // 990c260
            "b4446d2a49052b931509ec7f026ee269baf5093c33d3fe549dce535c693aa460", 13093L, // 3fd49b6
            "479e2065cf49d10b698a2cf9984fea51bbba9bb283175c999c63706656b067a7", 14564L); // 1c9bdca
    private static final int MAX_HELPER = 256 * 1024;
    record Desired(String sha256, long bytes) {
        Desired {
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || bytes < 1 || bytes > MAX_HELPER)
                throw new IllegalArgumentException("Invalid APK adapter member");
        }
    }
    interface Source {
        Desired desired() throws Exception;
        /** Stages and authenticates the APK archive before returning its path. */
        Path stage(Path directory) throws Exception;
    }
    interface Modes {
        int read(Path path) throws IOException;
        void write(Path path, int mode) throws IOException;
    }
    private record Snapshot(Object key, long bytes, java.nio.file.attribute.FileTime modified, String hash) { }

    /** Called only on a worker; returned shared lease must cover the entire child lifetime. */
    static InstallationLease acquire(Path filesRoot, Source source, Modes modes, Consumer<String> log) throws Exception {
        // Android's authoritative files directory may itself be an alias.
        Path anchor = filesRoot.toRealPath();
        Path linux = anchor.resolve("linux");
        Path helper = linux.resolve("rootfs").resolve(MEMBER);
        safeInstallation(anchor);
        Desired desired = source.desired();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (true) {
            running();
            try {
                safeInstallation(anchor);
                InstallationLease shared = InstallationLease.acquire(linux, InstallationLease.Mode.SHARED_USE);
                boolean keep = false;
                try {
                    safeInstallation(anchor);
                    if (desired == null) {
                        log.accept("This APK has no installer payload; keeping the Linux Android helper unchanged.");
                        keep = true;
                        return shared;
                    }
                    Snapshot before = inspect(anchor, helper, modes, log);
                    if (before == null || !known(before) || current(before, desired)) {
                        if (before != null && !current(before, desired)) log.accept("Keeping customized Linux Android helper unchanged.");
                        keep = true;
                        return shared;
                    }
                } finally { if (!keep) shared.close(); }
                // Never upgrade a held lock or open an overlapping channel. Another starter may
                // win here; retry from shared inspection so its completed refresh can be used.
                safeInstallation(anchor);
                try (InstallationLease exclusive = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
                    Snapshot before = inspect(anchor, helper, modes, log);
                    if (before != null && known(before) && !current(before, desired))
                        replace(anchor, linux, helper, before, desired, source, modes, log);
                }
            } catch (InstallationLease.BusyException busy) {
                if (System.nanoTime() >= deadline) throw new IOException("Linux installation is busy; stop running sessions and retry.", busy);
                try { Thread.sleep(50); }
                catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); running(); }
            }
        }
    }

    private static boolean current(Snapshot snapshot, Desired desired) {
        return snapshot.bytes == desired.bytes && snapshot.hash.equals(desired.sha256);
    }

    private static boolean known(Snapshot snapshot) {
        return Objects.equals(PREIMAGES.get(snapshot.hash), snapshot.bytes);
    }

    private static void safeInstallation(Path anchor) throws IOException {
        GuestPaths.noSymlinkParents(anchor, anchor.resolve("linux/rootfs/.installation-path-check"));
        if (!Files.isDirectory(anchor.resolve("linux/rootfs"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Linux installation is not available");
    }

    private static void safeHelperParents(Path anchor, Path helper) throws IOException {
        safeInstallation(anchor);
        GuestPaths.noSymlinkParents(anchor.resolve("linux/rootfs"), helper);
    }

    private static Snapshot inspect(Path anchor, Path helper, Modes modes, Consumer<String> log) throws Exception {
        safeInstallation(anchor);
        try { GuestPaths.noSymlinkParents(anchor.resolve("linux/rootfs"), helper); }
        catch (IOException alias) {
            log.accept("Keeping Linux Android helper with linked ancestor directories unchanged.");
            return null;
        }
        if (!Files.isRegularFile(helper, LinkOption.NOFOLLOW_LINKS) || modes.read(helper) != 0755) {
            log.accept("Keeping missing, linked, or mode-customized Linux Android helper unchanged.");
            return null;
        }
        BasicFileAttributes attributes = Files.readAttributes(helper, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.size() > MAX_HELPER) {
            log.accept("Keeping oversized customized Linux Android helper unchanged.");
            return null;
        }
        return new Snapshot(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), hash(helper));
    }

    private static void replace(Path anchor, Path linux, Path helper, Snapshot before, Desired desired,
            Source source, Modes modes, Consumer<String> log) throws Exception {
        safeHelperParents(anchor, helper);
        Path staging = Files.createTempDirectory(linux, "adapter-refresh-");
        Path temporary = null;
        try {
            Path archive = source.stage(staging);
            if (!archive.normalize().equals(staging.resolve("linux-shell.tgz"))
                    || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("APK helper archive is outside its staging directory");
            running();
            byte[] selected = null;
            try (var gzip = new GzipCompressorInputStream(Files.newInputStream(archive));
                 var tar = new TarArchiveInputStream(gzip)) {
                org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
                int entries = 0;
                while ((entry = tar.getNextEntry()) != null) {
                    running();
                    if (++entries > 100_000) throw new IOException("Too many APK payload members");
                    // Reject aliases of our destination too, including ./ and traversal forms.
                    Path member = GuestPaths.archivePath(staging, entry.getName());
                    if (!member.equals(staging.resolve(MEMBER))) continue;
                    if (!entry.getName().equals(MEMBER) || selected != null || !entry.isFile()
                            || entry.isLink() || entry.isSymbolicLink() || entry.getMode() != 0755
                            || entry.getSize() != desired.bytes)
                        throw new IOException("Invalid or duplicate APK Android helper member");
                    var output = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = tar.read(buffer)) != -1) {
                        running();
                        if (output.size() + count > desired.bytes) throw new IOException("APK Android helper exceeds metadata size");
                        output.write(buffer, 0, count);
                    }
                    selected = output.toByteArray();
                    if (selected.length != desired.bytes || !hash(selected).equals(desired.sha256))
                        throw new IOException("APK Android helper checksum mismatch");
                }
                // Consume the gzip trailer before publishing anything.
                while (gzip.read() != -1) { running(); }
            }
            if (selected == null) throw new IOException("APK Android helper member missing");
            safeHelperParents(anchor, helper);
            temporary = Files.createTempFile(helper.getParent(), ".omamobile-android-", ".tmp");
            Files.write(temporary, selected);
            modes.write(temporary, 0755);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            running();
            Snapshot now = inspect(anchor, helper, modes, log);
            if (now == null || !Objects.equals(before, now))
                throw new IOException("Linux Android helper changed during refresh; preserved it");
            // The lease protects cooperating app owners, not arbitrary same-UID guest editors.
            Files.move(temporary, helper, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            log.accept("Refreshed the Linux Android helper from this APK; Linux settings were preserved.");
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
            GuestPaths.deleteTree(staging);
        }
        // A crash after rename needs no receipt: the desired hash is the next startup's no-op.
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192]; int count; long bytes = 0;
            while ((count = in.read(buffer)) != -1) {
                running(); bytes += count;
                if (bytes > MAX_HELPER) throw new IOException("Linux Android helper grew during inspection; preserved it");
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }
    private static String hash(byte[] data) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static void running() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Android helper refresh cancelled");
    }
}
