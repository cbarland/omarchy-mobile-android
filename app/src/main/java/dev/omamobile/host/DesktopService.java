package dev.omamobile.host;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import java.util.EnumMap;
import java.util.List;

/** Android lifecycle owner for the two graphics hosts; both use the same Linux rootfs. */
public final class DesktopService extends Service {
    static final String STOP_SCREENS = "dev.omamobile.host.STOP_SCREENS";
    static final String START_UNIFIED = "dev.omamobile.host.START_UNIFIED";
    static final String PREVIEW_SCALE = "dev.omamobile.host.PREVIEW_SCALE";
    static final String KEEP_PREVIEW = "dev.omamobile.host.KEEP_PREVIEW";
    static final String REVERT_PREVIEW = "dev.omamobile.host.REVERT_PREVIEW";
    static final String DISPLAY_SETTINGS_CHANGED = "dev.omamobile.host.DISPLAY_SETTINGS_CHANGED";
    private final Handler previewHandler = new Handler(Looper.getMainLooper());
    private DisplayPreviewController preview;
    private DisplayManager displays;
    private enum Mode { LEGACY, UNIFIED }
    private enum Owner {
        PHONE("phone", PhoneCompositorOwnershipService.class),
        DESKTOP("desktop", DesktopCompositorOwnershipService.class),
        UNIFIED("unified", UnifiedCompositorOwnershipService.class);

        final String profile;
        final Class<?> service;
        Owner(String profile, Class<?> service) { this.profile = profile; this.service = service; }
    }
    private static final List<Owner> LEGACY_OWNERS = List.of(Owner.PHONE, Owner.DESKTOP);
    private static final List<Owner> UNIFIED_OWNERS = List.of(Owner.UNIFIED);
    private record Binding(ServiceConnection connection, long generation) { }
    private final EnumMap<Owner, Binding> ownership = new EnumMap<>(Owner.class);
    private long ownershipGeneration;
    private boolean running;
    private Mode mode;
    private int externalId = -1;
    private AndroidAppPortal portal;
    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) { if (running && externalId == -1) showDesktop(); }
        public void onDisplayChanged(int id) { }
        public void onDisplayRemoved(int id) {
            if (id == externalId) {
                externalId = -1;
                if (mode == Mode.LEGACY) {
                    sendStop(Owner.DESKTOP);
                    unbindOwnership(Owner.DESKTOP);
                }
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
                if (mode == Mode.UNIFIED)
                    throw new java.io.IOException("Desktop display preview is unavailable in shared compositor mode");
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
        startScreens(intent != null && START_UNIFIED.equals(intent.getAction()) ? Mode.UNIFIED : Mode.LEGACY);
        return START_NOT_STICKY;
    }
    private void startScreens(Mode requested) {
        if (running && mode != requested) {
            Mode previous = mode;
            if (previous == Mode.LEGACY && requested == Mode.UNIFIED) {
                previewHandler.removeCallbacksAndMessages(null);
                revertPreview();
            }
            running = false;
            mode = null;
            stopMode(previous);
        }
        running = true;
        mode = requested;
        Owner phoneOwner = requested == Mode.UNIFIED ? Owner.UNIFIED : Owner.PHONE;
        bindOwnership(phoneOwner);
        ActivityOptions phoneOutput = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        Class<?> phoneActivity = requested == Mode.UNIFIED ? UnifiedPhoneActivity.class : PhoneActivity.class;
        startActivity(new Intent(this, phoneActivity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS), phoneOutput.toBundle());
        showDesktop();
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
        if (mode == Mode.LEGACY) bindOwnership(Owner.DESKTOP);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(externalId);
        try {
            Class<?> activity = mode == Mode.UNIFIED ? UnifiedDesktopActivity.class : DesktopActivity.class;
            startActivity(new Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS), options.toBundle());
        } catch (IllegalArgumentException | SecurityException unavailable) {
            // Display removal/permission changes can race discovery. Keep the phone reachable.
            android.util.Log.w("OmamobileDisplay", "Cannot launch desktop on display " + externalId, unavailable);
            externalId = -1;
            if (mode == Mode.LEGACY) unbindOwnership(Owner.DESKTOP);
        }
    }
    private ServiceConnection ownershipConnection(Owner owner, long generation) {
        return new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, android.os.IBinder service) {
                if (!isCurrent(owner, this, generation)) return;
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (owner == Owner.UNIFIED && isCurrent(owner, this, generation)) {
                    android.util.Log.e("OmamobileOwnership", "Shared compositor owner died; stopping graphical foreground service");
                    stopScreens();
                    return;
                }
                // Keep the registration owned until the output is intentionally
                // stopped; Android may reconnect a temporarily dead process.
                android.util.Log.w("OmamobileOwnership", owner.profile + " owner disconnected");
            }
            @Override public void onBindingDied(ComponentName name) {
                if (!isCurrent(owner, this, generation)) return;
                if (owner == Owner.UNIFIED) {
                    android.util.Log.e("OmamobileOwnership", "Shared compositor binding died; stopping graphical foreground service");
                    stopScreens();
                    return;
                }
                unbindOwnership(owner);
                if (shouldOwn(owner)) bindOwnership(owner);
            }
        };
    }
    private boolean isCurrent(Owner owner, ServiceConnection connection, long generation) {
        Binding binding = ownership.get(owner);
        return binding != null && binding.connection() == connection && binding.generation() == generation;
    }
    private boolean shouldOwn(Owner owner) {
        if (!running) return false;
        if (mode == Mode.UNIFIED) return owner == Owner.UNIFIED;
        return mode == Mode.LEGACY && (owner == Owner.PHONE || owner == Owner.DESKTOP && externalId != -1);
    }
    private void bindOwnership(Owner owner) {
        if (ownership.containsKey(owner)) return;
        long generation = ++ownershipGeneration;
        ServiceConnection connection = ownershipConnection(owner, generation);
        ownership.put(owner, new Binding(connection, generation));
        boolean bound = bindService(new Intent(this, owner.service), connection, BIND_AUTO_CREATE | BIND_IMPORTANT);
        if (!bound) {
            Binding binding = ownership.get(owner);
            if (binding != null && binding.connection() == connection) ownership.remove(owner);
            android.util.Log.e("OmamobileOwnership", "Cannot bind " + owner.profile + " compositor owner");
        }
    }
    private void unbindOwnership(Owner owner) {
        Binding binding = ownership.remove(owner);
        if (binding == null) return;
        ServiceConnection connection = binding.connection();
        try { unbindService(connection); }
        catch (IllegalArgumentException alreadyUnbound) { android.util.Log.w("OmamobileOwnership", owner.profile + " owner already unbound", alreadyUnbound); }
    }
    private void sendStop(Owner owner) {
        sendBroadcast(new Intent(STOP_SCREENS).setPackage(getPackageName()).putExtra("profile", owner.profile));
    }
    private void stopMode(Mode stopped) {
        if (stopped == null) return;
        List<Owner> owners = stopped == Mode.UNIFIED ? UNIFIED_OWNERS : LEGACY_OWNERS;
        for (Owner owner : owners) sendStop(owner);
        for (Owner owner : owners) unbindOwnership(owner);
    }
    private void stopScreens() {
        running = false;
        Mode stopped = mode;
        mode = null;
        stopMode(stopped);
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
    @Override public void onDestroy() {
        running = false;
        Mode stopped = mode;
        mode = null;
        stopMode(stopped);
        previewHandler.removeCallbacksAndMessages(null); revertPreview(); displays.unregisterDisplayListener(listener);
        portal.close(); AndroidShell.get(this).close(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
