package dev.omamobile.host;

import android.content.Intent;
import android.os.BatteryManager;

/** A single read of Android's public sticky battery broadcast. */
final class BatterySnapshot {
    static final String CHARGING = "charging";
    static final String DISCHARGING = "discharging";
    static final String FULL = "full";
    static final String NOT_CHARGING = "not-charging";
    static final String UNKNOWN = "unknown";

    private final Boolean present;
    private final Double percentage;
    private final Boolean plugged;
    private final String state;

    private BatterySnapshot(Boolean present, Double percentage, Boolean plugged, String state) {
        this.present = present;
        this.percentage = percentage;
        this.plugged = plugged;
        this.state = state;
    }

    static BatterySnapshot fromIntent(Intent intent) {
        if (intent == null) return fromValues(null, null, null, null, null);
        Boolean present = booleanExtra(intent, BatteryManager.EXTRA_PRESENT);
        Integer level = intExtra(intent, BatteryManager.EXTRA_LEVEL);
        Integer scale = intExtra(intent, BatteryManager.EXTRA_SCALE);
        Integer status = intExtra(intent, BatteryManager.EXTRA_STATUS);
        Integer plugged = intExtra(intent, BatteryManager.EXTRA_PLUGGED);
        return fromValues(present, level, scale, status, plugged);
    }

    private static Boolean booleanExtra(Intent intent, String key) {
        if (!intent.hasExtra(key)) return null;
        try { return intent.getBooleanExtra(key, false); }
        catch (RuntimeException malformed) { return null; }
    }

    private static Integer intExtra(Intent intent, String key) {
        if (!intent.hasExtra(key)) return null;
        try { return intent.getIntExtra(key, Integer.MIN_VALUE); }
        catch (RuntimeException malformed) { return null; }
    }

    static BatterySnapshot fromValues(Boolean present, Integer level, Integer scale,
            Integer status, Integer plugged) {
        Double percentage = null;
        if (Boolean.TRUE.equals(present) && level != null && scale != null
                && scale > 0 && level >= 0 && level <= scale) {
            percentage = ((double) level * 100.0) / (double) scale;
        }
        Boolean isPlugged = plugged == null || plugged < 0 ? null : plugged != 0;
        return new BatterySnapshot(present, percentage, isPlugged, state(status));
    }

    private static String state(Integer status) {
        if (status == null) return UNKNOWN;
        return switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING -> CHARGING;
            case BatteryManager.BATTERY_STATUS_DISCHARGING -> DISCHARGING;
            case BatteryManager.BATTERY_STATUS_FULL -> FULL;
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING -> NOT_CHARGING;
            default -> UNKNOWN;
        };
    }

    Boolean present() { return present; }
    Double percentage() { return percentage; }
    Boolean plugged() { return plugged; }
    String state() { return state; }
}
