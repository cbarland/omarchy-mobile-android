package dev.omamobile.host;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.HashMap;
import java.util.Map;

/** Optional Shizuku shell-UID service. No root and no Android policy changes.
 * The interface accepts only displays owned by this app and tied to its live
 * Binder. It exposes no generic shell command, task enumeration or capture API.
 */
public final class AndroidShellService extends IAndroidShell.Stub {
    private final Context context;
    private final int appUid;
    private record Lease(android.hardware.display.VirtualDisplay display, IBinder owner, IBinder.DeathRecipient death) { }
    private final Map<Integer, Lease> owned = new HashMap<>();
    public AndroidShellService(Context context) throws Exception {
        this.context = context.createPackageContext("com.android.shell", 0);
        appUid = context.getPackageManager().getApplicationInfo("dev.omamobile.host", 0).uid;
        if (Process.myUid() != 2000) throw new SecurityException("Only Android shell mode is supported");
    }
    private void caller() { if (Binder.getCallingUid() != appUid) throw new SecurityException("Wrong caller UID"); }
    private synchronized android.view.Display display(int id) {
        caller();
        if (!owned.containsKey(id)) throw new SecurityException("Unowned Android app display");
        android.view.Display display = context.getSystemService(DisplayManager.class).getDisplay(id);
        if (display == null) throw new IllegalStateException("Android app display disconnected");
        return display;
    }
    @Override public int uid() { caller(); return Process.myUid(); }
    @Override public synchronized int create(int width, int height, android.view.Surface surface, IBinder owner) {
        caller();
        if (owner == null || !owner.isBinderAlive() || surface == null || !surface.isValid() || owned.size() >= 3)
            throw new SecurityException("Invalid display owner or too many windows");
        dimensions(width, height);
        long token = Binder.clearCallingIdentity();
        try {
            // AOSP DisplayManager flags: trusted (1<<10) permits ordinary Android
            // activities; destroy-content (1<<8) prevents tasks escaping to phone.
            // No SECURE flag: protected surfaces are never captured for Linux.
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | (1 << 6) | (1 << 8) | (1 << 10);
            // Android 14+: retain each hosted display's keyboard focus while
            // Linux remains focused on the physical external display.
            try { flags |= DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS").getInt(null); }
            catch (ReflectiveOperationException ignored) { }
            // Android 15+: hosted activities need local focus for injected keys,
            // but must never become the destination of the physical Bluetooth
            // keyboard/mouse. OWN_FOCUS alone still lets them steal top focus.
            try { flags |= DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null); }
            catch (ReflectiveOperationException ignored) { }
            var display = context.getSystemService(DisplayManager.class).createVirtualDisplay("Omamobile Android app", width, height, 160, surface, flags);
            if (display == null) throw new IllegalStateException("Android refused a hosted display");
            int id = display.getDisplay().getDisplayId();
            Lease lease = new Lease(display, owner, () -> release(id));
            owned.put(id, lease);
            try {
                // Hardware/host input reaches Linux first. Android's fallback IME
                // must not cover the physical desktop or take its key focus.
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Object wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
                    wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, id, 2);
                    int policy = (int) wm.getClass().getMethod("getDisplayImePolicy", int.class).invoke(wm, id);
                    if (policy != 2) throw new IllegalStateException("Android did not hide the hosted-display IME");
                }
                owner.linkToDeath(lease.death, 0);
            } catch (Exception e) { release(id); throw new IllegalStateException(e); }
            return id;
        }
        finally { Binder.restoreCallingIdentity(token); }
    }
    private static void dimensions(int width, int height) {
        if (width < 240 || height < 240 || width > 1920 || height > 1920) throw new IllegalArgumentException("Invalid window dimensions");
    }
    private synchronized void release(int id) {
        Lease lease = owned.remove(id);
        if (lease != null) {
            try { lease.owner.unlinkToDeath(lease.death, 0); } catch (java.util.NoSuchElementException ignored) { }
            lease.display.release();
        }
    }
    @Override public synchronized void resize(int id, int width, int height, android.view.Surface surface) {
        display(id); dimensions(width, height);
        if (surface == null || !surface.isValid()) throw new IllegalArgumentException("Invalid app surface");
        long token = Binder.clearCallingIdentity();
        try { owned.get(id).display.setSurface(surface); owned.get(id).display.resize(width, height, 160); }
        finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public String launch(int id, String component) {
        display(id);
        if (component == null || !component.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) throw new IllegalArgumentException("Invalid Android component");
        try {
            java.lang.Process child = new ProcessBuilder("/system/bin/am", "start", "--display", Integer.toString(id), "-n", component,
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-f", "0x18000000")
                    .redirectErrorStream(true).start();
            if (!child.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); throw new IllegalStateException("Android activity launch timed out"); }
            var bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int count;
            while ((count = child.getInputStream().read(buffer)) != -1) bytes.write(buffer, 0, count);
            String result = bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            if (child.exitValue() != 0 || result.contains("Error") || result.contains("Exception")) throw new IllegalStateException(result);
            return result;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void inject(InputEvent event, int display) {
        try {
            event.getClass().getMethod("setDisplayId", int.class).invoke(event, display);
            Class<?> manager = Class.forName("android.hardware.input.InputManager");
            Object input = manager.getMethod("getInstance").invoke(null);
            boolean accepted = (boolean) manager.getMethod("injectInputEvent", InputEvent.class, int.class).invoke(input, event, 0);
            if (!accepted) throw new IllegalStateException("Android rejected input");
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @Override public void touch(int id, int action, float x, float y, long downTime) {
        Point size = new Point(); display(id).getRealSize(size);
        if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0 || x >= size.x || y >= size.y || action < 0 || action > 3)
            throw new IllegalArgumentException("Invalid hosted-display touch");
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { inject(event, id); } finally { event.recycle(); }
    }
    @Override public void key(int id, int code, String text) {
        display(id);
        if (text != null && !text.isEmpty()) {
            if (text.length() > 256) throw new IllegalArgumentException("Text is too long");
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
            if (events == null) throw new IllegalArgumentException("Android keyboard cannot represent this text");
            for (KeyEvent event : events) inject(event, id);
        } else {
            if (code < 1 || code > KeyEvent.getMaxKeyCode()) throw new IllegalArgumentException("Invalid Android key");
            inject(new KeyEvent(KeyEvent.ACTION_DOWN, code), id); inject(new KeyEvent(KeyEvent.ACTION_UP, code), id);
        }
    }
    @Override public synchronized void detach(int id) {
        caller(); long token = Binder.clearCallingIdentity();
        try { release(id); } finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public void destroy() {
        if (Binder.getCallingUid() != appUid && Binder.getCallingUid() != 2000) throw new SecurityException("Wrong caller UID");
        Process.killProcess(Process.myPid());
    }
}
