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
            RootfsImporter importer = new RootfsImporter((path, mode) -> { });
            boolean[] prepared = {false};
            importer.install(new ByteArrayInputStream(archive()), linux, X86_64, QUIET,
                    staging -> prepared[0] = true, RootfsImporter.Layout.ARCH_BOOTSTRAP);
            Path root = linux.resolve("rootfs");
            Path shell = root.resolve("bin/sh");
            Path alias = root.resolve("bin/sh-alias");
            assertTrue(prepared[0]);
            assertTrue(Files.isRegularFile(shell, LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.isSameFile(shell, alias));
            assertFalse(Files.exists(root.resolve("version")));
            assertFalse(Files.exists(root.resolve("pkglist.x86_64.txt")));
        } finally { delete(linux); }
    }

    @Test
    public void truncatedZstdBootstrapNeverRunsPrepareOrPublishesRoot() throws Exception {
        Path linux = Files.createTempDirectory("bootstrap-archive");
        try {
            byte[] valid = archive();
            byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 5);
            RootfsImporter importer = new RootfsImporter((path, mode) -> { });
            boolean[] prepared = {false};
            assertThrows(Exception.class, () -> importer.install(
                    new ByteArrayInputStream(truncated), linux, X86_64, QUIET,
                    staging -> prepared[0] = true, RootfsImporter.Layout.ARCH_BOOTSTRAP));
            assertFalse(prepared[0]);
            assertFalse(Files.exists(linux.resolve("rootfs"), LinkOption.NOFOLLOW_LINKS));
        } finally { delete(linux); }
    }

    @Test public void rejectsEntriesAndHardlinksOutsideBootstrapWrapper() throws Exception {
        for (String name : new String[]{"elsewhere/bin/sh", "root.x86_64/../bin/sh", "root.x86_64//bin/sh"}) {
            assertRejected(archive(name, "root.x86_64/bin/sh"), X86_64);
        }
        assertRejected(archive("root.x86_64/bin/sh", "root.x86_64/../../outside"), X86_64);
        assertRejected(archive("root.x86_64/bin/sh", "outside/bin/sh"), X86_64);
    }

    @Test public void wrongArchitectureNeverPreparesOrPublishes() throws Exception {
        assertRejected(archive(), 183);
    }

    @Test public void metadataMustBeRegularFile() throws Exception {
        for (byte type : new byte[]{TarArchiveEntry.LF_SYMLINK, TarArchiveEntry.LF_LINK, TarArchiveEntry.LF_FIFO}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var zstd = new ZstdOutputStream(bytes); var tar = new TarArchiveOutputStream(zstd)) {
                file(tar, "root.x86_64/bin/sh", minimalX86Elf());
                TarArchiveEntry entry = new TarArchiveEntry("version", type);
                entry.setLinkName("root.x86_64/bin/sh");
                tar.putArchiveEntry(entry); tar.closeArchiveEntry();
            }
            assertRejected(bytes.toByteArray(), X86_64);
        }
    }

    @Test public void cancellationBeforePrepareNeverPublishes() throws Exception {
        Thread.currentThread().interrupt();
        try { assertRejected(archive(), X86_64); }
        finally { Thread.interrupted(); }
    }

    @Test public void corruptGzipTrailerNeverPreparesOrPublishes() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes);
             var tar = new TarArchiveOutputStream(gzip)) {
            file(tar, "bin/sh", minimalX86Elf());
        }
        byte[] corrupt = bytes.toByteArray();
        corrupt[corrupt.length - 8] ^= 1;
        Path linux = Files.createTempDirectory("bootstrap-gzip");
        boolean[] prepared = {false};
        try {
            assertThrows(IOException.class, () -> new RootfsImporter((path, mode) -> { }).install(
                    new ByteArrayInputStream(corrupt), linux, X86_64, QUIET, staging -> prepared[0] = true));
            assertFalse(prepared[0]);
            assertFalse(Files.exists(linux.resolve("rootfs")));
        } finally { delete(linux); }
    }

    private static void assertRejected(byte[] data, int machine) throws Exception {
        Path linux = Files.createTempDirectory("bootstrap-reject");
        boolean[] prepared = {false};
        try {
            assertThrows(Exception.class, () -> new RootfsImporter((path, mode) -> { }).install(
                    new ByteArrayInputStream(data), linux, machine, QUIET,
                    staging -> prepared[0] = true, RootfsImporter.Layout.ARCH_BOOTSTRAP));
            assertFalse(prepared[0]);
            assertFalse(Files.exists(linux.resolve("rootfs")));
            try (var children = Files.list(linux)) { assertEquals(0, children.count()); }
        } finally { delete(linux); }
    }

    private static byte[] archive() throws IOException {
        return archive("root.x86_64/bin/sh", "root.x86_64/bin/sh");
    }

    private static byte[] archive(String shellName, String hardlinkTarget) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(compressed);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            directory(tar, "root.x86_64/");
            directory(tar, "root.x86_64/bin/");
            byte[] elf = minimalX86Elf();
            file(tar, shellName, elf);
            TarArchiveEntry link = new TarArchiveEntry("root.x86_64/bin/sh-alias", TarArchiveEntry.LF_LINK);
            link.setLinkName(hardlinkTarget);
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            file(tar, "pkglist.x86_64.txt", "bash 5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            file(tar, "version", "2026.10.01\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
