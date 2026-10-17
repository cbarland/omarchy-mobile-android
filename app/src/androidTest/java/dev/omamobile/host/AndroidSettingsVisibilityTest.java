package dev.omamobile.host;

import static org.junit.Assert.assertTrue;

import android.app.ActivityOptions;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.view.Display;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in device check that Android's standard settings activities reach the foreground. */
public class AndroidSettingsVisibilityTest {
    @Test public void networkSettingsActivityBecomesVisible() throws Exception {
        assertSettingsVisible("network-settings");
    }

    @Test public void bluetoothSettingsActivityBecomesVisible() throws Exception {
        assertSettingsVisible("bluetooth-settings");
    }

    private static void assertSettingsVisible(String operation) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Assume.assumeTrue("Pass settingsActions=true for a device UI check",
                "true".equals(InstrumentationRegistry.getArguments().getString("settingsActions")));
        Context context = instrumentation.getTargetContext();
        var intent = AndroidAppPortal.settingsIntent(operation);
        var resolved = intent.resolveActivity(context.getPackageManager());
        assertTrue("Android has no activity for " + operation, resolved != null);
        context.startActivity(intent, ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());

        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        String activity = resolved.getClassName();
        String resumed = "";
        while (System.nanoTime() < deadline) {
            instrumentation.waitForIdleSync();
            try (ParcelFileDescriptor output = instrumentation.getUiAutomation()
                    .executeShellCommand("dumpsys activity activities")) {
                try (var input = new ParcelFileDescriptor.AutoCloseInputStream(output);
                     var bytes = new ByteArrayOutputStream()) {
                    input.transferTo(bytes);
                    resumed = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            if (resumed.lines().anyMatch(line -> line.contains("mResumedActivity") && line.contains(activity))) return;
            Thread.sleep(250);
        }
        assertTrue(operation + " did not become the resumed Android activity: " + activity, false);
    }
}
