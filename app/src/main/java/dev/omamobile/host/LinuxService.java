package dev.omamobile.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Started only by an explicit import/command; no boot receiver or background polling. */
public final class LinuxService extends Service {
    private android.os.PowerManager.WakeLock setupWakeLock;
    private int latestStartId;
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        latestStartId = startId;
        HostRuntime runtime = HostRuntime.get(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "linux", "Linux session", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "linux").setContentTitle("Omarchy Mobile")
                .setContentText("Linux operation in progress").setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(open).setOngoing(true).build());
        boolean export = intent.getBooleanExtra("exportData", false);
        boolean recover = intent.getBooleanExtra("recoverData", false);
        boolean accepted = export ? runtime.exportData(intent.getData(), () -> {
            if (!runtime.busy() && stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE);
        }) : recover ? runtime.recoverData(intent.getData(), () -> {
            if (!runtime.busy() && stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE);
        }) : runtime.submit(intent.getStringExtra("command"), intent.getData(), intent.getBooleanExtra("ando", false),
                intent.getBooleanExtra("androidDns", false), intent.getBooleanExtra("installOmarchy", false), () -> {
            // An earlier worker's posted completion must not cancel a newly
            // accepted operation. Rejected duplicate starts still advance the
            // service start ID, so stop the latest request only when idle.
            if (!runtime.busy() && stopSelfResult(latestStartId)) stopForeground(STOP_FOREGROUND_REMOVE);
        });
        if (!accepted) {
            // Existing operation owns this foreground service until its completion callback.
            if ((export || recover) && !runtime.busy()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelfResult(latestStartId);
            }
            return START_NOT_STICKY;
        }
        if (intent.getBooleanExtra("installOmarchy", false) || export || recover) {
            setupWakeLock = getSystemService(android.os.PowerManager.class).newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK, "omamobile:setup");
            setupWakeLock.setReferenceCounted(false);
            setupWakeLock.acquire(60L * 60 * 1000);
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        HostRuntime runtime = HostRuntime.get(this);
        if (runtime.busy()) runtime.stop();
        if (setupWakeLock != null && setupWakeLock.isHeld()) setupWakeLock.release();
        super.onDestroy();
    }
}
