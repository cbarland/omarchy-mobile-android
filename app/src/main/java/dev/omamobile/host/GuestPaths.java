package dev.omamobile.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Host-side access must interpret absolute links relative to the guest, never Android /. */
final class GuestPaths {
    static Path archivePath(Path root, String name) throws IOException {
        // Backslashes are literal Linux filename characters (e.g. systemd's
        // \x2d unit escapes). Only Windows host tests interpret them as separators.
        if (name.indexOf('\0') >= 0 || (name.indexOf('\\') >= 0 && root.getFileSystem().getSeparator().equals("\\"))
                || name.startsWith("/")
                || name.matches("^[A-Za-z]:.*") || name.length() > 4096) {
            throw new IOException("Invalid archive path: " + name);
        }
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root)) throw new IOException("Archive path escapes root: " + name);
        return path;
    }

    static void noSymlinkParents(Path root, Path path) throws IOException {
        for (Path parent = path.getParent(); parent != null && parent.startsWith(root);
                parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Symlink ancestor: " + path);
        }
    }

    static Path resolve(Path root, String guestPath) throws IOException {
        Path relative = java.nio.file.Paths.get(guestPath.startsWith("/") ? guestPath.substring(1) : guestPath);
        for (int links = 0; links < 40; links++) {
            Path cursor = root;
            boolean followed = false;
            for (int i = 0; i < relative.getNameCount(); i++) {
                cursor = cursor.resolve(relative.getName(i)).normalize();
                if (!cursor.startsWith(root)) throw new IOException("Guest path escapes root");
                if (Files.isSymbolicLink(cursor)) {
                    Path target = Files.readSymbolicLink(cursor);
                    Path resolved = target.isAbsolute()
                            ? root.resolve(target.toString().substring(1)) : cursor.getParent().resolve(target);
                    for (int j = i + 1; j < relative.getNameCount(); j++) resolved = resolved.resolve(relative.getName(j));
                    resolved = resolved.normalize();
                    if (!resolved.startsWith(root)) throw new IOException("Guest symlink escapes root");
                    relative = root.relativize(resolved);
                    followed = true;
                    break;
                }
            }
            if (!followed) return cursor;
        }
        throw new IOException("Guest symlink loop");
    }

    static int elfMachine(Path file) throws IOException {
        byte[] header = new byte[20];
        try (var in = Files.newInputStream(file)) {
            new java.io.DataInputStream(in).readFully(header);
            if (header[0] != 0x7f
                    || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                    || header[4] != 2 || header[5] != 1) throw new IOException("Expected a 64-bit little-endian ELF shell");
        }
        return (header[18] & 255) | ((header[19] & 255) << 8);
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        // Files.walk does not follow links. All callers supply a specific app-owned staging tree.
        try (var paths = Files.walk(root)) {
            var ordered = paths.sorted(java.util.Comparator.reverseOrder()).iterator();
            while (ordered.hasNext()) Files.delete(ordered.next());
        }
    }
}
