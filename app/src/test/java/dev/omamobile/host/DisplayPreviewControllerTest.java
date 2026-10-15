package dev.omamobile.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DisplayPreviewControllerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test public void previewKeepAndReopenPreserveTheChosenScale() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path monitor = root.resolve("desktop/monitors.lua");
        Files.createDirectories(monitor.getParent());
        Files.write(monitor, ("-- Per-profile Wayland output scale; GTK text scale is separate.\n"
                + "local omarchy_monitor_scale = 1\n").getBytes(StandardCharsets.UTF_8));
        DisplayPreviewController controller = new DisplayPreviewController(monitor, root.resolve("preview"));
        controller.preview(125);
        assertTrue(controller.hasPending());
        assertTrue(read(monitor).contains("omarchy_monitor_scale = 1.25"));
        controller.keep();
        DisplayPreviewController reopened = new DisplayPreviewController(monitor, root.resolve("preview"));
        assertFalse(reopened.hasPending());
        assertTrue(read(monitor).contains("omarchy_monitor_scale = 1.25"));
    }

    @Test public void expiryRevertsAfterControllerRecreation() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path monitor = root.resolve("desktop/monitors.lua");
        Files.createDirectories(monitor.getParent());
        Files.write(monitor, ("-- Per-profile Wayland output scale; GTK text scale is separate.\n"
                + "local omarchy_monitor_scale = 1\n").getBytes(StandardCharsets.UTF_8));
        DisplayPreviewController controller = new DisplayPreviewController(monitor, root.resolve("preview"));
        controller.preview(150);
        DisplayPreviewController reopened = new DisplayPreviewController(monitor, root.resolve("preview"));
        reopened.recoverExpiredAt(System.currentTimeMillis() + DisplayPreviewController.TIMEOUT_MS + 1);
        assertFalse(reopened.hasPending());
        assertTrue(read(monitor).contains("omarchy_monitor_scale = 1\n"));
    }

    @Test public void userEditBlocksRevertAndCustomConfigIsRejected() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path monitor = root.resolve("desktop/monitors.lua");
        Files.createDirectories(monitor.getParent());
        Files.write(monitor, ("-- Per-profile Wayland output scale; GTK text scale is separate.\n"
                + "local omarchy_monitor_scale = 1\n").getBytes(StandardCharsets.UTF_8));
        DisplayPreviewController controller = new DisplayPreviewController(monitor, root.resolve("preview"));
        controller.preview(200);
        Files.write(monitor, "-- user edit\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(DisplayConfigTransaction.RevertResult.CHANGED, controller.revert());
        assertEquals("-- user edit\n", read(monitor));
        Files.write(monitor, "hl.monitor({ output = \"DP-1\" })\n".getBytes(StandardCharsets.UTF_8));
        try {
            controller.preview(125);
            fail("custom monitor config must be rejected");
        } catch (java.io.IOException expected) { }
    }
}
