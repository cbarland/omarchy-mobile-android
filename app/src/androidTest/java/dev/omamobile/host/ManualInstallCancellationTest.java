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
    private static final Uri BLOCKED_OPEN = Uri.parse("content://dev.omamobile.host.test.blocking-archive/rootfs-open");

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
                    .setData(BLOCKED));
            assertTrue("Provider was not opened", awaitProviderOpened(context, BLOCKED, 10000));
            assertTrue("Install worker did not claim busy state", runtime.busy());
            assertTrue("Importer staging was not created", awaitImportStaging(runtime.linux, 10000));

            stopLinuxService(context);
            awaitServiceStopped(context, 10000);
            assertTrue("Provider writer was not held before cancellation",
                    callProvider(context, "state").getBoolean("writerHeld"));
            assertFalse("Manual cancellation activated rootfs", Files.exists(runtime.root));
            assertFalse("Preparation ran before archive completion", hasPrefix(runtime.linux, "setup-"));
            assertTrue("Import staging cleanup timed out", awaitNoPrefix(runtime.linux, "import-", 10000));
            assertEquals("preserve", new String(Files.readAllBytes(sentinel), StandardCharsets.UTF_8));
            awaitServiceStopped(context, 10000);
        } finally {
            stopLinuxService(context);
            callProvider(context, "release");
            GuestPaths.deleteTree(sentinel);
            awaitServiceStopped(context, 10000);
        }
    }

    @Test public void blockedProviderOpenCancelsBeforeImporterStaging() throws Exception {
        Assume.assumeTrue("Fresh disposable install-cancel emulator required", "true".equals(
                InstrumentationRegistry.getArguments().getString("installCancel")));
        Assume.assumeTrue("Emulator-only cancellation proof", "ranchu".equals(Build.HARDWARE)
                || "goldfish".equals(Build.HARDWARE));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HostRuntime runtime = HostRuntime.get(context);
        assertFalse("This test must never replace a prepared rootfs", runtime.installed());
        Files.createDirectories(runtime.linux);
        Path sentinel = runtime.linux.resolve("keep-install-cancel-open-sentinel");
        Files.write(sentinel, "preserve".getBytes(StandardCharsets.UTF_8));
        callProvider(context, "reset");
        try {
            context.startForegroundService(new Intent(context, LinuxService.class)
                    .setData(BLOCKED_OPEN));
            assertTrue("Provider open was not entered", awaitProviderOpened(context, BLOCKED_OPEN, 10000));
            assertFalse("Provider-open test reached importer staging", hasPrefix(runtime.linux, "import-"));
            assertTrue("Provider open was not held inside provider",
                    callProvider(context, BLOCKED_OPEN, "state").getBoolean("openHeld"));
            stopLinuxService(context);
            awaitServiceStopped(context, 10000);
            assertTrue("Provider open remained blocked after cancellation",
                    awaitProviderReleased(context, BLOCKED_OPEN, 10000));
            assertFalse("Provider-open cancellation activated rootfs", Files.exists(runtime.root));
            assertTrue("Provider-open staging cleanup timed out", awaitNoPrefix(runtime.linux, "import-", 10000));
            assertEquals("preserve", new String(Files.readAllBytes(sentinel), StandardCharsets.UTF_8));
        } finally {
            stopLinuxService(context);
            callProvider(context, "release");
            GuestPaths.deleteTree(sentinel);
            awaitServiceStopped(context, 10000);
        }
    }

    private static boolean awaitProviderOpened(Context context, Uri uri, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            android.os.Bundle state = callProvider(context, uri, "state");
            if (state.getBoolean("opened") || state.getBoolean("openHeld")) return true;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static boolean awaitProviderReleased(Context context, Uri uri, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            if (!callProvider(context, uri, "state").getBoolean("openHeld")) return true;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static android.os.Bundle callProvider(Context context, String method) {
        return callProvider(context, BLOCKED, method);
    }

    private static android.os.Bundle callProvider(Context context, Uri uri, String method) {
        android.os.Bundle result = context.getContentResolver().call(uri, method, null, null);
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

    private static boolean awaitNoPrefix(Path directory, String prefix, long timeout) throws Exception {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            if (!hasPrefix(directory, prefix)) return true;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static void stopLinuxService(Context context) {
        context.stopService(new Intent(context, LinuxService.class));
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
