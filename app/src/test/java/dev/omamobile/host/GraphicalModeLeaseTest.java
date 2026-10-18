package dev.omamobile.host;

import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class GraphicalModeLeaseTest {
    @Test public void sharedModeWaitsForPreviousOwnerThenAcquires() throws Exception {
        var files = Files.createTempDirectory("graphical-handoff");
        var previous = GraphicalModeLease.acquire(files, false, () -> false);
        var waiting = CompletableFuture.supplyAsync(() -> {
            try { return GraphicalModeLease.acquire(files, true, () -> false); }
            catch (java.io.IOException error) { throw new java.util.concurrent.CompletionException(error); }
        });
        try {
            // The waiting mode cannot claim ownership while the prior owner holds it.
            try { waiting.get(150, TimeUnit.MILLISECONDS); fail("Replaced a live owner"); }
            catch (java.util.concurrent.TimeoutException expected) { }
            previous.close();
            try (var replacement = waiting.get(2, TimeUnit.SECONDS)) { assertNotNull(replacement); }
        } finally { previous.close(); }
    }

    @Test public void cancelledSwitchDoesNotAcquireOrChangeLinuxLease() throws Exception {
        var files = Files.createTempDirectory("graphical-cancel");
        var cancelled = new AtomicBoolean(true);
        try {
            GraphicalModeLease.acquire(files, true, cancelled::get);
            fail("Cancelled start acquired ownership");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("cancelled")); }
        try (var mode = GraphicalModeLease.acquire(files, false, () -> false);
             var installer = InstallationLease.acquire(files.resolve("linux"), InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
            assertNotNull(mode);
            assertNotNull(installer);
        }
    }
}
