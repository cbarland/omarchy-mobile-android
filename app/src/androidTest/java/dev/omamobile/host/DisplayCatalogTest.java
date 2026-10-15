package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.app.ActivityManager;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.ImageReader;
import android.view.Display;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DisplayCatalogTest {
    @Test public void inventoryReportsRealModesAndRejectsUnlaunchableVirtualDisplays() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        try (ImageReader reader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 2);
             ImageReader secondReader = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 2)) {
            var first = manager.createVirtualDisplay("Omamobile catalog fixture", 640, 480, 160,
                    reader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            assertNotNull(first);
            try {
                int firstId = first.getDisplay().getDisplayId();
                List<DisplayCatalog.Output> outputs = DisplayCatalog.read(context);
                var phone = outputs.stream().filter(item -> item.candidate().id() == Display.DEFAULT_DISPLAY).findFirst().orElseThrow();
                assertFalse(phone.candidate().eligible());
                var output = outputs.stream().filter(item -> item.candidate().id() == firstId).findFirst().orElseThrow();
                boolean allowed = context.getSystemService(ActivityManager.class).isActivityStartAllowedOnDisplay(
                        context, firstId, new Intent(context, DesktopActivity.class));
                assertFalse("The private untrusted fixture must exercise Android's launch refusal", allowed);
                assertFalse("A presentation display which rejects DesktopActivity must not be offered", output.candidate().eligible());
                assertTrue(output.details(), output.details().contains("640 × 480"));
                assertTrue(output.details(), output.details().contains("160 dpi"));
                assertFalse(DisplaySelection.canRemember(DisplayCatalog.candidates(outputs), output.candidate()));
                String signature = output.candidate().signature();
                var second = manager.createVirtualDisplay("Omamobile catalog fixture", 640, 480, 160,
                        secondReader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
                assertNotNull(second);
                try {
                    var duplicates = DisplayCatalog.candidates(DisplayCatalog.read(context));
                    assertFalse(DisplaySelection.canRemember(duplicates, output.candidate()));
                    assertEquals(DisplaySelection.Reason.UNAVAILABLE, DisplaySelection.choose(duplicates, signature).reason());
                } finally { second.release(); }
            } finally { first.release(); }
        }
    }
}
