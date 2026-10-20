package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.IOException;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** One process, compositor and Linux client lifetime; Android owns two replaceable outputs. */
final class UnifiedCompositorController {
    private enum Phase { NEW, STARTING, RUNNING, STOPPING }
    private record Output(OutputOwnership.Lease lease, Surface surface, int width, int height) { }
    private static UnifiedCompositorController instance;
    static synchronized UnifiedCompositorController get(Context context) {
        if (instance == null) instance = new UnifiedCompositorController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Ownership and desired output state are confined to Android's main thread.
    private final OutputOwnership ownership = new OutputOwnership();
    private final EnumMap<OutputOwnership.Role, Output> outputs = new EnumMap<>(OutputOwnership.Role.class);
    private final EnumMap<OutputOwnership.Role, Integer> displayIds = new EnumMap<>(OutputOwnership.Role.class);
    private final CopyOnWriteArrayList<Consumer<String>> observers = new CopyOnWriteArrayList<>();
    private volatile Phase phase = Phase.NEW;
    private String status = "Starting shared Hyprland…";
    private CompletableFuture<Output> waitingForPhone;
    private volatile GraphicalSession session;
    // Leases live until process exit, including while Linux children are stopping.
    private InstallationLease installationLease, graphicalModeLease;
    private Thread compositor;
    private final DisplayManager displays;
    private final DisplayManager.DisplayListener displayLifetime = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayChanged(int id) { }
        @Override public void onDisplayRemoved(int id) {
            var role = OutputOwnership.Role.DESKTOP;
            if (!Integer.valueOf(id).equals(displayIds.get(role))) return;
            displayIds.remove(role);
            outputs.remove(role);
            long generation = ownership.retire(role);
            if (phase == Phase.RUNNING) queued("remove", NativeCompositor.outputRemove(role.id(), generation));
            finishTasks(UnifiedDesktopActivity.class.getName());
        }
    };
    private final BroadcastReceiver stopRequest = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String profile = intent.getStringExtra("profile");
            if (profile == null || profile.equals("unified")) stop();
        }
    };

    private UnifiedCompositorController(Context context) {
        this.context = context;
        displays = context.getSystemService(DisplayManager.class);
        displays.registerDisplayListener(displayLifetime, main);
        IntentFilter filter = new IntentFilter(DesktopService.STOP_SCREENS);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(stopRequest, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyStopReceiver(filter);
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyStopReceiver(IntentFilter filter) {
        context.registerReceiver(stopRequest, filter, "dev.omamobile.host.permission.CONTROL_SCREENS", main);
    }

    /** Each Activity retains its own lease, so late input cannot act as its replacement. */
    CompositorEndpoint endpoint(OutputOwnership.Role role) { return new Endpoint(role); }
    private final class Endpoint implements CompositorEndpoint {
        private final OutputOwnership.Role role;
        private OutputOwnership.Lease lease;
        private boolean focused;
        Endpoint(OutputOwnership.Role role) { this.role = role; }
        @Override public void observe(Consumer<String> listener) { observers.add(listener); listener.accept(status); }
        @Override public void unobserve(Consumer<String> listener) { observers.remove(listener); }
        @Override public void attach(Object owner, Surface surface, int width, int height, int displayId) {
            if (phase == Phase.STOPPING || !surface.isValid() || width <= 0 || height <= 0) return;
            // Android may reparent the external task to display zero before
            // onDisplayRemoved arrives. Retain its original physical identity
            // so that callback can retire the output and recover its windows.
            if (!role.acceptsAndroidDisplay(displayId)) {
                android.util.Log.i("OmamobileUnified", "Ignoring reparented " + role + " Surface on display " + displayId);
                return;
            }
            // A replaced/retired Activity cannot reclaim the role through a late resize.
            if (lease != null && !ownership.isGenerationCurrent(role, lease.generation())) return;
            if (displays.getDisplay(displayId) == null) return;
            lease = ownership.claim(role, owner, displayId);
            Output output = new Output(lease, surface, width, height);
            outputs.put(role, output);
            displayIds.put(role, displayId);
            if (phase == Phase.RUNNING) {
                // Native owns actual attachment state. A live same-generation
                // output resizes; a failed prior attach can retry this Surface.
                attachNative(output);
                if (focused) focus(true);
            }
            if (role == OutputOwnership.Role.PHONE) {
                if (waitingForPhone != null) { waitingForPhone.complete(output); waitingForPhone = null; }
                if (phase == Phase.NEW) start();
            }
        }
        @Override public void detach(Object owner) {
            if (lease == null || lease.owner() != owner || !ownership.release(lease)) return;
            outputs.remove(role);
            if (phase == Phase.RUNNING)
                queued("detach", NativeCompositor.outputSurface(role.id(), lease.generation(), null, 0, 0));
        }
        private boolean acceptsInput() { return phase == Phase.RUNNING && ownership.isCurrent(lease); }
        @Override public boolean sharedSeat() { return true; }
        @Override public boolean relativePointer(float dx, float dy, int button, boolean pressed) {
            if (acceptsInput()) NativeCompositor.relativePointerAt(role.id(), lease.generation(), dx, dy, button, pressed);
            return true;
        }
        @Override public void pointer(float x, float y, int button, boolean pressed) {
            if (acceptsInput()) NativeCompositor.pointerAt(role.id(), lease.generation(), x, y, button, pressed);
        }
        @Override public void touch(int id, int action, float x, float y) {
            if (acceptsInput()) NativeCompositor.touchAt(role.id(), lease.generation(), id, action, x, y);
        }
        @Override public void axis(float dx, float dy) {
            if (acceptsInput()) NativeCompositor.axisAt(role.id(), lease.generation(), dx, dy);
        }
        @Override public void key(int code, boolean pressed) {
            if (BuildConfig.DEBUG && android.util.Log.isLoggable("OmamobileKeyTrace", android.util.Log.DEBUG))
                android.util.Log.d("OmamobileKeyTrace", "route role=" + role + " code=" + code + " pressed=" + pressed
                        + " generation=" + (lease == null ? 0 : lease.generation()) + " accepted=" + acceptsInput());
            if (acceptsInput()) NativeCompositor.keyAt(role.id(), lease.generation(), code, pressed);
        }
        @Override public void unicode(int codepoint) {
            if (acceptsInput()) NativeCompositor.unicodeAt(role.id(), lease.generation(), codepoint);
        }
        @Override public void focus(boolean value) {
            focused = value;
            if (acceptsInput()) NativeCompositor.focusAt(role.id(), lease.generation(), value);
        }
    }
    private void status(String value) {
        status = value;
        observers.forEach(listener -> listener.accept(value));
    }
    private void queued(String operation, int result) {
        if (result < 0) {
            android.util.Log.e("OmamobileUnified", operation + " queue rejected: " + result);
            status("Shared display " + operation + " failed (" + result + "). See Hyprland logs.");
        }
    }
    private void attachNative(Output output) {
        var lease = output.lease();
        queued("attach", NativeCompositor.outputSurface(lease.role().id(), lease.generation(),
                output.surface(), output.width(), output.height()));
    }
    private void outputState(int id, long generation, int result) {
        main.post(() -> {
            if (phase == Phase.STOPPING || id < 0 || id >= OutputOwnership.Role.values().length) return;
            var role = OutputOwnership.Role.values()[id];
            if (!ownership.isGenerationCurrent(role, generation)) return;
            android.util.Log.i("OmamobileUnified", "output=" + id + " generation=" + generation + " state=" + result);
            if (result < 0) status("Shared display update failed (" + result + "). See Hyprland logs.");
        });
    }
    private Output awaitPhone() throws Exception {
        CompletableFuture<Output> current = new CompletableFuture<>();
        main.post(() -> {
            if (phase == Phase.STOPPING) current.completeExceptionally(new IOException("Shared start cancelled"));
            else {
                Output phone = outputs.get(OutputOwnership.Role.PHONE);
                if (phone != null && phone.surface().isValid()) current.complete(phone);
                else waitingForPhone = current;
            }
        });
        return current.get();
    }
    private void start() {
        phase = Phase.STARTING;
        compositor = new Thread(() -> {
            try {
                if (!NativeCompositor.AVAILABLE) throw new IOException("This APK has no Hyprland backend for this device ABI");
                graphicalModeLease = GraphicalModeLease.acquire(context.getFilesDir().toPath(), true, () -> phase == Phase.STOPPING);
                installationLease = InstallAssets.runtimeLease(context, message -> android.util.Log.i("OmamobileUnified", message));
                if (phase == Phase.STOPPING) return;
                session = new GraphicalSession(context, "unified", 0);
                Output initial = awaitPhone();
                if (phase == Phase.STOPPING) return;
                NativeCompositor.outputListener = this::outputState;
                NativeCompositor.listener = (display, signature) -> main.post(() -> ready(initial, display, signature));
                int result = NativeCompositor.runUnified(initial.surface(), initial.width(), initial.height(), initial.lease().generation(),
                        session.home.toString(), session.runtime.toString(), session.config.toString(), session.root.toString(),
                        session.wrapper.toString(), context.getApplicationInfo().nativeLibraryDir, session.magic.toString(), session.xkb.toString());
                android.util.Log.i("OmamobileUnified", "Native compositor exited: " + result);
            } catch (Exception error) {
                android.util.Log.e("OmamobileUnified", "Shared compositor failed", error);
            } finally {
                NativeCompositor.listener = null;
                NativeCompositor.outputListener = null;
                main.post(() -> stop(false));
            }
        }, "shared-hyprland");
        compositor.start();
    }
    private void ready(Output initial, String display, String signature) {
        if (phase == Phase.STOPPING) return;
        phase = Phase.RUNNING;
        status("");
        Output phone = outputs.get(OutputOwnership.Role.PHONE);
        if (phone == null) {
            queued("detach", NativeCompositor.outputSurface(0, initial.lease().generation(), null, 0, 0));
        } else if (phone.lease() == initial.lease()) {
            queued("resize", NativeCompositor.outputResize(0, phone.lease().generation(), phone.width(), phone.height()));
        } else attachNative(phone);
        Output desktop = outputs.get(OutputOwnership.Role.DESKTOP);
        if (desktop != null) attachNative(desktop);
        new Thread(() -> {
            try {
                if (phase == Phase.STOPPING) return;
                session.startClients(display, signature);
                // TAWC's PDEATHSIG follows the launching thread, not just its process.
                int exit = session.awaitClients();
                main.post(() -> { if (phase == Phase.RUNNING) status("Omarchy shell exited (" + exit + "). See " + session.home.resolve("linux-shell.log")); });
            } catch (Exception error) {
                android.util.Log.e("OmamobileUnified", "Shared clients failed", error);
                main.post(() -> { if (phase == Phase.RUNNING) status("Linux shell failed: " + error.getMessage()); });
            }
        }, "shared-clients").start();
    }
    void stop() { stop(true); }
    private void stop(boolean user) {
        if (phase == Phase.STOPPING) return;
        phase = Phase.STOPPING;
        if (waitingForPhone != null) waitingForPhone.completeExceptionally(new IOException("Shared start cancelled"));
        displays.unregisterDisplayListener(displayLifetime);
        context.unregisterReceiver(stopRequest);
        finishTasks(UnifiedPhoneActivity.class.getName());
        finishTasks(UnifiedDesktopActivity.class.getName());
        new Thread(() -> {
            android.os.SystemClock.sleep(6000);
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "shared-stop-deadline").start();
        new Thread(() -> {
            try {
                if (session != null) { if (user) session.stopForUser(); else session.stop(); }
                if (NativeCompositor.AVAILABLE) NativeCompositor.stop();
                if (compositor != null) compositor.join(1500);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { android.os.Process.killProcess(android.os.Process.myPid()); }
        }, "stop-shared").start();
    }
    private void finishTasks(String activityName) {
        for (var task : context.getSystemService(ActivityManager.class).getAppTasks()) {
            try {
                var info = task.getTaskInfo();
                var component = info == null || info.baseIntent == null ? null : info.baseIntent.getComponent();
                if (component != null && context.getPackageName().equals(component.getPackageName())
                        && activityName.equals(component.getClassName())) task.finishAndRemoveTask();
            } catch (IllegalArgumentException alreadyRemoved) {
                android.util.Log.d("OmamobileUnified", "Output task already removed", alreadyRemoved);
            }
        }
    }
}
