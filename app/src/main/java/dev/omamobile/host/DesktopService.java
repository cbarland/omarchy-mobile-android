package dev.omamobile.host;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;

/** Android lifecycle owner for the two graphics hosts; both use the same Linux rootfs. */
public final class DesktopService extends Service {
    static final String STOP_SCREENS = "dev.omamobile.host.STOP_SCREENS";
    static final String PREVIEW_SCALE = "dev.omamobile.host.PREVIEW_SCALE";
    static final String KEEP_PREVIEW = "dev.omamobile.host.KEEP_PREVIEW";
    static final String REVERT_PREVIEW = "dev.omamobile.host.REVERT_PREVIEW";
    static final String DISPLAY_SETTINGS_CHANGED = "dev.omamobile.host.DISPLAY_SETTINGS_CHANGED";
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private DisplayPreviewController preview;
    private DisplayManager displays;
    private boolean running;
    private int externalId = -1;
    private AndroidAppPortal portal;
    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { if (running && externalId == -1) showDesktop(); }
        public void onDisplayChanged(int id) { }
        public void onDisplayRemoved(int id) {
            if (id == externalId) {
                externalId = -1;
                sendBroadcast(new Intent(STOP_SCREENS).setPackage(getPackageName()).putExtra("profile", "desktop"));
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        displays = getSystemService(DisplayManager.class);
        displays.registerDisplayListener(listener, new Handler(Looper.getMainLooper()));
        // A previous service instance unbound its helper when the app card
        // closed. Reuse the existing grant when the sessions are reopened.
        AndroidShell.get(this).reconnect();
        portal = new AndroidAppPortal(this);
        try { portal.start(); } catch (java.io.IOException e) { android.util.Log.e("OmamobilePortal", "Cannot start Android app portal", e); }
        preview = new DisplayPreviewController(this);
        // A new service cannot inherit an unconfirmed preview from a dead owner.
        revertPreview();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP_SCREENS.equals(intent.getAction())) {
            stopScreens();
            return START_NOT_STICKY;
        }
        if (intent != null && (PREVIEW_SCALE.equals(intent.getAction())
                || KEEP_PREVIEW.equals(intent.getAction()) || REVERT_PREVIEW.equals(intent.getAction()))) {
            try {
                if (!running) throw new java.io.IOException("Start Omarchy screens before previewing desktop scale");
                switch (intent.getAction()) {
                    case PREVIEW_SCALE -> preview.preview(intent.getIntExtra("scale", -1), System.currentTimeMillis());
                    case KEEP_PREVIEW -> preview.keep(System.currentTimeMillis());
                    case REVERT_PREVIEW -> preview.revert();
                    default -> throw new IllegalArgumentException("Unknown preview action");
                }
                schedulePreviewExpiry();
            } catch (Exception error) {
                android.util.Log.w("OmamobileDisplay", "Display preview action failed", error);
            } finally {
                notifyDisplaySettings();
                if (!running) stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "desktop", "Omarchy display sessions", NotificationManager.IMPORTANCE_LOW));
        var stop = PendingIntent.getService(this, 10, new Intent(this, DesktopService.class).setAction(STOP_SCREENS), PendingIntent.FLAG_IMMUTABLE);
        var controls = PendingIntent.getActivity(this, 10, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(2, new Notification.Builder(this, "desktop").setSmallIcon(R.drawable.ic_host)
                .setContentTitle("Omarchy Mobile").setContentText("Hyprland phone and desktop sessions")
                .setContentIntent(controls).addAction(new Notification.Action.Builder(null, "Stop screens", stop).build()).setOngoing(true).build());
        running = true;
        // Reopening the controls must also let the user return to an already
        // running phone shell without creating another compositor.
        ActivityOptions phoneOutput = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        startActivity(new Intent(this, PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS), phoneOutput.toBundle());
        showDesktop();
        return START_NOT_STICKY;
    }
    private void notifyDisplaySettings() {
        sendBroadcast(new Intent(DISPLAY_SETTINGS_CHANGED).setPackage(getPackageName()));
    }
    private void revertPreview() {
        try {
            if (preview != null && preview.revert() == DisplayPreviewController.RevertResult.CHANGED)
                android.util.Log.w("OmamobileDisplay", "Newer desktop config preserved during preview rollback");
        } catch (java.io.IOException error) {
            android.util.Log.w("OmamobileDisplay", "Display preview recovery unavailable", error);
        }
        notifyDisplaySettings();
    }
    private void schedulePreviewExpiry() {
        previewHandler.removeCallbacksAndMessages(null);
        if (!preview.hasPending()) return;
        long delay;
        try { delay = Math.max(0, Math.min(DisplayPreviewController.TIMEOUT_MS, preview.deadline() - System.currentTimeMillis())); }
        catch (Exception error) { android.util.Log.w("OmamobileDisplay", "Malformed display preview state", error); return; }
        // Handler time is monotonic: changing the wall clock cannot extend a live preview.
        previewHandler.postDelayed(this::revertPreview, delay);
    }
    private void showDesktop() {
        // A saved target is applied only to a new desktop. Do not move an existing compositor
        // to another Surface when the user reopens the controls or changes the preference.
        if (externalId == -1) {
            var selection = DisplayCatalog.selection(this, DisplayCatalog.read(this));
            if (selection.id() == -1) return;
            externalId = selection.id();
        }
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(externalId);
        try {
            startActivity(new Intent(this, DesktopActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS), options.toBundle());
        } catch (IllegalArgumentException | SecurityException unavailable) {
            // Display removal/permission changes can race discovery. Keep the phone reachable.
            android.util.Log.w("OmamobileDisplay", "Cannot launch desktop on display " + externalId, unavailable);
            externalId = -1;
        }
    }
    private void stopScreens() {
        running = false;
        sendBroadcast(new Intent(STOP_SCREENS).setPackage(getPackageName()));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // There is one user-facing card. Swiping it away closes both hidden
        // output hosts instead of leaving a half-closed graphical session.
        if (rootIntent != null && rootIntent.getComponent() != null
                && rootIntent.getComponent().getClassName().equals(MainActivity.class.getName())) stopScreens();
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() { previewHandler.removeCallbacksAndMessages(null); revertPreview(); displays.unregisterDisplayListener(listener); portal.close(); AndroidShell.get(this).close(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
