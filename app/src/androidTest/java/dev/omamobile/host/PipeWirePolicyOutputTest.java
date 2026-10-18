package dev.omamobile.host;

import android.content.Context;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
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
import static dev.omamobile.host.PcmOutputAssertions.assertReleased;

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
        Path directory = root.resolve("tmp").resolve(name);
        Path fifo = directory.resolve("out.pcm");
        Path source = directory.resolve("source.pcm");
        Path evidence = context.getCacheDir().toPath().resolve("pipewire-policy-proof").resolve(name);
        StructStat fifoIdentity = null;
        StructStat sourceIdentity = null;
        Process helper = null;
        AndroidPcmOutput output = null;
        boolean createdDirectory = false;
        Throwable failure = null;
        try {
            Files.createDirectory(directory);
            createdDirectory = true;
            Os.chmod(directory.toString(), 0700);
            Os.mkfifo(fifo.toString(), 0600);
            fifoIdentity = Os.lstat(fifo.toString());
            Os.mkfifo(source.toString(), 0600);
            sourceIdentity = Os.lstat(source.toString());
            Files.createDirectories(evidence);
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
                assertEquals(4, new JSONArray(read(directory.resolve("children.json"))).length());
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
                assertEquals(7, receipt.getJSONArray("children").length());
                assertChildrenGone(directory);
            }
            if (!killHelper) {
                long end = SystemClock.elapsedRealtime() + 3000;
                while (output.workerAlive() && SystemClock.elapsedRealtime() < end) Thread.sleep(10);
                assertFalse("FIFO worker did not reach EOF after all producers stopped", output.workerAlive());
            }
            assertNull(output.failure());
            output.close();
            assertReleased(output, "Owned descriptor remains open");
            assertEquals(0, output.partialBytes());
            assertEquals(output.bytesRead(), output.framesSubmitted() * 4);
        } catch (Throwable error) {
            failure = error;
        } finally {
            Throwable cleanup = cleanup(directory, createdDirectory, fifo, fifoIdentity, source, sourceIdentity,
                    evidence, helper, output);
            if (cleanup != null) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
    }

    private static Throwable cleanup(Path directory, boolean createdDirectory,
                                     Path fifo, StructStat fifoIdentity,
                                     Path source, StructStat sourceIdentity, Path evidence,
                                     Process helper, AndroidPcmOutput output) {
        Throwable error = null;
        boolean helperGone = helper == null;
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
                helperGone = !helper.isAlive();
            }
        } catch (Throwable problem) { error = add(error, problem); }
        boolean outputClosed = output == null;
        try {
            if (output != null) output.close();
            outputClosed = output == null || !output.workerAlive();
        } catch (Throwable problem) { error = add(error, problem); }
        if (!createdDirectory) return error;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            return add(error, new AssertionError("Owned run directory missing during cleanup"));
        boolean copied = false;
        try {
            Files.createDirectories(evidence);
            try (var paths = Files.list(directory)) {
                for (Path path : paths.collect(java.util.stream.Collectors.toList()))
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        Files.copy(path, evidence.resolve(path.getFileName()));
            }
            copied = true;
        } catch (Throwable problem) { error = add(error, problem); }
        boolean childrenGone = helper == null;
        try {
            if (Files.exists(directory.resolve("children.json"))) {
                assertChildrenGone(directory);
                childrenGone = true;
            } else if (helper != null && Files.exists(directory.resolve("receipt.json"))) {
                JSONObject receipt = new JSONObject(read(directory.resolve("receipt.json")));
                childrenGone = receipt.getBoolean("allChildrenReaped")
                        && receipt.getJSONArray("children").length() == 0;
            }
        } catch (Throwable problem) { error = add(error, problem); }
        if (!helperGone || !childrenGone || !outputClosed || !copied) {
            return add(error, new AssertionError("Private run retained: helperGone=" + helperGone
                    + " childrenGone=" + childrenGone + " outputClosed=" + outputClosed
                    + " evidenceCopied=" + copied));
        }
        try {
            removeOwnedFifo(fifo, fifoIdentity);
            removeOwnedFifo(source, sourceIdentity);
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder())
                        .collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
            Files.write(evidence.resolve("cleanup.json"),
                    "{\"fifoRemoved\":true,\"runDirectoryRemoved\":true}"
                            .getBytes(StandardCharsets.UTF_8));
        } catch (Throwable problem) { error = add(error, problem); }
        return error;
    }

    private static void removeOwnedFifo(Path fifo, StructStat expected) throws Exception {
        if (!Files.exists(fifo, LinkOption.NOFOLLOW_LINKS)) return;
        StructStat actual = Os.lstat(fifo.toString());
        assertTrue(OsConstants.S_ISFIFO(actual.st_mode));
        assertEquals(android.os.Process.myUid(), actual.st_uid);
        if (expected != null) {
            assertEquals(expected.st_dev, actual.st_dev);
            assertEquals(expected.st_ino, actual.st_ino);
        }
        Files.delete(fifo);
    }

    private static Throwable add(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
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
        long captureStart = result.getLong("captureStart");
        long captureEnd = result.getLong("captureEnd");
        double toneAmplitude = assertCapturedTone(directory.resolve("capture.pcm"),
                captureStart, captureEnd);
        long catchup = SystemClock.elapsedRealtime() + 3000;
        while ((output.bytesRead() < captureEnd || output.framesSubmitted() * 4 < captureEnd)
                && output.failure() == null && SystemClock.elapsedRealtime() < catchup)
            Thread.sleep(20);
        assertNull(output.failure());
        assertTrue("Captured PCM did not all traverse Android FIFO in " + phase,
                output.bytesRead() >= captureEnd && output.framesSubmitted() * 4 >= captureEnd);
        return new JSONObject().put("phase", phase).put("bytesBefore", beforeBytes)
                .put("bytesAfter", output.bytesRead()).put("framesBefore", beforeFrames)
                .put("framesAfter", output.framesSubmitted()).put("headBefore", beforeHead)
                .put("headAfter", output.playbackHeadFrames())
                .put("captureStart", captureStart).put("captureEnd", captureEnd)
                .put("toneAmplitude", toneAmplitude)
                .put("progressedWhileClientActive", progressedWhileActive);
    }

    private static double assertCapturedTone(Path capture, long start, long end) throws Exception {
        assertTrue("Capture segment must contain at least one second of PCM", end - start >= 192000);
        assertEquals(0, start % 4);
        assertEquals(0, end % 4);
        byte[] pcm = Files.readAllBytes(capture);
        assertTrue("Captured segment exceeds file", end <= pcm.length);
        int frames = (int) ((end - start) / 4);
        double sine = 0, cosine = 0, power = 0;
        for (int i = 0; i < frames; i++) {
            int offset = (int) start + i * 4;
            int sample = (short) ((pcm[offset] & 0xff) | ((pcm[offset + 1] & 0xff) << 8));
            double angle = 2 * Math.PI * 440 * i / 48000;
            sine += sample * Math.sin(angle);
            cosine += sample * Math.cos(angle);
            power += (double) sample * sample;
        }
        double rms = Math.sqrt(power / frames);
        double amplitude = 2 * Math.hypot(sine, cosine) / frames;
        assertTrue("Capture lacks the generated 440 Hz tone: rms=" + rms + " amplitude=" + amplitude,
                rms > 200 && amplitude > 300 && amplitude > rms * 0.9);
        return amplitude;
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

}
