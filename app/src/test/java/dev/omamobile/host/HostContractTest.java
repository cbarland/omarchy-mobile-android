package dev.omamobile.host;

import org.junit.Test;
import java.nio.file.Path;
import java.util.List;
import static org.junit.Assert.*;

public class HostContractTest {
    @Test public void monitorAttachmentDoesNotReplacePhoneOrDuplicateLinux() {
        DisplayProfiles profiles = new DisplayProfiles("one-installation");
        var phone = profiles.connect(0, true);
        var desktop = profiles.connect(7, false);
        assertEquals(DisplayProfiles.Profile.PHONE, phone.profile());
        assertEquals(DisplayProfiles.Profile.DESKTOP, desktop.profile());
        assertEquals(phone.runtimeId(), desktop.runtimeId());
        profiles.disconnect(7);
        assertEquals(phone, profiles.snapshot().get(0));
        assertEquals(1, profiles.snapshot().size());
    }

    @Test public void shellMetacharactersRemainGuestArguments() {
        String program = "printf '%s' 'a; $(id)'";
        List<String> argv = RuntimeSpec.command(Path.of("/native/libtawcroot.so"), Path.of("/data/linux/rootfs"),
                List.of(new RuntimeSpec.Bind("/data/Shared folder", "/android/shared", true)), List.of("/bin/sh", "-lc", program));
        assertEquals(program, argv.get(argv.size() - 1));
        assertTrue(argv.contains("/data/Shared folder:/android/shared:ro"));
        assertEquals("/bin/sh", argv.get(argv.indexOf("--") + 1));
        assertFalse(RuntimeSpec.environment(false).containsKey("OMALAUNCHER_ANDO_SOCKET"));
    }

    @Test public void ambiguousOrRootReplacingBindsAreRejected() {
        for (String guest : List.of("/", "/android/../system", "/android:path", "relative")) {
            assertThrows(IllegalArgumentException.class, () -> new RuntimeSpec.Bind("/data/ok", guest, false));
        }
        assertThrows(IllegalArgumentException.class, () -> new RuntimeSpec.Bind("/data/a:b", "/android/shared", false));
    }
}
