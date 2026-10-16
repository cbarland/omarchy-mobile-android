package dev.omamobile.host;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;

/** Transient Android HOME bridge; the Linux phone surface remains the owner. */
public final class HomeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        route();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        route();
    }

    private void route() {
        HostRuntime runtime = HostRuntime.get(this);
        try {
            boolean prepared = runtime.graphicsReady() && !runtime.busy() && NativeCompositor.AVAILABLE;
            if (prepared) {
                startForegroundService(new Intent(this, DesktopService.class));
                Intent phone = new Intent(this, PhoneActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                startActivity(phone, ActivityOptions.makeBasic()
                        .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
            } else {
                Intent setup = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(setup, ActivityOptions.makeBasic()
                        .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
            }
        } catch (RuntimeException error) {
            Log.e("OmamobileHome", "Cannot route HOME request", error);
        } finally {
            // The bridge is transient; MainActivity/PhoneActivity owns recovery controls.
            finish();
        }
    }
}
