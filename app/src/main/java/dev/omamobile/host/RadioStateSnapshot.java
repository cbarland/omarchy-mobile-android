package dev.omamobile.host;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Read-only radio state and the default network visible to this Android UID. */
final class RadioStateSnapshot {
    enum NetworkState { AVAILABLE, UNAVAILABLE, UNKNOWN }
    enum WifiState { ENABLED, DISABLED, ENABLING, DISABLING, UNSUPPORTED, UNKNOWN }
    enum BluetoothState { ON, OFF, TURNING_ON, TURNING_OFF, UNSUPPORTED, UNKNOWN }
    enum Transport { WIFI, CELLULAR, ETHERNET, BLUETOOTH, VPN, WIFI_AWARE, LOWPAN, USB, THREAD, SATELLITE, OTHER }

    private final NetworkState networkState;
    private final List<Transport> transports;
    private final Boolean internetCapable;
    private final Boolean validated;
    private final Boolean wifiSupported;
    private final WifiState wifiState;
    private final Boolean bluetoothSupported;
    private final BluetoothState bluetoothState;

    private RadioStateSnapshot(NetworkState networkState, List<Transport> transports,
            Boolean internetCapable, Boolean validated, Boolean wifiSupported, WifiState wifiState,
            Boolean bluetoothSupported, BluetoothState bluetoothState) {
        this.networkState = networkState;
        this.transports = transports == null ? null : Collections.unmodifiableList(new ArrayList<>(transports));
        this.internetCapable = internetCapable;
        this.validated = validated;
        this.wifiSupported = wifiSupported;
        this.wifiState = wifiState;
        this.bluetoothSupported = bluetoothSupported;
        this.bluetoothState = bluetoothState;
    }

    static RadioStateSnapshot read(Context context) {
        NetworkSample network = readNetwork(context);
        WifiSample wifi = readWifi(context);
        BluetoothSample bluetooth = readBluetooth(context);
        return new RadioStateSnapshot(network.state(), network.transports(), network.internetCapable(),
                network.validated(), wifi.supported(), wifi.state(), bluetooth.supported(), bluetooth.state());
    }

    static RadioStateSnapshot fromValues(Boolean defaultNetworkVisible, Integer[] transportTypes,
            Boolean internetCapable, Boolean validated, Boolean wifiSupported, Integer wifiState,
            Boolean bluetoothSupported, Integer bluetoothState) {
        NetworkSample network = networkSample(defaultNetworkVisible, transportTypes, internetCapable, validated);
        WifiSample wifi = wifiSample(wifiSupported, wifiState);
        BluetoothSample bluetooth = bluetoothSample(bluetoothSupported, bluetoothState);
        return new RadioStateSnapshot(network.state(), network.transports(), network.internetCapable(),
                network.validated(), wifi.supported(), wifi.state(), bluetooth.supported(), bluetooth.state());
    }

    JSONObject toJson() throws JSONException {
        JSONArray transportNames = null;
        if (transports != null) transportNames = new JSONArray();
        if (transports != null) for (Transport transport : transports) transportNames.put(name(transport));
        return new JSONObject()
                .put("network", new JSONObject()
                        .put("state", name(networkState))
                        .put("transports", transports == null ? JSONObject.NULL : transportNames)
                        .put("internetCapable", nullable(internetCapable))
                        .put("validated", nullable(validated)))
                .put("wifi", new JSONObject()
                        .put("supported", nullable(wifiSupported))
                        .put("state", name(wifiState)))
                .put("bluetooth", new JSONObject()
                        .put("supported", nullable(bluetoothSupported))
                        .put("state", name(bluetoothState)));
    }

    NetworkState networkState() { return networkState; }
    List<Transport> transports() { return transports; }
    Boolean internetCapable() { return internetCapable; }
    Boolean validated() { return validated; }
    Boolean wifiSupported() { return wifiSupported; }
    WifiState wifiState() { return wifiState; }
    Boolean bluetoothSupported() { return bluetoothSupported; }
    BluetoothState bluetoothState() { return bluetoothState; }

    private static NetworkSample readNetwork(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            if (manager == null) return networkSample(null, null, null, null);
            Network active = manager.getActiveNetwork();
            // A null result also covers a default network blocked for this caller.
            if (active == null) return networkSample(false, null, null, null);
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
            if (capabilities == null) return networkSample(null, null, null, null);
            List<Integer> transports = new ArrayList<>();
            for (int type : new int[] { NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_BLUETOOTH,
                    NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                    NetworkCapabilities.TRANSPORT_LOWPAN, NetworkCapabilities.TRANSPORT_USB,
                    NetworkCapabilities.TRANSPORT_THREAD,
                    NetworkCapabilities.TRANSPORT_SATELLITE }) {
                if (capabilities.hasTransport(type)) {
                    transports.add(type);
                }
            }
            return networkSample(true, transports.toArray(new Integer[0]),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        } catch (RuntimeException unavailable) {
            return networkSample(null, null, null, null);
        }
    }

    private static WifiSample readWifi(Context context) {
        try {
            boolean supported = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
            if (!supported) return wifiSample(false, null);
            WifiManager manager = context.getSystemService(WifiManager.class);
            if (manager == null) return wifiSample(null, null);
            return wifiSample(true, manager.getWifiState());
        } catch (RuntimeException unavailable) {
            return wifiSample(null, null);
        }
    }

    private static BluetoothSample readBluetooth(Context context) {
        try {
            boolean supported = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
            if (!supported) return bluetoothSample(false, null);
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) return bluetoothSample(null, null);
            return bluetoothSample(true, adapter.getState());
        } catch (RuntimeException unavailable) {
            return bluetoothSample(null, null);
        }
    }

    private static NetworkSample networkSample(Boolean defaultNetworkVisible, Integer[] transportTypes,
            Boolean internetCapable, Boolean validated) {
        if (Boolean.FALSE.equals(defaultNetworkVisible))
            return new NetworkSample(NetworkState.UNAVAILABLE, List.of(), null, null);
        if (!Boolean.TRUE.equals(defaultNetworkVisible) || transportTypes == null
                || internetCapable == null || validated == null)
            return new NetworkSample(NetworkState.UNKNOWN, null, null, null);
        Set<Transport> transports = EnumSet.noneOf(Transport.class);
        for (Integer type : transportTypes) transports.add(type == null ? Transport.OTHER : transport(type));
        if (transports.isEmpty()) transports.add(Transport.OTHER);
        return new NetworkSample(NetworkState.AVAILABLE, new ArrayList<>(transports), internetCapable, validated);
    }

    private static WifiSample wifiSample(Boolean supported, Integer state) {
        if (Boolean.FALSE.equals(supported)) return new WifiSample(false, WifiState.UNSUPPORTED);
        if (!Boolean.TRUE.equals(supported) || state == null) return new WifiSample(supported, WifiState.UNKNOWN);
        return new WifiSample(true, switch (state) {
            case WifiManager.WIFI_STATE_ENABLED -> WifiState.ENABLED;
            case WifiManager.WIFI_STATE_DISABLED -> WifiState.DISABLED;
            case WifiManager.WIFI_STATE_ENABLING -> WifiState.ENABLING;
            case WifiManager.WIFI_STATE_DISABLING -> WifiState.DISABLING;
            default -> WifiState.UNKNOWN;
        });
    }

    private static BluetoothSample bluetoothSample(Boolean supported, Integer state) {
        if (Boolean.FALSE.equals(supported)) return new BluetoothSample(false, BluetoothState.UNSUPPORTED);
        if (!Boolean.TRUE.equals(supported) || state == null) return new BluetoothSample(supported, BluetoothState.UNKNOWN);
        return new BluetoothSample(true, switch (state) {
            case BluetoothAdapter.STATE_ON -> BluetoothState.ON;
            case BluetoothAdapter.STATE_OFF -> BluetoothState.OFF;
            case BluetoothAdapter.STATE_TURNING_ON -> BluetoothState.TURNING_ON;
            case BluetoothAdapter.STATE_TURNING_OFF -> BluetoothState.TURNING_OFF;
            default -> BluetoothState.UNKNOWN;
        });
    }

    private static Transport transport(int type) {
        return switch (type) {
            case NetworkCapabilities.TRANSPORT_WIFI -> Transport.WIFI;
            case NetworkCapabilities.TRANSPORT_CELLULAR -> Transport.CELLULAR;
            case NetworkCapabilities.TRANSPORT_ETHERNET -> Transport.ETHERNET;
            case NetworkCapabilities.TRANSPORT_BLUETOOTH -> Transport.BLUETOOTH;
            case NetworkCapabilities.TRANSPORT_VPN -> Transport.VPN;
            case NetworkCapabilities.TRANSPORT_WIFI_AWARE -> Transport.WIFI_AWARE;
            case NetworkCapabilities.TRANSPORT_LOWPAN -> Transport.LOWPAN;
            case NetworkCapabilities.TRANSPORT_USB -> Transport.USB;
            case NetworkCapabilities.TRANSPORT_THREAD -> Transport.THREAD;
            case NetworkCapabilities.TRANSPORT_SATELLITE -> Transport.SATELLITE;
            default -> Transport.OTHER;
        };
    }

    private static Object nullable(Boolean value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static String name(Enum<?> value) { return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'); }
    private record NetworkSample(NetworkState state, List<Transport> transports, Boolean internetCapable, Boolean validated) { }
    private record WifiSample(Boolean supported, WifiState state) { }
    private record BluetoothSample(Boolean supported, BluetoothState state) { }
}
