package dev.omamobile.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Test;

public final class BootstrapArchiveTest {
    private static final int X86_64 = 62;
    private static final RootfsImporter.Progress QUIET = message -> { };

    @Test
    public void zstdArchBootstrapStripsPrefixAndPreservesHardlink() throws Exception {
        Path linux = Files.createTempDirectory("bootstrap-archive");
        try {
            RootfsImporter importer = new RootfsImporter(path -> { });
            boolean[] prepared = {false};
            importer.install(new ByteArrayInputStream(archive()), linux, X86_64, QUIET,
                    staging -> prepared[0] = true, RootfsImporter.Layout.ARCH_BOOTSTRAP);
            Path root = linux.resolve("rootfs");
            Path shell = root.resolve("bin/sh");
            Path alias = root.resolve("bin/sh-alias");
            assertTrue(prepared[0]);
            assertTrue(Files.isRegularFile(shell, LinkOption.NOFOLLOW_LINKS));
            assertEquals(Files.readAttributes(shell, "unix:ino", LinkOption.NOFOLLOW_LINKS).get("ino"),
                    Files.readAttributes(alias, "unix:ino", LinkOption.NOFOLLOW_LINKS).get("ino"));
        } finally { delete(linux); }
    }

    @Test
    public void truncatedZstdBootstrapNeverRunsPrepareOrPublishesRoot() throws Exception {
        Path linux = Files.createTempDirectory("bootstrap-archive");
        try {
            byte[] valid = archive();
            byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 5);
            RootfsImporter importer = new RootfsImporter(path -> { });
            boolean[] prepared = {false};
            assertThrows(Exception.class, () -> importer.install(
                    new ByteArrayInputStream(truncated), linux, X86_64, QUIET,
                    staging -> prepared[0] = true, RootfsImporter.Layout.ARCH_BOOTSTRAP));
            assertFalse(prepared[0]);
            assertFalse(Files.exists(linux.resolve("rootfs"), LinkOption.NOFOLLOW_LINKS));
        } finally { delete(linux); }
    }

    private static byte[] archive() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(compressed);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            directory(tar, "root.x86_64/");
            directory(tar, "root.x86_64/bin/");
            byte[] elf = minimalX86Elf();
            file(tar, "root.x86_64/bin/sh", elf);
            TarArchiveEntry link = new TarArchiveEntry("root.x86_64/bin/sh-alias", TarArchiveEntry.LF_LINK);
            link.setLinkName("root.x86_64/bin/sh");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            tar.finish();
        }
        return compressed.toByteArray();
    }

    private static void directory(TarArchiveOutputStream tar, String name) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_DIR);
        entry.setMode(0755);
        tar.putArchiveEntry(entry); tar.closeArchiveEntry();
    }

    private static void file(TarArchiveOutputStream tar, String name, byte[] bytes) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_NORMAL);
        entry.setSize(bytes.length); entry.setMode(0755);
        tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry();
    }

    private static byte[] minimalX86Elf() {
        byte[] elf = new byte[64];
        elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        elf[4] = 2; // ELFCLASS64
        elf[5] = 1; // little endian
        elf[6] = 1; // current version
        elf[16] = 2; // ET_EXEC, little endian
        elf[17] = 0;
        elf[18] = (byte) X86_64; elf[19] = 0;
        elf[20] = 1;
        return elf;
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }
}
