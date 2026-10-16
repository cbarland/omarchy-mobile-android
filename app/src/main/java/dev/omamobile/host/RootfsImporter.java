package dev.omamobile.host;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import com.github.luben.zstd.ZstdInputStream;

/** Imports a standard tar/tar.gz without executing its contents or changing Android. */
final class RootfsImporter {
    enum Layout { ROOT, ARCH_BOOTSTRAP }
    interface Modes { void chmod(Path path, int mode) throws IOException; }
    interface Hardlinks { boolean create(Path source, Path destination) throws IOException; }
    interface Progress { void update(String message); }
    interface Prepare { void run(Path staging) throws Exception; }
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
        return install(source, linux, machine, progress, staging -> { });
    }

    String install(InputStream source, Path linux, int machine, Progress progress, Prepare prepare) throws Exception {
        return install(source, linux, machine, progress, prepare, Layout.ROOT);
    }

    String install(InputStream source, Path linux, int machine, Progress progress, Prepare prepare, Layout layout) throws Exception {
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
            try (InputStream payload = layout == Layout.ARCH_BOOTSTRAP
                    ? new ZstdInputStream(buffered).setContinuous(false)
                    : first == 0x1f && second == 0x8b ? new GzipCompressorInputStream(buffered) : buffered) {
                extract(payload, staging, progress, Collections.emptySet(), layout);
                // Finish the decompressor before package execution, including its trailer.
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
            // Package/setup failures remain invisible to display sessions and
            // cannot turn an incomplete staging directory into an installation.
            prepare.run(staging);
            if (Thread.currentThread().isInterrupted()) throw new IOException("Installation cancelled");
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("A Linux installation already exists");
            // New installation only: never merge into a running distro.
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

    /** Export user-owned Linux data without including the system/package tree. */
    void exportUserData(Path root, java.io.OutputStream destination, Progress progress) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Linux installation is not available");
        try (var gzip = new GzipCompressorOutputStream(destination);
             var tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (String top : List.of("root", "home")) {
                Path source = root.resolve(top);
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
                Files.walkFileTree(source, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                        Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    private int count;
                    private String archive(Path path) { return top + "/" + source.relativize(path).toString().replace('\\', '/'); }
                    private void add(Path path, BasicFileAttributes attrs, boolean directory) throws IOException {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Export cancelled");
                        if (++count > 500_000) throw new IOException("Too many data files");
                        String name = archive(path);
                        TarArchiveEntry entry;
                        if (attrs.isSymbolicLink()) {
                            entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
                            entry.setLinkName(Files.readSymbolicLink(path).toString().replace('\\', '/'));
                        } else if (directory) entry = new TarArchiveEntry(name, TarArchiveEntry.LF_DIR);
                        else if (attrs.isRegularFile()) {
                            entry = new TarArchiveEntry(name, TarArchiveEntry.LF_NORMAL);
                            entry.setSize(attrs.size());
                        } else return; // Device nodes and other host-only objects cannot be restored safely.
                        try { entry.setMode((int) (Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) instanceof Integer mode ? mode & 0777 : 0600)); }
                        catch (UnsupportedOperationException ignored) { entry.setMode(directory ? 0700 : 0600); }
                        tar.putArchiveEntry(entry);
                        if (attrs.isRegularFile()) try (InputStream in = Files.newInputStream(path)) {
                            byte[] buffer = new byte[64 * 1024]; int read;
                            while ((read = in.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new IOException("Export cancelled"); tar.write(buffer, 0, read); }
                        }
                        tar.closeArchiveEntry();
                        if (count % 500 == 0) progress.update("Exporting Linux data: " + count + " entries");
                    }
                    @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                        add(path, attrs, true); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException { add(path, attrs, false); return FileVisitResult.CONTINUE; }
                });
            }
            tar.finish();
        }
    }

    /** Recover an exported /root and /home archive as a new, private copy. */
    String recoverUserData(InputStream source, Path installedRoot, Progress progress) throws Exception {
        if (!Files.isDirectory(installedRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Linux installation is not available");
        Path recovered = installedRoot.resolve("root/Recovered");
        GuestPaths.noSymlinkParents(installedRoot, recovered);
        if (Files.exists(recovered, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(recovered, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Recovered data directory is not a real directory");
        } else {
            Files.createDirectory(recovered);
        }
        String id = UUID.randomUUID().toString();
        Path staging = installedRoot.getParent().resolve("recovery-" + id + ".tmp");
        Path published = recovered.resolve(id);
        Files.createDirectory(staging);
        boolean activated = false;
        try (var buffered = new BufferedInputStream(source)) {
            buffered.mark(2);
            int first = buffered.read(), second = buffered.read();
            buffered.reset();
            try (InputStream payload = first == 0x1f && second == 0x8b
                    ? new GzipCompressorInputStream(buffered) : buffered) {
                extract(payload, staging, progress, Set.of("root", "home"));
                byte[] remainder = new byte[8192];
                long trailing = 0;
                int read;
                while ((read = payload.read(remainder)) != -1) {
                    if ((trailing += read) > 1024 * 1024)
                        throw new IOException("Excessive data after recovery archive");
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Recovery cancelled");
            Files.move(staging, published, StandardCopyOption.ATOMIC_MOVE);
            activated = true;
            progress.update("User data recovered as a separate copy: /root/Recovered/" + id);
            return id;
        } finally {
            if (!activated) GuestPaths.deleteTree(staging);
        }
    }

    /** Trusted APK shell overlay; retain the importer's path/link/size checks. */
    void overlay(InputStream source, Path root, Progress progress) throws IOException {
        try (var payload = new GzipCompressorInputStream(new BufferedInputStream(source))) {
            extract(payload, root, progress);
            byte[] remainder = new byte[8192];
            long trailing = 0;
            int read;
            while ((read = payload.read(remainder)) != -1) {
                if ((trailing += read) > 1024 * 1024) throw new IOException("Excessive overlay trailer");
            }
        }
    }

    private void extract(InputStream payload, Path root, Progress progress) throws IOException {
        extract(payload, root, progress, Collections.emptySet());
    }

    private void extract(InputStream payload, Path root, Progress progress, Set<String> allowedTop) throws IOException {
        extract(payload, root, progress, allowedTop, Layout.ROOT);
    }

    private void extract(InputStream payload, Path root, Progress progress, Set<String> allowedTop, Layout layout) throws IOException {
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
            // Official Arch bootstraps also carry two release metadata files
            // beside the filesystem wrapper. They are not guest paths.
            if (layout == Layout.ARCH_BOOTSTRAP && (entry.getName().equals("pkglist.x86_64.txt")
                    || entry.getName().equals("version"))) {
                if ((entry.getLinkFlag() != TarArchiveEntry.LF_NORMAL && entry.getLinkFlag() != TarArchiveEntry.LF_OLDNORM)
                        || entry.isSparse() || entry.getSize() < 0 || entry.getSize() > 1024 * 1024)
                    throw new IOException("Invalid Arch bootstrap release metadata");
                while (tar.read(buffer) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Import cancelled");
                }
                continue;
            }
            String name = archiveName(entry.getName(), layout);
            if (name.isEmpty()) {
                if (!entry.isDirectory()) throw new IOException("Bootstrap wrapper must be a directory");
                continue;
            }
            if (!allowedTop.isEmpty()) validateNamespace(name, allowedTop);
            Path path = GuestPaths.archivePath(root, name);
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
                String target = entry.isLink() ? archiveName(entry.getLinkName(), layout) : entry.getLinkName();
                links.add(new Link(path, target, entry.isLink()));
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
                if (!allowedTop.isEmpty()) validateNamespace(link.target, allowedTop);
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

    private static void validateNamespace(String name, Set<String> allowedTop) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\0') >= 0)
            throw new IOException("Invalid recovery archive path");
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        String[] parts = clean.split("/", -1);
        if (parts.length == 0 || !allowedTop.contains(parts[0]))
            throw new IOException("Recovery archive contains data outside /root or /home");
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("Invalid recovery archive path");
    }

    private static String archiveName(String name, Layout layout) throws IOException {
        if (layout == Layout.ROOT) return name;
        String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : clean.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IOException("Invalid Arch bootstrap archive path");
        }
        if (clean.equals("root.x86_64")) return "";
        if (!clean.startsWith("root.x86_64/")) throw new IOException("Unexpected Arch bootstrap wrapper");
        return clean.substring("root.x86_64/".length());
    }

}
