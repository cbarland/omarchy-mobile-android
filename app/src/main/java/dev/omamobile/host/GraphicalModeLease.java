package dev.omamobile.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Excludes a shared compositor from the separate legacy graphical processes. */
final class GraphicalModeLease {
    private GraphicalModeLease() { }

    static InstallationLease acquire(Path files, boolean unified, BooleanSupplier cancelled) throws IOException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (!cancelled.getAsBoolean()) {
            try {
                // A separate lock from the Linux installation lease: both modes
                // still hold SHARED_USE on Linux and exclude its installer.
                return InstallationLease.acquire(files.resolve("graphics"), unified
                        ? InstallationLease.Mode.EXCLUSIVE_INSTALL : InstallationLease.Mode.SHARED_USE);
            } catch (InstallationLease.BusyException switchingMode) {
                if (System.nanoTime() >= deadline)
                    throw new IOException("Previous graphical sessions are still stopping", switchingMode);
                try { Thread.sleep(50); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Graphical mode switch interrupted", interrupted);
                }
            }
        }
        throw new IOException("Graphical start cancelled");
    }
}
