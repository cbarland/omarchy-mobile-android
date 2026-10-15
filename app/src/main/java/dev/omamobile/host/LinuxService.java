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
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        HostRuntime runtime = HostRuntime.get(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "linux", "Linux session", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "linux").setContentTitle("Omarchy Mobile")
                .setContentText("Linux operation in progress").setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(open).setOngoing(true).build());
        boolean accepted = runtime.submit(intent.getStringExtra("command"), intent.getData(), intent.getBooleanExtra("ando", false),
                intent.getBooleanExtra("androidDns", false), () -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        if (!accepted) {
            // Existing operation owns this foreground service until its completion callback.
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        HostRuntime runtime = HostRuntime.get(this);
        if (runtime.busy()) runtime.stop();
        super.onDestroy();
    }
}
