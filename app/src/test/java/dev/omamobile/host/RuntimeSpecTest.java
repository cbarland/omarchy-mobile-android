package dev.omamobile.host;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RuntimeSpecTest {
    @Test public void graphicalEnvironmentStagesAccessShellAdapter() {
        assertEquals("/root/.local/share/omamobile/omamobile-access-shell",
                RuntimeSpec.graphicalEnvironment("phone", "/run/omamobile/p", "UTC")
                        .get("OMAMOBILE_ACCESS_SHELL"));
    }
}
