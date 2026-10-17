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

/** Opt-in ordinary Linux clients through real Android PCM output, not an app audio service. */
public class PipeWirePolicyOutputTest {
    @Test public void nativeAndPulseClientsSurviveIdleAndReachAndroid() throws Exception {
        run(false);
    }

    @Test public void forcedHelperDeathKillsPolicyPulseAndCore() throws Exception {
        run(true);
    }

    private void run(boolean killHelper) throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e pipewirePolicyAudio true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("pipewirePolicyAudio")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (InstallationLease ignored = InstallationLease.acquire(
                context.getFilesDir().toPath().resolve("linux"), InstallationLease.Mode.SHARED_USE)) {
            runOwned(context, killHelper);
        }
    }

    private void runOwned(Context context, boolean killHelper) throws Exception {
        Path root = context.getFilesDir().toPath().resolve("linux/rootfs");
        assertTrue("Prepared Linux required for opted-in policy test", Files.isDirectory(root));
        String name = "pol-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = Files.createDirectory(root.resolve("tmp").resolve(name));
        Os.chmod(directory.toString(), 0700);
        Path fifo = directory.resolve("out.pcm");
        Os.mkfifo(fifo.toString(), 0600);
        var fifoIdentity = Os.lstat(fifo.toString());
        Path evidence = context.getCacheDir().toPath().resolve("pipewire-policy-proof").resolve(name);
        Files.createDirectories(evidence);
        Process helper = null;
        AndroidPcmOutput output = null;
        try {
            Path script = directory.resolve("fixture.py");
            try (var asset = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                    .open("pipewire_policy_fixture.py")) {
                Files.copy(asset, script);
            }
            output = AndroidPcmOutput.open(context, fifo.toFile());
            var argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir,
                    "libtawcroot.so").toPath(), root,
                    List.of(new RuntimeSpec.Bind("/dev", "/dev", false),
                            new RuntimeSpec.Bind("/proc", "/proc", false)),
                    List.of("/usr/bin/python3", "/tmp/" + name + "/fixture.py",
                            "/tmp/" + name, "/tmp/" + name + "/out.pcm"));
            var builder = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(directory.resolve("helper.log").toFile());
            builder.environment().clear();
            builder.environment().putAll(RuntimeSpec.environment(false));
            helper = builder.start();
            waitForFile(directory.resolve("ready"), helper, 20000);
            assertTrue(read(directory.resolve("pactl-info.stdout")).contains("Default Sink: oa-sink"));
            if (killHelper) {
                assertEquals(3, new JSONArray(read(directory.resolve("children.json"))).length());
                killOwnedHelper(directory, helper);
                assertTrue("Forced helper did not exit", helper.waitFor(8, TimeUnit.SECONDS));
                assertNotEquals(0, helper.exitValue());
                assertFalse("SIGKILL must bypass Python finally", Files.exists(directory.resolve("receipt.json")));
                assertChildrenGone(directory);
            } else {
                JSONArray observations = new JSONArray();
                for (String phase : List.of("native1", "native2", "pulse")) {
                    if (phase.equals("native2")) {
                        Thread.sleep(750);
                        assertTrue("Android PCM worker ended during idle", output.workerAlive());
                        assertNull(output.failure());
                    }
                    observations.put(playPhase(directory, helper, output, phase));
                    Files.write(directory.resolve("android.json"), observations.toString(2)
                            .getBytes(StandardCharsets.UTF_8));
                }
                control(helper, "stop");
                helper.getOutputStream().close();
                assertTrue("Fixture did not exit", helper.waitFor(10, TimeUnit.SECONDS));
                assertEquals(read(directory.resolve("helper.log")), 0, helper.exitValue());
                JSONObject receipt = new JSONObject(read(directory.resolve("receipt.json")));
                assertTrue(receipt.toString(), receipt.isNull("error"));
                assertTrue(receipt.toString(), receipt.getBoolean("allChildrenReaped"));
                assertEquals(receipt.toString(), 0, receipt.getJSONArray("cleanupErrors").length());
                assertEquals(6, receipt.getJSONArray("children").length());
                assertChildrenGone(directory);
            }
            if (!killHelper) {
                long end = SystemClock.elapsedRealtime() + 3000;
                while (output.workerAlive() && SystemClock.elapsedRealtime() < end) Thread.sleep(10);
                assertFalse("FIFO worker did not reach EOF after all producers stopped", output.workerAlive());
            }
            assertNull(output.failure());
            output.close();
            assertReleased(output);
            assertEquals(0, output.partialBytes());
            assertEquals(output.bytesRead(), output.framesSubmitted() * 4);
        } finally {
            Exception shutdownError = null;
            try {
                if (helper != null) {
                    try { helper.getOutputStream().close(); } catch (java.io.IOException alreadyExited) { }
                    if (!helper.waitFor(10, TimeUnit.SECONDS)) {
                        helper.destroy();
                        if (!helper.waitFor(4, TimeUnit.SECONDS)) {
                            killOwnedHelper(directory, helper);
                            helper.waitFor(3, TimeUnit.SECONDS);
                        }
                    }
                }
            } catch (Exception error) {
                shutdownError = error;
            }
            try { if (output != null) output.close(); }
            catch (Exception error) { if (shutdownError == null) shutdownError = error; }
            // Preserve all top-level regular evidence before removing the private run tree.
            try (var paths = Files.list(directory)) {
                for (Path path : paths.collect(java.util.stream.Collectors.toList()))
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        Files.copy(path, evidence.resolve(path.getFileName()));
            }
            if (shutdownError != null) throw shutdownError;
            if (helper != null) assertFalse("Owned fixture survived cleanup", helper.isAlive());
            if (Files.exists(directory.resolve("children.json"))) assertChildrenGone(directory);
            var actual = Os.lstat(fifo.toString());
            assertTrue(OsConstants.S_ISFIFO(actual.st_mode));
            assertEquals(fifoIdentity.st_dev, actual.st_dev);
            assertEquals(fifoIdentity.st_ino, actual.st_ino);
            Files.delete(fifo);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder())
                        .collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
            Files.write(evidence.resolve("cleanup.json"),
                    "{\"fifoRemoved\":true,\"runDirectoryRemoved\":true}"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JSONObject playPhase(Path directory, Process helper, AndroidPcmOutput output,
                                        String phase) throws Exception {
        long beforeBytes = output.bytesRead();
        long beforeFrames = output.framesSubmitted();
        long beforeHead = output.playbackHeadFrames();
        control(helper, "start " + phase);
        waitForFile(directory.resolve(phase + ".active"), helper, 6000);
        long end = SystemClock.elapsedRealtime() + 6000;
        boolean progressedWhileActive = false;
        while (SystemClock.elapsedRealtime() < end && helper.isAlive()) {
            assertNull(output.failure());
            assertTrue("Android PCM worker died in " + phase, output.workerAlive());
            if (output.bytesRead() - beforeBytes >= 192000
                    && output.framesSubmitted() - beforeFrames >= 48000
                    && output.playbackHeadFrames() - beforeHead > 12000
                    && !Files.exists(directory.resolve(phase + ".done.json"))) {
                progressedWhileActive = true;
                break;
            }
            Thread.sleep(20);
        }
        assertTrue("No correlated live Android progress in " + phase, progressedWhileActive);
        waitForFile(directory.resolve(phase + ".done.json"), helper, 8000);
        JSONObject result = new JSONObject(read(directory.resolve(phase + ".done.json")));
        assertTrue(result.toString(), result.getBoolean("activeLinkObserved"));
        assertEquals(result.toString(), 0, result.getInt("clientReturnCode"));
        assertNull(output.failure());
        return new JSONObject().put("phase", phase).put("bytesBefore", beforeBytes)
                .put("bytesAfter", output.bytesRead()).put("framesBefore", beforeFrames)
                .put("framesAfter", output.framesSubmitted()).put("headBefore", beforeHead)
                .put("headAfter", output.playbackHeadFrames())
                .put("progressedWhileClientActive", progressedWhileActive);
    }

    private static void control(Process helper, String text) throws Exception {
        helper.getOutputStream().write((text + "\n").getBytes(StandardCharsets.UTF_8));
        helper.getOutputStream().flush();
    }

    private static void waitForFile(Path file, Process helper, long milliseconds) throws Exception {
        long end = SystemClock.elapsedRealtime() + milliseconds;
        while (!Files.exists(file) && helper.isAlive() && SystemClock.elapsedRealtime() < end)
            Thread.sleep(20);
        assertTrue("Fixture marker missing " + file + ": " + read(file.getParent().resolve("helper.log")),
                Files.exists(file) && helper.isAlive());
    }

    private static void killOwnedHelper(Path directory, Process helper) throws Exception {
        JSONObject identity = new JSONObject(read(directory.resolve("helper.json")));
        int pid = identity.getInt("pid");
        String[] fields = statFields(read(Path.of("/proc/" + pid + "/stat")));
        assertTrue(helper.isAlive());
        assertEquals(identity.getString("starttime"), fields[19]);
        assertEquals(Integer.toString(pid), fields[2]); // private session leader
        assertEquals(Integer.toString(pid), fields[3]); // private process group
        Os.kill(pid, OsConstants.SIGKILL);
    }

    private static void assertChildrenGone(Path directory) throws Exception {
        JSONArray children = new JSONArray(read(directory.resolve("children.json")));
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.getJSONObject(i);
            int pid = child.getInt("pid");
            String start = child.getString("starttime");
            long end = SystemClock.elapsedRealtime() + 5000;
            while (true) {
                try {
                    if (!start.equals(statFields(read(Path.of("/proc/" + pid + "/stat")))[19]))
                        break;
                } catch (java.nio.file.NoSuchFileException gone) { break; }
                if (SystemClock.elapsedRealtime() >= end)
                    fail("Owned child identity survived pid=" + pid + " start=" + start);
                Thread.sleep(20);
            }
        }
    }

    private static String[] statFields(String stat) {
        return stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
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
