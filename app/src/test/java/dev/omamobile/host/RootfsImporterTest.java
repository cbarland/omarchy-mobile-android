package dev.omamobile.host;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class RootfsImporterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private byte[] tar(String path, byte[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry(path, true);
            entry.setSize(contents.length); entry.setMode(0755);
            out.putArchiveEntry(entry); out.write(contents); out.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
    private Path base() throws IOException { return temporary.newFolder().toPath().toAbsolutePath(); }
    private RootfsImporter importer(long limit) { return new RootfsImporter((p, mode) -> {}, limit); }
    private byte[] elf(int machine) {
        byte[] header = new byte[32];
        header[0] = 0x7f; header[1] = 'E'; header[2] = 'L'; header[3] = 'F';
        header[4] = 2; header[5] = 1; header[18] = (byte) machine;
        return header;
    }

    @Test public void wrongArchitectureRollsBackInsteadOfActivating() throws Exception {
        Path base = base();
        assertThrows(IOException.class, () -> importer(1000).install(new ByteArrayInputStream(tar("bin/sh", elf(62))), base, 183, p -> {}));
        assertFalse(Files.exists(base.resolve("rootfs")));
        try (var children = Files.list(base)) { assertEquals(0, children.count()); }
    }

    @Test public void rejectsTraversalAndAbsolutePaths() throws Exception {
        Path base = base();
        for (String path : new String[]{"../outside", "/outside", "C:/outside"}) {
            assertThrows(IOException.class, () -> GuestPaths.archivePath(base, path));
        }
        if (base.getFileSystem().getSeparator().equals("\\")) {
            assertThrows(IOException.class, () -> GuestPaths.archivePath(base, "..\\outside"));
        }
    }

    @Test public void expandedSizeLimitRollsBack() throws Exception {
        Path base = base();
        assertThrows(IOException.class, () -> importer(16).install(new ByteArrayInputStream(tar("bin/sh", elf(183))), base, 183, p -> {}));
        try (var children = Files.list(base)) { assertEquals(0, children.count()); }
    }

    @Test public void successfulImportDoesNotOverwriteAnExistingInstallation() throws Exception {
        Path base = base();
        byte[] contents = tar("bin/sh", elf(183));
        String digest = importer(1000).install(new ByteArrayInputStream(contents), base, 183, p -> {});
        assertEquals(64, digest.length());
        assertArrayEquals(elf(183), Files.readAllBytes(base.resolve("rootfs/bin/sh")));
        assertThrows(IOException.class, () -> importer(1000).install(new ByteArrayInputStream(contents), base, 183, p -> {}));
        assertArrayEquals(elf(183), Files.readAllBytes(base.resolve("rootfs/bin/sh")));
    }

    @Test public void preparationFailureRollsBackBeforeActivation() throws Exception {
        Path base = base();
        assertThrows(IOException.class, () -> importer(1000).install(new ByteArrayInputStream(tar("bin/sh", elf(183))),
                base, 183, p -> {}, staging -> {
                    assertFalse(Files.exists(base.resolve("rootfs")));
                    Files.write(staging.resolve("partial-package"), "unfinished".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    throw new IOException("package install failed");
                }));
        try (var children = Files.list(base)) { assertEquals(0, children.count()); }
    }

    @Test public void preparedFilesActivateTogetherWithTheRootfs() throws Exception {
        Path base = base();
        importer(1000).install(new ByteArrayInputStream(tar("bin/sh", elf(183))), base, 183, p -> {}, staging -> {
            assertFalse(Files.exists(base.resolve("rootfs")));
            Files.write(staging.resolve("prepared"), "ready".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        });
        assertEquals("ready", new String(Files.readAllBytes(base.resolve("rootfs/prepared")), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void userDataExportContainsOnlySupportedUserTreesAndRoundTrips() throws Exception {
        Path root = base();
        Files.createDirectories(root.resolve("root/.config"));
        Files.write(root.resolve("root/.config/settings"), "keep me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("home/alice"));
        Files.write(root.resolve("home/alice/note"), "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createDirectories(root.resolve("usr/bin"));
        Files.write(root.resolve("usr/bin/secret"), "excluded".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        importer(1000).exportUserData(root, archive, message -> {});
        Path restored = base();
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(archive.toByteArray()));
             var tar = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                Path target = restored.resolve(entry.getName()).normalize();
                assertTrue(target.startsWith(restored));
                if (entry.isDirectory()) Files.createDirectories(target);
                else if (entry.isSymbolicLink()) Files.createSymbolicLink(target, java.nio.file.Paths.get(entry.getLinkName()));
                else { Files.createDirectories(target.getParent()); Files.copy(tar, target); }
            }
        }
        assertEquals("keep me", new String(Files.readAllBytes(restored.resolve("root/.config/settings")), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("hello", new String(Files.readAllBytes(restored.resolve("home/alice/note")), java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(Files.exists(restored.resolve("usr")));
    }
}
