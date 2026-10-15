package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Bounded real Foot descendants, including detached and reparented Linux jobs. */
public class GraphicalProcessCleanupTest {
    private record Fixture(int pid, String start, String command, String name, JSONObject before) { }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    @Test public void plannedStopEndsOwnedLinuxDescendants() throws Exception {
        Assume.assumeTrue("Prepared graphics and explicit -e graphics true required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Path files = context.getFilesDir().toPath();
        Path test = Files.createTempDirectory(GuestPaths.resolve(files.resolve("linux/rootfs"), "/tmp"), "graphical-lifetime-");
        String guest = "/tmp/" + test.getFileName();
        List<Fixture> fixtures = new ArrayList<>();
        List<Fixture> owners = new ArrayList<>();
        JSONObject report = new JSONObject().put("fixture", guest).put("uid", android.os.Process.myUid());
        JSONArray results = new JSONArray();
        report.put("processes", results);
        try {
            new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
            GraphicsSmokeTest.awaitWidgetGuard(files);
            for (String profile : new String[]{"phone", "desktop"}) {
                String script = "#!/bin/bash\nset -eu\n";
                for (String kind : new String[]{"foreground", "detached", "orphan"}) {
                    String name = profile + "-" + kind;
                    String child = "printf '%s\\n' \"$$\" > " + quote(guest + "/" + name + ".pid")
                            + "; exec -a " + quote(test.getFileName() + "-" + name) + " /bin/sleep 180";
                    if (kind.equals("orphan")) {
                        // The setsid intermediate exits without waiting. Its
                        // sleep must really reparent before Stop is exercised.
                        String launcher = "printf '%s\\n' \"$$\" > " + quote(guest + "/" + name + "-parent.pid")
                                + "; /bin/bash -c " + quote(child) + " </dev/null >/dev/null 2>&1 &\nexit 0\n";
                        script += "/usr/bin/setsid /bin/bash -c " + quote(launcher) + " </dev/null >/dev/null 2>&1 &\n";
                    } else {
                        script += (kind.equals("detached") ? "/usr/bin/setsid " : "")
                                + "/bin/bash -c " + quote(child) + " </dev/null >/dev/null 2>&1 &\n";
                    }
                }
                script += "wait\n";
                Files.write(test.resolve(profile + ".sh"), script.getBytes(StandardCharsets.UTF_8));
                Properties ready = new Properties();
                try (var in = Files.newInputStream(files.resolve("graphics/" + profile + "/ready.properties"))) { ready.load(in); }
                String command = "foot --app-id=" + test.getFileName() + " /bin/bash " + quote(guest + "/" + profile + ".sh");
                String lua = "\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
                assertEquals("ok", GraphicsSmokeTest.ipc(context, profile, ready, "dispatch_lua_expression",
                        "hl.dsp.exec_cmd(" + lua + ")").trim());
                for (String kind : new String[]{"foreground", "detached", "orphan"}) {
                    String name = profile + "-" + kind;
                    Path pidFile = test.resolve(name + ".pid");
                    long deadline = SystemClock.uptimeMillis() + 15000;
                    while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
                    assertTrue("Foot did not start " + name, Files.exists(pidFile) && Files.size(pidFile) > 0);
                    int pid = Integer.parseInt(new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim());
                    assertTrue("Invalid fixture PID", pid > 1 && pid != android.os.Process.myPid());
                    JSONObject before = snapshot(pid);
                    assertEquals("Fixture escaped app UID", android.os.Process.myUid(), before.getInt("uid"));
                    String commandLine = test.getFileName() + "-" + name + " 180 ";
                    Fixture fixture = new Fixture(pid, before.getString("start"), commandLine, name, before);
                    fixtures.add(fixture);
                    deadline = SystemClock.uptimeMillis() + 5000;
                    while (!sameLiveProcess(fixture, before) && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(25);
                        before = snapshot(pid);
                    }
                    assertTrue("Fixture did not exec its uniquely identified sleep: " + before, sameLiveProcess(fixture, before));
                    if (kind.equals("orphan")) {
                        int launcherPid = Integer.parseInt(new String(Files.readAllBytes(test.resolve(name + "-parent.pid")), StandardCharsets.UTF_8).trim());
                        deadline = SystemClock.uptimeMillis() + 5000;
                        while (!reparentedToOwner(before, launcherPid, context.getPackageName() + ":" + profile)
                                && SystemClock.uptimeMillis() < deadline) {
                            SystemClock.sleep(25);
                            before = snapshot(pid);
                        }
                        assertTrue("Detached fixture never became a real orphan: " + before,
                                reparentedToOwner(before, launcherPid, context.getPackageName() + ":" + profile));
                        JSONObject launcherState = snapshot(launcherPid);
                        assertTrue("Orphan launcher still running", !launcherState.optBoolean("exists")
                                || launcherState.optString("state").equals("Z"));
                        before.put("launcherPid", launcherPid).put("reparentedBeforeStop", true);
                    }
                    fixtures.set(fixtures.size() - 1, new Fixture(pid, fixture.start(), commandLine, name, before));
                }
            }
            GraphicsSmokeTest.awaitWidgetGuard(files);
            for (var process : context.getSystemService(android.app.ActivityManager.class).getRunningAppProcesses()) {
                if (process.uid == android.os.Process.myUid() && (process.processName.equals(context.getPackageName() + ":phone")
                        || process.processName.equals(context.getPackageName() + ":desktop"))) {
                    JSONObject before = snapshot(process.pid);
                    owners.add(new Fixture(process.pid, before.getString("start"), before.getString("command"), process.processName, before));
                }
            }
            assertEquals("Expected both graphical owners", 2, owners.size());
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
            // The host watchdog bounds normal compositor shutdown to six seconds.
            long deadline = SystemClock.uptimeMillis() + 10000;
            while (SystemClock.uptimeMillis() < deadline && (anyAlive(fixtures) || anyAlive(owners))) SystemClock.sleep(100);
            JSONArray ownerResults = new JSONArray();
            for (Fixture owner : owners) ownerResults.put(new JSONObject().put("name", owner.name())
                    .put("before", owner.before()).put("after", snapshot(owner.pid())));
            report.put("owners", ownerResults);
            assertFalse("Graphical owner processes did not stop", anyAlive(owners));
            List<String> survivors = new ArrayList<>();
            for (Fixture fixture : fixtures) {
                JSONObject after = snapshot(fixture.pid());
                boolean alive = sameLiveProcess(fixture, after);
                results.put(new JSONObject().put("name", fixture.name()).put("before", fixture.before())
                        .put("after", after).put("survivedPlannedStop", alive));
                if (alive) survivors.add(fixture.name() + " pid=" + fixture.pid());
            }
            report.put("survivors", new JSONArray(survivors));
            assertTrue("Linux descendants survived planned graphical stop: " + survivors, survivors.isEmpty());
        } finally {
            JSONArray cleanup = new JSONArray();
            for (Fixture fixture : fixtures) {
                if (sameLiveProcess(fixture, snapshot(fixture.pid()))) {
                    // PID + kernel starttime + UID + exact command identify only this test's
                    // bounded sleep, never other sessions or the whole app UID.
                    android.os.Process.killProcess(fixture.pid());
                    cleanup.put(fixture.pid());
                }
            }
            report.put("fixturePidsKilled", cleanup);
            Files.write(files.resolve("graphics/verified-process-cleanup.json"), report.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.write(test.resolve("evidence.json"), report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean anyAlive(List<Fixture> fixtures) throws Exception {
        for (Fixture fixture : fixtures) if (sameLiveProcess(fixture, snapshot(fixture.pid()))) return true;
        return false;
    }

    private static boolean sameLiveProcess(Fixture fixture, JSONObject current) throws Exception {
        return current.optBoolean("exists") && current.getInt("uid") == android.os.Process.myUid()
                && current.getString("start").equals(fixture.start()) && !current.getString("state").equals("Z")
                && current.getString("command").equals(fixture.command());
    }

    private static boolean reparentedToOwner(JSONObject current, int launcher, String owner) throws Exception {
        if (!current.optBoolean("exists") || current.optString("state").equals("Z")) return false;
        int parent = Integer.parseInt(current.getString("ppid"));
        if (parent == launcher) return false;
        return parent == 1 || snapshot(parent).optString("command").trim().equals(owner);
    }

    private static JSONObject snapshot(int pid) throws Exception {
        JSONObject result = new JSONObject().put("pid", pid).put("exists", false);
        Path proc = Path.of("/proc/" + pid);
        try {
            String stat = new String(Files.readAllBytes(proc.resolve("stat")), StandardCharsets.UTF_8);
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
            String status = new String(Files.readAllBytes(proc.resolve("status")), StandardCharsets.UTF_8);
            int uid = -1;
            for (String line : status.split("\n")) if (line.startsWith("Uid:")) uid = Integer.parseInt(line.trim().split("\\s+")[1]);
            return result.put("exists", true).put("uid", uid).put("state", fields[0]).put("ppid", fields[1])
                    .put("pgid", fields[2]).put("sid", fields[3]).put("start", fields[19])
                    .put("command", new String(Files.readAllBytes(proc.resolve("cmdline")), StandardCharsets.UTF_8).replace('\0', ' '));
        } catch (java.nio.file.NoSuchFileException vanished) { return result; }
    }
}
