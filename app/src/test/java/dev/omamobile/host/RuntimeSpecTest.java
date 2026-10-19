package dev.omamobile.host;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RuntimeSpecTest {
    @Test public void graphicalEnvironmentStagesAccessShellAdapter() {
        assertEquals("/root/.local/share/omamobile/omamobile-access-shell",
                RuntimeSpec.graphicalEnvironment("phone", "/run/omamobile/p", "UTC")
                        .get("OMAMOBILE_ACCESS_SHELL"));
    }

    @Test public void sharedSessionRetainsItsShellProfile() {
        var environment = RuntimeSpec.graphicalEnvironment("unified", "/run/omamobile/u", "UTC");
        assertEquals("unified", environment.get("OMAMOBILE_PROFILE"));
        assertEquals("/run/omamobile/u", environment.get("XDG_RUNTIME_DIR"));
        assertEquals("wayland", environment.get("GTK_IM_MODULE"));
        assertEquals("wayland", environment.get("QT_IM_MODULE"));
    }
}
