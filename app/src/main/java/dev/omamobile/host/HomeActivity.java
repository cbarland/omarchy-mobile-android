package dev.omamobile.host;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
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
        if (HomeRouting.choose(HostRuntime.get(this).graphicsReady(), NativeCompositor.AVAILABLE)
                == HomeRouting.Destination.PHONE) {
            startForegroundService(new Intent(this, DesktopService.class));
            Intent phone = new Intent(this, PhoneActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(phone, ActivityOptions.makeBasic()
                    .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle());
        } else {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        // Finish only after the target launch request has been accepted so a
        // transient HOME task cannot replace the reachable host controls.
        finish();
    }
}
