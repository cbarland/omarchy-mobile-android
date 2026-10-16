package dev.omamobile.host;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Assume;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class HomeActivityTest {
    @Test public void preparedHomeReusesPhoneOwnerAndLeavesNoHomeTask() throws Exception {
        Assume.assumeTrue("Explicit -e graphics true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
        try {
            int initialPid = awaitPhonePid(context);
            String initialSignature = readySignature(context);
            startHome(context); awaitPhoneActivity(instrumentation);
            assertEquals(initialPid, awaitPhonePid(context));
            assertEquals(initialSignature, readySignature(context));
            startHome(context); awaitPhoneActivity(instrumentation);
            assertEquals(initialPid, awaitPhonePid(context));
            assertEquals(initialSignature, readySignature(context));
            assertFalse("Transient HOME activity must finish", hasHomeActivity(instrumentation));
        } finally {
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
        }
    }

    private static void startHome(Context context) {
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static void awaitPhoneActivity(android.app.Instrumentation instrumentation) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicBoolean found = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED))
                    if (activity instanceof PhoneActivity) found.set(true);
            });
            if (found.get()) return;
            SystemClock.sleep(50);
        }
        fail("Phone activity did not resume after HOME routing");
    }

    private static boolean hasHomeActivity(android.app.Instrumentation instrumentation) {
        AtomicBoolean found = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> {
            for (Stage stage : Stage.values()) {
                if (stage == Stage.PRE_ON_CREATE || stage == Stage.DESTROYED) continue;
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))
                    if (activity instanceof HomeActivity) found.set(true);
            }
        });
        return found.get();
    }

    private static int awaitPhonePid(Context context) {
        String process = context.getPackageName() + ":phone";
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            for (ActivityManager.RunningAppProcessInfo info : context.getSystemService(ActivityManager.class)
                    .getRunningAppProcesses()) if (process.equals(info.processName)) return info.pid;
            SystemClock.sleep(50);
        }
        fail("Phone compositor process did not remain alive"); return -1;
    }

    private static String readySignature(Context context) throws Exception {
        Path ready = context.getFilesDir().toPath().resolve("graphics/phone/ready.properties");
        Properties values = new Properties();
        try (var input = Files.newInputStream(ready)) { values.load(input); }
        return values.getProperty("HYPRLAND_INSTANCE_SIGNATURE", "");
    }
}
