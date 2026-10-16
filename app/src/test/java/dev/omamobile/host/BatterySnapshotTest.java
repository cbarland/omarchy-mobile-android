package dev.omamobile.host;

import android.os.BatteryManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class BatterySnapshotTest {
    @Test public void mapsValidReading() {
        BatterySnapshot value = BatterySnapshot.fromValues(true, 50, 100,
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_USB);
        assertEquals(Boolean.TRUE, value.present());
        assertEquals(50.0, value.percentage(), 0.0);
        assertEquals(Boolean.TRUE, value.plugged());
        assertEquals(BatterySnapshot.CHARGING, value.state());
    }

    @Test public void mapsStatesAndPlugBitmask() {
        assertEquals(BatterySnapshot.DISCHARGING, BatterySnapshot.fromValues(true, 1, 2,
                BatteryManager.BATTERY_STATUS_DISCHARGING, 0).state());
        assertEquals(BatterySnapshot.FULL, BatterySnapshot.fromValues(true, 1, 2,
                BatteryManager.BATTERY_STATUS_FULL, 1).state());
        assertEquals(BatterySnapshot.NOT_CHARGING, BatterySnapshot.fromValues(true, 1, 2,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING, 2).state());
        assertEquals(Boolean.FALSE, BatterySnapshot.fromValues(true, 1, 2, null, 0).plugged());
        assertEquals(Boolean.TRUE, BatterySnapshot.fromValues(true, 1, 2, null, Integer.MAX_VALUE).plugged());
        assertNull(BatterySnapshot.fromValues(true, 1, 2, null, -1).plugged());
    }

    @Test public void rejectsUnknownOrInvalidPercentageInputs() {
        assertNull(BatterySnapshot.fromValues(null, 1, 2, null, null).percentage());
        assertNull(BatterySnapshot.fromValues(false, 1, 2, null, null).percentage());
        assertNull(BatterySnapshot.fromValues(true, -1, 2, null, null).percentage());
        assertNull(BatterySnapshot.fromValues(true, 3, 2, null, null).percentage());
        assertNull(BatterySnapshot.fromValues(true, 1, 0, null, null).percentage());
        assertNull(BatterySnapshot.fromValues(true, 1, null, 999, null).percentage());
        assertEquals(BatterySnapshot.UNKNOWN, BatterySnapshot.fromValues(null, null, null, 999, null).state());
    }

    @Test public void usesDoubleArithmeticForLargeIntegersAndUnknownSticky() {
        assertEquals(100.0, BatterySnapshot.fromValues(true, Integer.MAX_VALUE,
                Integer.MAX_VALUE, null, null).percentage(), 0.0);
        BatterySnapshot value = BatterySnapshot.fromIntent(null);
        assertNull(value.present());
        assertNull(value.percentage());
        assertNull(value.plugged());
        assertEquals(BatterySnapshot.UNKNOWN, value.state());
    }
}
