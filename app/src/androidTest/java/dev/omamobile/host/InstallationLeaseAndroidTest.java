package dev.omamobile.host;

import android.content.Context;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class InstallationLeaseAndroidTest {
    @Test public void sharedLeaseBlocksGuestPosixExclusiveLockOnSameInode() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path root = context.getFilesDir().toPath().resolve("linux/rootfs");
        Assume.assumeTrue("Prepared Linux rootfs required", Files.isDirectory(root));
        assertTrue("Instrumentation must run as a regular app UID", android.os.Process.myUid() >= 10000);
        String name = "lease-" + UUID.randomUUID();
        Path scratch = root.resolve("tmp").resolve(name);
        Path capture = Files.createTempFile(context.getFilesDir().toPath(), ".lease-probe-", ".json");
        try (InstallationLease ignored = InstallationLease.acquire(scratch, InstallationLease.Mode.SHARED_USE)) {
            assertThrows(java.io.IOException.class, () -> InstallationLease.acquire(scratch, InstallationLease.Mode.EXCLUSIVE_INSTALL));
            Path lock = scratch.resolve(".installation-lease");
            long hostInode = Os.stat(lock.toString()).st_ino;
            String script = "import fcntl,json,os,sys\n"
                    + "f=open('/tmp/" + name + "/.installation-lease','r+')\n"
                    + "v={'inode':os.stat(f.fileno()).st_ino}\n"
                    + "try:\n fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB); v['status']='GRANTED'; print(json.dumps(v)); sys.exit(2)\n"
                    + "except BlockingIOError:\n v['status']='BUSY'; print(json.dumps(v)); sys.exit(0)\n";
            Process process = new ProcessBuilder(RuntimeSpec.command(
                    new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(), root,
                    List.of(new RuntimeSpec.Bind("/dev", "/dev", false), new RuntimeSpec.Bind("/proc", "/proc", false)),
                    List.of("/usr/bin/python3", "-c", script)))
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(capture.toFile()).start();
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
            assertTrue("Guest lock probe timed out: " + read(capture), completed);
            assertEquals("Guest lock probe failed: " + read(capture), 0, process.exitValue());
            JSONObject result = new JSONObject(read(capture));
            assertEquals(hostInode, result.getLong("inode"));
            assertEquals("BUSY", result.getString("status"));
        } finally {
            Files.deleteIfExists(capture);
            GuestPaths.deleteTree(scratch);
        }
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
    }
}
