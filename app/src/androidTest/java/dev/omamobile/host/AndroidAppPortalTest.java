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
import static org.junit.Assert.*;

public class AndroidAppPortalTest {
    private byte[] lastImage = new byte[0];
    private LocalSocket connect() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        context.startForegroundService(new Intent(context, DesktopService.class));
        String path = new File(context.getFilesDir(), "r/android.sock").toString();
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
        }
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
