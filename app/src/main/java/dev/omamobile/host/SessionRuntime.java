package dev.omamobile.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

/** Owns the physical guest runtime and its short host-side bind alias. */
final class SessionRuntime {
    static final String HOST_ALIAS = "s";

    private SessionRuntime() { }

    static Path prepare(Path files, Path root, String profile) throws IOException {
        if (!profile.equals("phone") && !profile.equals("desktop"))
            throw new IllegalArgumentException("Unknown graphical profile: " + profile);
        Path guestRoot = GuestPaths.resolve(root, "/run/omamobile");
        Files.createDirectories(guestRoot);
        Path hostAlias = files.resolve(HOST_ALIAS);
        ensureAlias(hostAlias, guestRoot);
        Path runtime = guestRoot.resolve(profile.equals("phone") ? "p" : "d");
        if (Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(runtime))
                throw new IOException("Graphical profile runtime must not be a symlink: " + runtime);
            if (!Files.isDirectory(runtime, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Graphical profile runtime is not a directory: " + runtime);
        } else {
            Files.createDirectories(runtime);
        }
        try {
            Files.setPosixFilePermissions(runtime, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException unsupported) {
            // Android's app filesystem is POSIX; host-only JVM fixtures may use
            // a filesystem without permission attributes.
        }
        return hostAlias.resolve(profile.equals("phone") ? "p" : "d");
    }

    private static void ensureAlias(Path alias, Path target) throws IOException {
        Path expected = target.toAbsolutePath().normalize();
        if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) {
            validateAlias(alias, expected);
            return;
        }
        try {
            Files.createSymbolicLink(alias, expected);
        } catch (java.nio.file.FileAlreadyExistsException race) {
            validateAlias(alias, expected);
        }
    }

    private static void validateAlias(Path alias, Path expected) throws IOException {
        if (!Files.isSymbolicLink(alias))
            throw new IOException("Runtime host alias is not a symlink: " + alias);
        Path target = Files.readSymbolicLink(alias);
        Path resolved = (target.isAbsolute() ? target : alias.getParent().resolve(target)).normalize().toAbsolutePath();
        if (!resolved.equals(expected))
            throw new IOException("Runtime host alias points elsewhere: " + alias + " -> " + target);
    }
}
