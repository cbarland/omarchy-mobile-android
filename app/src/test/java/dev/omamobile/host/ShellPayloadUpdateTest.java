package dev.omamobile.host;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class ShellPayloadUpdateTest {
    private Path directory, root, staged, metadata;
    private static final byte[] NEW_METADATA = "version-two".getBytes(StandardCharsets.UTF_8);
    private final ShellPayloadUpdate.Filesystem fs = new ShellPayloadUpdate.Filesystem() {
        @Override public int mode(Path path) { return 0644; }
        @Override public void mode(Path path, int mode) { assertEquals(0644, mode); }
        @Override public void replace(Path from, Path to) throws IOException {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    };
    @Before public void prepare() throws Exception {
        directory = Files.createTempDirectory("payload-update-test-");
        root = Files.createDirectories(directory.resolve("linux/rootfs"));
        staged = Files.createDirectory(directory.resolve("new"));
        metadata = root.resolve(ShellPayloadUpdate.MANIFEST);
        write(metadata, "version-one");
        write(root.resolve("root/.config/omamobile/home.json"), "my-widget-layout");
    }
    @After public void remove() throws Exception { GuestPaths.deleteTree(directory); }
    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent()); Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }
    private static String text(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private ShellPayloadUpdate.Member member(Path base, String path, String value) throws Exception {
        Path file = base.resolve(path); write(file, value);
        return new ShellPayloadUpdate.Member(ShellPayloadUpdate.hash(file), Files.size(file), 0644);
    }
    private int apply(Map<String, ShellPayloadUpdate.Member> old, Map<String, ShellPayloadUpdate.Member> next,
                      ShellPayloadUpdate.Filesystem filesystem) throws Exception {
        return ShellPayloadUpdate.apply(root, staged, old, next, NEW_METADATA, filesystem);
    }
    private void preservedLayout() throws Exception { assertEquals("my-widget-layout", text(root.resolve("root/.config/omamobile/home.json"))); }
    private static final class Crash extends Error { }
    private ShellPayloadUpdate.Filesystem crashAt(Path target, boolean after) {
        return new ShellPayloadUpdate.Filesystem() {
            @Override public int mode(Path path) throws IOException { return fs.mode(path); }
            @Override public void mode(Path path, int mode) throws IOException { fs.mode(path, mode); }
            @Override public void replace(Path from, Path to) throws IOException {
                if (to.equals(target) && !after) throw new Crash();
                fs.replace(from, to);
                if (to.equals(target) && after) throw new Crash();
            }
        };
    }
    @Test public void updatesStockAddsFilesAndKeepsUserConfiguration() throws Exception {
        var old = Map.of("usr/bin/one", member(root, "usr/bin/one", "old"));
        var next = Map.of("usr/bin/one", member(staged, "usr/bin/one", "new"),
                "usr/bin/two", member(staged, "usr/bin/two", "added"));
        assertEquals(2, apply(old, next, fs));
        assertEquals("new", text(root.resolve("usr/bin/one")));
        assertEquals("added", text(root.resolve("usr/bin/two")));
        preservedLayout(); assertFalse(Files.exists(root.getParent().resolve(ShellPayloadUpdate.TRANSACTION)));
        assertEquals(0, apply(next, next, fs));
    }
    @Test public void checksAllConflictsBeforeChangingAnyStockFile() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old-a"),
                "usr/bin/b", member(root, "usr/bin/b", "old-b"));
        var next = Map.of("usr/bin/a", member(staged, "usr/bin/a", "new-a"),
                "usr/bin/b", member(staged, "usr/bin/b", "new-b"));
        write(root.resolve("usr/bin/b"), "my-custom-command");
        assertThrows(IOException.class, () -> apply(old, next, fs));
        assertEquals("old-a", text(root.resolve("usr/bin/a")));
        assertEquals("my-custom-command", text(root.resolve("usr/bin/b"))); preservedLayout();
    }
    @Test public void unchangedStockMetadataPreservesCustomizedContent() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "stock"));
        write(root.resolve("usr/bin/a"), "my-custom-command");
        assertEquals(0, apply(old, old, fs));
        assertEquals("my-custom-command", text(root.resolve("usr/bin/a")));
    }
    @Test public void interruptedReplacementRollsBackThenCanBeRetried() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old"));
        var next = Map.of("usr/bin/a", member(staged, "usr/bin/a", "new"));
        assertThrows(Crash.class, () -> apply(old, next, crashAt(root.resolve("usr/bin/a"), true)));
        assertEquals("new", text(root.resolve("usr/bin/a")));
        ShellPayloadUpdate.recover(root, fs);
        assertEquals("old", text(root.resolve("usr/bin/a")));
        assertEquals("version-one", text(metadata));
        assertEquals(1, apply(old, next, fs)); preservedLayout();
    }
    @Test public void newerEditsBlockRollbackWithoutBeingOverwritten() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old"));
        var next = Map.of("usr/bin/a", member(staged, "usr/bin/a", "new"));
        assertThrows(Crash.class, () -> apply(old, next, crashAt(root.resolve("usr/bin/a"), true)));
        write(root.resolve("usr/bin/a"), "newer-user-edit");
        assertThrows(IOException.class, () -> ShellPayloadUpdate.recover(root, fs));
        assertEquals("newer-user-edit", text(root.resolve("usr/bin/a")));
        assertTrue(Files.exists(root.getParent().resolve(ShellPayloadUpdate.TRANSACTION))); preservedLayout();
    }
    @Test public void interruptedRemovalAndAdditionAreBothReversible() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old"));
        var next = Map.of("usr/bin/b", member(staged, "usr/bin/b", "new"));
        assertThrows(Crash.class, () -> apply(old, next, crashAt(metadata, false)));
        assertFalse(Files.exists(root.resolve("usr/bin/a")));
        ShellPayloadUpdate.recover(root, fs);
        assertEquals("old", text(root.resolve("usr/bin/a")));
        assertFalse(Files.exists(root.resolve("usr/bin/b"))); preservedLayout();
    }
    @Test public void committedManifestSurvivesInterruptedJournalCleanup() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old"));
        var next = Map.of("usr/bin/a", member(staged, "usr/bin/a", "new"));
        assertThrows(Crash.class, () -> apply(old, next, crashAt(metadata, true)));
        ShellPayloadUpdate.recover(root, fs);
        assertEquals("new", text(root.resolve("usr/bin/a")));
        assertEquals("version-two", text(metadata));
        assertFalse(Files.exists(root.getParent().resolve(ShellPayloadUpdate.TRANSACTION)));
    }
    @Test public void traversalNeverWritesOutsideTheLinuxRoot() throws Exception {
        var member = member(staged, "usr/bin/a", "new");
        assertThrows(IOException.class, () -> apply(Map.of(), Map.of("../escape", member), fs));
        assertFalse(Files.exists(root.getParent().resolve("escape"))); preservedLayout();
    }
    @Test public void sameVersionDefaultSeedRollsBackIfInterrupted() throws Exception {
        String path = "root/.local/share/omamobile/profiles/unified.json";
        var next = Map.of(path, member(staged, path, "default-profile"));
        assertThrows(Crash.class, () -> ShellPayloadUpdate.apply(root, staged, Map.of(), next,
                "version-one".getBytes(StandardCharsets.UTF_8), crashAt(root.resolve(path), true)));
        ShellPayloadUpdate.recover(root, fs);
        assertFalse(Files.exists(root.resolve(path))); preservedLayout();
    }
    @Test public void cleanupInterruptionCannotLeaveAnActiveHalfDeletedJournal() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old"));
        var next = Map.of("usr/bin/a", member(staged, "usr/bin/a", "new"));
        ShellPayloadUpdate.Filesystem crash = new ShellPayloadUpdate.Filesystem() {
            @Override public int mode(Path path) throws IOException { return fs.mode(path); }
            @Override public void mode(Path path, int mode) throws IOException { fs.mode(path, mode); }
            @Override public void replace(Path from, Path to) throws IOException {
                fs.replace(from, to);
                if (to.getFileName().toString().startsWith("payload-finished-")) throw new Crash();
            }
        };
        assertThrows(Crash.class, () -> apply(old, next, crash));
        assertFalse(Files.exists(root.getParent().resolve(ShellPayloadUpdate.TRANSACTION)));
        ShellPayloadUpdate.recover(root, fs);
        assertEquals("new", text(root.resolve("usr/bin/a")));
        assertEquals("version-two", text(metadata));
    }
    @Test public void cancellationRollsBackAndPreservesTheInterruptSignal() throws Exception {
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old"));
        var next = Map.of("usr/bin/a", member(staged, "usr/bin/a", "new"));
        ShellPayloadUpdate.Filesystem cancel = new ShellPayloadUpdate.Filesystem() {
            @Override public int mode(Path path) throws IOException { return fs.mode(path); }
            @Override public void mode(Path path, int mode) throws IOException { fs.mode(path, mode); }
            @Override public void replace(Path from, Path to) throws IOException {
                fs.replace(from, to);
                if (to.equals(root.resolve("usr/bin/a"))) Thread.currentThread().interrupt();
            }
        };
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> apply(old, next, cancel));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals("old", text(root.resolve("usr/bin/a")));
        assertEquals("version-one", text(metadata)); preservedLayout();
    }
    @Test public void symlinkDestinationIsPreserved() throws Exception {
        org.junit.Assume.assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        var old = Map.of("usr/bin/a", member(root, "usr/bin/a", "old"));
        var next = Map.of("usr/bin/a", member(staged, "usr/bin/a", "new"));
        Path outside = directory.resolve("user-data"); write(outside, "keep");
        Files.delete(root.resolve("usr/bin/a")); Files.createSymbolicLink(root.resolve("usr/bin/a"), outside);
        assertThrows(IOException.class, () -> apply(old, next, fs));
        assertEquals("keep", text(outside)); assertTrue(Files.isSymbolicLink(root.resolve("usr/bin/a")));
    }
}
