package dev.omamobile.host;

import android.media.AudioTrack;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

final class PcmOutputAssertions {
    private PcmOutputAssertions() {}

    static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static void assertReleased(AndroidPcmOutput output, String descriptorMessage) throws Exception {
        assertFalse(output.workerAlive());
        assertEquals(AudioTrack.STATE_UNINITIALIZED, ((AudioTrack) field(output, "track")).getState());
        ParcelFileDescriptor[] cancel = (ParcelFileDescriptor[]) field(output, "cancellation");
        for (FileDescriptor fd : new FileDescriptor[]{(FileDescriptor) field(output, "input"),
                cancel[0].getFileDescriptor(), cancel[1].getFileDescriptor()}) {
            try { Os.fstat(fd); fail(descriptorMessage); }
            catch (ErrnoException expected) { assertEquals(OsConstants.EBADF, expected.errno); }
        }
    }
}
