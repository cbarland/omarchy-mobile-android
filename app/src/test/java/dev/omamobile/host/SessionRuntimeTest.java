package dev.omamobile.host;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SessionRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void hostAliasRequiresInstalledRootfsAndPointsAtGuestPath() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("linux/rootfs");
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepareHostAlias(files, root));
        Files.createDirectories(root);
        Path alias = SessionRuntime.prepareHostAlias(files, root);
        assertEquals(GuestPaths.resolve(root, "/run/omamobile"), alias.toRealPath());
    }

    @Test public void prepareIsIdempotentAndUsesPhysicalGuestPath() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root);
        Path first = SessionRuntime.prepare(files, root, "phone");
        Path second = SessionRuntime.prepare(files, root, "phone");
        assertEquals(first, second);
        assertEquals(GuestPaths.resolve(root, "/run/omamobile/p"), first.toRealPath());
        assertTrue(first.normalize().startsWith(files.resolve(SessionRuntime.HOST_ALIAS)));
        assertTrue(Files.isSymbolicLink(files.resolve("s")));
    }

    @Test public void thirdRuntimeHasPrivateIdempotentAliasWithoutChangingExistingProfiles() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root);

        Path phone = SessionRuntime.prepare(files, root, "phone");
        Path desktop = SessionRuntime.prepare(files, root, "desktop");
        Path unified = SessionRuntime.prepare(files, root, "unified");

        assertEquals(GuestPaths.resolve(root, "/run/omamobile/p"), phone.toRealPath());
        assertEquals(GuestPaths.resolve(root, "/run/omamobile/d"), desktop.toRealPath());
        assertEquals(GuestPaths.resolve(root, "/run/omamobile/u"), unified.toRealPath());
        assertEquals(unified, SessionRuntime.prepare(files, root, "unified"));
        assertEquals(GuestPaths.resolve(root, "/run/omamobile"), files.resolve("s").toRealPath());
    }

    @Test public void unknownProfileIsRejected() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root);
        assertThrows(IllegalArgumentException.class, () -> SessionRuntime.prepare(files, root, "tablet"));
    }

    @Test public void foreignAliasIsPreservedAndRejected() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root);
        Path foreign = files.resolve("foreign");
        Files.createDirectories(foreign);
        Files.writeString(files.resolve("s"), "preserve");
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepare(files, root, "phone"));
        assertEquals("preserve", Files.readString(files.resolve("s")));
        assertTrue(Files.isDirectory(foreign));
    }

    @Test public void directoryAndWrongSymlinkAliasesArePreserved() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root);
        Files.createDirectories(files.resolve("s"));
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepare(files, root, "phone"));
        assertTrue(Files.isDirectory(files.resolve("s")));

        Path files2 = temporary.newFolder().toPath();
        Path root2 = files2.resolve("root");
        Files.createDirectories(root2);
        Path foreign = files2.resolve("foreign");
        Files.createDirectories(foreign);
        Files.createSymbolicLink(files2.resolve("s"), foreign);
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepare(files2, root2, "phone"));
        assertEquals(foreign, files2.resolve("s").toRealPath());
    }

    @Test public void preexistingProfileSymlinkIsRejectedWithoutFollowingIt() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root.resolve("run/omamobile"));
        Path profile = root.resolve("run/omamobile/p");
        Files.createSymbolicLink(profile, Path.of("/var/run/foreign"));
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepare(files, root, "phone"));
        assertTrue(Files.isSymbolicLink(profile));
        assertEquals(Path.of("/var/run/foreign"), Files.readSymbolicLink(profile));
    }

    @Test public void concurrentProfilesShareOneExclusiveAlias() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Callable<Path>> jobs = List.of(
                    () -> SessionRuntime.prepare(files, root, "phone"),
                    () -> SessionRuntime.prepare(files, root, "desktop"));
            List<java.util.concurrent.Future<Path>> results = pool.invokeAll(jobs);
            assertEquals(GuestPaths.resolve(root, "/run/omamobile/p"), results.get(0).get().toRealPath());
            assertEquals(GuestPaths.resolve(root, "/run/omamobile/d"), results.get(1).get().toRealPath());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(GuestPaths.resolve(root, "/run/omamobile"), files.resolve("s").toRealPath());
    }

    @Test public void prepareUsesGuestRunWhenRunResolvesToVarRun() throws Exception {
        Assume.assumeTrue("POSIX guest absolute-link semantics", java.io.File.separatorChar == '/');
        Path files = temporary.newFolder().toPath();
        Path root = files.resolve("root");
        Files.createDirectories(root.resolve("var/run"));
        Files.createSymbolicLink(root.resolve("run"), Path.of("/var/run"));
        Path prepared = SessionRuntime.prepare(files, root, "phone");
        assertEquals(root.resolve("var/run/omamobile/p"), prepared.toRealPath());
        assertTrue(prepared.normalize().startsWith(files.resolve(SessionRuntime.HOST_ALIAS)));
    }

    @Test public void adapterPreparationConvergesThroughTheHostAlias() throws Exception {
        Path files = temporary.newFolder().toPath();
        Path root = Files.createDirectory(files.resolve("root"));
        Path runtime = SessionRuntime.prepare(files, root, "phone");
        Path bin = SessionRuntime.prepareAdapterBin(runtime);
        Files.writeString(bin.resolve("custom"), "preserve");
        assertEquals(bin, SessionRuntime.prepareAdapterBin(runtime));
        assertEquals(runtime.resolve("ando/bin"), bin);
        assertTrue(Files.isDirectory(bin, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isDirectory(bin.getParent(), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(files.resolve("s")));
        assertEquals("preserve", Files.readString(bin.resolve("custom")));
    }

    @Test public void relativeAdapterAliasesPreserveCustomContentsAndPermissions() throws Exception {
        Assume.assumeTrue("POSIX symlink and permission semantics", java.io.File.separatorChar == '/');
        for (String aliasPath : List.of("ando", "ando/bin")) {
            Path files = temporary.newFolder().toPath();
            Path root = Files.createDirectory(files.resolve("root"));
            Path runtime = SessionRuntime.prepare(files, root, "phone");
            Path custom = Files.createDirectory(root.resolve("custom"));
            var directoryMode = PosixFilePermissions.fromString("rwxr-x---");
            var sentinelMode = PosixFilePermissions.fromString("rw-r-----");
            Files.setPosixFilePermissions(custom, directoryMode);
            for (String name : List.of("ando", "hyprland-dialog", "dialog.qml")) {
                Path sentinel = Files.writeString(custom.resolve(name), "custom " + name);
                Files.setPosixFilePermissions(sentinel, sentinelMode);
            }
            Path alias = runtime.toRealPath().resolve(aliasPath);
            if (aliasPath.equals("ando/bin")) Files.createDirectory(alias.getParent());
            Path relativeTarget = alias.getParent().relativize(custom);
            Files.createSymbolicLink(alias, relativeTarget);

            assertThrows(java.io.IOException.class, () -> SessionRuntime.prepareAdapterBin(runtime));

            assertTrue(Files.isSymbolicLink(alias));
            assertEquals(relativeTarget, Files.readSymbolicLink(alias));
            assertEquals(directoryMode, Files.getPosixFilePermissions(custom));
            for (String name : List.of("ando", "hyprland-dialog", "dialog.qml")) {
                assertEquals("custom " + name, Files.readString(custom.resolve(name)));
                assertEquals(sentinelMode, Files.getPosixFilePermissions(custom.resolve(name)));
            }
            try (var entries = Files.list(custom)) {
                assertEquals(3, entries.count());
            }
        }
    }

    @Test public void adapterPreparationRejectsNonDirectoriesWithoutChangingThem() throws Exception {
        for (String entryPath : List.of("ando", "ando/bin")) {
            Path runtime = temporary.newFolder().toPath();
            Path entry = runtime.resolve(entryPath);
            if (entryPath.equals("ando/bin")) Files.createDirectory(entry.getParent());
            Files.writeString(entry, "preserve");
            assertThrows(java.io.IOException.class, () -> SessionRuntime.prepareAdapterBin(runtime));
            assertEquals("preserve", Files.readString(entry));
        }
    }
}
