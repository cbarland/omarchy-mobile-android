package dev.omamobile.host;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.system.Os;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Serial owner in :audio, separate from the main JVM's command/install lease. */
final class SharedAudioRuntime {
    static final String GUEST = "/run/omamobile/a-shared";
    private static SharedAudioRuntime instance;
    static synchronized SharedAudioRuntime get(Context context) {
        if (instance == null) instance = new SharedAudioRuntime(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler handler;
    private Session session;
    private String error;
    private boolean desired;
    private SharedAudioRuntime(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("SharedLinuxAudio"); thread.start();
        handler = new Handler(thread.getLooper());
    }
    void start() { handler.post(() -> {
        desired = true;
        if (session != null) return;
        error = null;
        Session created = new Session();
        session = created;
        try { created.start(); }
        catch (Exception failure) { failed(created, failure); }
    }); }
    void stop() { handler.post(() -> { desired = false; stopOwned(); }); }
    private void stopOwned() {
        if (session == null) return;
        try { session.close(); session = null; }
        catch (Exception failure) {
            error = "Audio shutdown retains ownership: " + failure;
            log(error); // A later Stop can retry; never release a live child's installation lease.
        }
    }
    private void failed(Session owner, Exception failure) {
        if (session != owner) return;
        error = failure.toString(); log("Audio unavailable: " + error);
        stopOwned();
    }
    void status(Consumer<String> reply) { handler.post(() -> {
        try {
            JSONObject value = new JSONObject().put("desired", desired).put("ready", session != null && session.ready)
                    .put("ownerPid", Process.myPid()).put("error", error == null ? JSONObject.NULL : error);
            if (session != null && session.output != null) {
                var pcm = session.output.snapshot();
                value.put("track", pcm.track()).put("focus", pcm.focus()).put("bytes", pcm.bytes())
                        .put("submittedFrames", pcm.submittedFrames()).put("playbackFrames", pcm.playbackFrames())
                        .put("droppedBytes", pcm.droppedBytes()).put("readinessEvents", pcm.readinessEvents());
            }
            reply.accept(value.toString());
        } catch (Exception failure) {
            log("Cannot report audio state: " + failure);
            reply.accept("{\"error\":\"audio status unavailable\"}");
        }
    }); }
    private void log(String text) {
        android.util.Log.i("OmamobileAudio", text);
        try {
            Path directory = context.getFilesDir().toPath().resolve("audio"); Files.createDirectories(directory);
            Path log = directory.resolve("audio.log");
            if (Files.exists(log) && Files.size(log) > 256 * 1024) Files.delete(log);
            Files.write(log, (text + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) { android.util.Log.w("OmamobileAudio", "Cannot save audio log", failure); }
    }
    private static String processStart(int pid) throws IOException {
        String stat = new String(Files.readAllBytes(java.nio.file.Paths.get("/proc/" + pid + "/stat")), StandardCharsets.UTF_8);
        return stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+")[19];
    }
    private static boolean gone(JSONObject identity) throws Exception {
        int pid = identity.getInt("pid");
        if (pid <= 0 || !identity.getString("start").matches("[0-9]+")) throw new IOException("Invalid audio process identity");
        try {
            if (!processStart(pid).equals(identity.getString("start"))) return true;
            if (Os.stat("/proc/" + pid).st_uid != Process.myUid()) throw new IOException("Audio process owner changed");
            return false;
        } catch (java.nio.file.NoSuchFileException exited) { return true; }
    }
    private static JSONObject read(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384)
            throw new IOException("Invalid audio ownership record");
        return new JSONObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }
    private static void childrenGone(Path run) throws Exception {
        Path children = run.resolve("children.json");
        if (Files.exists(children, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(children, LinkOption.NOFOLLOW_LINKS) || Files.size(children) > 16384)
                throw new IOException("Invalid audio child records");
            JSONArray values = new JSONArray(new String(Files.readAllBytes(children), StandardCharsets.UTF_8));
            if (values.length() > 3) throw new IOException("Unexpected audio child count");
            for (int i = 0; i < values.length(); i++)
                if (!gone(values.getJSONObject(i))) throw new IOException("Audio child remains alive");
        }
        Path helper = run.resolve("helper.json");
        if (Files.exists(helper, LinkOption.NOFOLLOW_LINKS) && !gone(read(helper)))
            throw new IOException("Audio helper remains alive");
    }
    private final class Session implements AutoCloseable {
        private InstallationLease lease;
        private Path run;
        private java.lang.Process child;
        private Thread reader;
        private ManagedAudioOutput output;
        private boolean ready;
        private boolean ownsRun;
        void start() throws Exception {
            Path files = context.getFilesDir().toPath();
            Path root = files.resolve("linux/rootfs");
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Linux is not installed");
            lease = InstallAssets.runtimeLease(context, SharedAudioRuntime.this::log);
            run = root.resolve(GUEST.substring(1));
            GuestPaths.noSymlinkParents(root, run);
            if (Files.exists(run, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Audio runtime is not a real directory");
                JSONObject owner = read(run.resolve("owner.json"));
                if (!"omamobile-audio".equals(owner.getString("owner")) || !gone(owner))
                    throw new IOException("Previous audio runtime still owns its files");
                childrenGone(run);
                GuestPaths.deleteTree(run);
            }
            Files.createDirectories(run.getParent());
            Files.createDirectory(run); ownsRun = true;
            HostRuntime.chmod(run, 0700);
            Files.write(run.resolve("owner.json"), new JSONObject().put("owner", "omamobile-audio")
                    .put("pid", Process.myPid()).put("start", processStart(Process.myPid())).toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW);
            Path fifo = run.resolve("output.pcm"); Os.mkfifo(fifo.toString(), 0600);
            try (var source = context.getAssets().open("runtime-audio/audio-session.py")) {
                Files.copy(source, run.resolve("audio-session.py"));
            }
            output = ManagedAudioOutput.open(context, fifo.toFile(), handler, failure -> failed(this, failure));
            var argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(),
                    root, List.of(new RuntimeSpec.Bind("/dev", "/dev", false), new RuntimeSpec.Bind("/proc", "/proc", false)),
                    List.of("/usr/bin/python3", GUEST + "/audio-session.py", GUEST));
            ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
            builder.environment().clear(); builder.environment().putAll(RuntimeSpec.environment(false));
            child = builder.start();
            java.lang.Process started = child;
            reader = new Thread(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        String message = line;
                        handler.post(() -> received(this, message));
                    }
                } catch (IOException failure) { handler.post(() -> failed(this, failure)); }
                handler.post(() -> {
                    if (session == this && desired) failed(this, new IOException("Audio graph exited"));
                });
            }, "SharedAudioStatus");
            reader.start();
            log("Starting shared Linux audio graph");
        }
        @Override public void close() throws Exception {
            ready = false;
            if (output != null) output.silenceForStop();
            if (child != null) {
                try { child.getOutputStream().close(); } catch (IOException alreadyClosed) { }
                if (!child.waitFor(8, TimeUnit.SECONDS))
                    ProcessCleanup.stopAndWait(child, () -> log("Audio helper still stopping; retaining ownership"));
                if (reader != null) {
                    reader.join(2000);
                    if (reader.isAlive()) throw new IOException("Audio status reader remains alive");
                }
                childrenGone(run);
            }
            if (output != null) { output.close(); output = null; }
            if (ownsRun) {
                if (child != null) {
                    JSONObject owner = read(run.resolve("owner.json"));
                    if (owner.getInt("pid") != Process.myPid()
                            || !owner.getString("start").equals(processStart(Process.myPid())))
                        throw new IOException("Audio runtime ownership changed; preserved its files");
                }
                Path logs = context.getFilesDir().toPath().resolve("audio"); Files.createDirectories(logs);
                for (String name : List.of("pipewire.log", "wireplumber.log", "pipewire-pulse.log", "cleanup.json"))
                    if (Files.isRegularFile(run.resolve(name), LinkOption.NOFOLLOW_LINKS))
                        Files.copy(run.resolve(name), logs.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                GuestPaths.deleteTree(run);
                ownsRun = false;
            }
            if (lease != null) { lease.close(); lease = null; }
            log("Shared Linux audio stopped");
        }
    }
    private void received(Session owner, String line) {
        if (session != owner) return;
        try {
            JSONObject event = new JSONObject(line);
            if ("ready".equals(event.optString("event"))) {
                owner.ready = true; log("Shared Linux audio ready");
            } else if ("error".equals(event.optString("event"))) {
                failed(owner, new IOException(event.optString("message")));
            }
        } catch (Exception failure) { log("Audio helper: " + line); }
    }
}
