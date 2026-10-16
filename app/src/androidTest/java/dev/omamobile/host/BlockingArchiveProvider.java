package dev.omamobile.host;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.Process;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Test-only provider whose archive read stays blocked until the consumer closes it. */
public final class BlockingArchiveProvider extends ContentProvider {
    private static final Object LOCK = new Object();
    private static boolean opened;
    private static boolean openHeld;
    private static ParcelFileDescriptor writer;
    private static CountDownLatch openRelease;

    private static void reset() {
        releaseWriter();
        CountDownLatch release;
        synchronized (LOCK) { release = openRelease; openRelease = null; openHeld = false; opened = false; }
        if (release != null) release.countDown();
    }

    static void releaseWriter() {
        ParcelFileDescriptor current;
        synchronized (LOCK) { current = writer; writer = null; opened = false; }
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }

    private static void releaseOpen() {
        CountDownLatch release;
        synchronized (LOCK) { release = openRelease; }
        if (release != null) release.countDown();
    }

    @Override public boolean onCreate() { return true; }

    private void enforceTestCaller() {
        int caller = Binder.getCallingUid();
        if (caller != Process.myUid() && getContext().getPackageManager().checkSignatures(
                caller, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Blocking test provider requires the test APK signature");
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode, CancellationSignal cancellation)
            throws FileNotFoundException {
        enforceTestCaller();
        if ("rootfs-open".equals(uri.getLastPathSegment())) {
            CountDownLatch release = new CountDownLatch(1);
            synchronized (LOCK) { openHeld = true; openRelease = release; }
            if (cancellation != null) cancellation.setOnCancelListener(release::countDown);
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new FileNotFoundException("open timeout");
                throw new FileNotFoundException("open cancelled");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new FileNotFoundException("open interrupted");
            } finally {
                synchronized (LOCK) { openHeld = false; if (openRelease == release) openRelease = null; }
            }
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            synchronized (LOCK) { writer = pipe[1]; opened = true; }
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        return openAssetFile(uri, mode, null);
    }

    @Override public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal cancellation)
            throws FileNotFoundException {
        ParcelFileDescriptor descriptor = openFile(uri, mode, cancellation);
        return new AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceTestCaller();
        if ("reset".equals(method)) reset();
        else if ("release".equals(method)) { releaseOpen(); releaseWriter(); }
        Bundle result = new Bundle();
        synchronized (LOCK) {
            result.putBoolean("opened", opened);
            result.putBoolean("openHeld", openHeld);
            result.putBoolean("writerHeld", writer != null);
        }
        return result;
    }

    @Override public String getType(Uri uri) { enforceTestCaller(); return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) { enforceTestCaller(); return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { enforceTestCaller(); return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { enforceTestCaller(); return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { enforceTestCaller(); return 0; }
}
