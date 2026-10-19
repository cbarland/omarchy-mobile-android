package dev.omamobile.host;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;

/** Real graph, clients, Android focus and track; no claim of speaker audibility. */
public final class SharedAudioServiceTest {
    @Test public void ordinaryClientsShareOutputReleaseOnIdleAndStop() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e sharedAudio true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("sharedAudio")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Thread.sleep(750);
        CompletableFuture<Messenger> bound = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<Messenger> live = new java.util.concurrent.atomic.AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                Messenger messenger = new Messenger(service); live.set(messenger); bound.complete(messenger);
            }
            @Override public void onServiceDisconnected(ComponentName name) { live.set(null); }
        };
        LinkedBlockingQueue<JSONObject> replies = new LinkedBlockingQueue<>();
        Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            try { replies.add(new JSONObject(message.getData().getString("status"))); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return true;
        }));
        Path root = context.getFilesDir().toPath().resolve("linux/rootfs");
        Path work = Files.createTempDirectory(root.resolve("tmp"), "shared-audio-test-");
        Path evidence = context.getCacheDir().toPath().resolve("shared-audio-proof");
        Files.createDirectories(evidence);
        java.util.ArrayList<java.lang.Process> players = new java.util.ArrayList<>();
        InstallationLease commands = null;
        AudioManager manager = context.getSystemService(AudioManager.class);
        AudioFocusRequest interruption = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AndroidPcmOutput.attributes())
                .setOnAudioFocusChangeListener(value -> { }, new Handler(Looper.getMainLooper())).build();
        boolean connected = context.bindService(new Intent(context, SharedAudioService.class), connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ABOVE_CLIENT);
        assertTrue(connected);
        try {
            Messenger service = bound.get(10, TimeUnit.SECONDS);
            JSONObject state = await(service, reply, replies, value -> value.optBoolean("ready"));
            assertFalse(state.optBoolean("track"));
            assertFalse(state.optBoolean("focus"));
            int owner = state.getInt("ownerPid");
            assertThrows(InstallationLease.BusyException.class,
                    () -> InstallationLease.acquire(root.getParent(), InstallationLease.Mode.EXCLUSIVE_INSTALL));
            commands = InstallationLease.acquire(root.getParent(), InstallationLease.Mode.SHARED_USE);
            tone(work.resolve("tone.wav"), 3);
            long previous = 0;
            for (String client : List.of("pw-play", "paplay")) {
                {
                    String guest = "/tmp/" + work.getFileName() + "/tone.wav";
                    var argv = RuntimeSpec.command(java.nio.file.Paths.get(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so"),
                            root, List.of(new RuntimeSpec.Bind("/dev", "/dev", false), new RuntimeSpec.Bind("/proc", "/proc", false)),
                            List.of("/usr/bin/" + client, guest));
                    ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true)
                            .redirectOutput(work.resolve(client + ".log").toFile());
                    builder.environment().clear();
                    builder.environment().putAll(RuntimeSpec.graphicalEnvironment(client.equals("pw-play") ? "phone" : "desktop",
                            client.equals("pw-play") ? "/run/omamobile/p" : "/run/omamobile/d", "UTC"));
                    java.lang.Process player = builder.start(); players.add(player);
                    long baseline = previous;
                    state = await(service, reply, replies, value -> value.optBoolean("track")
                            && value.optBoolean("focus") && value.optLong("playbackFrames") > baseline + 1000);
                    assertTrue("Progress must occur while the ordinary client is active", player.isAlive());
                    assertTrue(player.waitFor(10, TimeUnit.SECONDS));
                    assertEquals(new String(Files.readAllBytes(work.resolve(client + ".log")), java.nio.charset.StandardCharsets.UTF_8), 0, player.exitValue());
                    state = await(service, reply, replies, value -> !value.optBoolean("track") && !value.optBoolean("focus"));
                    assertTrue(state.toString(), state.getLong("playbackFrames") > previous + 48000);
                    assertEquals(0, state.getLong("droppedBytes"));
                    assertEquals(owner, state.getInt("ownerPid"));
                    previous = state.getLong("playbackFrames");
                    Files.write(evidence.resolve(client + ".json"), state.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            tone(work.resolve("mix.wav"), 6);
            java.lang.Process nativePlayer = player(context, root, work, "pw-play", "phone", "native-mix");
            java.lang.Process pulsePlayer = player(context, root, work, "paplay", "desktop", "pulse-mix");
            players.add(nativePlayer); players.add(pulsePlayer);
            state = await(service, reply, replies, value -> value.optBoolean("track") && value.optBoolean("focus"));
            long mixDeadline = SystemClock.elapsedRealtime() + 3000;
            while (!mixedGraph(context, root, work, evidence)) {
                assertTrue("Both clients must link actively to the shared sink", SystemClock.elapsedRealtime() < mixDeadline);
                Thread.sleep(50);
            }
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(interruption));
            state = await(service, reply, replies, value -> !value.optBoolean("track") && !value.optBoolean("focus"));
            assertTrue(nativePlayer.isAlive()); assertTrue(pulsePlayer.isAlive());
            long beforeGain = state.getLong("playbackFrames");
            manager.abandonAudioFocusRequest(interruption);
            state = await(service, reply, replies, value -> value.optBoolean("track") && value.optBoolean("focus")
                    && value.optLong("playbackFrames") > beforeGain + 1000);
            assertEquals(owner, state.getInt("ownerPid"));
            assertTrue(nativePlayer.waitFor(10, TimeUnit.SECONDS));
            assertTrue(pulsePlayer.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, nativePlayer.exitValue()); assertEquals(0, pulsePlayer.exitValue());
            state = await(service, reply, replies, value -> !value.optBoolean("track") && !value.optBoolean("focus"));
            Files.write(evidence.resolve("mixed-focus-recovery.json"), state.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertNotEquals(android.os.Process.myPid(), owner);
            assertEquals(android.os.Process.myUid(), android.system.Os.stat("/proc/" + owner).st_uid);
            String commandLine = new String(Files.readAllBytes(java.nio.file.Paths.get("/proc/" + owner + "/cmdline")),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(commandLine.contains("dev.omamobile.host:audio"));
            android.system.Os.kill(owner, android.system.OsConstants.SIGKILL);
            long reconnectDeadline = SystemClock.elapsedRealtime() + 20000;
            while (true) {
                assertTrue("Android did not reconnect the bound audio owner", SystemClock.elapsedRealtime() < reconnectDeadline);
                Messenger candidate = live.get();
                if (candidate != null && candidate != service) { service = candidate; break; }
                Thread.sleep(100);
            }
            state = await(service, reply, replies, value -> value.optBoolean("ready"));
            assertNotEquals(owner, state.getInt("ownerPid"));
            tone(work.resolve("mix.wav"), 2);
            java.lang.Process recovered = player(context, root, work, "pw-play", "phone", "recovered");
            players.add(recovered);
            state = await(service, reply, replies, value -> value.optBoolean("track") && value.optBoolean("focus")
                    && value.optLong("playbackFrames") > 1000);
            assertTrue(recovered.waitFor(8, TimeUnit.SECONDS)); assertEquals(0, recovered.exitValue());
            state = await(service, reply, replies, value -> !value.optBoolean("track") && !value.optBoolean("focus"));
            Files.write(evidence.resolve("owner-death-recovery.json"), state.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (java.lang.Process player : players) ProcessCleanup.stopAndWait(player, () -> { });
            commands.close(); commands = null;
            context.unbindService(connection); connected = false;
            state = await(service, reply, replies, value -> !value.optBoolean("ready") && !value.optBoolean("desired"));
            assertTrue(state.toString(), state.isNull("error"));
            assertFalse(Files.exists(root.resolve(SharedAudioRuntime.GUEST.substring(1))));
            try (InstallationLease install = InstallationLease.acquire(root.getParent(), InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
                Files.write(evidence.resolve("stopped.json"), state.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            manager.abandonAudioFocusRequest(interruption);
            for (java.lang.Process player : players) ProcessCleanup.stopAndWait(player, () -> { });
            if (commands != null) commands.close();
            if (connected) context.unbindService(connection);
            GuestPaths.deleteTree(work);
        }
    }
    private static java.lang.Process player(Context context, Path root, Path work, String client,
                                            String profile, String label) throws Exception {
        var argv = RuntimeSpec.command(java.nio.file.Paths.get(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so"),
                root, List.of(new RuntimeSpec.Bind("/dev", "/dev", false), new RuntimeSpec.Bind("/proc", "/proc", false)),
                List.of("/usr/bin/" + client, "/tmp/" + work.getFileName() + "/mix.wav"));
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectOutput(work.resolve(label + ".log").toFile());
        builder.environment().clear();
        builder.environment().putAll(RuntimeSpec.graphicalEnvironment(profile, "/run/omamobile/" + profile, "UTC"));
        return builder.start();
    }
    private static boolean mixedGraph(Context context, Path root, Path work, Path evidence) throws Exception {
        var argv = RuntimeSpec.command(java.nio.file.Paths.get(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so"),
                root, List.of(new RuntimeSpec.Bind("/dev", "/dev", false), new RuntimeSpec.Bind("/proc", "/proc", false)),
                List.of("/usr/bin/pw-dump"));
        Path snapshot = work.resolve("mix-graph.json");
        ProcessBuilder builder = new ProcessBuilder(argv).redirectOutput(snapshot.toFile())
                .redirectError(work.resolve("mix-query.log").toFile());
        builder.environment().clear(); builder.environment().putAll(RuntimeSpec.environment(false));
        java.lang.Process query = builder.start();
        try {
            assertTrue(query.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, query.exitValue());
        } finally { ProcessCleanup.stopAndWait(query, () -> { }); }
        byte[] encoded = Files.readAllBytes(snapshot);
        org.json.JSONArray graph = new org.json.JSONArray(new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
        java.util.Set<Integer> sinks = new java.util.HashSet<>(), streams = new java.util.HashSet<>(), linked = new java.util.HashSet<>();
        for (int i = 0; i < graph.length(); i++) {
            JSONObject item = graph.getJSONObject(i), info = item.optJSONObject("info");
            if (info == null || !item.optString("type").endsWith(":Node")) continue;
            JSONObject properties = info.optJSONObject("props"); if (properties == null) continue;
            if ("omamobile-android".equals(properties.optString("node.name"))) sinks.add(item.getInt("id"));
            if ("Stream/Output/Audio".equals(properties.optString("media.class"))) streams.add(item.getInt("id"));
        }
        assertEquals(1, sinks.size());
        for (int i = 0; i < graph.length(); i++) {
            JSONObject item = graph.getJSONObject(i), info = item.optJSONObject("info");
            if (info == null || !item.optString("type").endsWith(":Link")) continue;
            if ("active".equals(info.optString("state")) && sinks.contains(info.optInt("input-node-id"))
                    && streams.contains(info.optInt("output-node-id"))) linked.add(info.getInt("output-node-id"));
        }
        Files.write(evidence.resolve("mixing-graph.json"), encoded);
        return linked.size() >= 2;
    }
    private interface Condition { boolean test(JSONObject value) throws Exception; }
    private static JSONObject await(Messenger service, Messenger reply, LinkedBlockingQueue<JSONObject> replies,
                                    Condition condition) throws Exception {
        long end = SystemClock.elapsedRealtime() + 18000;
        JSONObject last = null;
        while (SystemClock.elapsedRealtime() < end) {
            Message request = Message.obtain(null, SharedAudioService.STATUS); request.replyTo = reply; service.send(request);
            last = replies.poll(3, TimeUnit.SECONDS); assertNotNull("Audio status timed out", last);
            assertTrue(last.toString(), last.isNull("error"));
            if (condition.test(last)) return last;
            Thread.sleep(100);
        }
        throw new AssertionError("Audio state did not converge: " + last);
    }
    private static void tone(Path path, int seconds) throws Exception {
        int frames = seconds * 48000;
        ByteBuffer wav = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + frames * 4);
        wav.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(48000).putInt(192000)
                .putShort((short) 4).putShort((short) 16);
        wav.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(frames * 4);
        for (int i = 0; i < frames; i++) {
            short value = (short) (5000 * Math.sin(2 * Math.PI * 440 * i / 48000));
            wav.putShort(value).putShort(value);
        }
        Files.write(path, wav.array());
    }
}
