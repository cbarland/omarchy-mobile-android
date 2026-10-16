package dev.omamobile.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

public class SessionRuntimeTest {
    @Test public void prepareIsIdempotentAndUsesPhysicalGuestPath() throws Exception {
        Path files = Files.createTempDirectory("session-files-");
        Path root = files.resolve("root");
        Files.createDirectories(root);
        Path first = SessionRuntime.prepare(files, root, "phone");
        Path second = SessionRuntime.prepare(files, root, "phone");
        assertEquals(first, second);
        assertEquals(GuestPaths.resolve(root, "/run/omamobile/p"), first.toRealPath());
        assertTrue(first.normalize().startsWith(files.resolve(SessionRuntime.HOST_ALIAS)));
        assertTrue(Files.isSymbolicLink(files.resolve("s")));
    }

    @Test public void foreignAliasIsPreservedAndRejected() throws Exception {
        Path files = Files.createTempDirectory("session-files-");
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
        Path files = Files.createTempDirectory("session-files-");
        Path root = files.resolve("root");
        Files.createDirectories(root);
        Files.createDirectories(files.resolve("s"));
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepare(files, root, "phone"));
        assertTrue(Files.isDirectory(files.resolve("s")));

        Path files2 = Files.createTempDirectory("session-files-");
        Path root2 = files2.resolve("root");
        Files.createDirectories(root2);
        Path foreign = files2.resolve("foreign");
        Files.createDirectories(foreign);
        Files.createSymbolicLink(files2.resolve("s"), foreign);
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepare(files2, root2, "phone"));
        assertEquals(foreign, files2.resolve("s").toRealPath());
    }

    @Test public void preexistingProfileSymlinkIsRejectedWithoutFollowingIt() throws Exception {
        Path files = Files.createTempDirectory("session-files-");
        Path root = files.resolve("root");
        Files.createDirectories(root.resolve("run/omamobile"));
        Path profile = root.resolve("run/omamobile/p");
        Files.createSymbolicLink(profile, Path.of("/var/run/foreign"));
        assertThrows(java.io.IOException.class, () -> SessionRuntime.prepare(files, root, "phone"));
        assertTrue(Files.isSymbolicLink(profile));
        assertEquals(Path.of("/var/run/foreign"), Files.readSymbolicLink(profile));
    }

    @Test public void concurrentProfilesShareOneExclusiveAlias() throws Exception {
        Path files = Files.createTempDirectory("session-files-");
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
        Path files = Files.createTempDirectory("session-files-");
        Path root = files.resolve("root");
        Files.createDirectories(root.resolve("var/run"));
        Files.createSymbolicLink(root.resolve("run"), Path.of("/var/run"));
        Path prepared = SessionRuntime.prepare(files, root, "phone");
        assertEquals(root.resolve("var/run/omamobile/p"), prepared.toRealPath());
        assertTrue(prepared.normalize().startsWith(files.resolve(SessionRuntime.HOST_ALIAS)));
    }
}
