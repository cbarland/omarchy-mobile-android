package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AndroidAppPortalTest {
    private byte[] lastImage = new byte[0];
    private LocalSocket connect() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.junit.Assume.assumeTrue("Prepared Linux installation required", HostRuntime.get(context).installed());
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        context.startForegroundService(new Intent(context, DesktopService.class));
        String path = new File(context.getFilesDir(), SessionRuntime.HOST_ALIAS + "/android.sock").toString();
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                LocalSocket socket = new LocalSocket();
                socket.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                socket.setSoTimeout(10000); return socket;
            } catch (Exception e) { if (attempt == 99) throw e; SystemClock.sleep(40); }
        }
        throw new AssertionError();
    }
    private JSONObject request(LocalSocket socket, JSONObject json) throws Exception {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(bytes.length); out.write(bytes); out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] reply = new byte[in.readInt()]; in.readFully(reply);
        int image = in.readInt(); lastImage = in.readNBytes(image);
        return new JSONObject(new String(reply, StandardCharsets.UTF_8));
    }
    @Test public void protocolRejectsUnownedInputAndBadSizes() throws Exception {
        try (LocalSocket socket = connect()) {
            assertFalse(request(socket, new JSONObject().put("op", "gesture").put("display", 0)).getBoolean("ok"));
            assertFalse(request(socket, new JSONObject().put("op", "open").put("package", "com.android.settings").put("width", 20000).put("height", 600)).getBoolean("ok"));
            JSONObject list = request(socket, new JSONObject().put("op", "list"));
            assertTrue(list.toString(), list.getBoolean("ok"));
            assertTrue(list.getJSONArray("apps").length() > 0);
            assertFalse(request(socket, new JSONObject().put("op", "unknown-device-state-operation")).getBoolean("ok"));
        }
    }
    @Test public void deviceStateMatchesAndroidApisAndLinuxCli() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e graphics true and prepared Linux required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject host;
        try (LocalSocket socket = connect()) {
            host = request(socket, new JSONObject().put("op", "device-state"));
        }
        assertTrue(host.toString(), host.getBoolean("ok"));
        var keyguard = context.getSystemService(android.app.KeyguardManager.class);
        var power = context.getSystemService(android.os.PowerManager.class);
        assertNotNull(keyguard);
        assertNotNull(power);
        assertEquals(keyguard.isKeyguardLocked(), host.getBoolean("keyguardLocked"));
        assertEquals(keyguard.isDeviceLocked(), host.getBoolean("deviceLocked"));
        assertEquals(power.isInteractive(), host.getBoolean("interactive"));

        var root = context.getFilesDir().toPath().resolve("linux/rootfs");
        JSONObject cli = new JSONObject(runLinuxClient(context, root, "device-state", "device-state-"));
        assertEquals(host.getBoolean("keyguardLocked"), cli.getBoolean("keyguardLocked"));
        assertEquals(host.getBoolean("deviceLocked"), cli.getBoolean("deviceLocked"));
        assertEquals(host.getBoolean("interactive"), cli.getBoolean("interactive"));
    }
    @Test public void linuxClientReachesTheSamePortal() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e graphics true and prepared Linux required",
                "true".equals(InstrumentationRegistry.getArguments().getString("graphics")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.json.JSONArray hostApps;
        try (LocalSocket socket = connect()) {
            JSONObject reply = request(socket, new JSONObject().put("op", "list"));
            assertTrue(reply.toString(), reply.getBoolean("ok"));
            hostApps = reply.getJSONArray("apps");
        }
        var root = context.getFilesDir().toPath().resolve("linux/rootfs");
        var guestApps = new org.json.JSONArray(runLinuxClient(context, root, "list", "portal-list-"));
        assertTrue("Expected discoverable Android applications", guestApps.length() > 0);
        assertEquals(packages(hostApps), packages(guestApps));
    }
    private static String runLinuxClient(Context context, java.nio.file.Path root, String action, String prefix) throws Exception {
        var argv = RuntimeSpec.command(new File(context.getApplicationInfo().nativeLibraryDir, "libtawcroot.so").toPath(),
                root, List.of(new RuntimeSpec.Bind("/dev", "/dev", false),
                        new RuntimeSpec.Bind("/proc", "/proc", false)),
                List.of("/usr/bin/omamobile-android", action));
        var captured = Files.createTempFile(context.getCacheDir().toPath(), prefix, ".json");
        Process process = null;
        try {
            var builder = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(captured.toFile());
            builder.environment().clear();
            builder.environment().putAll(RuntimeSpec.environment(false));
            process = builder.start();
            process.getOutputStream().close();
            assertTrue("Linux portal client timed out", process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(Files.readAllBytes(captured), StandardCharsets.UTF_8).trim();
            assertEquals(output, 0, process.exitValue());
            return output;
        } finally {
            if (process != null) process.destroyForcibly();
            Files.deleteIfExists(captured);
        }
    }
    private static java.util.Set<String> packages(org.json.JSONArray apps) throws Exception {
        var packages = new HashSet<String>();
        for (int i = 0; i < apps.length(); i++) packages.add(apps.getJSONObject(i).getString("package"));
        return packages;
    }
    @Test public void settingsHasLiveFramesAndDisplayDiesWithClient() throws Exception {
        org.junit.Assume.assumeTrue("Explicit -e androidWindows true and device Shizuku authorization required",
                "true".equals(InstrumentationRegistry.getArguments().getString("androidWindows")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int id;
        try (LocalSocket socket = connect()) {
            JSONObject opened = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                opened = request(socket, new JSONObject().put("op", "open").put("package", "com.android.settings").put("width", 720).put("height", 800));
                if (opened.optBoolean("ok") || !opened.optString("error").contains("Connecting Android windows")) break;
                SystemClock.sleep(100);
            }
            assertTrue(opened.toString(), opened.getBoolean("ok"));
            id = opened.getInt("display");
            assertNotEquals(0, id);
            JSONObject frame = null;
            for (int i = 0; i < 100; i++) {
                frame = request(socket, new JSONObject().put("op", "frame"));
                if (frame.optLong("sequence") > 0 && hasContent(lastImage)) break;
                SystemClock.sleep(50);
            }
            assertTrue(String.valueOf(frame), frame != null && frame.getLong("sequence") > 0);
            assertTrue("Expected actual Android Settings content, not an empty display", hasContent(lastImage));
            IAndroidShell helper = AndroidShell.get(context).require();
            assertEquals(2000, helper.uid());
            assertNotEquals(0, android.os.Process.myUid());
            try {
                helper.key(0, android.view.KeyEvent.KEYCODE_A, "");
                fail("The shell adapter must reject input to the phone display");
            } catch (SecurityException expected) { }
        }
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        for (int i = 0; i < 100 && displays.getDisplay(id) != null; i++) SystemClock.sleep(30);
        assertNull("Android display must be released after the Linux client disconnects", displays.getDisplay(id));
    }
    private static boolean hasContent(byte[] image) {
        if (image.length == 0) return false;
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length);
        if (bitmap == null) return false;
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 40))
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 40)) colors.add(bitmap.getPixel(x, y) & 0x00f8f8f8);
        bitmap.recycle();
        return colors.size() > 8;
    }
}
