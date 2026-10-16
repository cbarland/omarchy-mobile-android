package dev.omamobile.host;

import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessCleanupTest {
    @Before public void optIn() {
        org.junit.Assume.assumeTrue("Explicit -e processCleanup true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("processCleanup")));
    }

    @Test public void gracefulExitIsObservedWithoutRetentionWarning() throws Exception {
        Process child = new ProcessBuilder("/system/bin/sh", "-c", "exit 0").start();
        assertTrue(child.waitFor(2, TimeUnit.SECONDS));
        AtomicBoolean warned = new AtomicBoolean();
        Thread.currentThread().interrupt();
        ProcessCleanup.stopAndWait(child, () -> warned.set(true));
        assertTrue(Thread.interrupted());
        assertFalse(warned.get()); assertFalse(child.isAlive()); assertEquals(0, child.exitValue());
    }

    @Test public void importerRollbackAndExclusiveLeaseWaitForResistantChild() throws Exception {
        retainsOwnership(InstallationLease.Mode.EXCLUSIVE_INSTALL, true);
    }

    @Test public void ordinaryCommandSharedLeaseWaitsForResistantChild() throws Exception {
        retainsOwnership(InstallationLease.Mode.SHARED_USE, false);
    }

    private void retainsOwnership(InstallationLease.Mode mode, boolean transaction) throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path directory = Files.createDirectory(context.getFilesDir().toPath().resolve("cleanup-" + UUID.randomUUID()));
        Path evidence = context.getCacheDir().toPath().resolve("process-ownership-proof").resolve(directory.getFileName());
        Files.createDirectories(evidence);
        Path linux = Files.createDirectory(directory.resolve("linux"));
        Path fifo = directory.resolve("hold"); Os.mkfifo(fifo.toString(), 0600);
        FileDescriptor hold = Os.open(fifo.toString(), OsConstants.O_RDWR | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
        Path script = directory.resolve("fixture.sh");
        Files.write(script, ("printf '%s\\n' \"$$\" > '" + directory.resolve("pid") + "'\n"
                + "cat /proc/$$/stat > '" + directory.resolve("stat") + "'\n"
                + "trap '' TERM\nprintf ready > '" + directory.resolve("ready") + "'\n"
                + "read ignored < '" + fifo + "'\n").getBytes(StandardCharsets.UTF_8));
        AtomicReference<Process> child = new AtomicReference<>();
        AtomicReference<Path> staging = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean restoredInterrupt = new AtomicBoolean();
        CountDownLatch retaining = new CountDownLatch(1);
        JSONObject result = new JSONObject().put("mode", mode.toString()).put("realImporter", transaction);
        Thread worker = new Thread(() -> {
            try (InstallationLease ignored = InstallationLease.acquire(linux, mode)) {
                RootfsImporter.Prepare prepare = path -> {
                    staging.set(path);
                    Process process = new ProcessBuilder("/system/bin/sh", script.toString()).redirectErrorStream(true).start();
                    child.set(process);
                    long end = SystemClock.elapsedRealtime() + 3000;
                    while (!Files.exists(directory.resolve("ready")) && process.isAlive() && SystemClock.elapsedRealtime() < end)
                        Thread.sleep(10);
                    if (!Files.exists(directory.resolve("ready"))) throw new IOException("Fixture not ready");
                    try {
                        process.destroy(); // Startup-cancel closes stdout before the first read.
                        process.getInputStream().read();
                        throw new AssertionError("Expected closed stdout");
                    } catch (IOException expected) {
                        throw new IOException("Controlled startup cancellation", expected);
                    } finally { ProcessCleanup.stopAndWait(process, retaining::countDown); }
                };
                if (transaction) new RootfsImporter((path, permissions) -> {}, 1024).install(
                        new ByteArrayInputStream(archive()), linux, 62, message -> {}, prepare);
                else prepare.run(Files.createDirectory(linux.resolve("working")));
                failure.set(new AssertionError("Cancelled preparation unexpectedly succeeded"));
            } catch (IOException expected) {
                if (!expected.getMessage().equals("Controlled startup cancellation")) failure.set(expected);
            } catch (Throwable unexpected) { failure.set(unexpected); }
            finally { restoredInterrupt.set(Thread.currentThread().isInterrupted()); }
        }, "ProcessCleanupTest-owner");
        worker.start();
        try {
            assertTrue("Did not reach retained-ownership wait", retaining.await(6, TimeUnit.SECONDS));
            assertTrue(worker.isAlive()); assertTrue(child.get().isAlive()); assertTrue(Files.isDirectory(staging.get()));
            assertBusy(linux);
            worker.interrupt();
            // A real timed join checks interruption cannot let cleanup/lease/staging escape.
            worker.join(200);
            assertTrue(worker.isAlive()); assertTrue(child.get().isAlive()); assertTrue(Files.isDirectory(staging.get()));
            assertBusy(linux);
            result.put("retainedBeyondSoftDeadline", true).put("retainedAfterInterrupt", true)
                    .put("stagingExistedWhileChildAlive", true);
            killOwned(directory, child.get());
            worker.join(3000); assertFalse(worker.isAlive()); assertNull(failure.get());
            assertTrue("Cleanup did not restore interrupt", restoredInterrupt.get());
            if (transaction) { assertFalse(Files.exists(staging.get())); assertFalse(Files.exists(linux.resolve("rootfs"))); }
            try (InstallationLease replacement = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
                result.put("exclusiveAvailableAfterExit", true);
            }
            result.put("pid", Integer.parseInt(read(directory.resolve("pid")).trim()))
                    .put("starttime", fields(read(directory.resolve("stat")))[19])
                    .put("exit", child.get().exitValue()).put("interruptRestored", restoredInterrupt.get())
                    .put("importerStagingRemovedAfterExit", transaction && !Files.exists(staging.get()));
            Files.write(evidence.resolve("result.json"), result.toString(2).getBytes(StandardCharsets.UTF_8));
        } finally {
            try {
                if (child.get() != null && child.get().isAlive()) killOwned(directory, child.get());
                worker.interrupt(); worker.join(3000); assertFalse("Worker survived cleanup", worker.isAlive());
            } finally {
                Os.close(hold);
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
                }
            }
        }
    }

    private static void assertBusy(Path linux) {
        assertThrows(IOException.class, () -> InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL));
        assertThrows(IOException.class, () -> InstallationLease.acquire(linux, InstallationLease.Mode.SHARED_USE));
    }
    private static void killOwned(Path directory, Process child) throws Exception {
        int pid = Integer.parseInt(read(directory.resolve("pid")).trim());
        String start = fields(read(directory.resolve("stat")))[19];
        assertTrue(child.isAlive());
        assertEquals(start, fields(read(Path.of("/proc/" + pid + "/stat")))[19]);
        Os.kill(pid, OsConstants.SIGKILL);
        assertTrue(child.waitFor(2, TimeUnit.SECONDS));
    }
    private static byte[] archive() throws IOException {
        byte[] elf = new byte[32]; elf[0] = 0x7f; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
        elf[4] = 2; elf[5] = 1; elf[18] = 62;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(bytes)) {
            TarArchiveEntry entry = new TarArchiveEntry("bin/sh"); entry.setMode(0755); entry.setSize(elf.length);
            out.putArchiveEntry(entry); out.write(elf); out.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static String[] fields(String stat) { return stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+"); }
}
