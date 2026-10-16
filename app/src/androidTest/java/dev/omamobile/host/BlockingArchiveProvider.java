package dev.omamobile.host;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only provider whose archive read stays blocked until the consumer closes it. */
public final class BlockingArchiveProvider extends ContentProvider {
    private static final Object LOCK = new Object();
    private static CountDownLatch opened = new CountDownLatch(1);
    private static ParcelFileDescriptor writer;

    static void reset() {
        releaseWriter();
        synchronized (LOCK) { opened = new CountDownLatch(1); }
    }

    static boolean awaitOpened(long timeoutMs) throws InterruptedException {
        return opened.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    static boolean readerClosed(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            ParcelFileDescriptor current;
            synchronized (LOCK) { current = writer; }
            if (current != null) {
                try {
                    java.io.FileOutputStream output = new java.io.FileOutputStream(current.getFileDescriptor());
                    output.write(1);
                    output.flush();
                } catch (IOException closed) {
                    return true;
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    static void releaseWriter() {
        ParcelFileDescriptor current;
        synchronized (LOCK) { current = writer; writer = null; }
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            synchronized (LOCK) { writer = pipe[1]; opened.countDown(); }
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
    }

    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
