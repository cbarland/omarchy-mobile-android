package dev.omamobile.host;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
}
