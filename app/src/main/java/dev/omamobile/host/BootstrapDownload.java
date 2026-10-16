package dev.omamobile.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** One user-started download. No resume/cache trust and no archive execution. */
final class BootstrapDownload {
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    private final long deadline = System.nanoTime() + 30L * 60 * 1_000_000_000;
    private volatile boolean cancelled;
    private HttpURLConnection active;

    BootstrapDownload() { this(url -> (HttpURLConnection) url.openConnection()); }
    BootstrapDownload(Connections connections) { this.connections = connections; }

    static final class Verified implements AutoCloseable {
        final Path archive;
        final BootstrapSource source;
        private final Path work;
        private Verified(Path work, Path archive, BootstrapSource source) {
            this.work = work; this.archive = archive; this.source = source;
        }
        @Override public void close() throws IOException { GuestPaths.deleteTree(work); }
    }

    Verified prepare(BootstrapSource source, Path linux, InputStream key, RootfsImporter.Progress progress) throws IOException {
        check();
        Path work = linux.resolve("download-" + UUID.randomUUID());
        Files.createDirectory(work);
        boolean transferred = false;
        try {
            Path signature = work.resolve("archive.sig");
            Path archive = work.resolve("archive");
            progress.update("Fetching official bootstrap signature: " + source.url + ".sig");
            fetch(new URL(source.url + ".sig"), signature, 1024 * 1024, progress);
            progress.update("Downloading official bootstrap: " + source.url);
            String hash = fetch(new URL(source.url), archive, 2L * 1024 * 1024 * 1024, progress);
            check();
            progress.update("Verifying the downloaded bootstrap's publisher signature…");
            String signer;
            try (var sig = Files.newInputStream(signature)) {
                signer = BootstrapSignature.verify(archive, sig, key, source.fingerprint);
            }
            check();
            progress.update("Authenticated bootstrap: " + source.url + " · signer " + signer + " · SHA-256 " + hash);
            transferred = true;
            return new Verified(work, archive, source);
        } finally {
            if (!transferred) GuestPaths.deleteTree(work);
        }
    }

    String fetch(URL original, Path destination, long limit, RootfsImporter.Progress progress) throws IOException {
        IOException previous = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            check();
            try { return request(original, destination, limit, progress); }
            catch (IOException error) {
                check();
                previous = error;
                if (attempt == 1) progress.update("Bootstrap request failed; retrying once: " + error.getMessage());
            }
        }
        throw previous;
    }

    private String request(URL original, Path destination, long limit, RootfsImporter.Progress progress) throws IOException {
        URL url = original;
        for (int redirect = 0; redirect <= 5; redirect++) {
            check();
            if (!"https".equals(url.getProtocol())) throw new IOException("Bootstrap requests require HTTPS");
            HttpURLConnection connection = connections.open(url);
            try {
                synchronized (this) { check(); active = connection; }
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(30_000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                check();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Bootstrap redirect has no location");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("Bootstrap HTTP " + status + " from " + url);
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                    throw new IOException("Unexpected bootstrap HTTP content encoding: " + encoding);
                long expected = connection.getContentLengthLong();
                if (expected > limit) throw new IOException("Bootstrap response exceeds download limit");
                MessageDigest hash;
                try { hash = MessageDigest.getInstance("SHA-256"); }
                catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
                long count = 0, reported = 0;
                try (InputStream input = connection.getInputStream(); var out = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[64 * 1024];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        check();
                        if (size > limit - count) throw new IOException("Bootstrap response exceeds download limit");
                        out.write(buffer, 0, size);
                        hash.update(buffer, 0, size);
                        count += size;
                        if (count - reported >= 8 * 1024 * 1024) {
                            progress.update("Downloaded " + count / (1024 * 1024) + " MiB of bootstrap data");
                            reported = count;
                        }
                    }
                }
                check();
                if (expected >= 0 && count != expected) throw new IOException("Truncated bootstrap response");
                StringBuilder hex = new StringBuilder();
                for (byte value : hash.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                return hex.toString();
            } finally {
                synchronized (this) { if (active == connection) active = null; }
                connection.disconnect();
            }
        }
        throw new IOException("Too many bootstrap redirects");
    }

    private void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Bootstrap download cancelled");
        if (System.nanoTime() >= deadline) throw new InterruptedIOException("Bootstrap download exceeded 30 minutes");
    }

    void cancel() {
        HttpURLConnection connection;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            connection = active;
        }
        if (connection != null) new Thread(connection::disconnect, "bootstrap-download-close").start();
    }
}
