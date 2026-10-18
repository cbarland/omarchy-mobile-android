package dev.omamobile.host;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/** Bound lifetime anchor for the shared two-output compositor process. */
public final class UnifiedCompositorOwnershipService extends Service {
    private final IBinder binder = new Binder();

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public boolean onUnbind(Intent intent) {
        UnifiedCompositorController.get(this).stop();
        return false;
    }
}
