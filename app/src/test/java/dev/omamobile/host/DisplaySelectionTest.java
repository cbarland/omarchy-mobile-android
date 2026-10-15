package dev.omamobile.host;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class DisplaySelectionTest {
    private DisplaySelection.Candidate output(int id, String signature, boolean eligible, boolean secure) {
        return new DisplaySelection.Candidate(id, signature, eligible, secure);
    }

    @Test public void automaticUsesPresentationTargetsAndKeepsExistingPriority() {
        var outputs = List.of(output(0, "phone", false, false), output(1, "android-window", false, false),
                output(2, "protected", true, true), output(9, "external", true, false), output(6, "external2", true, false));
        assertEquals(new DisplaySelection.Result(6, DisplaySelection.Reason.AUTOMATIC), DisplaySelection.choose(outputs, ""));
    }

    @Test public void savedMonitorSurvivesAndroidIdChangeAndDoesNotPreferAnotherOutput() {
        var connected = List.of(output(12, "saved", true, true), output(3, "other", true, false));
        assertEquals(new DisplaySelection.Result(12, DisplaySelection.Reason.SAVED), DisplaySelection.choose(connected, "saved"));
        var reconnected = List.of(output(71, "saved", true, true), output(3, "other", true, false));
        assertEquals(71, DisplaySelection.choose(reconnected, "saved").id());
    }

    @Test public void missingSavedMonitorDoesNotSendDesktopToDifferentScreen() {
        var outputs = List.of(output(3, "other", true, false));
        assertEquals(new DisplaySelection.Result(-1, DisplaySelection.Reason.UNAVAILABLE), DisplaySelection.choose(outputs, "saved"));
        assertEquals(3, DisplaySelection.choose(outputs, "").id());
    }

    @Test public void identicalMonitorsCannotBeRememberedOrSilentlyRestored() {
        var first = output(2, "same", true, false);
        var second = output(8, "same", true, false);
        var outputs = List.of(first, second);
        assertFalse(DisplaySelection.canRemember(outputs, first));
        assertEquals(new DisplaySelection.Result(-1, DisplaySelection.Reason.AMBIGUOUS), DisplaySelection.choose(outputs, "same"));
    }

    @Test public void phoneAndNonPresentationDisplaysNeverBecomeSavedDesktop() {
        var phone = output(0, "saved", false, false);
        assertFalse(DisplaySelection.canRemember(List.of(phone), phone));
        assertEquals(DisplaySelection.Reason.UNAVAILABLE, DisplaySelection.choose(List.of(phone), "saved").reason());
        assertEquals(DisplaySelection.Reason.NONE, DisplaySelection.choose(List.of(phone), "").reason());
    }

    @Test public void signatureIgnoresModeEnumerationOrderAndDuplicateModes() {
        String signature = DisplaySelection.signature("Monitor", List.of("1920x1080@60", "1280x720@60"));
        assertEquals(signature, DisplaySelection.signature("Monitor", List.of("1280x720@60", "1920x1080@60", "1920x1080@60")));
        assertNotEquals(signature, DisplaySelection.signature("Other monitor", List.of("1920x1080@60", "1280x720@60")));
        assertNotEquals(signature, DisplaySelection.signature("Monitor", List.of("1920x1080@60")));
    }
}
