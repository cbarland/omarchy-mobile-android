package dev.omamobile.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ProfileMonitorConfigTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String seed(int scale) {
        return "hl.monitor({ output = \"\", mode = \"preferred\", position = \"auto\", scale = " + scale + " })\n"
                + "hl.bind(\"SUPER + CTRL + D\", hl.dsp.exec_cmd(\"omarchy-shell shell toggle omarchy.monitor\"))\n";
    }
    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }
    private ProfileMonitorConfig.Result prepare(Path root, Path config, String profile, int scale) throws Exception {
        return ProfileMonitorConfig.prepare(root, config, profile, List.of(seed(scale)), scale);
    }

    @Test public void outputScalesAreIsolatedAndSurviveAtomicReplacementAndReopen() throws Exception {
        Path root = temporary.newFolder("rootfs").toPath();
        Path phoneConfig = temporary.getRoot().toPath().resolve("phone.lua");
        Path desktopConfig = temporary.getRoot().toPath().resolve("desktop.lua");
        write(phoneConfig, seed(3));
        write(desktopConfig, seed(1));
        var phone = prepare(root, phoneConfig, "phone", 3);
        var desktop = prepare(root, desktopConfig, "desktop", 1);
        assertTrue(phone.enabled());
        assertTrue(desktop.enabled());
        assertNotEquals(phone.directory(), desktop.directory());
        assertTrue(phone.directory().startsWith(root.resolve("root/.local/share/omamobile/profiles/phone")));
        assertTrue(read(phone.directory().resolve("monitors.lua")).contains("omarchy_monitor_scale = 3\n"));
        assertTrue(read(desktop.directory().resolve("monitors.lua")).contains("omarchy_monitor_scale = 1\n"));
        String nativeConfig = read(phoneConfig);
        assertTrue(nativeConfig.startsWith("require(\""));
        assertFalse(nativeConfig.startsWith("hl.monitor("));

        Path monitor = phone.directory().resolve("monitors.lua");
        Path replacement = phone.directory().resolve("sed-temporary");
        write(replacement, read(monitor).replace("omarchy_monitor_scale = 3", "omarchy_monitor_scale = 2"));
        Files.move(replacement, monitor, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(prepare(root, phoneConfig, "phone", 3).enabled());
        assertEquals(nativeConfig, read(phoneConfig));
        assertTrue(read(monitor).contains("omarchy_monitor_scale = 2\n"));
        assertTrue(read(desktop.directory().resolve("monitors.lua")).contains("omarchy_monitor_scale = 1\n"));
    }

    @Test public void sharedUserFilesAreNeverHiddenOrModified() throws Exception {
        Path root = temporary.newFolder("rootfs").toPath();
        Path config = temporary.getRoot().toPath().resolve("native.lua");
        Path custom = root.resolve("root/.config/hypr/monitors.lua");
        String original = "-- user output settings\nhl.monitor({ output = \"DP-1\", scale = 1.6 })\n";
        write(custom, original);
        write(config, seed(3));
        var result = prepare(root, config, "phone", 3);
        assertFalse(result.enabled());
        assertTrue(result.report().startsWith("runtime-only:"));
        assertEquals(original, read(custom));
        assertEquals(seed(3), read(config));
        assertFalse(Files.exists(root.resolve("root/.local/share/omamobile/profiles/phone/hypr")));
    }

    @Test public void customNativeConfigIsBytePreservedEvenWithGeneratedFragments() throws Exception {
        Path root = temporary.newFolder("rootfs").toPath();
        Path config = temporary.getRoot().toPath().resolve("native.lua");
        String custom = seed(3) + "-- my own bindings and monitor policy\n";
        write(config, custom);
        assertFalse(prepare(root, config, "phone", 3).enabled());
        assertEquals(custom, read(config));
        assertFalse(Files.exists(root.resolve("root/.local")));
    }

    @Test public void laterSharedConfigDisablesOnlyOurExactManagedConfig() throws Exception {
        Path root = temporary.newFolder("rootfs").toPath();
        Path config = temporary.getRoot().toPath().resolve("native.lua");
        write(config, seed(1));
        var first = prepare(root, config, "desktop", 1);
        Path monitor = first.directory().resolve("monitors.lua");
        String selected = read(monitor).replace("omarchy_monitor_scale = 1", "omarchy_monitor_scale = 2");
        write(monitor, selected);
        Path custom = root.resolve("root/.config/hypr/user.lua");
        write(custom, "-- shared user settings\n");
        assertFalse(prepare(root, config, "desktop", 1).enabled());
        assertEquals(seed(1), read(config));
        assertEquals(selected, read(monitor));
        assertEquals("-- shared user settings\n", read(custom));
    }

    @Test public void userEditsToManagedNativeConfigAreNotRewritten() throws Exception {
        Path root = temporary.newFolder("rootfs").toPath();
        Path config = temporary.getRoot().toPath().resolve("native.lua");
        write(config, seed(3));
        var first = prepare(root, config, "phone", 3);
        String selected = read(first.directory().resolve("monitors.lua"));
        String custom = read(config) + "-- retain my explicit import\n";
        write(config, custom);
        assertFalse(prepare(root, config, "phone", 3).enabled());
        assertEquals(custom, read(config));
        assertEquals(selected, read(first.directory().resolve("monitors.lua")));
    }

    @Test public void emptySharedDirectoryAllowsPersistenceAndExistingProfileFileIsRetained() throws Exception {
        Path root = temporary.newFolder("rootfs").toPath();
        Files.createDirectories(root.resolve("root/.config/hypr"));
        Path monitor = root.resolve("root/.local/share/omamobile/profiles/phone/hypr/monitors.lua");
        String existing = "local omarchy_monitor_scale = 1.6\nhl.monitor({ output = \"\", scale = omarchy_monitor_scale })\n";
        write(monitor, existing);
        Path config = temporary.getRoot().toPath().resolve("native.lua");
        write(config, seed(3));
        assertTrue(prepare(root, config, "phone", 3).enabled());
        assertEquals(existing, read(monitor));
    }

    @Test public void existingNonDirectoryConfigPathsArePreservedAndFallBack() throws Exception {
        int index = 0;
        for (String customPath : new String[]{"root/.config", "root/.config/hypr", "root/.local/share/omamobile/profiles/phone",
                "root/.local/share/omamobile/profiles/phone/hypr"}) {
            Path root = temporary.newFolder("rootfs" + index).toPath();
            Path config = temporary.getRoot().toPath().resolve("native" + index++ + ".lua");
            Path custom = root.resolve(customPath);
            write(custom, "user-owned file\n");
            write(config, seed(3));
            assertFalse(prepare(root, config, "phone", 3).enabled());
            assertEquals("user-owned file\n", read(custom));
            assertEquals(seed(3), read(config));
        }
    }
}
