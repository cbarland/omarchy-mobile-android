package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public class LinuxServiceLifecycleTest {
    @Test public void oldCompletionCannotCancelAnAcceptedNewCommand() throws Exception {
        Assume.assumeTrue("Prepared Linux required", "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        var runtime = HostRuntime.get(context);
        String marker = "service-generation-" + SystemClock.uptimeMillis();
        instrumentation.runOnMainSync(() -> {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            context.startForegroundService(command(context, "sleep 1"));
        });
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!runtime.busy() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
        assertTrue(runtime.busy());
        instrumentation.runOnMainSync(() -> {
            // Queue the new onStartCommand before the old worker posts done;
            // keep the main looper here until the old worker becomes idle.
            context.startForegroundService(command(context, "sleep 2; printf '" + marker + "\\n'"));
            long oldDeadline = SystemClock.uptimeMillis() + 5000;
            while (runtime.busy() && SystemClock.uptimeMillis() < oldDeadline) SystemClock.sleep(10);
            assertFalse("First command did not finish", runtime.busy());
        });
        deadline = SystemClock.uptimeMillis() + 10000;
        while ((!runtime.output().contains(marker) || runtime.busy()) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        // The command is logged before execution; require its output line too.
        assertTrue(runtime.output(), runtime.output().contains("\n" + marker + "\n"));
        assertFalse(runtime.busy());
        awaitStopped(context);
    }

    @Test public void rejectedDuplicateStartStillAllowsServiceToStop() throws Exception {
        Assume.assumeTrue("Prepared Linux required", "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        var runtime = HostRuntime.get(context);
        instrumentation.runOnMainSync(() -> {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            context.startForegroundService(command(context, "sleep 1"));
        });
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (!runtime.busy() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
        assertTrue(runtime.busy());
        instrumentation.runOnMainSync(() -> context.startForegroundService(command(context, "exit 99")));
        while (runtime.busy() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertFalse(runtime.busy());
        awaitStopped(context);
    }

    private static Intent command(Context context, String command) {
        return new Intent(context, LinuxService.class).putExtra("command", command);
    }
    @SuppressWarnings("deprecation")
    private static void awaitStopped(Context context) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        boolean running;
        do {
            running = context.getSystemService(ActivityManager.class).getRunningServices(100).stream()
                    .anyMatch(service -> service.service.getClassName().equals(LinuxService.class.getName()));
            if (running) SystemClock.sleep(50);
        } while (running && SystemClock.uptimeMillis() < deadline);
        assertFalse("Idle service did not relinquish foreground ownership", running);
    }
}
