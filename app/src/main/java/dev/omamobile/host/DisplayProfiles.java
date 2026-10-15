package dev.omamobile.host;

import java.util.LinkedHashMap;
import java.util.Map;

/** Phone/desktop routing around the graphics hosts, with one shared Linux installation. */
final class DisplayProfiles {
    enum Profile { PHONE, DESKTOP }
    record Output(int id, Profile profile, String runtimeId) { }
    private final String runtimeId;
    private final Map<Integer, Output> outputs = new LinkedHashMap<>();
    DisplayProfiles(String runtimeId) { this.runtimeId = runtimeId; }
    synchronized Output connect(int id, boolean defaultDisplay) {
        Output output = new Output(id, defaultDisplay ? Profile.PHONE : Profile.DESKTOP, runtimeId);
        outputs.put(id, output);
        return output;
    }
    synchronized void disconnect(int id) { outputs.remove(id); }
    synchronized Map<Integer, Output> snapshot() { return Map.copyOf(outputs); }
}
