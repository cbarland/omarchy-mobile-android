package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.app.ActivityManager;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Public Android display facts and a conservative saved routing preference. */
final class DisplayCatalog {
    private static final String PREFERENCES = "display-routing";
    private static final String PREFERRED = "preferred-signature";
    private static final String PREFERRED_NAME = "preferred-name";
    record Output(DisplaySelection.Candidate candidate, String name, String details) { }

    static List<Output> read(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        Set<Integer> presentations = Arrays.stream(manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION))
                .map(Display::getDisplayId).collect(Collectors.toSet());
        List<Output> result = new ArrayList<>();
        for (Display display : manager.getDisplays()) {
            if (!display.isValid()) continue;
            int id = display.getDisplayId();
            boolean phone = id == Display.DEFAULT_DISPLAY;
            // Presentation category alone also includes untrusted virtual
            // displays. Android can reject our Activity there even for the
            // same UID; use its public admission check before offering it.
            boolean eligible = !phone && presentations.contains(id)
                    && activities.isActivityStartAllowedOnDisplay(context, id, new Intent(context, DesktopActivity.class));
            List<String> modes = Arrays.stream(display.getSupportedModes()).map(DisplayCatalog::mode).distinct().sorted().collect(java.util.stream.Collectors.toList());
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            var candidate = new DisplaySelection.Candidate(id, DisplaySelection.signature(display.getName(), modes),
                    eligible, (display.getFlags() & Display.FLAG_SECURE) != 0);
            String details = (phone ? "Phone · Omarchy Mobile" : eligible ? "External · desktop eligible" : "Other Android display · not a desktop target")
                    + "\nAndroid ID " + id + " · " + state(display.getState())
                    + "\nCurrent mode: " + mode(display.getMode())
                    + "\nAndroid size: " + metrics.widthPixels + " × " + metrics.heightPixels
                    + " · density " + metrics.densityDpi + " dpi (" + String.format(Locale.ROOT, "%.2f", metrics.density) + "×)"
                    + "\nRotation: " + (display.getRotation() * 90) + "°"
                    + "\nSupported Android modes: " + String.join(", ", modes);
            result.add(new Output(candidate, display.getName(), details));
        }
        result.sort(Comparator.comparingInt(output -> output.candidate().id()));
        return java.util.Collections.unmodifiableList(result);
    }

    private static String mode(Display.Mode mode) {
        return mode.getPhysicalWidth() + " × " + mode.getPhysicalHeight() + " @ "
                + String.format(Locale.ROOT, "%.3f", mode.getRefreshRate()) + " Hz";
    }
    private static String state(int state) {
        return switch (state) {
            case Display.STATE_ON -> "on";
            case Display.STATE_OFF -> "off";
            case Display.STATE_DOZE -> "dozing";
            case Display.STATE_DOZE_SUSPEND -> "doze suspended";
            case Display.STATE_ON_SUSPEND -> "on suspended";
            case Display.STATE_VR -> "VR";
            default -> "state unknown";
        };
    }
    static List<DisplaySelection.Candidate> candidates(List<Output> outputs) {
        return outputs.stream().map(Output::candidate).collect(java.util.stream.Collectors.toList());
    }
    static String savedSignature(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(PREFERRED, "");
    }
    static String savedName(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(PREFERRED_NAME, "Saved display");
    }
    static void remember(Context context, Output output) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(PREFERRED, output == null ? "" : output.candidate().signature())
                .putString(PREFERRED_NAME, output == null ? "" : output.name()).apply();
    }
    static DisplaySelection.Result selection(Context context, List<Output> outputs) {
        return DisplaySelection.choose(candidates(outputs), savedSignature(context));
    }
    static String selectionSummary(Context context, List<Output> outputs) {
        var selected = selection(context, outputs);
        return switch (selected.reason()) {
            case NONE -> "No external presentation display connected.";
            case UNAVAILABLE -> "Waiting for saved display: " + savedName(context) + ". Choose Automatic to use another display.";
            case AMBIGUOUS -> "Saved display is ambiguous; automatic restoration paused. Choose Automatic to continue.";
            case SAVED -> "Next desktop start: " + savedName(context) + " (Android ID " + selected.id() + ").";
            case AUTOMATIC -> "Next desktop start: automatic (Android ID " + selected.id() + ").";
        };
    }
}
