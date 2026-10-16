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

/** Opt-in end-to-end export proof on a prepared disposable emulator.
 * The file URI keeps this test offline; SAF picker grants are a separate UI check. */
public class LinuxDataExportTest {
    @Test public void exportsUserTreesAndPreservesInstalledData() throws Exception {
        Assume.assumeTrue("Prepared disposable Linux required", "true".equals(
                InstrumentationRegistry.getArguments().getString("export")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var runtime = HostRuntime.get(context);
        assertTrue("Linux installation required", runtime.installed());
        Path fixture = runtime.root.resolve("root/.omamobile-export-fixture-" + SystemClock.uptimeMillis());
        Path destination = context.getFilesDir().toPath().resolve("export-result.tar.gz");
        Path abandoned = runtime.linux.resolve("export-01234567-89ab-cdef-0123-456789abcdef.tmp");
        Path unrelated = runtime.linux.resolve("export-keep.txt");
        Files.write(abandoned, "abandoned archive".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(unrelated, "leave me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.createDirectories(fixture);
        Path data = fixture.resolve("settings");
        Path link = fixture.resolve("settings-link");
        Files.write(data, "preserve me".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HostRuntime.chmod(data, 0600);
        Files.createSymbolicLink(link, java.nio.file.Paths.get("settings"));
        try {
            assertTrue("Null destination must be refused", !runtime.exportData(null, () -> { }));
            assertTrue("Export did not start", runtime.exportData(Uri.fromFile(destination.toFile()), () -> { }));
            assertFalse("Abandoned private export was not reclaimed", Files.exists(abandoned, java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertTrue("Unrelated export file was removed", Files.exists(unrelated, java.nio.file.LinkOption.NOFOLLOW_LINKS));
            waitIdle(runtime, 30000);
            assertFalse(runtime.busy());
            assertTrue(runtime.output(), Files.isRegularFile(destination));
            assertTrue("Export phase was not persisted", Files.readAllBytes(runtime.linux.resolve("install.log")).length > 0);
            String prefix = "root/" + fixture.getFileName().toString() + "/";
            Map<String, byte[]> files = new HashMap<>();
            Map<String, String> links = new HashMap<>();
            Map<String, Integer> modes = new HashMap<>();
            try (InputStream input = Files.newInputStream(destination);
                 GZIPInputStream gzip = new GZIPInputStream(input);
                 TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    assertTrue("Unexpected system path", entry.getName().startsWith("root/") || entry.getName().startsWith("home/"));
                    if (entry.getName().startsWith(prefix)) {
                        modes.put(entry.getName(), entry.getMode() & 0777);
                        if (entry.isSymbolicLink()) links.put(entry.getName(), entry.getLinkName());
                        else if (entry.isFile()) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); tar.transferTo(bytes); files.put(entry.getName(), bytes.toByteArray()); }
                    }
                }
            }
            assertArrayEquals("preserve me".getBytes(java.nio.charset.StandardCharsets.UTF_8), files.get(prefix + "settings"));
            assertEquals("settings", links.get(prefix + "settings-link"));
            assertEquals(Integer.valueOf(0600), modes.get(prefix + "settings"));
            assertFalse(files.keySet().stream().anyMatch(name -> name.startsWith("usr/")));
            assertEquals("preserve me", new String(Files.readAllBytes(data), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            runtime.stop();
            waitIdle(runtime, 30000);
            GuestPaths.deleteTree(fixture);
            Files.deleteIfExists(destination);
            Files.deleteIfExists(unrelated);
        }
    }

    @Test public void rejectedInvalidExportStopsItsIdleForegroundService() throws Exception {
        Assume.assumeTrue("Prepared disposable Linux required", "true".equals(
                InstrumentationRegistry.getArguments().getString("export")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        assertTrue(HostRuntime.get(context).installed());
        instrumentation.runOnMainSync(() -> context.startForegroundService(
                new android.content.Intent(context, LinuxService.class).putExtra("exportData", true)));
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean running;
        do {
            running = context.getSystemService(android.app.ActivityManager.class).getRunningServices(100).stream()
                    .anyMatch(service -> service.service.getClassName().equals(LinuxService.class.getName()));
            if (running) SystemClock.sleep(50);
        } while (running && SystemClock.uptimeMillis() < deadline);
        assertFalse("Invalid export request left an idle foreground service", running);
    }

    @Test public void rejectedInvalidRecoveryStopsItsIdleForegroundService() throws Exception {
        Assume.assumeTrue("Prepared disposable Linux required", "true".equals(
                InstrumentationRegistry.getArguments().getString("recovery")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        assertTrue(HostRuntime.get(context).installed());
        instrumentation.runOnMainSync(() -> context.startForegroundService(
                new android.content.Intent(context, LinuxService.class).putExtra("recoverData", true)));
        long deadline = SystemClock.uptimeMillis() + 10000;
        boolean running;
        do {
            running = context.getSystemService(android.app.ActivityManager.class).getRunningServices(100).stream()
                    .anyMatch(service -> service.service.getClassName().equals(LinuxService.class.getName()));
            if (running) SystemClock.sleep(50);
        } while (running && SystemClock.uptimeMillis() < deadline);
        assertFalse("Invalid recovery request left an idle foreground service", running);
    }

    @Test public void recoveryCreatesSeparateCopyAndKeepsLiveData() throws Exception {
        Assume.assumeTrue("Prepared disposable Linux required", "true".equals(
                InstrumentationRegistry.getArguments().getString("recovery")));
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        var runtime = HostRuntime.get(context);
        assertTrue(runtime.installed());
        Path fixture = runtime.root.resolve("root/.omamobile-recovery-fixture-" + SystemClock.uptimeMillis());
        Path archive = context.getFilesDir().toPath().resolve("recovery-result.tar.gz");
        Files.createDirectories(fixture);
        Files.write(fixture.resolve("settings"), "recovery proof".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path pluginLink = fixture.resolve("plugins");
        Files.createSymbolicLink(pluginLink, java.nio.file.Paths.get("/usr/share/omamobile/shell/plugins"));
        try {
            try (var out = Files.newOutputStream(archive)) {
                HostRuntime.importer().exportUserData(runtime.root, out, message -> { });
            }
            Path abandoned = runtime.root.resolve("Recovered/.01234567-89ab-cdef-0123-456789abcdef.staging");
            Files.createDirectories(abandoned);
            Files.write(abandoned.resolve("partial"), new byte[]{1});
            assertTrue(runtime.recoverData(Uri.fromFile(archive.toFile()), () -> { }));
            waitIdle(runtime, 30000);
            assertFalse("Abandoned recovery staging survived", Files.exists(abandoned, java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertEquals("recovery proof", new String(Files.readAllBytes(fixture.resolve("settings")), java.nio.charset.StandardCharsets.UTF_8));
            Path copy;
            try (var copies = Files.list(runtime.root.resolve("Recovered"))) {
                copy = copies.filter(Files::isDirectory).findFirst().orElseThrow();
            }
            assertEquals("recovery proof", new String(Files.readAllBytes(copy.resolve("root").resolve(fixture.getFileName())), java.nio.charset.StandardCharsets.UTF_8));
            Path recoveredLink = copy.resolve("root").resolve(fixture.getFileName()).resolve("plugins");
            assertTrue("External plugin link was not retained", Files.isSymbolicLink(recoveredLink));
            assertTrue(Files.readSymbolicLink(recoveredLink).toString().contains("usr/share"));
            assertFalse("Recovered plugin link reached live root", Files.exists(recoveredLink));
            assertTrue("Recovery phase was not persisted", new String(Files.readAllBytes(runtime.linux.resolve("install.log")), java.nio.charset.StandardCharsets.UTF_8).contains("recovery"));
        } finally {
            runtime.stop();
            waitIdle(runtime, 30000);
            GuestPaths.deleteTree(fixture);
            try (var copies = Files.list(runtime.root.resolve("Recovered"))) {
                copies.filter(Files::isDirectory).forEach(path -> { try { GuestPaths.deleteTree(path); } catch (IOException error) { throw new RuntimeException(error); } });
            }
            Files.deleteIfExists(archive);
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
