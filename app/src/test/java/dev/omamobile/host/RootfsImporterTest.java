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
import java.util.ArrayList;
import java.util.List;
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
    private byte[] recoveryTar(String path, byte[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry(path);
            entry.setSize(contents.length); entry.setMode(0600);
            out.putArchiveEntry(entry); out.write(contents); out.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
    private byte[] recoveryTarWithDirectory(String directory, String path, byte[] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry dir = new TarArchiveEntry(directory + "/", TarArchiveEntry.LF_DIR);
            dir.setMode(0750); out.putArchiveEntry(dir); out.closeArchiveEntry();
            TarArchiveEntry entry = new TarArchiveEntry(path);
            entry.setSize(contents.length); entry.setMode(0600);
            out.putArchiveEntry(entry); out.write(contents); out.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
    private byte[] recoveryLinkTar(String path, String target) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry(path, TarArchiveEntry.LF_SYMLINK);
            entry.setLinkName(target);
            out.putArchiveEntry(entry); out.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
    private byte[] safeRecoveryLinksTar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry file = new TarArchiveEntry("root/.config/target");
            byte[] value = "linked".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            file.setSize(value.length); out.putArchiveEntry(file); out.write(value); out.closeArchiveEntry();
            TarArchiveEntry relative = new TarArchiveEntry("root/.config/relative", TarArchiveEntry.LF_SYMLINK);
            relative.setLinkName("target"); out.putArchiveEntry(relative); out.closeArchiveEntry();
            TarArchiveEntry absolute = new TarArchiveEntry("home/alice/absolute", TarArchiveEntry.LF_SYMLINK);
            absolute.setLinkName("/root/.config/target"); out.putArchiveEntry(absolute); out.closeArchiveEntry();
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
        Files.createDirectories(root.resolve("root/Recovered/old/root"));
        Files.write(root.resolve("root/Recovered/old/root/duplicate"), "do not nest".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        assertEquals("do not nest", new String(Files.readAllBytes(restored.resolve("root/Recovered/old/root/duplicate")), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void recoveryPublishesSeparateCopyAndPreservesExistingData() throws Exception {
        Path root = base();
        Files.createDirectories(root.resolve("root"));
        Files.createDirectories(root.resolve("root/.config"));
        Files.write(root.resolve("root/.config/live"), "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<Integer> modes = new ArrayList<>();
        String id = new RootfsImporter((p, mode) -> modes.add(mode), 1000).recoverUserData(new ByteArrayInputStream(
                recoveryTarWithDirectory("root/.config", "root/.config/recovered", "restored".getBytes(java.nio.charset.StandardCharsets.UTF_8))), root, p -> {});
        assertEquals("keep", new String(Files.readAllBytes(root.resolve("root/.config/live")), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("restored", new String(Files.readAllBytes(root.resolve("root/Recovered").resolve(id).resolve("root/.config/recovered")), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(root.resolve("root/Recovered").resolve(id)));
        assertTrue("Explicit directory mode was not restored", modes.contains(0750));
        assertTrue("File mode was not restored", modes.contains(0600));
    }

    @Test public void recoveryPreservesSafeRelativeAndGuestAbsoluteLinksInsideCopy() throws Exception {
        Path root = base();
        Files.createDirectories(root.resolve("root"));
        String id = importer(1000).recoverUserData(new ByteArrayInputStream(safeRecoveryLinksTar()), root, p -> {});
        Path copy = root.resolve("root/Recovered").resolve(id);
        assertTrue(Files.isSymbolicLink(copy.resolve("root/.config/relative")));
        assertTrue(Files.isSymbolicLink(copy.resolve("home/alice/absolute")));
        assertEquals("target", Files.readSymbolicLink(copy.resolve("root/.config/relative")).toString());
        assertEquals("linked", new String(Files.readAllBytes(copy.resolve("home/alice/absolute")), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void recoveryRejectsOutsideNamespaceAndEscapingLinks() throws Exception {
        Path root = base();
        Files.createDirectories(root.resolve("root"));
        assertThrows(IOException.class, () -> importer(1000).recoverUserData(new ByteArrayInputStream(
                recoveryTar("usr/bin/evil", new byte[]{1})), root, p -> {}));
        assertThrows(IOException.class, () -> importer(1000).recoverUserData(new ByteArrayInputStream(
                recoveryLinkTar("root/escape", "../../outside")), root, p -> {}));
        assertFalse(Files.exists(root.resolve("outside")));
        assertThrows(IOException.class, () -> importer(1000).recoverUserData(new ByteArrayInputStream(
                recoveryLinkTar("root/absolute-escape", "//etc/passwd")), root, p -> {}));
        try (var children = Files.list(root.resolve("root/Recovered"))) {
            assertEquals(0, children.count());
        }
    }

    @Test public void recoveryRepresentsExternalLinksAsDanglingInCopy() throws Exception {
        Path root = base();
        Files.createDirectories(root.resolve("root"));
        String id = importer(1000).recoverUserData(new ByteArrayInputStream(
                recoveryLinkTar("root/shared", "/usr/share")), root, p -> {});
        Path link = root.resolve("root/Recovered").resolve(id).resolve("root/shared");
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.readSymbolicLink(link).toString().contains("usr"));
        assertFalse(Files.exists(link));
    }

    @Test public void cancelledRecoveryRemovesPrivateStaging() throws Exception {
        Path root = base();
        Files.createDirectories(root.resolve("root"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> importer(1000).recoverUserData(new ByteArrayInputStream(
                    recoveryTar("home/alice/note", new byte[]{1})), root, p -> {}));
        } finally { Thread.interrupted(); }
        try (var children = Files.list(root.resolve("root/Recovered"))) {
            assertEquals(0, children.count());
        }
    }
}
