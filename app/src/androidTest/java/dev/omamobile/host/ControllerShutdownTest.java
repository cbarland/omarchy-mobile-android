package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** A Surface/Activity is replaceable; it cannot own the only session stop endpoint. */
public class ControllerShutdownTest {
    @Test public void coldOutputsPublishCompleteAssetAndStopWithoutActivities() throws Exception {
        Assume.assumeTrue("Prepared two-display Linux installation required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        stop(context);
        awaitStopped(context);
        var magic = context.getFilesDir().toPath().resolve("graphics/magic.mgc");
        Files.deleteIfExists(magic);
        try {
            new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
            byte[] expected;
            try (var in = context.getAssets().open("native/magic.mgc")) {
                expected = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            }
            assertArrayEquals("Concurrent output startup published an incomplete database", expected,
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(magic)));
            int removed = 0;
            for (var task : context.getSystemService(ActivityManager.class).getAppTasks()) {
                var component = task.getTaskInfo().baseIntent.getComponent();
                if (component != null && (component.getClassName().equals(PhoneActivity.class.getName())
                        || component.getClassName().equals(DesktopActivity.class.getName()))) {
                    task.finishAndRemoveTask();
                    removed++;
                }
            }
            assertEquals("Expected both output Activities", 2, removed);
            // Allow Activity destruction and Surface detachment to complete.
            SystemClock.sleep(1500);
            assertEquals("The controller must survive its Activity", 2, owners(context));
            for (var task : context.getSystemService(ActivityManager.class).getAppTasks()) {
                var component = task.getTaskInfo().baseIntent.getComponent();
                if (component != null) {
                    assertNotEquals(PhoneActivity.class.getName(), component.getClassName());
                    assertNotEquals(DesktopActivity.class.getName(), component.getClassName());
                }
            }
            stop(context);
            awaitStopped(context);
            for (String profile : new String[]{"p", "d"}) {
                var socket = new android.net.LocalSocket();
                try (socket) {
                    socket.connect(new android.net.LocalSocketAddress(context.getFilesDir() + "/r/" + profile
                            + "/ando/ando.sock", android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                    fail("Stopped controller left its Android broker accepting clients");
                } catch (java.io.IOException expectedClosed) { }
            }
        } finally { stop(context); awaitStopped(context); }
    }

    private static int owners(Context context) {
        return (int) context.getSystemService(ActivityManager.class).getRunningAppProcesses().stream()
                .filter(p -> p.uid == android.os.Process.myUid() && (p.processName.equals(context.getPackageName() + ":phone")
                        || p.processName.equals(context.getPackageName() + ":desktop"))).count();
    }
    private static void stop(Context context) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> context.startService(
                new Intent(context, DesktopService.class).setAction(DesktopService.STOP_SCREENS)));
    }
    private static void awaitStopped(Context context) {
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (owners(context) != 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        assertEquals("Stop screens left an output owner running", 0, owners(context));
    }
}
