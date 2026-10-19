package dev.omamobile.host;

import static org.junit.Assert.*;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.system.Os;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real FIFO/AudioTrack transport; deterministic focus policy, no audibility claim. */
public final class ManagedAudioOutputTest {
    private HandlerThread thread;
    private Handler handler;
    private File fifo;
    private ManagedAudioOutput output;
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final TestFocus focus = new TestFocus();
    private static final class TestFocus implements ManagedAudioOutput.Focus {
        boolean grant = true;
        int requests, abandoned;
        IntConsumer listener;
        @Override public boolean request(IntConsumer listener) {
            this.listener = listener;
            requests++;
            return grant;
        }
        @Override public void abandon() { abandoned++; }
    }
    @Before public void open() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e managedAudio true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("managedAudio")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fifo = new File(context.getFilesDir(), "managed-audio-" + java.util.UUID.randomUUID() + ".pcm");
        Os.mkfifo(fifo.toString(), 0600);
        thread = new HandlerThread("ManagedAudioOutputTest");
        thread.start();
        handler = new Handler(thread.getLooper());
        onOwner(() -> { output = new ManagedAudioOutput(context, fifo, handler, focus, failure::set); return null; });
    }
    @After public void close() throws Exception {
        if (handler != null) {
            try { onOwner(() -> { if (output != null) output.close(); return null; }); }
            finally { thread.quitSafely(); thread.join(5000); assertFalse(thread.isAlive()); }
        }
        if (fifo != null) assertTrue(fifo.delete());
        assertNull(failure.get());
    }
    private <T> T onOwner(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        assertTrue(handler.post(task));
        return task.get(8, TimeUnit.SECONDS);
    }
    private ManagedAudioOutput.Snapshot snapshot() throws Exception {
        return onOwner(output::snapshot);
    }
    private void await(Callable<Boolean> predicate) throws Exception {
        long end = SystemClock.elapsedRealtime() + 7000;
        while (SystemClock.elapsedRealtime() < end) {
            assertNull(failure.get());
            if (predicate.call()) return;
            Thread.sleep(20);
        }
        fail("Managed audio state did not converge: " + snapshot());
    }
    private void play(int blocks) throws Exception {
        // 20 ms stereo frames, with an odd split to test first-frame retention.
        byte[] pcm = new byte[3840];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 17);
        try (FileOutputStream writer = new FileOutputStream(fifo)) {
            for (int i = 0; i < blocks; i++) {
                writer.write(pcm, 0, 3);
                writer.write(pcm, 3, pcm.length - 3);
                Thread.sleep(20);
            }
        }
    }
    @Test public void idleOwnsNoTrackAndReplayKeepsEveryFrame() throws Exception {
        Thread.sleep(750);
        assertFalse(snapshot().track());
        assertFalse(snapshot().focus());
        assertEquals(0, (int) onOwner(() -> focus.requests));
        long idleEvents = snapshot().readinessEvents();
        Thread.sleep(750);
        assertEquals(idleEvents, snapshot().readinessEvents());
        for (int run = 1; run <= 2; run++) {
            play(35);
            long bytes = run * 35L * 3840;
            await(() -> snapshot().submittedFrames() == bytes / 4);
            assertEquals(bytes, snapshot().bytes());
            await(() -> !snapshot().track() && !snapshot().focus());
            assertTrue(snapshot().playbackFrames() > 0);
            assertEquals(0, snapshot().droppedBytes());
            assertEquals(run, (int) onOwner(() -> focus.requests));
        }
    }
    @Test public void deniedStreamDoesNotCreateTrackOrRepeatedlyStealFocus() throws Exception {
        onOwner(() -> { focus.grant = false; return null; });
        play(35);
        await(() -> snapshot().droppedBytes() == 35L * 3840);
        assertEquals(0, snapshot().submittedFrames());
        assertFalse(snapshot().track());
        assertEquals(1, (int) onOwner(() -> focus.requests));
        Thread.sleep(ManagedAudioOutput.IDLE_MS + 250);
        onOwner(() -> { focus.grant = true; return null; });
        play(35);
        await(() -> snapshot().submittedFrames() == 35L * 3840 / 4);
        assertEquals(2, (int) onOwner(() -> focus.requests));
    }
    @Test public void focusLossReleasesTrackAndIdleRearmsPlayback() throws Exception {
        play(15);
        await(() -> snapshot().submittedFrames() == 15L * 3840 / 4);
        onOwner(() -> { focus.listener.accept(AudioManager.AUDIOFOCUS_LOSS); return null; });
        assertFalse(snapshot().track());
        long submitted = snapshot().submittedFrames();
        play(15);
        await(() -> snapshot().droppedBytes() == 15L * 3840);
        assertEquals(submitted, snapshot().submittedFrames());
        assertEquals(1, (int) onOwner(() -> focus.requests));
        Thread.sleep(ManagedAudioOutput.IDLE_MS + 250);
        play(15);
        await(() -> snapshot().submittedFrames() > submitted);
        assertEquals(2, (int) onOwner(() -> focus.requests));
    }
    @Test public void stoppingSilencesWithoutRearmingWhileGraphFinishes() throws Exception {
        play(15);
        await(() -> snapshot().submittedFrames() > 0);
        onOwner(() -> { output.silenceForStop(); return null; });
        assertFalse(snapshot().track());
        assertFalse(snapshot().focus());
        long frames = snapshot().submittedFrames();
        play(35);
        Thread.sleep(ManagedAudioOutput.IDLE_MS + 250);
        assertFalse(snapshot().track());
        assertEquals(frames, snapshot().submittedFrames());
        assertEquals(1, (int) onOwner(() -> focus.requests));
    }
}
