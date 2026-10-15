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
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP_SCREENS.equals(intent.getAction())) {
            stopScreens();
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
    @Override public void onDestroy() { displays.unregisterDisplayListener(listener); portal.close(); AndroidShell.get(this).close(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
