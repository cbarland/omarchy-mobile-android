package dev.omamobile.host;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in real fresh-install test. Never resets or replaces an existing installation. */
public class ArchiveInstallTest {
    @Test public void verifiedBaseArchiveBecomesBothOmarchyScreens() throws Exception {
        Assume.assumeTrue("Explicit disposable fresh-install test required",
                "true".equals(InstrumentationRegistry.getArguments().getString("install")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var runtime = HostRuntime.get(context);
        assertFalse("Use a fresh disposable emulator, never replace existing Linux data", runtime.installed());
        assertTrue("Full graphical/install assets required", runtime.installerAvailable());
        assertTrue(android.os.Process.myUid() >= 10000);
        var archive = context.getFilesDir().toPath().resolve("install-base.tar.gz");
        assertTrue("Stage a verified, unwrapped architecture-matching base archive", Files.isRegularFile(archive));
        instrumentation.runOnMainSync(() -> {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        // File URIs here stay inside the test process. The actual UI uses a
        // SAF content URI and LinuxService to own foreground setup.
        assertTrue(runtime.submit(null, Uri.fromFile(archive.toFile()), false, false, true, () -> { }));
        long started = SystemClock.uptimeMillis() + 10000;
        while (!runtime.busy() && SystemClock.uptimeMillis() < started) SystemClock.sleep(100);
        assertTrue("Installer did not start: " + runtime.output(), runtime.busy());
        long deadline = SystemClock.uptimeMillis() + 20L * 60 * 1000;
        while (runtime.busy() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(1000);
        if (runtime.busy()) { runtime.stop(); fail("Installer timed out: " + runtime.output()); }
        assertTrue(runtime.output(), runtime.graphicsReady());
        assertTrue(Files.isRegularFile(runtime.root.resolve("usr/share/omamobile/android/install-manifest.json")));
        try {
            new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
        } finally {
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
        }
    }
}
