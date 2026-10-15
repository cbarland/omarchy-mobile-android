package dev.omamobile.host;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import rikka.shizuku.Shizuku;

/** User-authorized shell service binding; never requests root or grants itself permissions. */
final class AndroidShell implements AutoCloseable {
    private static AndroidShell instance;
    private final Shizuku.UserServiceArgs args;
    private volatile IAndroidShell helper;
    private volatile boolean binding;
    private volatile String state = "Start Shizuku in ADB mode, then authorize Android windows";
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                IAndroidShell candidate = IAndroidShell.Stub.asInterface(binder);
                if (candidate.uid() != 2000) throw new SecurityException("Android shell UID required");
                helper = candidate; state = "Android windows connected (shell UID 2000)";
            } catch (Exception e) { state = e.toString(); binding = false; }
        }
        public void onServiceDisconnected(ComponentName name) { helper = null; binding = false; state = "Android window connection lost"; }
    };
    private final Shizuku.OnBinderReceivedListener received = this::connect;
    private final Shizuku.OnBinderDeadListener died = () -> { helper = null; binding = false; state = "Shizuku disconnected"; };
    private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> { if (code == 6201) connect(); };
    static synchronized AndroidShell get(Context context) { if (instance == null) instance = new AndroidShell(context.getApplicationContext()); return instance; }
    private AndroidShell(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, AndroidShellService.class))
                .tag("omamobile-android-windows").processNameSuffix("android_windows").daemon(false).version(6).debuggable(BuildConfig.DEBUG);
        Shizuku.addBinderReceivedListenerSticky(received);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addRequestPermissionResultListener(permission);
    }
    private void connect() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.getUid() != 2000) { state = "Start Shizuku in ADB mode"; return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { state = "Authorize Android windows"; return; }
            if (binding) return;
            binding = true; state = "Connecting Android windows";
            Shizuku.bindUserService(args, connection);
        } catch (Exception e) { binding = false; state = e.toString(); }
    }
    void reconnect() { connect(); }
    void authorize() {
        try {
            if (Shizuku.pingBinder() && Shizuku.getUid() == 2000 && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Shizuku.requestPermission(6201);
            else connect();
        } catch (Exception e) { state = e.toString(); }
    }
    IAndroidShell require() throws java.io.IOException {
        if (helper == null || !helper.asBinder().isBinderAlive()) throw new java.io.IOException(state);
        return helper;
    }
    String state() { return state; }
    @Override public void close() {
        if (binding) try { Shizuku.unbindUserService(args, connection, true); } catch (Exception ignored) { }
        helper = null; binding = false;
        state = "Android windows disconnected";
    }
}
