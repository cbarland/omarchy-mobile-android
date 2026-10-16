package dev.omamobile.host;

import java.util.concurrent.TimeUnit;

/** Worker-thread barrier: cancellation never abandons a still-running direct child. */
final class ProcessCleanup {
    private ProcessCleanup() { }

    static void stopAndWait(Process process, Runnable retainingOwnership) {
        boolean interrupted = Thread.interrupted();
        try {
            if (process.isAlive()) {
                process.destroy();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (process.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try { process.waitFor(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException cancelled) { interrupted = true; }
                }
                if (process.isAlive()) process.destroyForcibly();
                if (process.isAlive()) retainingOwnership.run();
            }
        } finally {
            // Android destroyForcibly may only request TERM. Never let that request
            // release a lease or permit staging deletion before observed exit.
            while (true) {
                try { process.waitFor(); break; }
                catch (InterruptedException cancelled) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
