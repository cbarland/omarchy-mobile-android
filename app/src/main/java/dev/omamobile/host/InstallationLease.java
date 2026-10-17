package dev.omamobile.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cross-process ownership of the app's Linux installation directory. */
final class InstallationLease implements AutoCloseable {
    enum Mode { SHARED_USE, EXCLUSIVE_INSTALL }
    static final class BusyException extends IOException {
        BusyException() { super("Linux installation is busy"); }
        BusyException(Throwable cause) { super("Linux installation is busy", cause); }
    }

    private final FileChannel channel;
    private final FileLock lock;
    private final Path ownerKey;
    private final Object owner;
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final ConcurrentHashMap<Path, Object> OWNERS = new ConcurrentHashMap<>();

    private InstallationLease(FileChannel channel, FileLock lock, Path ownerKey, Object owner) {
        this.channel = channel;
        this.lock = lock;
        this.ownerKey = ownerKey;
        this.owner = owner;
    }

    static InstallationLease acquire(Path linux, Mode mode) throws IOException {
        Files.createDirectories(linux);
        Path ownerKey = linux.toRealPath();
        Object owner = new Object();
        if (OWNERS.putIfAbsent(ownerKey, owner) != null) throw new BusyException();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(ownerKey.resolve(".installation-lease"),
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock(0, Long.MAX_VALUE, mode == Mode.SHARED_USE);
            } catch (OverlappingFileLockException busy) {
                throw new BusyException(busy);
            }
            if (lock == null) throw new BusyException();
            return new InstallationLease(channel, lock, ownerKey, owner);
        } catch (IOException | RuntimeException error) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            OWNERS.remove(ownerKey, owner);
            throw error;
        }
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { lock.close(); }
        finally {
            try { channel.close(); }
            finally { OWNERS.remove(ownerKey, owner); }
        }
    }
}
