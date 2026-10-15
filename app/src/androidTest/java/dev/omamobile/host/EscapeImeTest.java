package dev.omamobile.host;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Android pre-IME Escape edges observed by an actual Wayland client. */
public class EscapeImeTest {
    @Test public void escapeReachesWaylandOncePerEdgeFromVisibleImeAndBackStillHidesIme() throws Exception {
        var arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Prepared graphics and explicit -e graphics true -e escapeIme true required",
                "true".equals(arguments.getString("graphics")) && "true".equals(arguments.getString("escapeIme")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        var ui = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Path files = context.getFilesDir().toPath();
        Path root = files.resolve("linux/rootfs");
        assertTrue("Install signed official extra/wev in the disposable fixture first",
                Files.isRegularFile(GuestPaths.resolve(root, "/usr/bin/wev")));
        Path scratch = Files.createTempDirectory(GuestPaths.resolve(root, "/tmp"), "escape-ime-");
        String guest = "/tmp/" + scratch.getFileName();
        Path evidence = Files.createDirectories(files.resolve("graphics/escape-ime/" + scratch.getFileName()));
        String previousShowIme = shell(ui, "settings get secure show_ime_with_hard_keyboard").trim();
        Properties ready = new Properties();
        JSONArray imeOutcomes = new JSONArray();
        boolean preferenceChanged = false;
        Throwable failure = null;
        try {
            assertTrue("Unexpected secure setting value", List.of("null", "0", "1").contains(previousShowIme));
            String currentIme = shell(ui, "settings get secure default_input_method").trim();
            assertTrue("An enabled current Android IME is required", !currentIme.isEmpty() && !currentIme.equals("null"));
            preferenceChanged = true;
            shell(ui, "settings put secure show_ime_with_hard_keyboard 1");
            assertEquals("Fixture could not enable the current IME with a hardware keyboard", "1",
                    shell(ui, "settings get secure show_ime_with_hard_keyboard").trim());
            new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
            try (var in = Files.newInputStream(files.resolve("graphics/phone/ready.properties"))) { ready.load(in); }
            assertEquals("Inject only into the default phone display", "0", ready.getProperty("ANDROID_DISPLAY_ID"));
            JSONArray clients = new JSONArray(GraphicsSmokeTest.ipc(context, "phone", ready, "clients"));
            for (int i = 0; i < clients.length(); i++)
                assertNotEquals("Close an existing wev before this focused fixture", "wev", clients.getJSONObject(i).optString("class"));

            // wev is an ordinary signed Wayland xdg-shell client. stdbuf makes
            // key events observable before exit; timeout bounds its lifetime.
            // It logs XKB keycodes (evdev+8), so Escape appears as key 9.
            String script = "#!/bin/bash\nprintf '%s' \"$$\" > " + quote(guest + "/observer.pid") + "\n"
                    + "exec stdbuf -oL -eL timeout --kill-after=1s 120s wev -f wl_keyboard:key -f wl_keyboard:enter > "
                    + quote(guest + "/wev.log") + " 2>&1\n";
            Files.write(scratch.resolve("observer.sh"), script.getBytes(StandardCharsets.UTF_8));
            exec(context, ready, "/bin/bash " + quote(guest + "/observer.sh"));
            awaitWev(context, ready, scratch.resolve("wev.log"));

            assertEquals("Observer already saw an Escape before injection", List.of(), escapeEdges(read(scratch.resolve("wev.log"))));

            for (int scanCode : new int[]{0, 1}) {
                // Android may hide its IME independently after a hardware key.
                // Establish the visible starting state separately for each pair.
                showImeForPhone(context, ui, evidence, "before-scan-" + scanCode);
                awaitWev(context, ready, scratch.resolve("wev.log"));
                long down = SystemClock.uptimeMillis();
                inject(ui, new KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode, 0, InputDevice.SOURCE_KEYBOARD));
                SystemClock.sleep(50);
                inject(ui, new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, scanCode, 0, InputDevice.SOURCE_KEYBOARD));
                int expected = scanCode == 0 ? 2 : 4;
                long deadline = SystemClock.uptimeMillis() + 5000;
                while (escapeEdges(read(scratch.resolve("wev.log"))).size() < expected && SystemClock.uptimeMillis() < deadline)
                    SystemClock.sleep(50);
                SystemClock.sleep(250); // Observe unexpected duplicate delivery too.
                List<Integer> edges = escapeEdges(read(scratch.resolve("wev.log")));
                assertEquals("Escape must reach Wayland exactly once per DOWN/UP", scanCode == 0 ? List.of(1, 0) : List.of(1, 0, 1, 0), edges);
                imeOutcomes.put(saveImeObservation(ui, evidence.resolve("phone-ime-after-scan-" + scanCode + ".txt"))
                        .put("scanCode", scanCode).put("imeVisibleBeforePair", true));
            }

            // Re-show the IME before checking Android Back's existing meaning;
            // an already hidden keyboard would not prove this behavior.
            showImeForPhone(context, ui, evidence, "before-back");
            awaitWev(context, ready, scratch.resolve("wev.log"));
            // Use Android's ordinary input command for system Back semantics,
            // including its system/virtual-key flags on predictive-back builds.
            shell(ui, "input keyboard -d 0 keyevent KEYCODE_BACK");
            awaitIme(ui, false, false, evidence.resolve("phone-ime-after-back.txt"));
            assertEquals(List.of(1, 0, 1, 0), escapeEdges(read(scratch.resolve("wev.log"))));
        } catch (Throwable error) { failure = error; }
        finally {
            try {
                if (Files.exists(scratch.resolve("wev.log"))) Files.copy(scratch.resolve("wev.log"), evidence.resolve("wev.log"));
            } catch (Throwable error) { failure = append(failure, error); }
            try {
                if (Files.exists(scratch.resolve("observer.pid"))) {
                    int pid = Integer.parseInt(read(scratch.resolve("observer.pid")).trim());
                    assertTrue("Observer PID must be real", pid > 1);
                    Path process = Path.of("/proc/" + pid);
                    if (Files.exists(process)) {
                        String command = read(process.resolve("cmdline"));
                        assertTrue("Refusing to signal a reused unrelated PID: " + command,
                                command.contains("timeout") && command.contains("wev"));
                        String status = read(process.resolve("status"));
                        assertTrue("Observer escaped the app UID", java.util.regex.Pattern.compile("(?m)^Uid:\\s+"
                                + android.os.Process.myUid() + "\\s").matcher(status).find());
                        exec(context, ready, "/bin/kill -TERM " + pid);
                    }
                    long deadline = SystemClock.uptimeMillis() + 3000;
                    while (Files.exists(Path.of("/proc/" + pid)) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
                    assertFalse("Observer survived cleanup", Files.exists(Path.of("/proc/" + pid)));
                }
            } catch (Throwable error) { failure = append(failure, error); }
            try {
                if (preferenceChanged) {
                    if (previousShowIme.equals("null")) shell(ui, "settings delete secure show_ime_with_hard_keyboard");
                    else shell(ui, "settings put secure show_ime_with_hard_keyboard " + previousShowIme);
                    assertEquals("IME preference was not restored", previousShowIme,
                            shell(ui, "settings get secure show_ime_with_hard_keyboard").trim());
                }
            } catch (Throwable error) { failure = append(failure, error); }
            try { GraphicsSmokeTest.awaitWidgetGuard(files); } catch (Throwable error) { failure = append(failure, error); }
            try { GuestPaths.deleteTree(scratch); } catch (Throwable error) { failure = append(failure, error); }
            if (failure != null) Files.write(evidence.resolve("failure.txt"), android.util.Log.getStackTraceString(failure).getBytes(StandardCharsets.UTF_8));
        }
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        Files.write(evidence.resolve("verified.json"), new JSONObject().put("display", 0).put("imeVisibleBeforeEachEscapePair", true)
                .put("escapeEdges", new JSONArray(List.of(1, 0, 1, 0))).put("syntheticAndPhysicalScanCodes", true)
                .put("imeAfterEscapeObservations", imeOutcomes)
                .put("backHidIme", true).put("imePreferenceRestored", true).put("observerStopped", true)
                .toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitWev(Context context, Properties ready, Path output) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            JSONObject active = new JSONObject(GraphicsSmokeTest.ipc(context, "phone", ready, "activewindow"));
            if ("wev".equals(active.optString("class")) && Files.exists(output) && read(output).contains("wl_keyboard")) return;
            SystemClock.sleep(100);
        }
        fail("The actual Wayland observer must be focused and receive keyboard enter");
    }

    private static void showImeForPhone(Context context, android.app.UiAutomation ui, Path evidence, String stage) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        instrumentation.waitForIdleSync();
        MainActivity main = awaitControls();
        instrumentation.runOnMainSync(() -> {
            EditText editor = findEditor(main.getWindow().getDecorView());
            assertNotNull("Main controls' real Android text editor is required", editor);
            editor.requestFocus();
            main.getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
        awaitIme(ui, true, false, evidence.resolve("controls-ime-" + stage + ".txt"));
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, PhoneActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()));
        instrumentation.waitForIdleSync();
        awaitIme(ui, true, true, evidence.resolve("phone-ime-" + stage + ".txt"));
    }

    private static JSONObject saveImeObservation(android.app.UiAutomation ui, Path evidence) throws Exception {
        String dump = shell(ui, "dumpsys input_method");
        String windows = shell(ui, "dumpsys window displays");
        Files.write(evidence, dump.getBytes(StandardCharsets.UTF_8));
        Files.write(windowEvidence(evidence), windows.getBytes(StandardCharsets.UTF_8));
        assertTrue("Phone lost Android window focus after Escape", phoneFocused(windows));
        return new JSONObject().put("inputShown", currentField(dump, "mInputShown", "true"))
                .put("decorViewVisible", currentField(dump, "mDecorViewVisible", "true"))
                .put("windowVisible", currentField(dump, "mWindowVisible", "true"))
                .put("inputViewShown", currentField(dump, "mIsInputViewShown", "true"))
                .put("phoneFocused", true);
    }

    private static void awaitIme(android.app.UiAutomation ui, boolean shown, boolean phone, Path evidence) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8000;
        String dump;
        String windows;
        Path windowEvidence = windowEvidence(evidence);
        do {
            dump = shell(ui, "dumpsys input_method");
            windows = shell(ui, "dumpsys window displays");
            // Android 15 reports actual visibility in the current IME process
            // dump. A show request alone is insufficient. Anchor state fields
            // to current mField= lines rather than matching historical events.
            boolean imeState = currentField(dump, "mDecorViewVisible", Boolean.toString(shown))
                    && currentField(dump, "mWindowVisible", Boolean.toString(shown))
                    // Android can retain mIsInputViewShown=true after hiding
                    // its window. Require it only for the visible precondition.
                    && (!shown || currentField(dump, "mIsInputViewShown", "true"));
            boolean showRequested = currentField(dump, "mInputShown", Boolean.toString(shown));
            boolean phoneDisplay = currentField(dump, "mCurTokenDisplayId", "0");
            if (imeState && showRequested && (!shown || phoneDisplay) && (!phone || phoneFocused(windows))) {
                Files.write(evidence, dump.getBytes(StandardCharsets.UTF_8));
                Files.write(windowEvidence, windows.getBytes(StandardCharsets.UTF_8));
                return;
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        Files.write(evidence, dump.getBytes(StandardCharsets.UTF_8));
        Files.write(windowEvidence, windows.getBytes(StandardCharsets.UTF_8));
        fail("Explicit IME fixture precondition failed: visible=" + shown + ", phone focused=" + phone + "; see " + evidence);
    }

    private static Path windowEvidence(Path evidence) {
        return evidence.resolveSibling(evidence.getFileName().toString().replace(".txt", "-window-displays.txt"));
    }

    private static boolean phoneFocused(String windows) {
        return java.util.regex.Pattern.compile("(?m)^[ \\t]*mCurrentFocus=Window\\{[^\\r\\n]*"
                + "dev\\.omamobile\\.host/(?:dev\\.omamobile\\.host\\.|\\.)?PhoneActivity(?:[ \\t}]|$)").matcher(windows).find();
    }

    private static boolean currentField(String dump, String field, String value) {
        return java.util.regex.Pattern.compile("(?m)^[ \\t]*(?=m[A-Za-z0-9_]+=)[^\\r\\n]*\\b"
                + java.util.regex.Pattern.quote(field) + "=" + java.util.regex.Pattern.quote(value) + "(?=[ \\t]|$)")
                .matcher(dump).find();
    }

    private static List<Integer> escapeEdges(String output) {
        List<Integer> edges = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("\\bkey:\\s*9;\\s*state:\\s*([01])\\b").matcher(output);
        while (matcher.find()) edges.add(Integer.parseInt(matcher.group(1)));
        return edges;
    }

    private static MainActivity awaitControls() {
        AtomicReference<MainActivity> current = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                for (var activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (!(activity instanceof MainActivity controls) || !controls.hasWindowFocus()) continue;
                    EditText editor = findEditor(controls.getWindow().getDecorView());
                    if (editor == null || !editor.isShown() || editor.getWindowToken() == null) continue;
                    editor.requestFocus();
                    if (controls.getSystemService(InputMethodManager.class).isActive(editor)) current.set(controls);
                }
            });
            if (current.get() != null) return current.get();
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Main controls did not resume with a focused, active Android text editor");
    }

    private static EditText findEditor(View view) {
        if (view instanceof EditText editor) return editor;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText editor = findEditor(group.getChildAt(i));
            if (editor != null) return editor;
        }
        return null;
    }

    private static void exec(Context context, Properties ready, String command) throws Exception {
        String reply = GraphicsSmokeTest.ipc(context, "phone", ready, "dispatch_lua_expression", "hl.dsp.exec_cmd(\""
                + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\")");
        assertEquals("Native exec wrapper rejected the test command", "ok", reply.trim());
    }

    private static void inject(android.app.UiAutomation ui, KeyEvent event) throws Exception {
        event.setSource(InputDevice.SOURCE_KEYBOARD);
        event.getClass().getMethod("setDisplayId", int.class).invoke(event, 0);
        assertTrue("Android rejected the phone test event", ui.injectInputEvent(event, true));
    }

    private static String shell(android.app.UiAutomation ui, String command) throws Exception {
        try (var parcel = ui.executeShellCommand("timeout 5 " + command); var in = new ParcelFileDescriptor.AutoCloseInputStream(parcel)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
    private static Throwable append(Throwable first, Throwable next) { if (first == null) return next; first.addSuppressed(next); return first; }
}
