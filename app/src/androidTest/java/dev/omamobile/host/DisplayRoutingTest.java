package dev.omamobile.host;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.ImageReader;
import android.view.Display;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DisplayRoutingTest {
    @Test public void androidOutputHotplugKeepsPhoneAndSingleRuntime() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        HostRuntime runtime = HostRuntime.get(context);
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        try (ImageReader reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 2)) {
            var display = manager.createVirtualDisplay("Omamobile adapter test", 640, 480, 160,
                    reader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            assertNotNull(display);
            int id = display.getDisplay().getDisplayId();
            CountDownLatch added = new CountDownLatch(1);
            Runnable observe = () -> { if (runtime.displays.snapshot().containsKey(id)) added.countDown(); };
            runtime.observe(observe);
            try {
                observe.run();
                assertTrue("Host did not observe Android display attachment", added.await(5, TimeUnit.SECONDS));
                var phone = runtime.displays.snapshot().get(Display.DEFAULT_DISPLAY);
                var desktop = runtime.displays.snapshot().get(id);
                assertEquals(DisplayProfiles.Profile.PHONE, phone.profile());
                assertEquals(DisplayProfiles.Profile.DESKTOP, desktop.profile());
                assertEquals(phone.runtimeId(), desktop.runtimeId());
            } finally {
                runtime.unobserve(observe);
                display.release();
            }
            CountDownLatch removed = new CountDownLatch(1);
            Runnable disconnect = () -> { if (!runtime.displays.snapshot().containsKey(id)) removed.countDown(); };
            runtime.observe(disconnect);
            try {
                disconnect.run();
                assertTrue("Host did not observe Android display removal", removed.await(5, TimeUnit.SECONDS));
                assertEquals(DisplayProfiles.Profile.PHONE, runtime.displays.snapshot().get(Display.DEFAULT_DISPLAY).profile());
            } finally { runtime.unobserve(disconnect); }
        }
    }
}
