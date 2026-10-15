package dev.omamobile.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DisplayConfigTransactionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void write(Path path, String value) throws Exception {
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void previewDoesNotTouchFilesAndApplyCanRevert() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path phone = root.resolve("phone/monitors.lua");
        Path desktop = root.resolve("desktop/monitors.lua");
        Files.createDirectories(phone.getParent());
        Files.createDirectories(desktop.getParent());
        write(phone, "phone-old\n");
        write(desktop, "desktop-old\n");
        Map<Path, String> desired = new LinkedHashMap<>();
        desired.put(phone, "phone-preview\n");
        desired.put(desktop, "desktop-preview\n");

        DisplayConfigTransaction.Preview preview = DisplayConfigTransaction.preview(desired);
        assertEquals("phone-old\n", read(phone));
        var transaction = DisplayConfigTransaction.apply(preview);
        assertEquals("phone-preview\n", read(phone));
        assertEquals("desktop-preview\n", read(desktop));
        assertEquals(DisplayConfigTransaction.RevertResult.REVERTED, transaction.revert());
        assertEquals("phone-old\n", read(phone));
        assertEquals("desktop-old\n", read(desktop));
    }

    @Test public void revertPreservesAUserEditAcrossAllFiles() throws Exception {
        Path root = temporary.getRoot().toPath();
        Path phone = root.resolve("phone/monitors.lua");
        Path desktop = root.resolve("desktop/monitors.lua");
        Files.createDirectories(phone.getParent());
        Files.createDirectories(desktop.getParent());
        write(phone, "phone-old\n");
        write(desktop, "desktop-old\n");
        var transaction = DisplayConfigTransaction.apply(DisplayConfigTransaction.preview(Map.of(
                phone, "phone-preview\n", desktop, "desktop-preview\n")));
        write(desktop, "user-new\n");
        assertEquals(DisplayConfigTransaction.RevertResult.CHANGED, transaction.revert());
        assertEquals("phone-preview\n", read(phone));
        assertEquals("user-new\n", read(desktop));
    }

    @Test public void applySupportsNewFileAndRevertRemovesOnlyItsFile() throws Exception {
        Path path = temporary.getRoot().toPath().resolve("profile/monitors.lua");
        var transaction = DisplayConfigTransaction.apply(
                DisplayConfigTransaction.Preview.single(path, "generated\n"));
        assertEquals("generated\n", read(path));
        assertEquals(DisplayConfigTransaction.RevertResult.REVERTED, transaction.revert());
        assertFalse(Files.exists(path));
    }
}
