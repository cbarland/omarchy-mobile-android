package dev.omamobile.host;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Imports a standard tar/tar.gz without executing its contents or changing Android. */
final class RootfsImporter {
    interface Modes { void chmod(Path path, int mode) throws IOException; }
    interface Hardlinks { boolean create(Path source, Path destination) throws IOException; }
    interface Progress { void update(String message); }
    private final Modes modes;
    private final long maxBytes;
    private final Hardlinks hardlinks;
    RootfsImporter(Modes modes) { this(modes, 8L * 1024 * 1024 * 1024); }
    RootfsImporter(Modes modes, long maxBytes) {
        this(modes, maxBytes, (source, destination) -> { Files.createLink(destination, source); return true; });
    }
    RootfsImporter(Modes modes, long maxBytes, Hardlinks hardlinks) {
        this.modes = modes; this.maxBytes = maxBytes; this.hardlinks = hardlinks;
    }
    private record Link(Path path, String target, boolean hard) { }

    String install(InputStream source, Path linux, int machine, Progress progress) throws Exception {
        Files.createDirectories(linux);
        Path root = linux.resolve("rootfs");
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("A Linux installation already exists. Export or remove it before replacing it.");
        }
        Path staging = linux.resolve("import-" + UUID.randomUUID());
        Files.createDirectory(staging);
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        boolean activated = false;
        try (DigestInputStream digest = new DigestInputStream(source, hash);
                BufferedInputStream buffered = new BufferedInputStream(digest)) {
            buffered.mark(2);
            int first = buffered.read(), second = buffered.read();
            buffered.reset();
            try (InputStream payload = first == 0x1f && second == 0x8b
                    ? new GzipCompressorInputStream(buffered) : buffered) {
                extract(payload, staging, progress);
                // Finish the decompressor too, validating gzip trailer/CRC before activation.
                byte[] remainder = new byte[8192];
                long trailing = 0;
                int read;
                while ((read = payload.read(remainder)) != -1) {
                    trailing += read;
                    if (trailing > 1024 * 1024) throw new IOException("Excessive data after tar archive");
                }
                // Include trailing compressed bytes in the import fingerprint.
                while (buffered.read(remainder) != -1) { }
            }
            Path shell = GuestPaths.resolve(staging, "/bin/sh");
            if (GuestPaths.elfMachine(shell) != machine) {
                throw new IOException("Rootfs architecture does not match this Android device");
            }
            // New installation only: atomic rename never merges into or overwrites a running distro.
            Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
            activated = true;
            StringBuilder hex = new StringBuilder();
            for (byte value : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            String fingerprint = hex.toString();
            progress.update("Rootfs imported. SHA-256 " + fingerprint);
            return fingerprint;
        } finally {
            if (!activated) GuestPaths.deleteTree(staging);
        }
    }

    private void extract(InputStream payload, Path root, Progress progress) throws IOException {
        List<Link> links = new ArrayList<>();
        Set<Path> symlinks = new HashSet<>();
        List<Path> entries = new ArrayList<>();
        long total = 0;
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        // Do not close payload here: the caller also fingerprints trailing archive bytes.
        TarArchiveInputStream tar = new TarArchiveInputStream(payload);
        TarArchiveEntry entry;
        while ((entry = tar.getNextEntry()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
            if (++count > 500_000) throw new IOException("Archive contains too many entries");
            Path path = GuestPaths.archivePath(root, entry.getName());
            if (path.equals(root)) {
                if (!entry.isDirectory()) throw new IOException("Invalid root entry");
                continue;
            }
            GuestPaths.noSymlinkParents(root, path);
            entries.add(path);
            Files.createDirectories(path.getParent());
            if (entry.isSymbolicLink() || entry.isLink()) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        || links.stream().anyMatch(link -> link.path.equals(path))) {
                    throw new IOException("Duplicate link entry: " + entry.getName());
                }
                links.add(new Link(path, entry.getLinkName(), entry.isLink()));
                if (entry.isSymbolicLink()) symlinks.add(path);
            } else if (entry.isDirectory()) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Conflicting directory: " + entry.getName());
                }
                Files.createDirectories(path);
                // Keep imported directories writable by the app during package management.
                modes.chmod(path, (entry.getMode() & 0777) | 0700);
            } else if (entry.isFile() && !entry.isSparse()) {
                if (entry.getSize() < 0 || entry.getSize() > maxBytes - total) throw new IOException("Rootfs exceeds import limit");
                total += entry.getSize();
                if (root.toFile().getUsableSpace() < entry.getSize() + 64L * 1024 * 1024) {
                    throw new IOException("Insufficient storage for rootfs");
                }
                try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int size;
                    while ((size = tar.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                        out.write(buffer, 0, size);
                    }
                }
                modes.chmod(path, entry.getMode() & 0777); // Never restore setuid/setgid or ownership.
            } else if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                // Real Android /dev is bound at spawn; rootless apps cannot create device nodes.
                continue;
            } else {
                throw new IOException("Unsupported archive entry: " + entry.getName());
            }
            if (count % 500 == 0) progress.update("Importing Linux: " + count + " entries, " + (total / 1048576) + " MiB");
        }
        // Defer all links, rejecting entries through symlink parents in either archive order.
        for (Path path : entries) {
            for (Path parent = path.getParent(); parent != null && !parent.equals(root); parent = parent.getParent()) {
                if (symlinks.contains(parent)) throw new IOException("Archive writes through a symlink: " + path);
            }
        }
        for (Link link : links) {
            if (link.hard) continue;
            String target = link.target;
            if (target.isEmpty() || target.indexOf('\0') >= 0
                    || (target.indexOf('\\') >= 0 && root.getFileSystem().getSeparator().equals("\\")))
                throw new IOException("Invalid symlink target");
            Path resolved = target.startsWith("/") ? root.resolve(target.substring(1)) : link.path.getParent().resolve(target);
            resolved = resolved.normalize();
            if (!resolved.startsWith(root)) throw new IOException("Symlink escapes root");
            // Guest-absolute links become equivalent relative links safe for host-side access.
            Path relative = link.path.getParent().relativize(resolved);
            Files.createSymbolicLink(link.path, relative.toString().isEmpty() ? java.nio.file.Paths.get(".") : relative);
        }
        List<Link> pendingHardlinks = new ArrayList<>();
        java.util.Map<Path, Path> aliases = new java.util.HashMap<>();
        int fallbackCount = 0;
        for (Link link : links) if (link.hard) pendingHardlinks.add(link);
        while (!pendingHardlinks.isEmpty()) {
            int before = pendingHardlinks.size();
            var iterator = pendingHardlinks.iterator();
            while (iterator.hasNext()) {
                Link link = iterator.next();
                Path target = GuestPaths.archivePath(root, link.target);
                GuestPaths.noSymlinkParents(root, target);
                if (aliases.containsKey(target)) target = aliases.get(target);
                if (Files.isSymbolicLink(target)) throw new IOException("Hardlink targets a symlink");
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!hardlinks.create(target, link.path)) {
                    // TAWC ProotArchiveExtractor's stock-Android fallback: preserve data access
                    // without requiring the app_data_file hardlink permission SELinux denies.
                    Files.createSymbolicLink(link.path, link.path.getParent().relativize(target));
                    aliases.put(link.path, target);
                    fallbackCount++;
                }
                iterator.remove();
            }
            if (pendingHardlinks.size() == before) throw new IOException("Unresolved hardlinks");
        }
        if (fallbackCount > 0) progress.update("Android denied hardlinks; imported " + fallbackCount
                + " aliases as relative symlinks. Exact hardlink identity is not preserved for these aliases.");
    }
}
