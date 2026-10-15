package dev.omamobile.host;

import android.content.Context;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Test-APK-only entry point for reproducible integration setup under the normal app UID. */
@RunWith(AndroidJUnit4.class)
public class LinuxCommandTest {
    @Test public void runRequestedLinuxCommand() throws Exception {
        String encoded = InstrumentationRegistry.getArguments().getString("commandBase64");
        Assume.assumeTrue("Explicit commandBase64 argument required", encoded != null);
        String command = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HostRuntime runtime = HostRuntime.get(context);
        assertTrue("Import a Linux rootfs before this test", runtime.installed());
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(runtime.submit(command, null, true, true, done::countDown));
        assertTrue("Linux command timed out", done.await(30, TimeUnit.MINUTES));
        String output = runtime.output();
        Files.write(context.getFilesDir().toPath().resolve("linux-command-output.txt"), output.getBytes(StandardCharsets.UTF_8));
        assertTrue(output, output.endsWith("Exit 0\n"));
    }
}
