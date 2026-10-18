package dev.omamobile.host;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { bound.complete(new Messenger(service)); }
            @Override public void onServiceDisconnected(ComponentName name) { }
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
        boolean connected = context.bindService(new Intent(context, SharedAudioService.class), connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_ABOVE_CLIENT);
        assertTrue(connected);
        try {
            Messenger service = bound.get(10, TimeUnit.SECONDS);
            JSONObject state = await(service, reply, replies, value -> value.optBoolean("ready"));
            assertFalse(state.optBoolean("track"));
            assertFalse(state.optBoolean("focus"));
            int owner = state.getInt("ownerPid");
            tone(work.resolve("tone.wav"));
            long previous = 0;
            for (String client : List.of("pw-play", "paplay")) {
                try (InstallationLease command = InstallationLease.acquire(root.getParent(), InstallationLease.Mode.SHARED_USE)) {
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
            context.unbindService(connection); connected = false;
            state = await(service, reply, replies, value -> !value.optBoolean("ready") && !value.optBoolean("desired"));
            assertTrue(state.toString(), state.isNull("error"));
            assertFalse(Files.exists(root.resolve(SharedAudioRuntime.GUEST.substring(1))));
            try (InstallationLease install = InstallationLease.acquire(root.getParent(), InstallationLease.Mode.EXCLUSIVE_INSTALL)) {
                Files.write(evidence.resolve("stopped.json"), state.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            for (java.lang.Process player : players) ProcessCleanup.stopAndWait(player, () -> { });
            if (connected) context.unbindService(connection);
            GuestPaths.deleteTree(work);
        }
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
    private static void tone(Path path) throws Exception {
        int frames = 3 * 48000;
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
