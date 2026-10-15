package dev.omamobile.host;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Wayland terminals and normal app-UID brokers; requires a prepared emulator. */
public class GraphicalAndoTest {
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    @Test public void terminalsUseAppUidAndBrokersFollowTheirOwnDisplayLifetime() throws Exception {
        Assume.assumeTrue("Explicit prepared two-display graphics test required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        int uid = android.os.Process.myUid();
        assertTrue("Expected an ordinary application UID", uid >= 10000);
        Path files = context.getFilesDir().toPath();
        Path root = files.resolve("linux/rootfs");
        Path test = Files.createTempDirectory(GuestPaths.resolve(root, "/tmp"), "graphical-ando-");
        String guest = "/tmp/" + test.getFileName();
        try {
            new GraphicsSmokeTest().upstreamShellsMapOnBothNativeHyprlandSessions();
            for (String profile : new String[]{"phone", "desktop"}) verifyTerminal(context, test, guest, profile, uid);

            int phoneWorker = startWorker(context, test, guest, "phone");
            int desktopWorker = startWorker(context, test, guest, "desktop");
            assertTrue("Phone broker is not accepting clients", acceptsConnections(files.resolve("r/p/ando/ando.sock")));
            assertTrue("Desktop broker is not accepting clients", acceptsConnections(files.resolve("r/d/ando/ando.sock")));
            GraphicsSmokeTest.awaitWidgetGuard(files);
            context.sendBroadcast(new Intent(DesktopService.STOP_SCREENS).setPackage(context.getPackageName())
                    .putExtra("profile", "phone"));
            awaitStopped(files.resolve("r/p/ando/ando.sock"), phoneWorker);
            assertTrue("Phone stop revoked desktop broker", acceptsConnections(files.resolve("r/d/ando/ando.sock")));
            assertTrue("Phone stop killed desktop command", Files.exists(Path.of("/proc/" + desktopWorker)));
            verifyTerminal(context, test, guest, "desktop", uid);

            // Abruptly losing the owner must close its lifetime pipe even when
            // Java stop() cannot run. Restrict the target to our own desktop PID.
            var desktop = context.getSystemService(ActivityManager.class).getRunningAppProcesses().stream()
                    .filter(p -> p.uid == uid && p.processName.equals(context.getPackageName() + ":desktop"))
                    .findFirst().orElseThrow();
            android.os.Process.killProcess(desktop.pid);
            awaitStopped(files.resolve("r/d/ando/ando.sock"), desktopWorker);
        } finally {
            instrumentation.runOnMainSync(() -> context.startService(new Intent(context, DesktopService.class)
                    .setAction(DesktopService.STOP_SCREENS)));
            GuestPaths.deleteTree(test);
        }
    }

    private static void verifyTerminal(Context context, Path test, String guest, String profile, int uid) throws Exception {
        String prefix = profile + "-probe";
        Path done = test.resolve(prefix + ".done");
        Files.deleteIfExists(done);
        String command = "IFS= read -r line; printf 'stdin=%s\\narg1=%s\\narg2=%s\\nenv=%s\\n' "
                + "\"$line\" \"$1\" \"$2\" \"$ANDO_TEST_VALUE\"; printf 'stderr-marker\\n' >&2; exit 37";
        String script = "#!/bin/bash\nset -u\ncd " + quote(guest) + " || exit 1\n"
                + "exec > " + quote(prefix + ".out") + " 2> " + quote(prefix + ".err") + "\n"
                + "[[ -t 0 ]] || { echo missing-terminal; exit 1; }\n"
                + "printf 'profile=%s\\nsocket=%s\\n' \"$OMAMOBILE_PROFILE\" \"$OMALAUNCHER_ANDO_SOCKET\"\n"
                + "ando /system/bin/id -u; printf 'id-status=%s\\n' \"$?\"\n"
                + "ando /system/bin/pwd\n"
                + "printf 'pipe payload\\n' | ando -e 'ANDO_TEST_VALUE=env with spaces' /system/bin/sh -c "
                + quote(command) + " child 'argument with spaces' 'literal;$()'\n"
                + "printf 'command-status=%s\\n' \"$?\"\n"
                + "ando /system/bin/sh -c 'kill -TERM $$'; printf 'signal-status=%s\\n' \"$?\"\n"
                + "printf done > " + quote(prefix + ".done") + "\n";
        launchFoot(context, test, guest, profile, prefix, script);
        awaitFile(done);
        String output = new String(Files.readAllBytes(test.resolve(prefix + ".out")), StandardCharsets.UTF_8);
        String error = new String(Files.readAllBytes(test.resolve(prefix + ".err")), StandardCharsets.UTF_8);
        assertEquals(output, "profile=" + profile + "\nsocket=/run/omamobile-ando/ando.sock\n" + uid
                + "\nid-status=0\n" + test.toRealPath() + "\nstdin=pipe payload\narg1=argument with spaces\n"
                + "arg2=literal;$()\nenv=env with spaces\ncommand-status=37\nsignal-status=143\n", output);
        assertEquals(error, "stderr-marker\n", error);
    }

    private static int startWorker(Context context, Path test, String guest, String profile) throws Exception {
        String prefix = profile + "-worker";
        launchFoot(context, test, guest, profile, prefix, "#!/bin/bash\nando /system/bin/sh -c "
                + quote("echo $$; exec /system/bin/sleep 120") + " > " + quote(guest + "/" + prefix + ".pid") + "\n");
        Path pidFile = test.resolve(prefix + ".pid");
        awaitFile(pidFile);
        int pid = Integer.parseInt(new String(Files.readAllBytes(pidFile), StandardCharsets.UTF_8).trim());
        assertTrue("Expected a real Android worker PID", pid > 1 && Files.exists(Path.of("/proc/" + pid)));
        return pid;
    }

    private static void launchFoot(Context context, Path test, String guest, String profile, String name, String script) throws Exception {
        Files.write(test.resolve(name + ".sh"), script.getBytes(StandardCharsets.UTF_8));
        Properties ready = new Properties();
        try (var in = Files.newInputStream(context.getFilesDir().toPath().resolve("graphics/" + profile + "/ready.properties"))) {
            ready.load(in);
        }
        // This IPC goes through the native compositor's real exec wrapper;
        // Foot then starts glibc Bash with a real Wayland window and PTY.
        String command = "foot --app-id=omamobile-ando-test /bin/bash " + quote(guest + "/" + name + ".sh");
        String luaCommand = "\"" + command.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        // Pinned Hyprland uses Lua dispatchers, as in the working
        // tools/linux/verify-desktop-compatibility integration check.
        String reply = GraphicsSmokeTest.ipc(context, profile, ready, "dispatch_lua_expression",
                "hl.dsp.exec_cmd(" + luaCommand + ")");
        assertTrue("Foot launch failed: " + reply, reply.trim().equals("ok"));
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while ((!Files.exists(file) || Files.size(file) == 0) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertTrue("Graphical command did not finish: " + file, Files.exists(file) && Files.size(file) > 0);
    }

    private static boolean acceptsConnections(Path socket) {
        try (var client = new android.net.LocalSocket()) {
            client.connect(new android.net.LocalSocketAddress(socket.toString(), android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (java.io.IOException closed) { return false; }
    }

    private static void awaitStopped(Path socket, int worker) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while ((acceptsConnections(socket) || Files.exists(Path.of("/proc/" + worker))) && SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(50);
        boolean accepting = acceptsConnections(socket);
        boolean workerExists = Files.exists(Path.of("/proc/" + worker));
        // A SIGKILL can leave the filesystem socket entry behind. The next
        // broker removes it before binding; it is not a live command endpoint.
        var evidence = new org.json.JSONObject().put("socket", socket.toString())
                .put("socketEntryExists", Files.exists(socket)).put("acceptsConnections", accepting)
                .put("workerPid", worker).put("workerExists", workerExists);
        Files.write(socket.resolveSibling("verified-teardown.json"), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        android.util.Log.i("OmamobileAndoTest", evidence.toString());
        assertFalse("Broker endpoint survived owner teardown: " + socket, accepting);
        assertFalse("Android command survived broker teardown: " + worker, workerExists);
    }
}
