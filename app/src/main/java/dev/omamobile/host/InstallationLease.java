package dev.omamobile.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Cross-process ownership of the app's Linux installation directory. */
final class InstallationLease implements AutoCloseable {
    enum Mode { SHARED_USE, EXCLUSIVE_INSTALL }

    private final FileChannel channel;
    private final FileLock lock;

    private InstallationLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static InstallationLease acquire(Path linux, Mode mode) throws IOException {
        Files.createDirectories(linux);
        FileChannel channel = FileChannel.open(linux.resolve(".installation-lease"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock(0, Long.MAX_VALUE, mode == Mode.SHARED_USE);
            } catch (OverlappingFileLockException busy) {
                throw new IOException("Linux installation is busy", busy);
            }
            if (lock == null) throw new IOException("Linux installation is busy");
            return new InstallationLease(channel, lock);
        } catch (IOException | RuntimeException error) {
            try { channel.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    @Override public void close() throws IOException {
        try { lock.close(); }
        finally { channel.close(); }
    }
}
