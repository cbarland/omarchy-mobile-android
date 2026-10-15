package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real phone/OWL startup guard with only its own Android broker suspended. */
public class SlowBrokerShutdownTest {
    private record Identity(int pid, int uid, String start, String command, int parent, String state) { }

    @Test public void plannedStopClearsRealWidgetGuardBeforeStalledBrokerWait() throws Exception {
        Assume.assumeTrue("Explicit prepared emulator slow-broker test required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics"))
                && "true".equals(InstrumentationRegistry.getArguments().getString("owl"))
                && "true".equals(InstrumentationRegistry.getArguments().getString("slowBroker")));
        Assume.assumeTrue("Never suspend a broker on a physical device",
                android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish"));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Path files = context.getFilesDir().toPath();
        Path guard = GuestPaths.resolve(files.resolve("linux/rootfs"), "/root/.config/omamobile/widget-guard.json");
        String sentinel = "omamobile.test.slow-broker-stop";
        Identity broker = null, owner = null, shell = null;
        boolean seededCrash = false;
        JSONObject report = new JSONObject().put("uid", android.os.Process.myUid());
        try {
            // Cold-start only the phone; do not close the desktop service or
            // its authorized Shizuku connection, and never restore old files.
            stopPhone(context);
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (phoneOwner(context) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertNull("Previous phone owner did not stop", phoneOwner(context));
            if (Files.exists(guard)) {
                JSONObject state = readGuard(guard);
                assertFalse("A preexisting real widget crash must not be cleared by this test",
                        state.getJSONObject("crashed").has("simonschubert.todo"));
                assertFalse("Synthetic crash record already exists", state.getJSONObject("crashed").has(sentinel));
            }
            instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, PhoneActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)));
            deadline = SystemClock.uptimeMillis() + 75000;
            String expectedBroker = context.getApplicationInfo().nativeLibraryDir + "/libando.so\0--omalauncher-broker\0"
                    + files.resolve("r/p/ando/ando.sock") + "\0";
            boolean activeGuard = false;
            while (SystemClock.uptimeMillis() < deadline) {
                if (owner == null) owner = phoneOwner(context);
                if (owner != null && (broker == null || shell == null)) {
                    try (var entries = Files.newDirectoryStream(Path.of("/proc"))) {
                        for (Path entry : entries) {
                            int pid;
                            try { pid = Integer.parseInt(entry.getFileName().toString()); }
                            catch (NumberFormatException ignored) { continue; }
                            Identity candidate = identity(pid);
                            if (candidate == null || candidate.uid() != android.os.Process.myUid()
                                    || candidate.parent() != owner.pid() || candidate.state().equals("Z")) continue;
                            if (candidate.command().equals(expectedBroker)) broker = candidate;
                            if (candidate.command().contains("quickshell\0-n\0-p\0/root/.local/share/omarchy/shell\0")) shell = candidate;
                        }
                    }
                }
                if (broker != null && shell != null && Files.exists(guard)) {
                    JSONObject state = readGuard(guard);
                    assertFalse("Real widget crashed during startup", state.getJSONObject("crashed").has("simonschubert.todo"));
                    if (state.getJSONObject("loading").has("simonschubert.todo")) { activeGuard = true; break; }
                }
                SystemClock.sleep(25);
            }
            report.put("identifiedOwner", owner != null).put("identifiedBroker", broker != null).put("identifiedShell", shell != null);
            assertTrue("Did not identify the live phone shell, broker, and real OWL startup marker: " + report, activeGuard);
            assertTrue("Phone shell exited before the fixture", sameLive(shell));
            signal(broker, OsConstants.SIGSTOP);
            deadline = SystemClock.uptimeMillis() + 500;
            while (!stopped(broker) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
            assertTrue("Exact phone broker was not suspended", stopped(broker));
            JSONObject state = readGuard(guard);
            assertTrue("The real widget guard expired before planned stop", state.getJSONObject("loading").has("simonschubert.todo"));
            assertFalse(state.getJSONObject("crashed").has(sentinel));
            state.getJSONObject("crashed").put(sentinel, "test-only recorded crash");
            String recordedCrashes = state.getJSONObject("crashed").toString();
            writeGuard(guard, state);
            seededCrash = true;
            report.put("ownerPid", owner.pid()).put("shellPid", shell.pid()).put("brokerPid", broker.pid())
                    .put("brokerStart", broker.start()).put("brokerCommand", broker.command().replace('\0', ' '))
                    .put("guardBeforeStop", state);
            long started = SystemClock.uptimeMillis();
            stopPhone(context);
            boolean cleared = false;
            while (SystemClock.uptimeMillis() - started < 5500) {
                state = readGuard(guard);
                if (state.getJSONObject("loading").length() == 0 && !sameLive(shell)) { cleared = true; break; }
                SystemClock.sleep(25);
            }
            report.put("markerClearMillis", SystemClock.uptimeMillis() - started).put("guardAfterStop", state)
                    .put("shellAlive", sameLive(shell)).put("ownerAliveAtMarkerCheck", sameLive(owner))
                    .put("brokerStillSuspended", stopped(broker));
            assertTrue("Planned stop did not reap the real shell and clear its loading markers before the six-second watchdog", cleared);
            assertTrue("Test did not observe cleanup before owner watchdog exit", sameLive(owner));
            assertTrue("Broker was no longer stalled when marker cleanup was observed", stopped(broker));
            assertEquals("Planned stop changed recorded crashes", recordedCrashes, state.getJSONObject("crashed").toString());
            assertFalse("Planned stop converted the real OWL widget into a crash", state.getJSONObject("crashed").has("simonschubert.todo"));
            deadline = started + 10000;
            while (sameLive(owner) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            assertFalse("The unchanged host watchdog did not bound stalled broker shutdown", sameLive(owner));
        } finally {
            // Never signal a recycled PID or another session's broker. Resume
            // this exact fixture first so its closed lifetime pipe can drain.
            if (broker != null && sameLive(broker)) {
                signal(broker, OsConstants.SIGCONT);
                long deadline = SystemClock.uptimeMillis() + 1500;
                while (sameLive(broker) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
                if (sameLive(broker)) signal(broker, OsConstants.SIGKILL);
            }
            stopPhone(context);
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (phoneOwner(context) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            if (seededCrash && Files.exists(guard)) {
                JSONObject state = readGuard(guard);
                state.getJSONObject("crashed").remove(sentinel);
                writeGuard(guard, state);
            }
            Files.createDirectories(files.resolve("graphics"));
            Files.write(files.resolve("graphics/verified-slow-broker-stop.json"), report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void stopPhone(Context context) {
        context.sendBroadcast(new Intent(DesktopService.STOP_SCREENS).setPackage(context.getPackageName()).putExtra("profile", "phone"));
    }

    private static Identity phoneOwner(Context context) {
        var processes = context.getSystemService(ActivityManager.class).getRunningAppProcesses();
        if (processes != null) for (var process : processes)
            if (process.uid == android.os.Process.myUid() && process.processName.equals(context.getPackageName() + ":phone")) {
                Identity current = identity(process.pid);
                if (current != null && !current.state().equals("Z")
                        && current.command().replace('\0', ' ').trim().equals(context.getPackageName() + ":phone")) return current;
            }
        return null;
    }

    private static Identity identity(int pid) {
        if (pid <= 1 || pid == android.os.Process.myPid()) return null;
        try {
            Path proc = Path.of("/proc/" + pid);
            String stat = new String(Files.readAllBytes(proc.resolve("stat")), StandardCharsets.UTF_8);
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
            int uid = -1;
            for (String line : new String(Files.readAllBytes(proc.resolve("status")), StandardCharsets.UTF_8).split("\n"))
                if (line.startsWith("Uid:")) uid = Integer.parseInt(line.trim().split("\\s+")[1]);
            return new Identity(pid, uid, fields[19],
                    new String(Files.readAllBytes(proc.resolve("cmdline")), StandardCharsets.UTF_8),
                    Integer.parseInt(fields[1]), fields[0]);
        } catch (java.io.IOException | SecurityException | NumberFormatException unavailable) { return null; }
    }

    private static boolean sameLive(Identity expected) {
        if (expected == null) return false;
        Identity current = identity(expected.pid());
        return current != null && current.uid() == android.os.Process.myUid() && current.uid() == expected.uid()
                && current.start().equals(expected.start()) && current.command().equals(expected.command()) && !current.state().equals("Z");
    }

    private static boolean stopped(Identity expected) {
        Identity current = expected == null ? null : identity(expected.pid());
        return sameLive(expected) && current != null && (current.state().equals("T") || current.state().equals("t"));
    }

    private static void signal(Identity expected, int signal) throws Exception {
        if (sameLive(expected)) Os.kill(expected.pid(), signal);
    }

    private static JSONObject readGuard(Path guard) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(guard), StandardCharsets.UTF_8));
    }

    private static void writeGuard(Path guard, JSONObject state) throws Exception {
        Path temporary = Files.createTempFile(guard.getParent(), ".slow-broker-test-", ".json");
        try {
            Files.write(temporary, (state.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, guard, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
