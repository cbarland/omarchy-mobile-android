package dev.omamobile.host;

import android.net.Uri;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in end-to-end export proof on a prepared disposable emulator. */
public class LinuxDataExportTest {
    @Test public void exportsUserTreesAndPreservesInstalledData() throws Exception {
        Assume.assumeTrue("Prepared disposable Linux required", "true".equals(
                InstrumentationRegistry.getArguments().getString("export")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var runtime = HostRuntime.get(context);
        assertTrue("Linux installation required", runtime.installed());
        Path fixture = runtime.root.resolve("root/.omamobile-export-fixture-" + SystemClock.uptimeMillis());
        Path destination = context.getFilesDir().toPath().resolve("export-result.tar.gz");
        Files.createDirectories(fixture);
        Path data = fixture.resolve("settings");
        Path link = fixture.resolve("settings-link");
        Files.write(data, "preserve me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HostRuntime.chmod(data, 0600);
        Files.createSymbolicLink(link, java.nio.file.Paths.get("settings"));
        try {
            assertTrue("Null destination must be refused", !runtime.exportData(null, () -> { }));
            assertTrue("Export did not start", runtime.exportData(Uri.fromFile(destination.toFile()), () -> { }));
            waitIdle(runtime, 30000);
            assertFalse(runtime.busy());
            assertTrue(runtime.output(), Files.isRegularFile(destination));
            assertTrue("Export phase was not persisted", Files.readAllBytes(runtime.linux.resolve("install.log")).length > 0);
            Map<String, byte[]> files = new HashMap<>();
            Map<String, String> links = new HashMap<>();
            try (InputStream input = Files.newInputStream(destination);
                 GZIPInputStream gzip = new GZIPInputStream(input);
                 TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    assertTrue("Unexpected system path", entry.getName().startsWith("root/") || entry.getName().startsWith("home/"));
                    if (entry.isSymbolicLink()) links.put(entry.getName(), entry.getLinkName());
                    else if (entry.isFile()) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); tar.transferTo(bytes); files.put(entry.getName(), bytes.toByteArray()); }
                    if (entry.getName().endsWith("/settings")) assertEquals(0600, entry.getMode() & 0777);
                }
            }
            String prefix = "root/" + fixture.getFileName().toString() + "/";
            assertArrayEquals("preserve me".getBytes(java.nio.charset.StandardCharsets.UTF_8), files.get(prefix + "settings"));
            assertEquals("settings", links.get(prefix + "settings-link"));
            assertFalse(files.keySet().stream().anyMatch(name -> name.startsWith("usr/")));
            assertEquals("preserve me", new String(Files.readAllBytes(data), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            runtime.stop();
            waitIdle(runtime, 30000);
            GuestPaths.deleteTree(fixture);
            Files.deleteIfExists(destination);
        }
    }

    @Test public void cancellationRemovesPrivatePartialArchive() throws Exception {
        Assume.assumeTrue("Prepared disposable Linux required", "true".equals(
                InstrumentationRegistry.getArguments().getString("export")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var runtime = HostRuntime.get(context);
        assertTrue(runtime.installed());
        Path fixture = runtime.root.resolve("root/.omamobile-export-cancel-" + SystemClock.uptimeMillis());
        Path destination = context.getFilesDir().toPath().resolve("export-cancel.tar.gz");
        Files.createDirectories(fixture);
        byte[] block = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(fixture.resolve("large"), StandardOpenOption.CREATE_NEW)) {
            for (int i = 0; i < 64; i++) out.write(block);
        }
        try {
            assertTrue(runtime.exportData(Uri.fromFile(destination.toFile()), () -> { }));
            while (!runtime.busy()) SystemClock.sleep(10);
            runtime.stop();
            waitIdle(runtime, 30000);
            assertFalse(runtime.busy());
            try (var children = Files.list(runtime.linux)) {
                assertFalse("Private temporary export survived cancellation",
                        children.anyMatch(path -> path.getFileName().toString().startsWith("export-") && path.toString().endsWith(".tmp")));
            }
        } finally {
            GuestPaths.deleteTree(fixture);
            Files.deleteIfExists(destination);
        }
    }

    private static void waitIdle(HostRuntime runtime, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (runtime.busy() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertFalse("Export timed out: " + runtime.output(), runtime.busy());
    }
}
