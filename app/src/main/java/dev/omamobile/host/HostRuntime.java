package dev.omamobile.host;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Display;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One owner per Android process/application, independent of activities and display attachment. */
final class HostRuntime {
    // Holds applicationContext only, deliberately for the lifetime of this application process.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static HostRuntime instance;
    static synchronized HostRuntime get(Context context) {
        if (instance == null) instance = new HostRuntime(context.getApplicationContext());
        return instance;
    }
    final Path linux;
    final Path root;
    final Path shared;
    final File nativeDir;
    final DisplayProfiles displays;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean updatePending = new java.util.concurrent.atomic.AtomicBoolean();
    private final StringBuilder log = new StringBuilder();
    private volatile boolean busy;
    private volatile boolean stopping;
    private volatile Process guest;
    private volatile AndroidBroker broker;
    private volatile Thread activeWorker;

    private HostRuntime(Context context) {
        this.context = context;
        linux = context.getFilesDir().toPath().resolve("linux");
        root = linux.resolve("rootfs");
        File external = context.getExternalFilesDir(null);
        shared = (external != null ? external : new File(context.getFilesDir(), "shared")).toPath().resolve("Shared");
        nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        displays = new DisplayProfiles(root.toString());
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        for (Display display : manager.getDisplays()) displays.connect(display.getDisplayId(), display.getDisplayId() == Display.DEFAULT_DISPLAY);
        manager.registerDisplayListener(new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id) { displays.connect(id, id == Display.DEFAULT_DISPLAY); changed(); }
            public void onDisplayRemoved(int id) { displays.disconnect(id); changed(); }
            public void onDisplayChanged(int id) { changed(); }
        }, main);
        append("Native runtime: TAWC/tawcroot (no PRoot).\nGraphics: dedicated Hyprland display sessions share this rootfs.\n");
    }

    boolean busy() { return busy; }
    boolean installed() { return Files.isDirectory(root); }
    synchronized String output() { return log.toString(); }
    void observe(Runnable listener) { listeners.add(listener); }
    void unobserve(Runnable listener) { listeners.remove(listener); }
    private void changed() {
        if (updatePending.compareAndSet(false, true)) main.postDelayed(() -> {
            updatePending.set(false);
            listeners.forEach(Runnable::run);
        }, 50);
    }
    private synchronized void append(String text) {
        if (BuildConfig.DEBUG) android.util.Log.i("OmamobileLinux", text);
        log.append(text);
        if (log.length() > 64 * 1024) log.delete(0, log.length() - 48 * 1024);
        changed();
    }

    synchronized boolean submit(String command, Uri image, boolean ando, boolean androidDns, Runnable done) {
        if (busy) return false;
        busy = true;
        stopping = false;
        changed();
        worker.execute(() -> {
            activeWorker = Thread.currentThread();
            try {
                if (image != null) {
                    var input = context.getContentResolver().openInputStream(image);
                    if (input == null) throw new IOException("Cannot read the selected archive");
                    try (input) {
                        importer().install(input, linux, machine(), this::appendLine);
                    }
                } else {
                    run(command, ando, androidDns);
                }
            } catch (Exception e) {
                appendLine("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                // Reused executor threads must not carry cancellation into a later session.
                Thread.interrupted();
                AndroidBroker activeBroker = broker;
                if (activeBroker != null) activeBroker.close();
                broker = null;
                guest = null;
                activeWorker = null;
                synchronized (this) { busy = false; }
                changed();
                main.post(done);
            }
        });
        return true;
    }

    private void appendLine(String line) { append(line + "\n"); }

    static void chmod(Path path, int mode) throws IOException {
        try { Os.chmod(path.toString(), mode); } catch (ErrnoException e) { throw new IOException(e); }
    }

    static RootfsImporter importer() {
        return new RootfsImporter(HostRuntime::chmod, 8L * 1024 * 1024 * 1024, (source, destination) -> {
            try {
                Os.link(source.toString(), destination.toString());
                return true;
            } catch (ErrnoException e) {
                if (e.errno == android.system.OsConstants.EACCES || e.errno == android.system.OsConstants.EPERM) return false;
                throw new IOException(e);
            }
        });
    }

    int machine() throws IOException {
        String abi = android.os.Build.SUPPORTED_ABIS[0];
        if (abi.equals("arm64-v8a")) return 183;
        if (abi.equals("x86_64")) return 62;
        throw new IOException("Unsupported Android architecture: " + abi);
    }

    private void run(String command, boolean ando, boolean androidDns) throws Exception {
        if (!installed()) throw new IOException("Import a standard ARM64 rootfs first (x86_64 on this emulator)");
        if (GuestPaths.elfMachine(GuestPaths.resolve(root, "/bin/sh")) != machine()) throw new IOException("Rootfs architecture mismatch");
        Files.createDirectories(shared);
        for (String directory : List.of("/tmp", "/root", "/run", "/opt/omamobile/bin")) {
            Files.createDirectories(GuestPaths.resolve(root, directory));
        }
        var binds = new ArrayList<RuntimeSpec.Bind>();
        binds.add(new RuntimeSpec.Bind("/dev", "/dev", false));
        binds.add(new RuntimeSpec.Bind("/proc", "/proc", false));
        binds.add(new RuntimeSpec.Bind("/sys", "/sys", true));
        binds.add(new RuntimeSpec.Bind(shared.toString(), "/android/shared", false));
        for (String partition : List.of("/system", "/apex", "/vendor", "/system_ext")) {
            if (new File(partition).exists()) binds.add(new RuntimeSpec.Bind(partition, partition, true));
        }
        if (androidDns) configureDns();
        if (ando) {
            AndroidBroker active = new AndroidBroker(new File(nativeDir, "libando.so"), linux.resolve("ando").toFile());
            active.start();
            broker = active;
            binds.add(new RuntimeSpec.Bind(active.directory.toString(), "/run/omamobile-ando", false));
            Path bin = Files.createDirectories(linux.resolve("adapter/bin"));
            Files.copy(new File(nativeDir, "libando.so").toPath(), bin.resolve("ando"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            chmod(bin.resolve("ando"), 0755);
            binds.add(new RuntimeSpec.Bind(bin.toString(), "/opt/omamobile/bin", true));
        }
        if (stopping) return;
        List<String> argv = RuntimeSpec.command(new File(nativeDir, "libtawcroot.so").toPath(), root, binds,
                List.of("/bin/sh", "-lc", command));
        ProcessBuilder builder = new ProcessBuilder(argv).directory(GuestPaths.resolve(root, "/root").toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(RuntimeSpec.environment(ando));
        appendLine("$ " + command);
        Process process = builder.start();
        guest = process;
        process.getOutputStream().close(); // Batch console: stdin EOF, no invisible prompt.
        if (stopping) process.destroy();
        try (var input = new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = input.read(buffer)) != -1) append(new String(buffer, 0, read));
        }
        appendLine("\nExit " + process.waitFor());
    }

    private void configureDns() throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        var properties = manager.getLinkProperties(manager.getActiveNetwork());
        if (properties == null || properties.getDnsServers().isEmpty()) {
            appendLine("No Android DNS servers available; keeping the rootfs resolver configuration.");
            return;
        }
        StringBuilder contents = new StringBuilder("# Android active network; generated by Omarchy Mobile host\n");
        properties.getDnsServers().forEach(server -> contents.append("nameserver ").append(server.getHostAddress()).append('\n'));
        Path dns = GuestPaths.resolve(root, "/etc/resolv.conf");
        Files.createDirectories(dns.getParent());
        Files.write(dns, contents.toString().getBytes(StandardCharsets.UTF_8));
        appendLine("Updated Linux /etc/resolv.conf from Android (requested for this operation).");
    }

    void stop() {
        stopping = true;
        Thread job = activeWorker;
        if (job != null && guest == null) job.interrupt();
        Process process = guest;
        if (process != null) {
            process.destroy();
            // Do not block Android's main thread waiting for uncooperative guest programs.
            new Thread(() -> {
                try {
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "linux-stop").start();
        }
        appendLine("Stop requested. Android app force-stop remains the complete process-tree boundary.");
    }
}
