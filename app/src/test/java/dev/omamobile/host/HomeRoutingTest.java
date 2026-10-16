package dev.omamobile.host;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class HomeRoutingTest {
    @Test public void preparedGraphicsUsesExistingPhoneOwner() {
        assertEquals(HomeRouting.Destination.PHONE, HomeRouting.choose(true, true));
    }

    @Test public void missingSetupOrNativePayloadReturnsToHostSetup() {
        assertEquals(HomeRouting.Destination.SETUP, HomeRouting.choose(false, true));
        assertEquals(HomeRouting.Destination.SETUP, HomeRouting.choose(true, false));
    }
}
