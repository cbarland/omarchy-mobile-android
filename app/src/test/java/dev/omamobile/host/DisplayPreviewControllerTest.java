package dev.omamobile.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DisplayPreviewControllerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path monitor, directory;
    private static final String ORIGINAL = "-- Per-profile Wayland output scale; GTK text scale is separate.\n"
            + "local omarchy_monitor_scale = 1\n"
            + "hl.monitor({ output = \"\", mode = \"preferred\", position = \"auto\", scale = omarchy_monitor_scale })\n";
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8); }
    private static void write(Path path, String text) throws IOException { Files.write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private DisplayPreviewController controller() throws Exception {
        if (monitor == null) {
            directory = temporary.getRoot().toPath().resolve("preview");
            monitor = temporary.getRoot().toPath().resolve("desktop/monitors.lua");
            Files.createDirectories(monitor.getParent());
            write(monitor, ORIGINAL);
        }
        return new DisplayPreviewController(monitor, directory);
    }
    @Test public void keepSurvivesNewOwnerAndConsumesJournal() throws Exception {
        var controller = controller();
        controller.preview(125, 1000);
        assertTrue(controller.hasPending());
        assertTrue(read(monitor).contains("scale = 1.25"));
        assertTrue(controller.keep(1001));
        assertFalse(controller().hasPending());
        assertEquals(DisplayPreviewController.RevertResult.NONE, controller().revert());
        assertTrue(read(monitor).contains("scale = 1.25"));
    }
    @Test public void expiryAndLateKeepRestoreOriginalBytes() throws Exception {
        var controller = controller();
        controller.preview(150, 1000);
        assertTrue(controller.hasPending());
        assertFalse(controller().keep(1000 + DisplayPreviewController.TIMEOUT_MS));
        assertFalse(controller.hasPending());
        assertEquals(ORIGINAL, read(monitor));
    }
    @Test public void serviceRestartRevertsEvenBeforeOriginalDeadline() throws Exception {
        controller().preview(150, 1000);
        assertEquals(DisplayPreviewController.RevertResult.REVERTED, controller().revert());
        assertEquals(ORIGINAL, read(monitor));
        assertFalse(controller().hasPending());
    }
    @Test public void unpublishedJournalRecoveryDoesNotRewriteOriginal() throws Exception {
        controller().preview(150, 1000);
        write(monitor, ORIGINAL); // Simulate process death before the live rename.
        assertEquals(DisplayPreviewController.RevertResult.NONE, controller().revert());
        assertEquals(ORIGINAL, read(monitor));
        assertFalse(controller().hasPending());
    }
    @Test public void laterUserEditIsPreservedAndPreviewEnds() throws Exception {
        controller().preview(200, 1000);
        write(monitor, "-- user edit\n");
        assertEquals(DisplayPreviewController.RevertResult.CHANGED, controller().revert());
        assertEquals("-- user edit\n", read(monitor));
        assertFalse(controller().hasPending());
        assertThrows(IOException.class, () -> controller().preview(125, 2000));
    }
    @Test public void secondPreviewCannotReplaceUnconfirmedJournal() throws Exception {
        controller().preview(125, 1000);
        assertThrows(IOException.class, () -> controller().preview(200, 2000));
        controller().revert();
        assertEquals(ORIGINAL, read(monitor));
    }
    @Test public void malformedJournalFailsClosed() throws Exception {
        controller().preview(125, 1000);
        String selected = read(monitor);
        write(directory.resolve("pending.properties"), "version=1\ndeadline=bad\n");
        assertThrows(IOException.class, () -> controller().revert());
        assertEquals(selected, read(monitor));
        assertTrue(controller().hasPending());
    }
    @Test public void symlinkSubstitutionNeverWritesTheTarget() throws Exception {
        controller().preview(125, 1000);
        Path target = temporary.getRoot().toPath().resolve("user.lua");
        write(target, "-- keep target\n");
        Files.delete(monitor);
        try { Files.createSymbolicLink(monitor, target); }
        catch (IOException | UnsupportedOperationException denied) { Assume.assumeNoException(denied); }
        assertEquals(DisplayPreviewController.RevertResult.CHANGED, controller().revert());
        assertEquals("-- keep target\n", read(target));
        assertThrows(IOException.class, () -> controller().preview(150, 2000));
    }

    @Test public void unifiedPreviewTargetsExternalOutputAndKeepsPhoneScale() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("preview");
        Path unified = temporary.getRoot().toPath().resolve("unified/monitors.lua");
        String original = "hl.monitor({ output = \"ANDROID-1\", mode = \"preferred\", position = \"0x0\", scale = 3 })\n"
                + "hl.monitor({ output = \"ANDROID-2\", mode = \"preferred\", position = \"auto\", scale = 1 })\n";
        Files.createDirectories(unified.getParent());
        write(unified, original);
        var preview = new DisplayPreviewController(unified, directory, "unified");
        preview.preview(125, 1000);
        assertTrue(read(unified).contains("ANDROID-1\", mode = \"preferred\", position = \"0x0\", scale = 3"));
        assertTrue(read(unified).contains("ANDROID-2\", mode = \"preferred\", position = \"auto\", scale = 1.25"));
        String journal = read(directory.resolve("pending.properties"));
        assertTrue(journal.contains("profile=unified"));
        assertEquals(DisplayPreviewController.RevertResult.REVERTED, preview.revert());
        assertEquals(original, read(unified));
    }
}
