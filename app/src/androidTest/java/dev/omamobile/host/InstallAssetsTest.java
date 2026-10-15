package dev.omamobile.host;

import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.file.Files;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

public class InstallAssetsTest {
    @Test public void packagedAssetsRetainTheirManifestHashesAndUnpack() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Assume.assumeTrue("Installer assets must be prepared before building", InstallAssets.available(context));
        var directory = Files.createTempDirectory(context.getCacheDir().toPath(), "install-assets-test-");
        try {
            InstallAssets.stage(context, directory);
            var root = Files.createDirectory(directory.resolve("overlay"));
            try (var in = Files.newInputStream(directory.resolve("linux-shell.tgz"))) {
                HostRuntime.importer().overlay(in, root, message -> { });
            }
            assertTrue(Files.isRegularFile(root.resolve("root/.local/share/omamobile/start-shell")));
            assertTrue(Files.isRegularFile(root.resolve("root/.local/share/omarchy/LICENSE")));
            assertTrue(Files.isRegularFile(root.resolve("usr/share/omamobile/android/phone.json")));
            assertTrue(Files.isRegularFile(root.resolve("usr/share/omamobile/android/desktop.json")));
        } finally { GuestPaths.deleteTree(directory); }
    }
}
