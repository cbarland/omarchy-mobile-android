package dev.omamobile.host;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Opt-in app-UID Android commands. Native broker checks SO_PEERCRED; no ART fork. */
final class AndroidBroker implements AutoCloseable {
    private Process child;
    private boolean stopRequested;
    private final File binary;
    final File directory;
    AndroidBroker(File binary, File directory) { this.binary = binary; this.directory = directory; }

    void start() throws IOException {
        close();
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create broker directory");
        // Private upstream protocol flag; keeping source unchanged makes future updates reviewable.
        child = new ProcessBuilder(binary.toString(), "--omalauncher-broker", new File(directory, "ando.sock").toString())
                .redirectErrorStream(true).start();
        stopRequested = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        var output = new StringBuilder();
        while (System.nanoTime() < deadline && child.isAlive()) {
            if (child.getInputStream().available() > 0) {
                int value = child.getInputStream().read();
                if (value == '\n') {
                    if (output.toString().equals("READY")) return;
                    break;
                }
                output.append((char) value);
                if (output.length() > 1024) break;
            } else {
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        close();
        throw new IOException("Android command broker did not become ready: " + output);
    }

    /** Revoke this session's lifetime pipe without waiting for its workers. */
    void requestStop() {
        if (child == null || stopRequested) return;
        stopRequested = true;
        try {
            child.getOutputStream().close(); // Lifetime pipe: native supervisor kills active workers/groups.
        } catch (IOException ignored) {
            child.destroyForcibly();
        }
    }

    @Override public void close() {
        if (child == null) return;
        requestStop();
        try {
            if (!child.waitFor(7, TimeUnit.SECONDS)) child.destroyForcibly();
            child.getInputStream().close();
            child.getErrorStream().close();
        } catch (IOException ignored) {
            child.destroyForcibly();
        } catch (InterruptedException e) {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        child = null;
    }
}
