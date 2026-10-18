package dev.omamobile.host;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.WindowManager;

/** Private feasibility host: one process, one Wayland session, two Android surfaces.
 * Stop normal graphical sessions before launching. This is deliberately not a
 * production lifetime/hotplug controller and is absent from release APKs.
 */
public final class UnifiedOutputSpikeActivity extends Activity {
    private static final String TAG = "OmamobileUnifiedSpike";
    private OutputView phone, external;
    private Presentation presentation;
    private volatile GraphicalSession session;
    private InstallationLease lease;
    private boolean started, ready;
    private volatile boolean stopping;
    private static native int output(int id, Surface surface, int width, int height);
    private static native int pointerOutput(int id, float x, float y, int button, boolean pressed);

    private static void accepted(int result) {
        if (result != 0) throw new IllegalStateException("Native spike command rejected: " + result);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        phone = new OutputView(this, 0);
        setContentView(phone);
        Display[] displays = getSystemService(DisplayManager.class)
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) throw new IllegalStateException("Two-output spike requires an external display");
        presentation = new Presentation(this, displays[0]) {
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                return UnifiedOutputSpikeActivity.this.dispatchKeyEvent(event);
            }
        };
        external = new OutputView(presentation.getContext(), 1);
        presentation.setContentView(external);
        presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        presentation.show();
        Log.i(TAG, "owner=" + android.os.Process.myPid() + " phone=0 external=" + displays[0].getDisplayId());
    }

    private void attach(OutputView view) {
        if (stopping) return;
        if (ready) {
            int result = output(view.output, view.getHolder().getSurface(), view.width, view.height);
            accepted(result);
            Log.i(TAG, "attach output=" + view.output + " size=" + view.width + "x" + view.height + " queued=" + result);
            return;
        }
        if (view.output != 0 || started) return;
        started = true;
        new Thread(() -> {
            try {
                if (!NativeCompositor.AVAILABLE) throw new IllegalStateException("Native compositor unavailable");
                lease = InstallAssets.runtimeLease(this, message -> Log.i(TAG, message));
                if (stopping) return;
                // Reuse the existing prepared desktop runtime exclusively for
                // this spike. Both outputs intentionally use scale1 here.
                session = new GraphicalSession(this, false, Display.DEFAULT_DISPLAY);
                if (stopping) return;
                NativeCompositor.listener = (display, signature) -> {
                    runOnUiThread(() -> {
                        if (stopping) return;
                        ready = true;
                        // run() already owns the initial phone Surface. A new
                        // ANativeWindow wrapper can still reference that same
                        // BufferQueue, which cannot have a second EGLSurface.
                        if (external.getHolder().getSurface().isValid()) attach(external);
                    });
                    new Thread(() -> {
                        try {
                            if (stopping) return;
                            session.startClients(display, signature);
                            Log.i(TAG, "ready owner=" + android.os.Process.myPid() + " socket=" + display + " signature=" + signature);
                            session.awaitClients(); // Keep tawcroot's PDEATHSIG parent alive.
                        } catch (Exception error) { Log.e(TAG, "Clients failed", error); }
                    }, "unified-spike-clients").start();
                };
                if (stopping) return;
                int result = NativeCompositor.run(phone.getHolder().getSurface(), phone.width, phone.height,
                        session.home.toString(), session.runtime.toString(), session.config.toString(),
                        session.root.toString(), session.wrapper.toString(), getApplicationInfo().nativeLibraryDir,
                        session.magic.toString(), session.xkb.toString());
                Log.i(TAG, "native exited=" + result);
            } catch (Exception error) { Log.e(TAG, "Spike failed", error); }
        }, "unified-spike-native").start();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!ready || event.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP)
            return super.dispatchKeyEvent(event);
        int code = event.getScanCode();
        if (code == 0 && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) code = 28;
        if (code != 0) { NativeCompositor.key(code, event.getAction() == KeyEvent.ACTION_DOWN); return true; }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getUnicodeChar() > 0)
            NativeCompositor.unicode(event.getUnicodeChar());
        return true;
    }

    @Override protected void onDestroy() {
        stopping = true;
        if (presentation != null) presentation.dismiss();
        new Thread(() -> {
            try {
                if (session != null) session.stopForUser();
                if (NativeCompositor.AVAILABLE) NativeCompositor.stop();
            } finally {
                // Process exit owns lease release, as in the production host.
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "unified-spike-stop").start();
        super.onDestroy();
    }

    private final class OutputView extends SurfaceView implements SurfaceHolder.Callback {
        final int output;
        int width, height;
        OutputView(Context context, int output) {
            super(context); this.output = output;
            setFocusable(true); setFocusableInTouchMode(true); requestFocus();
            getHolder().addCallback(this);
        }
        @Override public void surfaceCreated(SurfaceHolder holder) { }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            this.width = width; this.height = height; attach(this);
        }
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            if (ready && !stopping) accepted(output(output, null, 0, 0));
        }
        private boolean pointer(MotionEvent event) {
            if (!ready) return true;
            requestFocus();
            int action = event.getActionMasked();
            int button = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP ? 272 : 0;
            accepted(pointerOutput(output, event.getX(), event.getY(), button, action == MotionEvent.ACTION_DOWN));
            return true;
        }
        @Override public boolean onTouchEvent(MotionEvent event) { return pointer(event); }
        @Override public boolean onGenericMotionEvent(MotionEvent event) { return pointer(event); }
        @Override public boolean onHoverEvent(MotionEvent event) { return pointer(event); }
    }
}
