package dev.omamobile.host;

import android.content.Context;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit public-API transport checks, not a claim of audible hardware output. */
public class AndroidPcmOutputTest {
    private Context context;
    private final ArrayList<File> fixtures = new ArrayList<>();

    @Before public void optIn() {
        org.junit.Assume.assumeTrue("Explicit -e audio true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("audio")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After public void removeOwnedFixtures() throws Exception {
        for (File directory : fixtures) {
            if (!directory.exists()) continue;
            try (var paths = Files.walk(directory.toPath())) {
                for (var path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.delete(path);
            }
        }
    }

    @Test public void rejectsMissingRegularAndSymlinkInputs() throws Exception {
        File directory = directory();
        try {
            reject(new File(directory, "missing"));
            File regular = new File(directory, "regular"); Files.write(regular.toPath(), new byte[4]);
            reject(regular);
            File fifo = fifo(directory);
            File alias = new File(directory, "alias"); Os.symlink(fifo.getPath(), alias.getPath());
            reject(alias);
            File parentAlias = new File(directory, "parent");
            Os.symlink(directory.getPath(), parentAlias.getPath());
            reject(new File(parentAlias, fifo.getName()));
            Files.delete(parentAlias.toPath()); Files.delete(alias.toPath());
            Files.delete(fifo.toPath()); Files.delete(regular.toPath());
        } finally { Files.delete(directory.toPath()); }
    }

    @Test public void closeUnblocksMissingWriterAndRapidReopenReleasesResources() throws Exception {
        File directory = directory(); File fifo = fifo(directory);
        try {
            for (int i = 0; i < 8; i++) {
                AndroidPcmOutput output = AndroidPcmOutput.open(context, fifo);
                assertTrue(output.workerAlive());
                long start = SystemClock.elapsedRealtime();
                output.close(); output.close();
                assertTrue("close took too long", SystemClock.elapsedRealtime() - start < 1000);
                assertReleased(output);
                assertEquals(0, output.bytesRead());
                assertNull(output.failure());
            }
        } finally { Files.delete(fifo.toPath()); Files.delete(directory.toPath()); }
    }

    @Test public void stalledConnectedReaderCancelsWithoutWriterDisconnect() throws Exception {
        File directory = directory(); File fifo = fifo(directory);
        AndroidPcmOutput output = AndroidPcmOutput.open(context, fifo);
        FileDescriptor writer = Os.open(fifo.getPath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
        try {
            Os.write(writer, new byte[]{1, 2, 3, 4}, 0, 4);
            await(() -> output.framesSubmitted() == 1, 2000);
            output.close();
            assertReleased(output); assertNull(output.failure());
        } finally { output.close(); Os.close(writer); Files.delete(fifo.toPath()); Files.delete(directory.toPath()); }
    }

    @Test public void fragmentedInputPreservesFramesAndPublicPlaybackHeadAdvances() throws Exception {
        File directory = directory(); File fifo = fifo(directory);
        AndroidPcmOutput output = AndroidPcmOutput.open(context, fifo);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        byte[] tone = tone(48000);
        Thread writer = writer(fifo, tone, writerFailure, true);
        try {
            await(() -> !output.workerAlive(), 7000);
            writer.join(1000); assertFalse(writer.isAlive()); assertNull(writerFailure.get());
            assertNull(output.failure());
            assertEquals(tone.length, output.bytesRead());
            assertEquals(48000, output.framesSubmitted());
            assertEquals(0, output.partialBytes());
            await(() -> output.playbackHeadFrames() >= 24000, 3000);
            android.util.Log.i("AndroidPcmProof", "read=" + output.bytesRead()
                    + " submitted=" + output.framesSubmitted() + " head=" + output.playbackHeadFrames());
        } finally {
            output.close(); writer.interrupt(); writer.join(1500);
            assertFalse(writer.isAlive()); assertReleased(output);
            Files.delete(fifo.toPath()); Files.delete(directory.toPath());
        }
    }

    @Test public void incompleteFinalFrameIsCountedAndNeverSubmitted() throws Exception {
        File directory = directory(); File fifo = fifo(directory);
        try (AndroidPcmOutput output = AndroidPcmOutput.open(context, fifo)) {
            FileDescriptor writer = Os.open(fifo.getPath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
            Os.write(writer, new byte[]{1, 2, 3}, 0, 3); Os.close(writer);
            await(() -> !output.workerAlive(), 2000);
            assertEquals(3, output.bytesRead()); assertEquals(3, output.partialBytes());
            assertEquals(0, output.framesSubmitted()); assertEquals(0, output.playbackHeadFrames());
            assertNull(output.failure());
        } finally { Files.delete(fifo.toPath()); Files.delete(directory.toPath()); }
    }

    @Test public void closeInterruptsActuallyBackpressuredAudioTrackWrite() throws Exception {
        File directory = directory(); File fifo = fifo(directory);
        AndroidPcmOutput output = AndroidPcmOutput.open(context, fifo);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = writer(fifo, tone(48000 * 8), writerFailure, false);
        try {
            Thread worker = (Thread) field(output, "worker");
            await(() -> {
                for (StackTraceElement element : worker.getStackTrace())
                    if (element.getClassName().equals(AudioTrack.class.getName())
                            && element.getMethodName().contains("write")) return true;
                return false;
            }, 3000);
            assertTrue(output.bytesRead() > output.framesSubmitted() * 4);
            long start = SystemClock.elapsedRealtime(); output.close();
            long elapsed = SystemClock.elapsedRealtime() - start;
            assertTrue("blocked write close=" + elapsed, elapsed < 1000);
            assertReleased(output); assertNull(output.failure());
            android.util.Log.i("AndroidPcmProof", "backpressured closeMs=" + elapsed);
        } finally {
            output.close(); writer.interrupt(); writer.join(1500); assertFalse(writer.isAlive());
            Files.delete(fifo.toPath()); Files.delete(directory.toPath());
        }
    }

    private Thread writer(File fifo, byte[] bytes, AtomicReference<Throwable> failure, boolean fragment) {
        Thread thread = new Thread(() -> {
            FileDescriptor fd = null;
            try {
                fd = Os.open(fifo.getPath(), OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
                int offset = 0;
                while (offset < bytes.length && !Thread.currentThread().isInterrupted()) {
                    int size = Math.min(offset < 12 && fragment ? (offset % 3) + 1 : 4093, bytes.length - offset);
                    try { offset += Os.write(fd, bytes, offset, size); }
                    catch (ErrnoException error) {
                        if (error.errno != OsConstants.EAGAIN) throw error;
                        Thread.sleep(5);
                    }
                    if (fragment && offset < 12) Thread.sleep(10);
                }
            } catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            catch (Throwable error) { if (!Thread.currentThread().isInterrupted()) failure.set(error); }
            finally { if (fd != null) try { Os.close(fd); } catch (ErrnoException error) { failure.set(error); } }
        }, "SyntheticPcmWriter");
        thread.start(); return thread;
    }

    private static byte[] tone(int frames) {
        byte[] bytes = new byte[frames * 4];
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (8192 * Math.sin(2 * Math.PI * 1000 * frame / 48000));
            for (int channel = 0; channel < 2; channel++) {
                bytes[frame * 4 + channel * 2] = (byte) sample;
                bytes[frame * 4 + channel * 2 + 1] = (byte) (sample >> 8);
            }
        }
        return bytes;
    }
    private File directory() throws Exception {
        File directory = new File(context.getFilesDir(), "pcm-test-" + UUID.randomUUID());
        assertTrue(directory.mkdir()); fixtures.add(directory);
        Os.chmod(directory.getPath(), 0700); return directory;
    }
    private static File fifo(File directory) throws Exception {
        File fifo = new File(directory, "pcm"); Os.mkfifo(fifo.getPath(), 0600); return fifo;
    }
    private void reject(File file) throws Exception {
        try (AndroidPcmOutput ignored = AndroidPcmOutput.open(context, file)) { fail("Accepted " + file); }
        catch (java.io.IOException expected) { }
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void assertReleased(AndroidPcmOutput output) throws Exception {
        assertFalse(output.workerAlive());
        assertEquals(AudioTrack.STATE_UNINITIALIZED, ((AudioTrack) field(output, "track")).getState());
        ParcelFileDescriptor[] cancel = (ParcelFileDescriptor[]) field(output, "cancellation");
        for (FileDescriptor fd : new FileDescriptor[]{(FileDescriptor) field(output, "input"),
                cancel[0].getFileDescriptor(), cancel[1].getFileDescriptor()}) {
            try { Os.fstat(fd); fail("PCM descriptor remains open"); }
            catch (ErrnoException expected) { assertEquals(OsConstants.EBADF, expected.errno); }
        }
    }
    private interface Condition { boolean done() throws Exception; }
    private static void await(Condition condition, long timeout) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.done() && SystemClock.elapsedRealtime() < end) Thread.sleep(10);
        assertTrue("Timed out awaiting PCM behavior", condition.done());
    }
}
