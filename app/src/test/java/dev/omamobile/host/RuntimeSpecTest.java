package dev.omamobile.host;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RuntimeSpecTest {
    @Test public void graphicalProfilesShareAudioButKeepSeparateDisplayRuntimes() {
        for (String profile : java.util.List.of("phone", "desktop", "unified")) {
            String runtime = "/run/omamobile/" + profile;
            var environment = RuntimeSpec.graphicalEnvironment(profile, runtime, "UTC");
            assertEquals(runtime, environment.get("XDG_RUNTIME_DIR"));
            assertEquals("/run/omamobile/a-shared", environment.get("PIPEWIRE_RUNTIME_DIR"));
            assertEquals("pipewire-0", environment.get("PIPEWIRE_REMOTE"));
            assertEquals("unix:/run/omamobile/a-shared/pulse/native", environment.get("PULSE_SERVER"));
        }
    }
    @Test public void graphicalEnvironmentStagesAccessShellAdapter() {
        assertEquals("/root/.local/share/omamobile/omamobile-access-shell",
                RuntimeSpec.graphicalEnvironment("phone", "/run/omamobile/p", "UTC")
                        .get("OMAMOBILE_ACCESS_SHELL"));
    }

    @Test public void sharedSessionRetainsItsShellProfile() {
        var environment = RuntimeSpec.graphicalEnvironment("unified", "/run/omamobile/u", "UTC");
        assertEquals("unified", environment.get("OMAMOBILE_PROFILE"));
        assertEquals("/run/omamobile/u", environment.get("XDG_RUNTIME_DIR"));
    }
}
