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
                    encoded.url, dir.resolve("encoded"), 100, QUIET));

            FakeConnection oversized = new FakeConnection(new URL("https://example.test/b"), 200, bytes(4));
            BootstrapDownload oversizedDownload = new BootstrapDownload(url -> oversized);
            assertThrows(IOException.class, () -> oversizedDownload.fetch(
                    oversized.url, dir.resolve("oversized"), 3, QUIET));
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
                    truncated.url, dir.resolve("truncated"), 100, QUIET));

            FakeConnection failure = new FakeConnection(new URL("https://example.test/retry"), 503, new byte[0]);
            FakeConnection success = new FakeConnection(new URL("https://example.test/retry"), 200, bytes(7));
            Queue<FakeConnection> connections = new ArrayDeque<>();
            connections.add(failure); connections.add(success);
            BootstrapDownload retry = new BootstrapDownload(url -> connections.remove());
            Path destination = dir.resolve("retry");
            String hash = retry.fetch(success.url, destination, 100, QUIET);
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
                    connection.url, dir.resolve("cancelled"), 100, QUIET));
            assertFalse(Files.exists(dir.resolve("cancelled")));
        } finally { delete(dir); }
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
