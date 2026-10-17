package dev.omamobile.host;

import android.bluetooth.BluetoothAdapter;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RadioStateSnapshotTest {
    @Test public void distinguishesNoAppVisibleDefaultFromUnavailableObservation() {
        RadioStateSnapshot unavailable = RadioStateSnapshot.fromValues(false, null, null, null,
                false, null, false, null);
        assertEquals(RadioStateSnapshot.NetworkState.UNAVAILABLE, unavailable.networkState());
        assertEquals(List.of(), unavailable.transports());
        assertNull(unavailable.internetCapable());
        assertNull(unavailable.validated());

        RadioStateSnapshot unknown = RadioStateSnapshot.fromValues(null, null, null, null,
                null, null, null, null);
        assertEquals(RadioStateSnapshot.NetworkState.UNKNOWN, unknown.networkState());
        assertNull(unknown.transports());
        assertNull(unknown.wifiSupported());
        assertEquals(RadioStateSnapshot.WifiState.UNKNOWN, unknown.wifiState());
        assertNull(unknown.bluetoothSupported());
        assertEquals(RadioStateSnapshot.BluetoothState.UNKNOWN, unknown.bluetoothState());
    }

    @Test public void retainsNetworkCapabilitiesAndMapsUnknownTransportWithoutGuessing() {
        RadioStateSnapshot value = RadioStateSnapshot.fromValues(true,
                new Integer[] { NetworkCapabilities.TRANSPORT_WIFI, 9876 }, true, false,
                true, WifiManager.WIFI_STATE_ENABLED, true, BluetoothAdapter.STATE_OFF);
        assertEquals(RadioStateSnapshot.NetworkState.AVAILABLE, value.networkState());
        assertEquals(List.of(RadioStateSnapshot.Transport.WIFI, RadioStateSnapshot.Transport.OTHER),
                value.transports());
        assertEquals(Boolean.TRUE, value.internetCapable());
        assertEquals(Boolean.FALSE, value.validated());
        assertEquals(RadioStateSnapshot.WifiState.ENABLED, value.wifiState());
        assertEquals(RadioStateSnapshot.BluetoothState.OFF, value.bluetoothState());
    }

    @Test public void preservesRadioTransitionsUnsupportedAndUnrecognizedPlatformValues() {
        assertEquals(RadioStateSnapshot.WifiState.ENABLING, snapshotWifi(WifiManager.WIFI_STATE_ENABLING));
        assertEquals(RadioStateSnapshot.WifiState.DISABLING, snapshotWifi(WifiManager.WIFI_STATE_DISABLING));
        assertEquals(RadioStateSnapshot.WifiState.DISABLED, snapshotWifi(WifiManager.WIFI_STATE_DISABLED));
        assertEquals(RadioStateSnapshot.WifiState.UNKNOWN, snapshotWifi(999));
        assertEquals(RadioStateSnapshot.WifiState.UNSUPPORTED, RadioStateSnapshot.fromValues(true, null,
                null, null, false, null, true, BluetoothAdapter.STATE_ON).wifiState());
        assertEquals(RadioStateSnapshot.BluetoothState.TURNING_ON, snapshotBluetooth(BluetoothAdapter.STATE_TURNING_ON));
        assertEquals(RadioStateSnapshot.BluetoothState.TURNING_OFF, snapshotBluetooth(BluetoothAdapter.STATE_TURNING_OFF));
        assertEquals(RadioStateSnapshot.BluetoothState.ON, snapshotBluetooth(BluetoothAdapter.STATE_ON));
        assertEquals(RadioStateSnapshot.BluetoothState.UNKNOWN, snapshotBluetooth(999));
        RadioStateSnapshot unsupported = RadioStateSnapshot.fromValues(false, null, null, null,
                false, null, false, null);
        assertEquals(RadioStateSnapshot.WifiState.UNSUPPORTED, unsupported.wifiState());
        assertEquals(RadioStateSnapshot.BluetoothState.UNSUPPORTED, unsupported.bluetoothState());
        assertEquals(RadioStateSnapshot.NetworkState.UNKNOWN,
                RadioStateSnapshot.fromValues(true, null, null, null, true,
                        WifiManager.WIFI_STATE_ENABLED, true, BluetoothAdapter.STATE_ON).networkState());
    }

    private static RadioStateSnapshot.WifiState snapshotWifi(int state) {
        return RadioStateSnapshot.fromValues(false, null, null, null, true, state, false, null).wifiState();
    }

    private static RadioStateSnapshot.BluetoothState snapshotBluetooth(int state) {
        return RadioStateSnapshot.fromValues(false, null, null, null, false, null, true, state).bluetoothState();
    }
}
