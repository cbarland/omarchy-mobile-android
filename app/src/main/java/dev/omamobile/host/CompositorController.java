package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** A display's native compositor survives Activity/Surface replacement in its dedicated process. */
final class CompositorController implements CompositorEndpoint {
    private static CompositorController instance;
    static synchronized CompositorController get(Context context, boolean phone) {
        if (instance == null) instance = new CompositorController(context.getApplicationContext(), phone);
        return instance;
    }
    private final Context context;
    private final boolean phone;
    private final DisplayManager displays;
    private volatile GraphicalSession session;
    private volatile InstallationLease installationLease;
    private volatile InstallationLease graphicalModeLease;
    private Thread thread;
    private boolean started;
    private int attachedDisplayId = Display.INVALID_DISPLAY;
    private volatile boolean stopping;
    private Object surfaceOwner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DisplayManager.DisplayListener displayLifetime = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayChanged(int id) { }
        @Override public void onDisplayRemoved(int id) {
            synchronized (CompositorController.this) {
                if (id != attachedDisplayId) return;
                // Display and stop ownership outlive Activity/Surface changes.
                android.util.Log.i("OmamobileHyprland", "Owned Android display removed: " + id);
                stop();
            }
        }
    };
    private final BroadcastReceiver stopRequest = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String profile = intent.getStringExtra("profile");
            if (profile == null || profile.equals(phone ? "phone" : "desktop")) stop();
        }
    };
    private final CopyOnWriteArrayList<Consumer<String>> observers = new CopyOnWriteArrayList<>();
    private volatile String status = "Starting native Hyprland…";

    private CompositorController(Context context, boolean phone) {
        this.context = context;
        this.phone = phone;
        displays = context.getSystemService(DisplayManager.class);
        displays.registerDisplayListener(displayLifetime, main);
        IntentFilter filter = new IntentFilter(DesktopService.STOP_SCREENS);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(stopRequest, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyStopReceiver(filter);
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyStopReceiver(IntentFilter filter) {
        // Android 10-12 have no NOT_EXPORTED flag; retain the signature gate.
        context.registerReceiver(stopRequest, filter, "dev.omamobile.host.permission.CONTROL_SCREENS", main);
    }
    @Override public void observe(Consumer<String> listener) { observers.add(listener); listener.accept(status); }
    @Override public void unobserve(Consumer<String> listener) { observers.remove(listener); }
    private void status(String value) { status = value; main.post(() -> observers.forEach(listener -> listener.accept(value))); }

    @Override public synchronized void attach(Object owner, Surface surface, int width, int height, int androidDisplayId) {
        if (stopping) return;
        surfaceOwner = owner;
        if (started) { if (NativeCompositor.AVAILABLE) NativeCompositor.window(surface, width, height); return; }
        attachedDisplayId = androidDisplayId;
        started = true;
        thread = new Thread(() -> {
            try {
                if (!NativeCompositor.AVAILABLE) throw new IllegalStateException("This APK has no Hyprland backend for this device ABI");
                graphicalModeLease = GraphicalModeLease.acquire(context.getFilesDir().toPath(), false, () -> stopping);
                installationLease = InstallAssets.runtimeLease(context,
                        message -> android.util.Log.i("OmamobileHyprland", message));
                if (stopping) return;
                session = new GraphicalSession(context, phone, androidDisplayId);
                if (stopping) return;
                NativeCompositor.listener = (display, signature) -> {
                    if (stopping) return;
                    new Thread(() -> {
                        try {
                            if (stopping) return;
                            session.startClients(display, signature); status("");
                            // tawcroot's PDEATHSIG tracks the launching Linux
                            // thread. Keep this thread alive for its children.
                            int exit = session.awaitClients();
                            if (!stopping) status("Omarchy shell exited (" + exit + "). See " + session.home.resolve("linux-shell.log"));
                        }
                        catch (Exception error) { if (!stopping) status("Linux shell failed: " + error.getMessage()); }
                    }, "omarchy-clients").start();
                };
                if (stopping) return;
                int result = NativeCompositor.run(surface, width, height, session.home.toString(), session.runtime.toString(),
                        session.config.toString(), session.root.toString(), session.wrapper.toString(),
                        context.getApplicationInfo().nativeLibraryDir, session.magic.toString(), session.xkb.toString());
                status("Hyprland stopped (" + result + "). Logs: " + session.home);
            } catch (Exception error) {
                android.util.Log.e("OmamobileHyprland", "Compositor failed", error);
                status(error.toString());
            } finally {
                // Planned shutdown has a single cleanup owner below. Racing it
                // here could discard the live shell before stopForUser records
                // that its widget startup marker belongs to an intentional stop.
                // Planned stop keeps this owner process alive until the existing
                // kill deadline, so its shared lease cannot race an installer.
                if (!stopping) {
                    // Keep the lease owned by this Android process after an
                    // unexpected native exit. session.stop() only begins
                    // child cleanup; releasing here could let an installer
                    // race still-live Linux children. The existing planned
                    // stop path releases it after owner shutdown, while an
                    // unexpected owner remains protected until process death.
                    if (session != null) session.stop();
                }
            }
        }, "native-hyprland");
        thread.start();
    }
    @Override public synchronized void detach(Object owner) {
        // A replaced Activity can destroy its old Surface after the replacement
        // has attached. It must not detach the replacement's native window.
        if (surfaceOwner != owner) return;
        surfaceOwner = null;
        if (started && NativeCompositor.AVAILABLE) NativeCompositor.window(null, 0, 0);
    }
    @Override public void pointer(float x, float y, int button, boolean pressed) { NativeCompositor.pointer(x, y, button, pressed); }
    @Override public void touch(int id, int action, float x, float y) { NativeCompositor.touch(id, action, x, y); }
    @Override public void axis(float dx, float dy) { NativeCompositor.axis(dx, dy); }
    @Override public void key(int code, boolean pressed) { NativeCompositor.key(code, pressed); }
    @Override public void unicode(int codepoint) { NativeCompositor.unicode(codepoint); }
    @Override public void focus(boolean focused) { }
    synchronized void stop() {
        if (stopping) return;
        stopping = true;
        displays.unregisterDisplayListener(displayLifetime);
        context.unregisterReceiver(stopRequest);
        // Removing an output can reparent its Activity task onto the phone.
        // Finish our own profile's task before exiting, otherwise Android may
        // restore that top Activity into a new owner process on display zero.
        String activityName = (phone ? PhoneActivity.class : DesktopActivity.class).getName();
        for (var task : context.getSystemService(ActivityManager.class).getAppTasks()) {
            try {
                var info = task.getTaskInfo();
                var component = info == null || info.baseIntent == null ? null : info.baseIntent.getComponent();
                if (component == null || !context.getPackageName().equals(component.getPackageName())
                        || !activityName.equals(component.getClassName())) continue;
                task.finishAndRemoveTask();
                android.util.Log.i("OmamobileHyprland", "Removed stopped output task " + info.taskId + " (" + activityName + ")");
            } catch (IllegalArgumentException alreadyRemoved) {
                // Android may already have removed the Activity's task.
                android.util.Log.d("OmamobileHyprland", "Stopped output task was already removed", alreadyRemoved);
            }
        }
        // The port has one compositor lifetime per dedicated host process.
        // Bound even a native stop that cannot drain after its Surface detaches.
        new Thread(() -> {
            android.os.SystemClock.sleep(6000);
            android.util.Log.w("OmamobileHyprland", "Graphical shutdown exceeded its deadline");
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "omarchy-stop-deadline").start();
        // Linux cleanup can wait briefly for termination. Keep it off Android's
        // input thread while the two output hosts finish their shutdown.
        new Thread(() -> {
            try {
                if (session != null) session.stopForUser();
                if (NativeCompositor.AVAILABLE) NativeCompositor.stop();
                if (thread != null) thread.join(1500);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "stop-omarchy").start();
    }
}
