package dev.omamobile.host;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class InstallationLeaseTest {
    @Test public void sharedOwnersBlockExclusiveUntilBothRelease() throws Exception {
        Path linux = Files.createTempDirectory("lease-");
        Process first = holder(linux, "SHARED_USE");
        Process second = holder(linux, "SHARED_USE");
        try {
            assertThrows(java.io.IOException.class, () -> InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL));
            stop(first);
            assertThrows(java.io.IOException.class, () -> InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL));
            stop(second);
            try (InstallationLease ignored = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) { assertNotNull(ignored); }
        } finally { cleanup(first, second); }
    }

    @Test public void exclusiveOwnerBlocksSharedUntilRelease() throws Exception {
        Path linux = Files.createTempDirectory("lease-");
        try (InstallationLease ignored = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
            Process denied = launch(linux, "SHARED_USE");
            assertTrue(denied.waitFor(5, TimeUnit.SECONDS));
            assertTrue("denied child unexpectedly succeeded", denied.exitValue() != 0);
            String output = new String(denied.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue("missing busy diagnostic: " + output, output.contains("Linux installation is busy"));
        }
        Process shared = holder(linux, "SHARED_USE");
        cleanup(shared);
    }

    @Test public void processDeathReleasesSharedLease() throws Exception {
        Path linux = Files.createTempDirectory("lease-");
        Process child = holder(linux, "SHARED_USE");
        child.destroyForcibly();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        try (InstallationLease ignored = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) { assertNotNull(ignored); }
    }

    @Test public void failedChannelOpenDoesNotPoisonCanonicalOwner() throws Exception {
        Path linux = Files.createTempDirectory("lease-");
        Files.createDirectory(linux.resolve(".installation-lease"));
        assertThrows(java.io.IOException.class, () -> InstallationLease.acquire(linux, InstallationLease.Mode.SHARED_USE));
        Files.delete(linux.resolve(".installation-lease"));
        InstallationLease lease = InstallationLease.acquire(linux, InstallationLease.Mode.SHARED_USE);
        assertThrows(java.io.IOException.class, () -> InstallationLease.acquire(linux.resolve("."), InstallationLease.Mode.EXCLUSIVE_INSTALL));
        lease.close();
        try (InstallationLease replacement = InstallationLease.acquire(linux, InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
            assertNotNull(replacement);
            lease.close();
            assertThrows(java.io.IOException.class, () -> InstallationLease.acquire(linux.resolve("."), InstallationLease.Mode.SHARED_USE));
        }
    }

    private static Process holder(Path linux, String mode) throws Exception {
        Process process = launch(linux, mode);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (process.getInputStream().available() == 0 && process.isAlive()
                && System.nanoTime() < deadline) Thread.sleep(10);
        if (process.getInputStream().available() == 0 || process.getInputStream().read() != 'R') {
            process.destroyForcibly();
            throw new AssertionError("lease holder did not start");
        }
        return process;
    }

    private static Process launch(Path linux, String mode) throws Exception {
        String classes = InstallationLease.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                + java.io.File.pathSeparator
                + InstallationLeaseTest.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return new ProcessBuilder(javaBinary(), "-cp", classes,
                Holder.class.getName(), linux.toString(), mode).redirectErrorStream(true).start();
    }

    private static String javaBinary() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private static void stop(Process process) throws Exception {
        if (!process.isAlive()) return;
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream()))) { writer.println("stop"); }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        assertTrue(!process.isAlive());
    }
    private static void cleanup(Process... processes) throws Exception { for (Process process : processes) stop(process); }

    public static final class Holder {
        public static void main(String[] args) throws Exception {
            try (InstallationLease ignored = InstallationLease.acquire(Path.of(args[0]), InstallationLease.Mode.valueOf(args[1]))) {
                System.out.print('R'); System.out.flush();
                new BufferedReader(new InputStreamReader(System.in)).readLine();
            }
        }
    }
}
