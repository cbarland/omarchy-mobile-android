package dev.omamobile.host;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.Process;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test-only provider whose archive read stays blocked until the consumer closes it. */
public final class BlockingArchiveProvider extends ContentProvider {
    private static final Object LOCK = new Object();
    private static boolean opened;
    private static ParcelFileDescriptor writer;

    private static void reset() {
        releaseWriter();
        synchronized (LOCK) { opened = false; }
    }

    static void releaseWriter() {
        ParcelFileDescriptor current;
        synchronized (LOCK) { current = writer; writer = null; opened = false; }
        if (current != null) try { current.close(); } catch (IOException ignored) { }
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
        enforceTestCaller();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            synchronized (LOCK) { writer = pipe[1]; opened = true; }
            return pipe[0];
        } catch (IOException error) {
            throw new FileNotFoundException(error.toString());
        }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceTestCaller();
        if ("reset".equals(method)) reset();
        else if ("release".equals(method)) releaseWriter();
        Bundle result = new Bundle();
        synchronized (LOCK) {
            result.putBoolean("opened", opened);
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
