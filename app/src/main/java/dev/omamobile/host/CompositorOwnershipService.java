package dev.omamobile.host;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

/**
 * Bound-only lifetime anchor for a compositor output process.
 *
 * The main graphical foreground service owns the binding. Keeping this tiny
 * service in each output process gives Android an important binding while the
 * compositor activity is covered or not currently visible.
 */
public abstract class CompositorOwnershipService extends Service {
    private final IBinder binder = new Binder();

    protected abstract boolean phone();

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) {
        // Stop locally before Android can classify this process as cached. The
        // main service unbinds immediately before its stop broadcast, so the
        // output cannot remain alive waiting for a queued broadcast.
        CompositorController.get(this, phone()).stop();
        return false;
    }
}
