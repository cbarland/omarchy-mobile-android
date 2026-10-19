package dev.omamobile.host;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Worker-looper owned: first PCM acquires focus; a silent FIFO owns no AudioTrack. */
final class ManagedAudioOutput implements AutoCloseable {
    static final long IDLE_MS = 500;
    interface Focus {
        boolean request(IntConsumer callback);
        void abandon();
    }
    private enum State { IDLE, GRANTED, DENIED, TRANSIENT_LOSS, LOST, STOPPED }
    record Snapshot(boolean track, boolean focus, long bytes, long submittedFrames,
                    long playbackFrames, long droppedBytes, long readinessEvents) { }
    private final Context context;
    private final File fifo;
    private final Handler handler;
    private final MessageQueue queue;
    private final Focus focus;
    private final Consumer<IOException> failure;
    private final FileDescriptor probe;
    private final byte[] discard = new byte[8192];
    private final Runnable idleCheck = this::idle;
    private State state = State.IDLE;
    private AndroidPcmOutput output;
    private long bytes, submitted, played, dropped, events, lastBytes, generation;
    private boolean closed, failed;

    static ManagedAudioOutput open(Context context, File fifo, Handler handler,
                                  Consumer<IOException> failure) throws IOException {
        AudioManager manager = context.getSystemService(AudioManager.class);
        Focus focus = new Focus() {
            private AudioFocusRequest request;
            @Override public boolean request(IntConsumer callback) {
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(AndroidPcmOutput.attributes()).setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(callback::accept, handler).build();
                return manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            @Override public void abandon() {
                if (request != null) manager.abandonAudioFocusRequest(request);
                request = null;
            }
        };
        return new ManagedAudioOutput(context, fifo, handler, focus, failure);
    }

    ManagedAudioOutput(Context context, File fifo, Handler handler, Focus focus,
                       Consumer<IOException> failure) throws IOException {
        this.context = context.getApplicationContext();
        this.fifo = fifo;
        this.handler = handler;
        this.queue = handler.getLooper().getQueue();
        this.focus = focus;
        this.failure = failure;
        owner();
        probe = AndroidPcmOutput.openOwnedFifo(context, fifo, true);
        listen();
    }

    private void owner() {
        if (Looper.myLooper() != handler.getLooper())
            throw new IllegalStateException("Audio output requires its owning looper");
    }
    private void listen() {
        if (!closed && !failed) queue.addOnFileDescriptorEventListener(probe,
                MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                        | MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR,
                (descriptor, ready) -> readable(ready));
    }
    private int readable(int ready) {
        if (closed || failed || output != null) return 0;
        events++;
        try {
            if ((ready & MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR) != 0)
                throw new IOException("Shared audio FIFO failed");
            if (state == State.IDLE) {
                long token = ++generation;
                state = focus.request(value -> {
                    if (token == generation && !closed && !failed) changed(value);
                }) ? State.GRANTED : State.DENIED;
            }
            if (state == State.GRANTED) {
                int partial = (int) ((bytes + dropped) % AndroidPcmOutput.FRAME_BYTES);
                if (partial != 0) {
                    try { dropped += Os.read(probe, discard, 0, AndroidPcmOutput.FRAME_BYTES - partial); }
                    catch (ErrnoException error) {
                        if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error;
                    }
                    if ((bytes + dropped) % AndroidPcmOutput.FRAME_BYTES != 0) {
                        scheduleIdle();
                        return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                                | MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
                    }
                }
                // The probe only observed readability: the first frame is still in the FIFO.
                output = AndroidPcmOutput.open(context, fifo);
                lastBytes = 0;
                scheduleIdle();
                return 0;
            }
            try { dropped += Os.read(probe, discard, 0, discard.length); }
            catch (ErrnoException error) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error;
            }
            if (state != State.STOPPED) scheduleIdle();
            return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                    | MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
        } catch (IOException | ErrnoException | RuntimeException error) {
            fail(error);
            return 0;
        }
    }
    private void scheduleIdle() {
        handler.removeCallbacks(idleCheck);
        handler.postDelayed(idleCheck, IDLE_MS);
    }
    private void idle() {
        if (closed || failed || state == State.STOPPED) return;
        try {
            if (output != null) {
                if (output.failure() != null) throw output.failure();
                if (!output.workerAlive()) throw new IOException("Shared PCM worker exited unexpectedly");
                long current = output.bytesRead();
                if (current != lastBytes) {
                    lastBytes = current;
                    scheduleIdle();
                    return;
                }
            }
            releaseTrack();
            generation++;
            focus.abandon();
            state = State.IDLE;
            listen();
        } catch (IOException | RuntimeException error) { fail(error); }
    }
    private void changed(int value) {
        try {
            if (value == AudioManager.AUDIOFOCUS_GAIN) {
                if (state == State.TRANSIENT_LOSS) state = State.GRANTED;
            } else if (value == AudioManager.AUDIOFOCUS_LOSS
                    || value == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || value == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                releaseTrack();
                state = value == AudioManager.AUDIOFOCUS_LOSS ? State.LOST : State.TRANSIENT_LOSS;
                listen();
                scheduleIdle();
            }
        } catch (IOException | RuntimeException error) { fail(error); }
    }
    void silenceForStop() throws IOException {
        owner();
        generation++;
        focus.abandon();
        state = State.STOPPED;
        handler.removeCallbacks(idleCheck);
        releaseTrack();
        listen();
    }
    private void releaseTrack() throws IOException {
        if (output == null) return;
        output.close(); // Retain the reference if joining/releasing fails.
        bytes += output.bytesRead();
        submitted += output.framesSubmitted();
        played += output.playbackHeadFrames();
        output = null;
    }
    Snapshot snapshot() {
        owner();
        return new Snapshot(output != null, state == State.GRANTED,
                bytes + (output == null ? 0 : output.bytesRead()),
                submitted + (output == null ? 0 : output.framesSubmitted()),
                played + (output == null ? 0 : output.playbackHeadFrames()), dropped, events);
    }
    private void fail(Exception error) {
        failed = true;
        queue.removeOnFileDescriptorEventListener(probe);
        handler.removeCallbacks(idleCheck);
        failure.accept(new IOException("Managed Android audio output failed", error));
    }
    @Override public void close() throws IOException {
        owner();
        if (closed) return;
        queue.removeOnFileDescriptorEventListener(probe);
        handler.removeCallbacks(idleCheck);
        releaseTrack();
        generation++;
        focus.abandon();
        state = State.IDLE;
        try { Os.close(probe); }
        catch (ErrnoException error) { throw new IOException("Cannot close audio probe", error); }
        closed = true;
    }
}
