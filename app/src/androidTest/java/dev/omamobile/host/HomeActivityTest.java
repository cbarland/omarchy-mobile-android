package dev.omamobile.host;

import android.app.ActivityManager;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.Intent;
import android.os.SystemClock;
import android.view.Display;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Assume;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class HomeActivityTest {
    @Test public void missingSetupHomeReturnsToMainActivityOnDefaultDisplay() throws Exception {
        Assume.assumeTrue("Explicit -e homeFallback true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("homeFallback")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Assume.assumeFalse("Fallback route requires an unprepared host",
                HostRuntime.get(context).installed());
        assertHomeComponentIsDiscoverable(context);
        startHome(context);
        Activity main = awaitMainActivity(instrumentation);
        awaitTopResumed(context, MainActivity.class);
        assertEquals(Display.DEFAULT_DISPLAY, main.getDisplay().getDisplayId());
        instrumentation.runOnMainSync(main::finish);
        assertFalse("Fallback must not start the phone owner", hasPhoneProcess(context));
    }

    @Test public void preparedHomeReusesPhoneOwnerAndLeavesNoHomeTask() throws Exception {
        Assume.assumeTrue("Explicit -e graphics true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
        try {
            assertHomeComponentIsDiscoverable(context);
            int initialPid = awaitPhonePid(context);
            String initialSignature = readySignature(context);
            assertPhoneTaskAndNoHomeTask(context);
            bringHostControlsToFront(context, instrumentation);
            var monitor = instrumentation.addMonitor(HomeActivity.class.getName(), null, false);
            Activity home;
            try {
                startHome(context);
                home = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            } finally {
                instrumentation.removeMonitor(monitor);
            }
            assertNotNull("Explicit HOME request must launch the bridge", home);
            awaitFinished(instrumentation, home);
            awaitPhoneTask(context);
            awaitTopResumed(context, PhoneActivity.class);
            assertEquals(initialPid, awaitPhonePid(context));
            assertEquals(initialSignature, readySignature(context));
            bringHostControlsToFront(context, instrumentation);
            awaitTopResumed(context, MainActivity.class);
            startHome(context); awaitPhoneTask(context);
            awaitTopResumed(context, PhoneActivity.class);
            assertEquals(initialPid, awaitPhonePid(context));
            assertEquals(initialSignature, readySignature(context));
        } finally {
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
        }
    }

    private static void startHome(Context context) {
        context.startActivity(new Intent(context, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private static void assertHomeComponentIsDiscoverable(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        boolean found = false;
        for (ResolveInfo info : context.getPackageManager().queryIntentActivities(home, 0)) {
            if (info.activityInfo != null && context.getPackageName().equals(info.activityInfo.packageName)
                    && HomeActivity.class.getName().equals(info.activityInfo.name)) {
                found = true;
                break;
            }
        }
        assertTrue("HOME query must include the optional bridge", found);
    }

    private static void awaitPhoneTask(Context context) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean phone = false;
            boolean home = false;
            for (ActivityManager.AppTask task : context.getSystemService(ActivityManager.class).getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                Intent base = info == null ? null : info.baseIntent;
                if (base == null || base.getComponent() == null) continue;
                String name = base.getComponent().getClassName();
                phone |= PhoneActivity.class.getName().equals(name)
                        && info.topActivity != null
                        && PhoneActivity.class.getName().equals(info.topActivity.getClassName());
                home |= HomeActivity.class.getName().equals(name);
            }
            if (phone && !home) return;
            SystemClock.sleep(50);
        }
        fail("Phone task did not become the foreground app task after HOME routing");
    }

    private static void bringHostControlsToFront(Context context,
            android.app.Instrumentation instrumentation) {
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
        awaitMainActivity(instrumentation);
    }

    private static void awaitTopResumed(Context context, Class<?> activityClass) throws Exception {
        String component = context.getPackageName() + "/." + activityClass.getSimpleName();
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            String dump = shell("dumpsys activity activities");
            int display = dump.indexOf("Display #0");
            int nextDisplay = display < 0 ? -1 : dump.indexOf("Display #", display + 9);
            String defaultDisplay = display < 0 ? "" : dump.substring(display,
                    nextDisplay < 0 ? dump.length() : nextDisplay);
            int top = defaultDisplay.indexOf("topResumedActivity=");
            if (top >= 0 && defaultDisplay.substring(top).contains(component)) return;
            SystemClock.sleep(200);
        }
        fail("Display 0 did not focus " + component);
    }

    private static String shell(String command) throws Exception {
        var ui = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try (var parcel = ui.executeShellCommand(command);
             var in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(parcel)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void assertPhoneTaskAndNoHomeTask(Context context) {
        boolean phone = false;
        boolean home = false;
        for (ActivityManager.AppTask task : context.getSystemService(ActivityManager.class).getAppTasks()) {
            ActivityManager.RecentTaskInfo info = task.getTaskInfo();
            Intent base = info == null ? null : info.baseIntent;
            if (base == null || base.getComponent() == null) continue;
            String name = base.getComponent().getClassName();
            phone |= PhoneActivity.class.getName().equals(name);
            home |= HomeActivity.class.getName().equals(name);
        }
        assertTrue("Prepared test requires an existing phone task", phone);
        assertFalse("HOME bridge must not already own a task", home);
    }

    private static void awaitFinished(android.app.Instrumentation instrumentation, Activity activity) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Boolean> finished = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> finished.set(activity.isFinishing() || activity.isDestroyed()));
            if (finished.get()) return;
            SystemClock.sleep(50);
        }
        fail("HOME bridge activity did not finish after routing");
    }

    private static Activity awaitMainActivity(android.app.Instrumentation instrumentation) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            AtomicReference<Activity> found = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)) {
                    if (activity instanceof MainActivity) found.set(activity);
                }
            });
            if (found.get() != null) return found.get();
            SystemClock.sleep(50);
        }
        fail("Main activity did not resume after fallback HOME routing");
        return null;
    }

    private static boolean hasPhoneProcess(Context context) {
        for (ActivityManager.RunningAppProcessInfo info : context.getSystemService(ActivityManager.class)
                .getRunningAppProcesses()) {
            if ((context.getPackageName() + ":phone").equals(info.processName)) return true;
        }
        return false;
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
