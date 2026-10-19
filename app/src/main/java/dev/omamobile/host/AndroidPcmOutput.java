package dev.omamobile.host;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

/** One owned, app-private FIFO of S16LE stereo 48 kHz PCM. Caller must close even after EOF/failure. */
final class AndroidPcmOutput implements AutoCloseable {
    static final int RATE = 48000;
    static final int FRAME_BYTES = 4;
    private final FileDescriptor input;
    private final ParcelFileDescriptor[] cancellation;
    private final AudioTrack track;
    private final Thread worker;
    private volatile boolean cancelled;
    private boolean closed;
    private boolean cancellationSignalled;
    private volatile long bytesRead;
    private volatile long framesSubmitted;
    private volatile int partialBytes;
    private volatile IOException failure;
    private long finalPlaybackHead;

    static AndroidPcmOutput open(Context context, File fifo) throws IOException {
        FileDescriptor input = null;
        ParcelFileDescriptor[] cancel = null;
        AudioTrack track = null;
        AndroidPcmOutput output = null;
        try {
            input = openOwnedFifo(context, fifo, false);
            cancel = ParcelFileDescriptor.createPipe();
            int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("PCM output format unavailable: " + minimum);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minimum, 4096)).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED)
                throw new IOException("PCM AudioTrack initialization failed");
            track.play();
            output = new AndroidPcmOutput(input, cancel, track);
            return output;
        } catch (RuntimeException error) {
            throw new IOException("Cannot open PCM output", error);
        } finally {
            if (output == null) {
                try { if (track != null) track.release(); }
                finally {
                    closeFd(input);
                    if (cancel != null) try { cancel[0].close(); } finally { cancel[1].close(); }
                }
            }
        }
    }

    static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    /** A keep-alive probe never consumes playback data before focus is granted. */
    static FileDescriptor openOwnedFifo(Context context, File fifo, boolean keepAlive) throws IOException {
        // Reject aliases in every component and paths outside this app's private files tree.
        File root = context.getFilesDir().getCanonicalFile();
        java.nio.file.Path contextRoot = context.getFilesDir().getAbsoluteFile().toPath();
        File absolute = fifo.getAbsoluteFile();
        java.nio.file.Path prefix = absolute.toPath().startsWith(contextRoot) ? contextRoot : root.toPath();
        if (!absolute.toPath().normalize().equals(absolute.toPath())
                || !absolute.toPath().startsWith(prefix)
                || !root.toPath().resolve(prefix.relativize(absolute.toPath()))
                        .equals(fifo.getCanonicalFile().toPath()))
            throw new IOException("PCM FIFO must be an unaliased private files path");
        FileDescriptor input = null;
        boolean accepted = false;
        try {
            StructStat before = Os.lstat(absolute.getPath());
            if (!OsConstants.S_ISFIFO(before.st_mode) || before.st_uid != Process.myUid())
                throw new IOException("PCM input must be an owned FIFO");
            input = Os.open(absolute.getPath(), (keepAlive ? OsConstants.O_RDWR : OsConstants.O_RDONLY) | OsConstants.O_NONBLOCK
                    | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
            StructStat actual = Os.fstat(input);
            if (!OsConstants.S_ISFIFO(actual.st_mode) || actual.st_uid != Process.myUid()
                    || before.st_dev != actual.st_dev || before.st_ino != actual.st_ino)
                throw new IOException("PCM input must be an owned FIFO");
            accepted = true;
            return input;
        } catch (ErrnoException | RuntimeException error) {
            throw new IOException("Cannot open PCM output", error);
        } finally {
            if (!accepted) closeFd(input);
        }
    }
    private AndroidPcmOutput(FileDescriptor input, ParcelFileDescriptor[] cancel, AudioTrack track) {
        this.input = input;
        this.cancellation = cancel;
        this.track = track;
        worker = new Thread(this::pump, "AndroidPcmOutput");
        worker.start();
    }

    private void pump() {
        byte[] bytes = new byte[8192];
        int carried = 0;
        StructPollfd audio = new StructPollfd();
        audio.fd = input; audio.events = (short) OsConstants.POLLIN;
        StructPollfd cancel = new StructPollfd();
        cancel.fd = cancellation[0].getFileDescriptor(); cancel.events = (short) OsConstants.POLLIN;
        try {
            while (!cancelled) {
                try { Os.poll(new StructPollfd[]{audio, cancel}, -1); }
                catch (ErrnoException error) {
                    if (error.errno == OsConstants.EINTR) continue;
                    throw error;
                }
                if (cancelled || cancel.revents != 0) break;
                if ((audio.revents & (OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0)
                    throw new IOException("PCM FIFO poll failed: " + audio.revents);
                int count;
                try { count = Os.read(input, bytes, carried, bytes.length - carried); }
                catch (ErrnoException error) {
                    if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue;
                    throw error;
                }
                if (count == 0) break; // Writer disconnected; never spin on persistent POLLHUP.
                bytesRead += count;
                int total = carried + count;
                int complete = total - total % FRAME_BYTES;
                ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, complete);
                while (buffer.hasRemaining() && !cancelled) {
                    int written = track.write(buffer, buffer.remaining(), AudioTrack.WRITE_BLOCKING);
                    if (written > 0) framesSubmitted += written / FRAME_BYTES;
                    if (cancelled) break;
                    if (written <= 0 || written % FRAME_BYTES != 0)
                        throw new IOException("PCM AudioTrack write failed: " + written);

                }
                carried = total - complete;
                System.arraycopy(bytes, complete, bytes, 0, carried);
            }
        } catch (ErrnoException | IOException | RuntimeException error) {
            if (!cancelled) failure = new IOException("PCM worker failed", error);
        } finally { partialBytes = carried; }
    }

    long bytesRead() { return bytesRead; }
    long framesSubmitted() { return framesSubmitted; }
    int partialBytes() { return partialBytes; }
    IOException failure() { return failure; }
    boolean workerAlive() { return worker.isAlive(); }
    synchronized long playbackHeadFrames() {
        return closed ? finalPlaybackHead : Integer.toUnsignedLong(track.getPlaybackHeadPosition());
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        cancelled = true;
        // Public AudioTrack contract: pause interrupts another thread's blocking write.
        track.pause();
        if (!cancellationSignalled) {
            // Exactly one byte is ever written to this otherwise empty pipe: it cannot fill.
            while (true) {
                try { Os.write(cancellation[1].getFileDescriptor(), new byte[]{1}, 0, 1); break; }
                catch (ErrnoException error) {
                    if (error.errno != OsConstants.EINTR) throw new IOException("PCM cancellation failed", error);
                }
            }
            cancellationSignalled = true;
        }
        try { worker.join(2000); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing PCM output", error);
        }
        // Retain ownership if a platform failure prevents joining; a later close may retry.
        if (worker.isAlive()) throw new IOException("PCM worker did not stop");
        finalPlaybackHead = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
        try { track.release(); }
        finally {
            closeFd(input);
            try { cancellation[0].close(); }
            finally {
                try { cancellation[1].close(); }
                finally { closed = true; }
            }
        }
    }

    private static void closeFd(FileDescriptor fd) {
        if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { }
    }
}
