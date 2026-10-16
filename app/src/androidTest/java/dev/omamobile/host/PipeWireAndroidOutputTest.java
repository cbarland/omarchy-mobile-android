package dev.omamobile.host;

import android.content.Context;
import android.media.AudioTrack;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Linux producer and Android consumer, deliberately disconnected from application services. */
public class PipeWireAndroidOutputTest {
    @Test public void pipeWireFramesReachAndroidPlaybackAndNormalStopReapsProducer() throws Exception {
        run(false);
    }

    @Test public void cancelledOutputAndInjectedFixtureFailureReleaseAllOwnedResources() throws Exception {
        run(true);
    }

    private void run(boolean injectFailure) throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e pipewireAudio true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("pipewireAudio")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        try (InstallationLease ignored = InstallationLease.acquire(
                context.getFilesDir().toPath().resolve("linux"), InstallationLease.Mode.SHARED_USE)) {
            runOwned(context, injectFailure);
        }
    }

    private void runOwned(Context context, boolean injectFailure) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Path root = context.getFilesDir().toPath().resolve("linux/rootfs");
        assertTrue("Prepared Linux is required; do not skip this opted-in test", Files.isDirectory(root));
        String name = "pa-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = Files.createDirectory(root.resolve("tmp").resolve(name));
        Os.chmod(directory.toString(), 0700);
        Path fifo = directory.resolve("out.pcm");
        Os.mkfifo(fifo.toString(), 0600);
        var fifoIdentity = Os.lstat(fifo.toString());
        Path evidence = context.getCacheDir().toPath().resolve("pipewire-android-proof").resolve(name);
        Files.createDirectories(evidence);
        Process helper = null;
        AndroidPcmOutput output = null;
        try {
            Path script = directory.resolve("fixture.py");
            try (var asset = instrumentation.getContext().getAssets().open("pipewire_pcm_fixture.py")) {
                Files.copy(asset, script);
            }
            output = AndroidPcmOutput.open(context, fifo.toFile());
            var argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir,
                    "libtawcroot.so").toPath(), root,
                    List.of(new RuntimeSpec.Bind("/dev", "/dev", false), new RuntimeSpec.Bind("/proc", "/proc", false)),
                    List.of("/usr/bin/python3", "/tmp/" + name + "/fixture.py", "/tmp/" + name));
            var builder = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("helper.log").toFile());
            builder.environment().clear(); builder.environment().putAll(RuntimeSpec.environment(false));
            helper = builder.start();
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (!Files.exists(directory.resolve("ready")) && helper.isAlive()
                    && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            assertTrue("Fixture did not reach ready: " + read(directory.resolve("helper.log")),
                    Files.exists(directory.resolve("ready")) && helper.isAlive());
            assertTrue(read(directory.resolve("inventory.stdout")).contains("pipewire 1:1.6.9-1"));
            assertRunningGraph(directory);
            long initialHead = output.playbackHeadFrames();
            deadline = SystemClock.elapsedRealtime() + 5000;
            while ((output.framesSubmitted() < 48000 || output.playbackHeadFrames() <= initialHead + 12000)
                    && output.failure() == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            assertNull(output.failure());
            assertTrue("Real FIFO frames missing", output.framesSubmitted() >= 48000);
            assertTrue("Public playback head did not advance", output.playbackHeadFrames() > initialHead + 12000);
            JSONObject observed = new JSONObject().put("bytesRead", output.bytesRead())
                    .put("framesSubmitted", output.framesSubmitted()).put("headBefore", initialHead)
                    .put("headAfter", output.playbackHeadFrames()).put("injectedFailure", injectFailure);
            if (injectFailure) {
                output.close(); // Cancel the consumer while the real producer is still running.
                assertReleased(output);
                assertTrue(helper.isAlive());
            }
            helper.getOutputStream().write((injectFailure ? "fail\n" : "stop\n").getBytes(StandardCharsets.UTF_8));
            helper.getOutputStream().close();
            assertTrue("Fixture did not exit", helper.waitFor(8, TimeUnit.SECONDS));
            assertEquals(read(directory.resolve("helper.log")), injectFailure ? 7 : 0, helper.exitValue());
            JSONObject receipt = new JSONObject(read(directory.resolve("receipt.json")));
            assertTrue(receipt.toString(), receipt.getBoolean("serverReaped"));
            assertEquals(0, receipt.getInt("serverReturnCode"));
            assertEquals(injectFailure, receipt.getBoolean("requestedFailure"));
            if (!injectFailure) {
                deadline = SystemClock.elapsedRealtime() + 2000;
                while (output.workerAlive() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
                assertFalse(output.workerAlive()); assertNull(output.failure());
                assertEquals(0, output.partialBytes());
                assertEquals(output.bytesRead(), output.framesSubmitted() * 4);
            }
            output.close(); assertReleased(output);
            observed.put("finalBytesRead", output.bytesRead()).put("finalFramesSubmitted", output.framesSubmitted())
                    .put("workerJoined", !output.workerAlive()).put("trackReleased", true);
            Files.write(directory.resolve("android.json"), observed.toString(2).getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("PipeWireAndroidProof", name + " " + observed);
        } finally {
            // Even a failed assertion closes the consumer and signals the fixture's finally block.
            try { if (output != null) output.close(); }
            finally {
                if (helper != null) {
                    try { helper.getOutputStream().close(); } catch (java.io.IOException alreadyExited) { }
                    if (!helper.waitFor(10, TimeUnit.SECONDS)) {
                        helper.destroy();
                        if (!helper.waitFor(8, TimeUnit.SECONDS)) {
                            helper.destroyForcibly(); helper.waitFor(2, TimeUnit.SECONDS);
                        }
                    }
                    assertFalse("Owned fixture survived cleanup", helper.isAlive());
                }
                // Keep regular evidence in cache, remove only this test's exact private run tree.
                try (var paths = Files.list(directory)) {
                    for (Path path : paths.collect(java.util.stream.Collectors.toList()))
                        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            Files.copy(path, evidence.resolve(path.getFileName()));
                }
                var actual = Os.lstat(fifo.toString());
                assertTrue(OsConstants.S_ISFIFO(actual.st_mode));
                assertEquals(fifoIdentity.st_dev, actual.st_dev); assertEquals(fifoIdentity.st_ino, actual.st_ino);
                Files.delete(fifo);
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))
                        Files.delete(path);
                }
                Files.write(evidence.resolve("cleanup.json"), "{\"fifoRemoved\":true,\"runDirectoryRemoved\":true}".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void assertRunningGraph(Path directory) throws Exception {
        JSONArray graph = new JSONArray(read(directory.resolve("graph-running.stdout")));
        int nodes = 0, links = 0;
        for (int i = 0; i < graph.length(); i++) {
            JSONObject object = graph.getJSONObject(i);
            if (object.getString("type").endsWith(":Link")) {
                assertEquals("active", object.getJSONObject("info").getString("state")); links++;
            } else if (object.getString("type").endsWith(":Node")) {
                JSONObject info = object.getJSONObject("info");
                String name = info.getJSONObject("props").getString("node.name");
                if (!name.equals("oa-tone") && !name.equals("oa-sink")) continue;
                assertEquals("running", info.getString("state"));
                JSONObject format = info.getJSONObject("params").getJSONArray("Format").getJSONObject(0);
                assertEquals(48000, format.getInt("rate")); assertEquals(2, format.getInt("channels"));
                assertEquals(name.equals("oa-sink") ? "S16LE" : "F32LE", format.getString("format"));
                if (name.equals("oa-tone")) {
                    JSONArray props = info.getJSONObject("params").getJSONArray("Props");
                    boolean tone = false;
                    for (int p = 0; p < props.length(); p++)
                        if (props.getJSONObject(p).has("frequency")) {
                            assertEquals(440.0, props.getJSONObject(p).getDouble("frequency"), 0.001); tone = true;
                            assertTrue(props.getJSONObject(p).getDouble("volume") > 0);
                        }
                    assertTrue("Actual source frequency missing", tone);
                }
                nodes++;
            }
        }
        assertEquals(2, nodes); assertEquals(2, links);
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void assertReleased(AndroidPcmOutput output) throws Exception {
        assertFalse(output.workerAlive());
        assertEquals(AudioTrack.STATE_UNINITIALIZED, ((AudioTrack) field(output, "track")).getState());
        var cancel = (ParcelFileDescriptor[]) field(output, "cancellation");
        for (FileDescriptor fd : new FileDescriptor[]{(FileDescriptor) field(output, "input"),
                cancel[0].getFileDescriptor(), cancel[1].getFileDescriptor()}) {
            try { Os.fstat(fd); fail("Owned descriptor remains open"); }
            catch (ErrnoException expected) { assertEquals(OsConstants.EBADF, expected.errno); }
        }
    }
}
