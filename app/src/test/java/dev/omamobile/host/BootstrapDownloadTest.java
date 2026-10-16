package dev.omamobile.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class BootstrapDownloadTest {
    private static final RootfsImporter.Progress QUIET = message -> { };

    @Test
    public void redirectsMustRemainHttps() throws Exception {
        FakeConnection first = new FakeConnection(new URL("https://example.test/a"), 302, new byte[0]);
        first.location = "http://example.test/insecure";
        BootstrapDownload download = new BootstrapDownload(url -> first);
        Path dir = Files.createTempDirectory("bootstrap-download");
        try {
            assertThrows(IOException.class, () -> download.fetch(
                    new URL("https://example.test/a"), dir.resolve("archive"), 100, QUIET));
        } finally { delete(dir); }
    }

    @Test
    public void rejectsUnexpectedEncodingAndOversizedResponses() throws Exception {
        Path dir = Files.createTempDirectory("bootstrap-download");
        try {
            FakeConnection encoded = new FakeConnection(new URL("https://example.test/a"), 200, bytes(3));
            encoded.encoding = "gzip";
            BootstrapDownload encodedDownload = new BootstrapDownload(url -> encoded);
            assertThrows(IOException.class, () -> encodedDownload.fetch(
                    encoded.getURL(), dir.resolve("encoded"), 100, QUIET));

            FakeConnection oversized = new FakeConnection(new URL("https://example.test/b"), 200, bytes(4));
            BootstrapDownload oversizedDownload = new BootstrapDownload(url -> oversized);
            assertThrows(IOException.class, () -> oversizedDownload.fetch(
                    oversized.getURL(), dir.resolve("oversized"), 3, QUIET));
            assertFalse(Files.exists(dir.resolve("oversized")) && Files.size(dir.resolve("oversized")) > 3);
        } finally { delete(dir); }
    }

    @Test
    public void rejectsTruncatedBodyAndRetriesOneTransientFailure() throws Exception {
        Path dir = Files.createTempDirectory("bootstrap-download");
        try {
            FakeConnection truncated = new FakeConnection(new URL("https://example.test/truncated"), 200, bytes(2));
            truncated.contentLength = 4;
            BootstrapDownload one = new BootstrapDownload(url -> truncated);
            assertThrows(IOException.class, () -> one.fetch(
                    truncated.getURL(), dir.resolve("truncated"), 100, QUIET));

            FakeConnection failure = new FakeConnection(new URL("https://example.test/retry"), 503, new byte[0]);
            FakeConnection success = new FakeConnection(new URL("https://example.test/retry"), 200, bytes(7));
            Queue<FakeConnection> connections = new ArrayDeque<>();
            connections.add(failure); connections.add(success);
            BootstrapDownload retry = new BootstrapDownload(url -> connections.remove());
            Path destination = dir.resolve("retry");
            String hash = retry.fetch(success.getURL(), destination, 100, QUIET);
            assertEquals(hex(MessageDigest.getInstance("SHA-256").digest(bytes(7))), hash);
            assertEquals(7, Files.size(destination));
        } finally { delete(dir); }
    }

    @Test
    public void cancellationBeforeRequestFailsWithoutWriting() throws Exception {
        FakeConnection connection = new FakeConnection(new URL("https://example.test/cancel"), 200, bytes(2));
        BootstrapDownload download = new BootstrapDownload(url -> connection);
        download.cancel();
        Path dir = Files.createTempDirectory("bootstrap-download");
        try {
            assertThrows(IOException.class, () -> download.fetch(
                    connection.getURL(), dir.resolve("cancelled"), 100, QUIET));
            assertFalse(Files.exists(dir.resolve("cancelled")));
        } finally { delete(dir); }
    }

    @Test public void cancellationDisconnectsBlockedActiveRead() throws Exception {
        CountDownLatch reading = new CountDownLatch(1), disconnected = new CountDownLatch(1);
        HttpURLConnection connection = new HttpURLConnection(new URL("https://example.test/blocked")) {
            @Override public int getResponseCode() { return 200; }
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() throws IOException {
                        reading.countDown();
                        try {
                            if (!disconnected.await(5, TimeUnit.SECONDS)) throw new IOException("test read timed out");
                        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        throw new IOException("connection disconnected");
                    }
                };
            }
            @Override public void disconnect() { disconnected.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
        BootstrapDownload download = new BootstrapDownload(url -> connection);
        Path dir = Files.createTempDirectory("bootstrap-cancel");
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { download.fetch(connection.getURL(), dir.resolve("archive"), 100, QUIET); }
            catch (Throwable error) { result.set(error); }
        });
        try {
            worker.start();
            assertTrue("read never began", reading.await(5, TimeUnit.SECONDS));
            download.cancel();
            worker.join(5000);
            assertFalse("cancel left download blocked", worker.isAlive());
            assertTrue(result.get() instanceof java.io.InterruptedIOException);
            assertEquals(0, Files.size(dir.resolve("archive")));
        } finally { download.cancel(); worker.interrupt(); worker.join(5000); delete(dir); }
    }

    @Test public void signatureFailureDeletesOnlyItsDownloadTree() throws Exception {
        Path linux = Files.createTempDirectory("bootstrap-trust");
        Path sentinel = Files.writeString(linux.resolve("keep"), "existing unrelated data");
        BootstrapSource source = BootstrapSource.ARCH_X86;
        Queue<FakeConnection> responses = new ArrayDeque<>();
        responses.add(new FakeConnection(new URL(source.url + ".sig"), 200, new byte[]{1}));
        responses.add(new FakeConnection(new URL(source.url), 200, bytes(7)));
        BootstrapDownload download = new BootstrapDownload(url -> responses.remove());
        java.util.List<String> log = new java.util.ArrayList<>();
        try (InputStream key = Files.newInputStream(Path.of("src/main/res/raw/arch_signing_key.asc"))) {
            assertThrows(IOException.class, () -> download.prepare(source, linux, key, log::add));
            assertFalse(Files.exists(linux.resolve("rootfs")));
            assertEquals("existing unrelated data", Files.readString(sentinel));
            try (var children = Files.list(linux)) { assertEquals(1, children.count()); }
            assertFalse(log.stream().anyMatch(line -> line.startsWith("Authenticated bootstrap:")));
        } finally { delete(linux); }
    }

    private static byte[] bytes(int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) result[i] = (byte) (i + 1);
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final byte[] body;
        private final int status;
        private String encoding;
        private long contentLength = -1;
        private String location;
        private boolean disconnected;

        FakeConnection(URL url, int status, byte[] body) {
            super(url); this.status = status; this.body = body;
        }
        @Override public int getResponseCode() { return status; }
        @Override public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }
        @Override public String getContentEncoding() { return encoding; }
        @Override public long getContentLengthLong() { return contentLength; }
        @Override public InputStream getInputStream() throws IOException {
            if (disconnected) throw new IOException("disconnected");
            return new ByteArrayInputStream(body);
        }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
