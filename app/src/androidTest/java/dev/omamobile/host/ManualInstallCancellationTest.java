package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.os.Build;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in fresh-emulator proof that manual provider reads are cancellable. */
public final class ManualInstallCancellationTest {
    private static final Uri BLOCKED = Uri.parse("content://dev.omamobile.host.test.blocking-archive/rootfs");

    @Test public void blockedManualArchiveReadClosesAndCleansBeforePrepare() throws Exception {
        Assume.assumeTrue("Fresh disposable install-cancel emulator required", "true".equals(
                InstrumentationRegistry.getArguments().getString("installCancel")));
        Assume.assumeTrue("Emulator-only cancellation proof", "ranchu".equals(Build.HARDWARE)
                || "goldfish".equals(Build.HARDWARE));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HostRuntime runtime = HostRuntime.get(context);
        assertFalse("This test must never replace a prepared rootfs", runtime.installed());
        Files.createDirectories(runtime.linux);
        Path sentinel = runtime.linux.resolve("keep-install-cancel-sentinel");
        Files.write(sentinel, "preserve".getBytes(StandardCharsets.UTF_8));
        callProvider(context, "reset");
        try {
            context.startForegroundService(new Intent(context, LinuxService.class)
                    .setData(BLOCKED).putExtra("installOmarchy", true));
            assertTrue("Provider was not opened", awaitProviderOpened(context, 10000));
            assertTrue("Install worker did not claim busy state", runtime.busy());
            assertTrue("Importer staging was not created", awaitImportStaging(runtime.linux, 10000));

            runtime.stop();
            awaitIdle(runtime, 10000);
            assertTrue("Provider writer closed before cancellation proof",
                    callProvider(context, "state").getBoolean("writerHeld"));
            assertFalse("Manual cancellation activated rootfs", Files.exists(runtime.root));
            assertFalse("Preparation ran before archive completion", hasPrefix(runtime.linux, "setup-"));
            assertFalse("Import staging survived cancellation", hasPrefix(runtime.linux, "import-"));
            assertEquals("preserve", new String(Files.readAllBytes(sentinel), StandardCharsets.UTF_8));
            awaitServiceStopped(context, 10000);
        } finally {
            runtime.stop();
            callProvider(context, "release");
            GuestPaths.deleteTree(sentinel);
            awaitIdle(runtime, 10000);
        }
    }

    private static boolean awaitProviderOpened(Context context, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            if (callProvider(context, "state").getBoolean("opened")) return true;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static android.os.Bundle callProvider(Context context, String method) {
        android.os.Bundle result = context.getContentResolver().call(BLOCKED, method, null, null);
        return result == null ? new android.os.Bundle() : result;
    }

    private static boolean awaitImportStaging(Path linux, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            if (hasPrefix(linux, "import-")) return true;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static boolean hasPrefix(Path directory, String prefix) throws Exception {
        if (!Files.isDirectory(directory)) return false;
        try (var children = Files.list(directory)) {
            return children.anyMatch(path -> path.getFileName().toString().startsWith(prefix));
        }
    }

    private static void awaitIdle(HostRuntime runtime, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (runtime.busy() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
        assertFalse("Manual install remained busy: " + runtime.output(), runtime.busy());
    }

    @SuppressWarnings("deprecation")
    private static void awaitServiceStopped(Context context, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean running = context.getSystemService(ActivityManager.class).getRunningServices(100).stream()
                    .anyMatch(service -> service.service.getClassName().equals(LinuxService.class.getName()));
            if (!running) return;
            SystemClock.sleep(50);
        }
        fail("LinuxService remained foreground after cancellation");
    }
}
