package dev.omamobile.host;

import android.os.SystemClock;
import android.app.ActivityOptions;
import android.app.Application;
import android.os.ParcelFileDescriptor;
import android.content.Intent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.BaseInputConnection;
import android.text.Editable;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Direct phone-process InputConnection trace against a real focused Wayland client. */
public class ImeCompositionTest {
    @Test public void directCompositionTraceUsesFocusedWaylandClient() throws Exception {
        Assume.assumeTrue("Explicit -e graphics true and -e imeComposition true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics"))
                        && "true".equals(InstrumentationRegistry.getArguments().getString("imeComposition")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        Path files = context.getFilesDir().toPath();
        Path root = files.resolve("linux/rootfs");
        assertTrue("Signed wev must be installed", Files.isRegularFile(GuestPaths.resolve(root, "/usr/bin/wev")));
        new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
        Properties ready = new Properties();
        try (var in = Files.newInputStream(files.resolve("graphics/phone/ready.properties"))) { ready.load(in); }
        Path scratch = Files.createTempDirectory(GuestPaths.resolve(root, "/tmp"), "ime-composition-");
        String guest = "/tmp/" + scratch.getFileName();
        Path log = scratch.resolve("wev.log");
        Path pidFile = scratch.resolve("wev.pid");
        JSONObject report = new JSONObject().put("display", 0).put("cases", new JSONArray());
        Throwable failure = null;
        try {
            Files.write(scratch.resolve("start.sh"), ("#!/bin/bash\n"
                    + "printf '%s' \"$$\" > " + quote(guest + "/wev.pid") + "\n"
                    + "exec stdbuf -oL -eL timeout --kill-after=1s 90s wev -f wl_keyboard:key -f wl_keyboard:enter > "
                    + quote(guest + "/wev.log") + " 2>&1\n").getBytes(StandardCharsets.UTF_8));
            GraphicsSmokeTest.runGuest(context, "phone", ready,
                    List.of("/bin/bash", "-c", "chmod 700 " + quote(guest + "/start.sh")
                            + "; setsid " + quote(guest + "/start.sh") + " >/dev/null 2>&1 &"));
            long clientDeadline = SystemClock.uptimeMillis() + 10000;
            boolean clientExists = false;
            while (SystemClock.uptimeMillis() < clientDeadline) {
                JSONArray clients = new JSONArray(GraphicsSmokeTest.ipc(context, "phone", ready, "clients"));
                for (int i = 0; i < clients.length(); i++)
                    if ("wev".equals(clients.getJSONObject(i).optString("class"))) clientExists = true;
                if (clientExists) break;
                SystemClock.sleep(100);
            }
            assertTrue("wev did not appear in Hyprland clients", clientExists);
            runGuest(context, ready, "/usr/bin/hyprctl", "dispatch", "hl.dsp.focus({ window = 'class:wev' })");
            long started = SystemClock.uptimeMillis() + 10000;
            while (SystemClock.uptimeMillis() < started) {
                JSONObject active = new JSONObject(GraphicsSmokeTest.ipc(context, "phone", ready, "activewindow"));
                if ("wev".equals(active.optString("class")) && Files.exists(log)
                        && read(log).contains("wl_keyboard")) break;
                SystemClock.sleep(100);
            }
            JSONObject focused = new JSONObject(GraphicsSmokeTest.ipc(context, "phone", ready, "activewindow"));
            assertEquals("wev did not become the actual active Wayland client", "wev", focused.optString("class"));
            assertTrue("wev did not receive keyboard enter", read(log).contains("wl_keyboard"));
            String before = read(log);
            InputConnection input = findPhoneInput(instrumentation, report);
            assertNotNull("Phone LinuxSurface InputConnection missing", input);
            commit(instrumentation, input, "a", 1);
            List<String> asciiA = awaitCanonical(log, before.length(), 2);
            assertEquals(List.of("38:1", "38:0"), asciiA);
            before = read(log);
            compose(instrumentation, input, "xy", 2);
            assertEquals("xy", editable(input));
            int zStart = read(log).length();
            commit(instrumentation, input, "z", 1);
            assertEquals("", editable(input));
            assertEquals(List.of("52:1", "52:0"), awaitCanonical(log, zStart, 2));
            report.getJSONArray("cases").put(new JSONObject().put("name", "xy-commit-z-before-finish")
                    .put("events", canonical(read(log).substring(before.length())).toString()));
            int noFinishStart = read(log).length();
            finish(instrumentation, input);
            SystemClock.sleep(1000);
            assertTrue(canonical(read(log).substring(noFinishStart)).isEmpty());
            finish(instrumentation, input);
            SystemClock.sleep(1000);
            assertTrue(canonical(read(log).substring(noFinishStart)).isEmpty());
            before = read(log);
            commit(instrumentation, input, "é界😀", 3);
            List<String> unicodeDirect = awaitCanonical(log, before.length(), 46);
            assertEquals(46, unicodeDirect.size());
            before = read(log);
            compose(instrumentation, input, "é界😀", 3);
            assertEquals("é界😀", editable(input));
            int pendingStart = read(log).length();
            finish(instrumentation, input);
            assertEquals(unicodeDirect, awaitCanonical(log, pendingStart, 46));
            assertEquals("", editable(input));
            report.getJSONArray("cases").put(new JSONObject().put("name", "unicode-direct-and-pending-finish")
                    .put("events", unicodeDirect.toString()));
            before = read(log);
            commit(instrumentation, input, "bc", 2);
            List<String> asciiBC = awaitCanonical(log, before.length(), 4);
            before = read(log);
            compose(instrumentation, input, "bc", 2);
            assertEquals("bc", editable(input));
            int asciiPendingStart = read(log).length();
            finish(instrumentation, input);
            assertEquals(asciiBC, awaitCanonical(log, asciiPendingStart, 4));
            assertEquals("", editable(input));
            InputConnection deleting = findPhoneInput(instrumentation, report);
            int draftControlStart = read(log).length();
            commit(instrumentation, deleting, "draft", 5);
            List<String> draftControl = awaitCanonical(log, draftControlStart, 10);
            InputConnection deletingPending = findPhoneInput(instrumentation, report);
            compose(instrumentation, deletingPending, "draft", 5);
            int deleteStart = read(log).length();
            assertTrue(deleteSurrounding(instrumentation, deletingPending, 1, 0));
            assertTrue(deleteCodePoints(instrumentation, deletingPending, 1, 0));
            assertEquals("draft", editable(deletingPending));
            SystemClock.sleep(1000);
            assertTrue(canonical(read(log).substring(deleteStart)).isEmpty());
            finish(instrumentation, deletingPending);
            assertEquals(draftControl, awaitCanonical(log, deleteStart, 10));
            InputConnection aliased = findPhoneInput(instrumentation, report);
            int aliasControlStart = read(log).length();
            commit(instrumentation, aliased, "alias", 5);
            List<String> aliasControl = awaitCanonical(log, aliasControlStart, 10);
            InputConnection aliasedPending = findPhoneInput(instrumentation, report);
            compose(instrumentation, aliasedPending, "alias", 5);
            Editable alias = ((BaseInputConnection) aliasedPending).getEditable();
            int aliasStart = read(log).length();
            commit(instrumentation, aliasedPending, alias, 5);
            assertEquals(aliasControl, awaitCanonical(log, aliasStart, 10));
            assertEquals("", editable(aliasedPending));
            InputConnection closed = findPhoneInput(instrumentation, report);
            compose(instrumentation, closed, "q", 1);
            assertEquals("q", editable(closed));
            String beforeClose = read(log);
            finishClosed(instrumentation, closed);
            SystemClock.sleep(1000);
            assertTrue(canonical(read(log).substring(beforeClose.length())).isEmpty());
            assertEquals("", editable(closed));
            AtomicReference<Boolean> closedCommit = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> closedCommit.set(closed.commitText("r", 1)));
            assertEquals(Boolean.FALSE, closedCommit.get());
            SystemClock.sleep(1000);
            assertTrue(canonical(read(log).substring(beforeClose.length())).isEmpty());
            report.getJSONArray("cases").put(new JSONObject().put("name", "close-clears-pending").put("events", "[]"));
        } catch (Throwable error) {
            failure = error;
            if (error instanceof Error fatal) throw fatal;
            if (error instanceof Exception checked) throw checked;
            throw new RuntimeException(error);
        } finally {
            try {
                report.put("rawLog", Files.exists(log) ? read(log) : "<missing>");
                report.put("failure", failure == null ? JSONObject.NULL : failure.toString());
                Files.write(files.resolve("graphics/ime-composition-trace.json"), report.toString(2).getBytes(StandardCharsets.UTF_8));
            } finally {
                if (Files.exists(pidFile)) {
                    int pid = Integer.parseInt(read(pidFile).trim());
                    assertTrue("Observer PID must be real", pid > 1);
                    Path proc = Path.of("/proc/" + pid);
                    if (Files.exists(proc)) {
                        String command = read(proc.resolve("cmdline"));
                        assertTrue("Refusing to signal unrelated observer PID: " + command,
                                command.contains("timeout") && command.contains("wev"));
                        String status = read(proc.resolve("status"));
                        assertTrue("Observer escaped the app UID", java.util.regex.Pattern.compile("(?m)^Uid:\\s+"
                                + android.os.Process.myUid() + "\\s").matcher(status).find());
                        runGuest(context, ready, "/bin/kill", "-TERM", Integer.toString(pid));
                        long deadline = SystemClock.uptimeMillis() + 3000;
                        while (Files.exists(proc) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
                        assertFalse("Observer survived cleanup", Files.exists(proc));
                    }
                }
                GuestPaths.deleteTree(scratch);
            }
        }
    }

    private static InputConnection findPhoneInput(android.app.Instrumentation instrumentation, JSONObject report) throws Exception {
        assertEquals("Instrumentation must run in the phone process", instrumentation.getTargetContext().getPackageName() + ":phone",
                Application.getProcessName());
        report.put("processName", Application.getProcessName()).put("pid", android.os.Process.myPid());
        AtomicReference<InputConnection> result = new AtomicReference<>();
        AtomicReference<JSONObject> state = new AtomicReference<>(new JSONObject());
        instrumentation.getTargetContext().startActivity(new Intent(instrumentation.getTargetContext(), PhoneActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
        AtomicReference<int[]> tapPoint = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.CREATED}) {
                for (var activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                    if (activity instanceof PhoneActivity) {
                        SurfaceView surface = findSurface(activity.getWindow().getDecorView());
                        if (surface != null) {
                            int[] location = new int[2];
                            surface.getLocationOnScreen(location);
                            tapPoint.set(new int[]{location[0] + Math.max(1, surface.getWidth() / 2),
                                    location[1] + Math.max(1, surface.getHeight() / 2)});
                        }
                    }
                }
            }
        });
        if (tapPoint.get() != null) {
            int[] point = tapPoint.get();
            try (ParcelFileDescriptor ignored = instrumentation.getUiAutomation().executeShellCommand(
                    "input tap " + point[0] + " " + point[1])) { }
        }
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (result.get() == null && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> {
                for (Stage stage : new Stage[]{Stage.RESUMED, Stage.STARTED, Stage.CREATED}) {
                    for (var activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                        if (!(activity instanceof PhoneActivity)) continue;
                        try {
                            state.get().put("stage", stage.toString()).put("activity", activity.getClass().getName());
                        } catch (Exception impossible) { throw new AssertionError(impossible); }
                        SurfaceView surface = findSurface(activity.getWindow().getDecorView());
                        if (surface == null) continue;
                        try {
                            state.get().put("surface", surface.getClass().getName()).put("focused", surface.isFocused())
                                    .put("windowFocused", activity.hasWindowFocus());
                        } catch (Exception impossible) { throw new AssertionError(impossible); }
                        surface.requestFocus();
                        if (!surface.isFocused() || !activity.hasWindowFocus()) continue;
                        InputConnection input = surface.onCreateInputConnection(new EditorInfo());
                        if (input != null) result.set(input);
                    }
                    if (result.get() != null) break;
                }
            });
            if (result.get() == null) SystemClock.sleep(100);
        }
        report.put("phoneActivityState", state.get());
        return result.get();
    }

    private static SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView surface) return surface;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            SurfaceView surface = findSurface(group.getChildAt(i));
            if (surface != null) return surface;
        }
        return null;
    }

    private static void commit(android.app.Instrumentation instrumentation, InputConnection input, String text, int cursor) throws Exception {
        commit(instrumentation, input, (CharSequence) text, cursor);
    }

    private static void commit(android.app.Instrumentation instrumentation, InputConnection input, CharSequence text, int cursor) throws Exception {
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> ok.set(input.commitText(text, cursor)));
        assertTrue("InputConnection commitText rejected direct call", ok.get());
    }

    private static void compose(android.app.Instrumentation instrumentation, InputConnection input, String text, int cursor) throws Exception {
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> ok.set(input.setComposingText(text, cursor)));
        assertTrue("InputConnection setComposingText rejected direct call", ok.get());
    }

    private static void finish(android.app.Instrumentation instrumentation, InputConnection input) throws Exception {
        instrumentation.runOnMainSync(input::finishComposingText);
    }

    private static void finishClosed(android.app.Instrumentation instrumentation, InputConnection input) throws Exception {
        instrumentation.runOnMainSync(input::closeConnection);
    }

    private static boolean deleteSurrounding(android.app.Instrumentation instrumentation, InputConnection input,
            int before, int after) throws Exception {
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> ok.set(input.deleteSurroundingText(before, after)));
        return ok.get();
    }

    private static boolean deleteCodePoints(android.app.Instrumentation instrumentation, InputConnection input,
            int before, int after) throws Exception {
        AtomicReference<Boolean> ok = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> ok.set(input.deleteSurroundingTextInCodePoints(before, after)));
        return ok.get();
    }

    private static String editable(InputConnection input) {
        return input instanceof BaseInputConnection base && base.getEditable() != null
                ? base.getEditable().toString() : "<unavailable>";
    }

    private static List<String> awaitCanonical(Path log, int oldLength, int count) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            List<String> events = canonical(read(log).substring(oldLength));
            if (events.size() >= count) {
                SystemClock.sleep(1000);
                List<String> quiet = canonical(read(log).substring(oldLength));
                assertEquals("Unexpected extra wl_keyboard events after quiet period", count, quiet.size());
                return quiet;
            }
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for " + count + " canonical wl_keyboard events");
        return List.of();
    }

    private static List<String> canonical(String text) {
        List<String> events = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^.*\\[\\s*\\d+:\\s*wl_keyboard\\]\\s*key:.*?\\bkey:\\s*(\\d+);\\s*state:\\s*([01])\\b.*$").matcher(text);
        while (matcher.find()) events.add(matcher.group(1) + ":" + matcher.group(2));
        return events;
    }

    private static void runGuest(android.content.Context context, Properties ready, String... command) throws Exception {
        GraphicsSmokeTest.runGuest(context, "phone", ready, List.of(command));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
}
