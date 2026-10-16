package dev.omamobile.host;

/** Chooses the only safe HOME destination before any activity is launched. */
final class HomeRouting {
    enum Destination { PHONE, SETUP }

    static Destination choose(boolean graphicsReady, boolean nativeAvailable) {
        return graphicsReady && nativeAvailable ? Destination.PHONE : Destination.SETUP;
    }
}
