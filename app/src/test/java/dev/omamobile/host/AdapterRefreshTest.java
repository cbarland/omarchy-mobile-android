package dev.omamobile.host;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.Test;

public class AdapterRefreshTest {
    @Test public void interruptedPayloadUpdateBlocksHelperRefreshBeforeExecution() throws Exception {
        byte[] before = legacy();
        try (Fixture fixture = new Fixture(before)) {
            Files.createDirectory(fixture.linux.resolve(ShellPayloadUpdate.TRANSACTION));
            assertThrows(IOException.class, () -> fixture.acquire(fixture.source()));
            assertEquals(0, fixture.staged);
            assertArrayEquals(before, Files.readAllBytes(fixture.helper));
        }
    }
    private static final byte[] DESIRED = "#!/bin/sh\necho updated\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static byte[] legacy() throws IOException {
        return legacy("636daa9");
    }
    private static byte[] legacy(String revision) throws IOException {
        try (var in = AdapterRefreshTest.class.getResourceAsStream("omamobile-android-" + revision)) { return in.readAllBytes(); }
    }
    private static final class Fixture implements AutoCloseable {
        final Path files = Files.createTempDirectory("adapter-refresh-");
        final Path linux = files.resolve("linux");
        final Path helper = linux.resolve("rootfs/" + AdapterRefresh.MEMBER);
        final List<String> logs = new ArrayList<>();
        int mode = 0755;
        int staged;
        final AdapterRefresh.Modes modes = new AdapterRefresh.Modes() {
            public int read(Path path) { return mode; }
            public void write(Path path, int value) { assertEquals(0755, value); }
        };
        Fixture(byte[] contents) throws Exception {
            Files.createDirectories(helper.getParent());
            if (contents != null) Files.write(helper, contents);
        }
        AdapterRefresh.Source source(String name, int archiveMode, boolean duplicate, byte[] data) {
            return new AdapterRefresh.Source() {
                public AdapterRefresh.Desired desired() throws Exception { return new AdapterRefresh.Desired(hash(DESIRED), DESIRED.length); }
                public Path stage(Path directory) throws Exception {
                    staged++;
                    Path archive = directory.resolve("linux-shell.tgz");
                    try (var gzip = new GzipCompressorOutputStream(Files.newOutputStream(archive));
                            var tar = new TarArchiveOutputStream(gzip)) {
                        for (int i = 0; i < (duplicate ? 2 : 1); i++) {
                            TarArchiveEntry entry = new TarArchiveEntry(name);
                            entry.setMode(archiveMode); entry.setSize(data.length);
                            tar.putArchiveEntry(entry); tar.write(data); tar.closeArchiveEntry();
                        }
                    }
                    return archive;
                }
            };
        }
        AdapterRefresh.Source source() { return source(AdapterRefresh.MEMBER, 0755, false, DESIRED); }
        InstallationLease acquire(AdapterRefresh.Source source) throws Exception {
            return AdapterRefresh.acquire(files, source, modes, logs::add);
        }
        public void close() throws IOException { GuestPaths.deleteTree(files); }
    }

    @Test public void exactLegacyRefreshesAndRetainedLeaseBlocksInstaller() throws Exception {
        for (String revision : List.of("636daa9", "3fd49b6")) try (Fixture fixture = new Fixture(legacy(revision))) {
            try (InstallationLease lease = fixture.acquire(fixture.source())) {
                assertArrayEquals(DESIRED, Files.readAllBytes(fixture.helper));
                assertEquals(1, fixture.staged);
                assertThrows(InstallationLease.BusyException.class,
                        () -> InstallationLease.acquire(fixture.linux, InstallationLease.Mode.EXCLUSIVE_INSTALL));
            }
            try (InstallationLease lease = fixture.acquire(fixture.source())) { assertEquals(1, fixture.staged); }
            try (var children = Files.list(fixture.linux)) { assertFalse(children.anyMatch(p -> p.getFileName().toString().startsWith("adapter-refresh-"))); }
        }
    }

    @Test public void currentUnknownMissingAndModeChangesNeverStage() throws Exception {
        for (byte[] content : new byte[][] { DESIRED, "user edit".getBytes(), null, legacy() }) {
            try (Fixture fixture = new Fixture(content)) {
                if (content != null && content.length == 8498) fixture.mode = 0700;
                try (InstallationLease lease = fixture.acquire(fixture.source())) {
                    assertEquals(0, fixture.staged);
                    if (content == null) assertFalse(Files.exists(fixture.helper));
                    else assertArrayEquals(content, Files.readAllBytes(fixture.helper));
                }
            }
        }
    }

    @Test public void malformedArchivePreservesLegacyAndCanRetry() throws Exception {
        for (int bad = 0; bad < 5; bad++) {
            try (Fixture fixture = new Fixture(legacy())) {
                var source = switch (bad) {
                    case 0 -> fixture.source(AdapterRefresh.MEMBER, 0755, true, DESIRED);
                    case 1 -> fixture.source(AdapterRefresh.MEMBER, 0700, false, DESIRED);
                    case 2 -> fixture.source("./" + AdapterRefresh.MEMBER, 0755, false, DESIRED);
                    case 3 -> fixture.source("missing", 0755, false, DESIRED);
                    default -> fixture.source(AdapterRefresh.MEMBER, 0755, false, new byte[DESIRED.length]);
                };
                assertThrows(IOException.class, () -> fixture.acquire(source));
                assertArrayEquals(legacy(), Files.readAllBytes(fixture.helper));
                try (InstallationLease lease = fixture.acquire(fixture.source())) { assertArrayEquals(DESIRED, Files.readAllBytes(fixture.helper)); }
            }
        }
    }

    @Test public void concurrentEditDuringStagingIsPreserved() throws Exception {
        try (Fixture fixture = new Fixture(legacy())) {
            AdapterRefresh.Source good = fixture.source();
            AdapterRefresh.Source edited = new AdapterRefresh.Source() {
                public AdapterRefresh.Desired desired() throws Exception { return good.desired(); }
                public Path stage(Path directory) throws Exception {
                    Path archive = good.stage(directory);
                    Files.writeString(fixture.helper, "user replacement");
                    return archive;
                }
            };
            assertThrows(IOException.class, () -> fixture.acquire(edited));
            assertEquals("user replacement", Files.readString(fixture.helper));
        }
    }

    @Test public void interruptedStageLeavesLegacyAndRetryWorks() throws Exception {
        try (Fixture fixture = new Fixture(legacy())) {
            AdapterRefresh.Source good = fixture.source();
            AdapterRefresh.Source interrupted = new AdapterRefresh.Source() {
                public AdapterRefresh.Desired desired() throws Exception { return good.desired(); }
                public Path stage(Path directory) throws Exception { Path archive = good.stage(directory); Thread.currentThread().interrupt(); return archive; }
            };
            try { assertThrows(java.io.InterruptedIOException.class, () -> fixture.acquire(interrupted)); }
            finally { Thread.interrupted(); }
            assertArrayEquals(legacy(), Files.readAllBytes(fixture.helper));
            try (InstallationLease lease = fixture.acquire(good)) { assertArrayEquals(DESIRED, Files.readAllBytes(fixture.helper)); }
        }
    }

    @Test public void busyOwnerRetainsOwnershipAndCancelledWaitDoesNotPoisonIt() throws Exception {
        try (Fixture fixture = new Fixture(legacy());
                InstallationLease owner = InstallationLease.acquire(fixture.linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
            AtomicReference<Throwable> result = new AtomicReference<>();
            CountDownLatch began = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                began.countDown();
                try (InstallationLease lease = fixture.acquire(fixture.source())) { result.set(new AssertionError("unexpected acquisition")); }
                catch (Throwable error) { result.set(error); }
            });
            waiter.start(); assertTrue(began.await(2, TimeUnit.SECONDS));
            Thread.sleep(100); waiter.interrupt(); waiter.join(2000);
            assertFalse(waiter.isAlive()); assertTrue(String.valueOf(result.get()), result.get() instanceof java.io.InterruptedIOException);
            assertThrows(InstallationLease.BusyException.class,
                    () -> InstallationLease.acquire(fixture.linux, InstallationLease.Mode.SHARED_USE));
            assertArrayEquals(legacy(), Files.readAllBytes(fixture.helper));
        }
    }

    @Test public void startupWaitsForInstallerThenRefreshesWithoutLosingOwnership() throws Exception {
        try (Fixture fixture = new Fixture(legacy())) {
            InstallationLease owner = InstallationLease.acquire(fixture.linux, InstallationLease.Mode.EXCLUSIVE_INSTALL);
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread starter = new Thread(() -> {
                try (InstallationLease lease = fixture.acquire(fixture.source())) {
                    acquired.countDown(); release.await();
                } catch (Throwable failure) { error.set(failure); acquired.countDown(); }
            });
            try {
                starter.start(); assertFalse(acquired.await(100, TimeUnit.MILLISECONDS));
                assertThrows(InstallationLease.BusyException.class,
                        () -> InstallationLease.acquire(fixture.linux, InstallationLease.Mode.SHARED_USE));
                owner.close(); assertTrue(acquired.await(5, TimeUnit.SECONDS));
                assertNull(error.get()); assertArrayEquals(DESIRED, Files.readAllBytes(fixture.helper));
                assertThrows(InstallationLease.BusyException.class,
                        () -> InstallationLease.acquire(fixture.linux, InstallationLease.Mode.EXCLUSIVE_INSTALL));
            } finally { owner.close(); release.countDown(); starter.join(2000); }
            assertFalse(starter.isAlive()); assertNull(error.get());
        }
    }

    @Test public void noPayloadSkipsHelperInspectionAndRetainsSharedOwnership() throws Exception {
        try (Fixture fixture = new Fixture(legacy())) {
            try (InstallationLease lease = AdapterRefresh.acquire(fixture.files, noPayload(), noInspection(), fixture.logs::add)) {
                assertArrayEquals(legacy(), Files.readAllBytes(fixture.helper));
                assertThrows(InstallationLease.BusyException.class,
                        () -> InstallationLease.acquire(fixture.linux, InstallationLease.Mode.EXCLUSIVE_INSTALL));
            }
        }
    }

    @Test public void symbolicHelperAndDescendantAliasPreserveStartupWithoutFollowing() throws Exception {
        org.junit.Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        try (Fixture fixture = new Fixture(legacy())) {
            Path outside = fixture.files.resolve("user-helper"); Files.move(fixture.helper, outside);
            Files.createSymbolicLink(fixture.helper, outside);
            try (InstallationLease lease = fixture.acquire(fixture.source())) { assertEquals(0, fixture.staged); }
            assertTrue(Files.isSymbolicLink(fixture.helper)); assertArrayEquals(legacy(), Files.readAllBytes(outside));
            Files.delete(fixture.helper);
            Path bin = fixture.helper.getParent(); Path moved = fixture.files.resolve("bin"); Files.move(bin, moved);
            Files.copy(outside, moved.resolve("omamobile-android"));
            Files.createSymbolicLink(bin, moved);
            try (InstallationLease lease = AdapterRefresh.acquire(fixture.files, fixture.source(), noInspection(), fixture.logs::add)) {
                assertTrue(Files.isSymbolicLink(bin));
            }
            assertEquals(0, fixture.staged);
            assertTrue(fixture.logs.stream().anyMatch(message -> message.contains("linked ancestor")));
            try (InstallationLease lease = AdapterRefresh.acquire(fixture.files, noPayload(), noInspection(), fixture.logs::add)) {
                assertArrayEquals(legacy(), Files.readAllBytes(moved.resolve("omamobile-android")));
            }
        }
    }

    @Test public void aliasIntroducedDuringStagingFailsBeforeWritingIntoIt() throws Exception {
        org.junit.Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        try (Fixture fixture = new Fixture(legacy())) {
            AdapterRefresh.Source good = fixture.source();
            Path bin = fixture.helper.getParent(), moved = fixture.files.resolve("original-bin");
            Path outside = Files.createDirectory(fixture.files.resolve("outside-bin"));
            AdapterRefresh.Source changed = new AdapterRefresh.Source() {
                public AdapterRefresh.Desired desired() throws Exception { return good.desired(); }
                public Path stage(Path directory) throws Exception {
                    Path archive = good.stage(directory);
                    Files.move(bin, moved); Files.createSymbolicLink(bin, outside);
                    return archive;
                }
            };
            assertThrows(IOException.class, () -> fixture.acquire(changed));
            assertArrayEquals(legacy(), Files.readAllBytes(moved.resolve("omamobile-android")));
            try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        }
    }

    @Test public void installationAliasesAreRejectedBeforeOpeningLease() throws Exception {
        org.junit.Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        for (String target : List.of("linux", "linux/rootfs")) try (Fixture fixture = new Fixture(legacy())) {
            Path original = fixture.files.resolve(target), moved = fixture.files.resolve("aliased-installation");
            Files.move(original, moved); Files.createSymbolicLink(original, moved);
            assertThrows(IOException.class, () -> fixture.acquire(fixture.source()));
            Path lock = target.equals("linux") ? moved.resolve(".installation-lease") : fixture.linux.resolve(".installation-lease");
            assertFalse(Files.exists(lock)); assertEquals(0, fixture.staged);
        }
    }

    private static AdapterRefresh.Modes noInspection() {
        return new AdapterRefresh.Modes() {
            public int read(Path path) { throw new AssertionError("must not inspect helper through aliases or without payload"); }
            public void write(Path path, int mode) { throw new AssertionError("must not mutate helper"); }
        };
    }

    private static AdapterRefresh.Source noPayload() {
        return new AdapterRefresh.Source() {
            public AdapterRefresh.Desired desired() { return null; }
            public Path stage(Path directory) { throw new AssertionError("must not stage without payload"); }
        };
    }

    private static String hash(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
