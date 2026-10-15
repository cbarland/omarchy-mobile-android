package dev.omamobile.host;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Explicit opt-in: install a first Linux userland, or verify an explicitly reused installation. */
@RunWith(AndroidJUnit4.class)
public class RealUserlandTest {
    @Test public void officialArchRunsThroughTheSharedHost() throws Exception {
        String archive = InstrumentationRegistry.getArguments().getString("archArchive");
        Assume.assumeTrue("Supply -e archArchive after copying a verified official Arch userspace", archive != null);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HostRuntime runtime = HostRuntime.get(context);
        assertEquals(runtime.machine() == 183 ? "arch-arm64.tar.gz" : "arch-emulator.tar.gz", archive);
        if (runtime.installed()) {
            assertEquals("Existing test installation requires explicit -e archReuse true; it is never replaced", "true",
                    InstrumentationRegistry.getArguments().getString("archReuse"));
        } else {
            try (var in = context.openFileInput(archive)) {
                HostRuntime.importer().install(in, runtime.linux, runtime.machine(), p -> android.util.Log.i("OmamobileImport", p));
            }
        }
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(runtime.submit("printf 'REAL_ARCH_OK\\n'; uname -m; cat /etc/os-release; pacman --version; pacman -Q bash glibc; "
                + "printf 'arch-shared\\n' > /android/shared/arch-test.txt; ando /system/bin/id", null, true, false, done::countDown));
        assertTrue("Real Arch command timed out", done.await(45, TimeUnit.SECONDS));
        String output = runtime.output();
        Files.write(context.getFilesDir().toPath().resolve("real-arch-verification.txt"), output.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(output, output.contains("REAL_ARCH_OK"));
        assertTrue(output, output.contains("Arch Linux"));
        assertTrue(output, output.contains("Pacman v"));
        assertTrue(output, output.contains("uid=" + android.os.Process.myUid() + "("));
        assertTrue(output, output.contains("Exit 0"));
        assertTrue(Files.exists(runtime.shared.resolve("arch-test.txt")));
        android.util.Log.i("OmamobileRealUserland", output);
    }
}
