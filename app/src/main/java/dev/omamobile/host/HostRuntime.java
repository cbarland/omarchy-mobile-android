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
    private java.io.Closeable activeInput;
    private BootstrapDownload activeDownload;
    private volatile AndroidBroker broker;
    private volatile Thread activeWorker;
    private volatile boolean recordingSetup;

    private HostRuntime(Context context) {
        this.context = context;
        linux = context.getFilesDir().toPath().resolve("linux");
        root = linux.resolve("rootfs");
        File external = context.getExternalFilesDir(null);
        shared = (external != null ? external : new File(context.getFilesDir(), "shared")).toPath().resolve("Shared");
        nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
        Path previousSetup = linux.resolve("install.log");
        try {
            if (Files.isRegularFile(previousSetup)) {
                try (var channel = Files.newByteChannel(previousSetup)) {
                    channel.position(Math.max(0, channel.size() - 48 * 1024));
                    var tail = java.nio.ByteBuffer.allocate(48 * 1024);
                    while (tail.hasRemaining() && channel.read(tail) != -1) { }
                    log.append(new String(tail.array(), 0, tail.position(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException error) { android.util.Log.w("OmamobileLinux", "Cannot read previous setup log", error); }
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
    boolean graphicsReady() {
        return Files.isRegularFile(root.resolve("root/.local/share/omamobile/start-shell"))
                && Files.isRegularFile(root.resolve("usr/bin/quickshell"));
    }
    boolean installerAvailable() {
        return new File(nativeDir, "libomamobile_hyprland.so").isFile() && InstallAssets.available(context);
    }
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
        if (recordingSetup) try {
            Files.write(linux.resolve("install.log"), text.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException error) { android.util.Log.w("OmamobileLinux", "Cannot persist setup log", error); }
        if (log.length() > 64 * 1024) log.delete(0, log.length() - 48 * 1024);
        changed();
    }

    synchronized boolean submit(String command, Uri image, boolean ando, boolean androidDns, Runnable done) {
        return submit(command, image, ando, androidDns, false, done);
    }

    synchronized boolean exportData(Uri destination, Runnable done) {
        if (busy || destination == null || !installed()) return false;
        try {
            // A force-stop can bypass the worker's finally block. Reclaim only
            // our exact private export names before admitting the next writer.
            try (var children = Files.list(linux)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    if (child.getFileName().toString().matches("export-[0-9a-f-]{36}\\.tmp"))
                        GuestPaths.deleteTree(child);
                }
            }
        } catch (IOException error) {
            appendLine("Cannot reclaim an abandoned export archive: " + error.getMessage());
            return false;
        }
        busy = true; stopping = false; recordingSetup = true; changed();
        worker.execute(() -> {
            activeWorker = Thread.currentThread();
            Path temporary = linux.resolve("export-" + java.util.UUID.randomUUID() + ".tmp");
            try {
                Files.createDirectories(linux);
                try (var output = Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    appendLine("Exporting Linux user data (/root and /home) to a temporary archive…");
                    importer().exportUserData(root, output, this::appendLine);
                }
                checkSetupRunning();
                var output = context.getContentResolver().openOutputStream(destination);
                if (output == null) throw new IOException("Cannot write the selected export destination");
                try (output; var input = Files.newInputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024]; int read;
                    while ((read = input.read(buffer)) != -1) { checkSetupRunning(); output.write(buffer, 0, read); }
                }
                appendLine("Linux user data export completed.");
            } catch (Exception e) {
                appendLine(stopping ? "Operation cancelled." : "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                try { GuestPaths.deleteTree(temporary); }
                catch (IOException error) { android.util.Log.w("OmamobileLinux", "Cannot remove temporary export", error); }
                Thread.interrupted(); activeWorker = null; recordingSetup = false; synchronized (this) { busy = false; }
                changed(); main.post(done);
            }
        });
        return true;
    }

    synchronized boolean recoverData(Uri archive, Runnable done) {
        if (busy || archive == null || !installed()) return false;
        busy = true; stopping = false; recordingSetup = true; changed();
        worker.execute(() -> {
            activeWorker = Thread.currentThread();
            try {
                checkSetupRunning();
                appendLine("Recovering Linux user data as a separate copy under /root/Recovered/…");
                Path recovered = root.resolve("root/Recovered");
                GuestPaths.noSymlinkParents(root, recovered);
                if (Files.exists(recovered, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(recovered, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Recovered data directory is not a real directory");
                Files.createDirectories(recovered);
                try (var children = Files.list(linux)) {
                    for (Path child : (Iterable<Path>) children::iterator) {
                        checkSetupRunning();
                        if (child.getFileName().toString().matches("recovery-[0-9a-f-]{36}\\.tmp"))
                            GuestPaths.deleteTree(child);
                    }
                }
                checkSetupRunning();
                var input = context.getContentResolver().openInputStream(archive);
                if (input == null) throw new IOException("Cannot read the selected recovery archive");
                synchronized (this) { activeInput = input; }
                try (input) {
                    checkSetupRunning();
                    importer().recoverUserData(input, root, this::appendLine);
                }
                appendLine("Linux user data recovery completed; the existing installation was preserved.");
            } catch (Exception e) {
                appendLine(stopping ? "Operation cancelled." : "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                Thread.interrupted(); synchronized (this) { activeInput = null; } activeWorker = null; recordingSetup = false;
                synchronized (this) { busy = false; }
                changed(); main.post(done);
            }
        });
        return true;
    }

    synchronized boolean submit(String command, Uri image, boolean ando, boolean androidDns, boolean installOmarchy, Runnable done) {
        if (busy) return false;
        busy = true;
        stopping = false;
        changed();
        worker.execute(() -> {
            activeWorker = Thread.currentThread();
            java.io.Closeable inputForCleanup = null;
            try {
                checkSetupRunning();
                if (image != null) {
                    if (installOmarchy) {
                        beginOmarchyInstall("Installing Omarchy from the selected trusted Linux archive.");
                    }
                    var input = context.getContentResolver().openInputStream(image);
                    if (input == null) throw new IOException("Cannot read the selected archive");
                    inputForCleanup = input;
                    synchronized (this) { activeInput = input; }
                    try (input) {
                        // Register before this check: stop() may race with provider open.
                        checkSetupRunning();
                        importer().install(input, linux, machine(), this::appendLine, staging -> {
                            if (installOmarchy) prepareOmarchy(staging);
                        });
                    }
                    if (installOmarchy) appendLine("Omarchy is ready. Press Start Omarchy screens.");
                } else {
                    run(command, ando, androidDns);
                }
            } catch (Exception e) {
                appendLine(stopping ? "Operation cancelled." : "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                // Reused executor threads must not carry cancellation into a later session.
                Thread.interrupted();
                AndroidBroker activeBroker = broker;
                if (activeBroker != null) activeBroker.close();
                broker = null;
                guest = null;
                activeWorker = null;
                recordingSetup = false;
                synchronized (this) {
                    if (inputForCleanup != null && activeInput == inputForCleanup) activeInput = null;
                }
                synchronized (this) { busy = false; }
                changed();
                main.post(done);
            }
        });
        return true;
    }

    synchronized boolean installDownloaded(Runnable done) {
        if (busy || installed()) return false;
        busy = true; stopping = false; changed();
        worker.execute(() -> {
            activeWorker = Thread.currentThread();
            try {
                checkSetupRunning();
                BootstrapSource source = BootstrapSource.forMachine(machine());
                beginOmarchyInstall("Downloading a publisher-authenticated Arch bootstrap.");
                BootstrapDownload download = new BootstrapDownload();
                synchronized (this) { activeDownload = download; }
                checkSetupRunning();
                BootstrapDownload.Verified verified;
                try (var key = context.getResources().openRawResource(source.keyResource)) {
                    verified = download.prepare(source, linux, key, this::appendLine);
                }
                try (verified; var input = Files.newInputStream(verified.archive)) {
                    synchronized (this) { activeInput = input; }
                    checkSetupRunning();
                    importer().install(input, linux, source.machine, this::appendLine, this::prepareOmarchy, source.layout);
                }
                appendLine("Omarchy is ready. Press Start Omarchy screens.");
            } catch (Exception error) {
                appendLine(stopping ? "Operation cancelled." : "Error: "
                        + (error.getMessage() == null ? error.toString() : error.getMessage()));
            } finally {
                Thread.interrupted();
                synchronized (this) { activeInput = null; activeDownload = null; }
                guest = null; activeWorker = null; recordingSetup = false;
                synchronized (this) { busy = false; }
                changed(); main.post(done);
            }
        });
        return true;
    }

    private void beginOmarchyInstall(String description) throws IOException {
        if (!installerAvailable()) throw new IOException("Install the full graphical APK to set up Omarchy");
        if (installed()) throw new IOException("An installation already exists; it will not be replaced");
        checkSetupRunning();
        Files.createDirectories(linux);
        Files.write(linux.resolve("install.log"), new byte[0]);
        recordingSetup = true;
        appendLine(description + " Signed packages require internet access.");
        // A killed worker cannot run finally. Only unpublished app-owned work
        // is reclaimed, after the foreground operation has claimed ownership.
        try (var children = Files.list(linux)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                checkSetupRunning();
                if (child.getFileName().toString().matches("(?:import|setup|download)-[0-9a-f-]{36}"))
                    GuestPaths.deleteTree(child);
            }
        }
    }

    private void appendLine(String line) { append(line + "\n"); }

    private void prepareOmarchy(Path staging) throws Exception {
        Path assets = linux.resolve("setup-" + java.util.UUID.randomUUID());
        Files.createDirectory(assets);
        try {
            checkSetupRunning();
            appendLine("Verifying the APK's pinned Omarchy shell sources…");
            InstallAssets.stage(context, assets);
            try (var payload = Files.newInputStream(assets.resolve("linux-shell.tgz"))) {
                importer().overlay(payload, staging, this::appendLine);
            }
            configureDns(staging);
            Files.createDirectories(shared);
            for (String name : List.of("/tmp", "/root", "/run")) Files.createDirectories(GuestPaths.resolve(staging, name));
            var binds = List.of(new RuntimeSpec.Bind("/dev", "/dev", false),
                    new RuntimeSpec.Bind("/proc", "/proc", false), new RuntimeSpec.Bind("/sys", "/sys", true),
                    new RuntimeSpec.Bind(shared.toString(), "/android/shared", false),
                    new RuntimeSpec.Bind(assets.toString(), "/run/omamobile-install", true));
            List<String> argv = RuntimeSpec.command(new File(nativeDir, "libtawcroot.so").toPath(), staging, binds,
                    List.of("/bin/bash", "-c", "set -e; /bin/bash /run/omamobile-install/prepare-arch-graphics; "
                            + "/bin/bash /root/.local/share/omamobile/setup-linux-shell; "
                            + "pacman -Q hyprland quickshell foot; test -r /usr/share/X11/xkb/rules/evdev"));
            ProcessBuilder builder = new ProcessBuilder(argv).directory(GuestPaths.resolve(staging, "/root").toFile())
                    .redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().putAll(RuntimeSpec.environment(false));
            builder.environment().put("GSETTINGS_BACKEND", "keyfile");
            builder.environment().put("OMARCHY_THEME_HEADLESS", "1");
            appendLine("Installing signed Linux packages and preparing phone/desktop profiles…");
            checkSetupRunning();
            Process process = builder.start();
            guest = process;
            process.getOutputStream().close();
            if (stopping) process.destroy();
            int exit;
            try {
                try (var in = new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = in.read(buffer)) != -1) append(new String(buffer, 0, count));
                }
                exit = process.waitFor();
            } finally {
                if (process.isAlive()) {
                    process.destroy();
                    try { if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
                    catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
                }
                guest = null;
            }
            checkSetupRunning();
            if (exit != 0) throw new IOException("Linux setup failed (exit " + exit + "). See the setup log; retry with the archive.");
            Path provenance = GuestPaths.resolve(staging, "/usr/share/omamobile/android/install-manifest.json");
            Files.copy(assets.resolve("install-manifest.json"), provenance);
            appendLine("Linux setup completed; activating the prepared installation.");
        } finally { GuestPaths.deleteTree(assets); }
    }

    private void checkSetupRunning() throws IOException {
        if (stopping || Thread.currentThread().isInterrupted()) throw new IOException("Installation cancelled");
    }

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
        if (androidDns) configureDns(root);
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

    private void configureDns(Path guestRoot) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        var properties = manager.getLinkProperties(manager.getActiveNetwork());
        if (properties == null || properties.getDnsServers().isEmpty()) {
            appendLine("No Android DNS servers available; keeping the rootfs resolver configuration.");
            return;
        }
        StringBuilder contents = new StringBuilder("# Android active network; generated by Omarchy Mobile host\n");
        properties.getDnsServers().forEach(server -> contents.append("nameserver ").append(server.getHostAddress()).append('\n'));
        Path dns = GuestPaths.resolve(guestRoot, "/etc/resolv.conf");
        Files.createDirectories(dns.getParent());
        Files.write(dns, contents.toString().getBytes(StandardCharsets.UTF_8));
        appendLine("Updated Linux /etc/resolv.conf from Android (requested for this operation).");
    }

    synchronized void stop() {
        stopping = true;
        Thread job = activeWorker;
        if (activeDownload != null) activeDownload.cancel();
        java.io.Closeable input = activeInput;
        if (input != null) new Thread(() -> {
            try { input.close(); } catch (java.io.IOException ignored) { }
        }, "linux-recovery-close").start();
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
