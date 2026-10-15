package dev.omamobile.host;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** App-owned Android displays exposed as ordinary Linux Wayland client windows.
 * The host has no screen-mirroring or secure-display permission. An optional
 * user-authorized Shizuku shell service owns only these hosted app displays.
 * One connection owns one display; disconnect releases it. SO_PEERCRED restricts
 * the private filesystem socket to this app's UID (including its Linux clients).
 */
final class AndroidAppPortal implements AutoCloseable {
    private final Context context;
    interface ShellProvider { IAndroidShell require() throws IOException; }
    private final ShellProvider shellProvider;
    private final ExecutorService workers = Executors.newFixedThreadPool(4);
    private final Set<LocalSocket> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger windows = new AtomicInteger();
    private LocalSocket binding;
    private LocalServerSocket server;
    private File path;
    private volatile boolean closed;

    AndroidAppPortal(Context context) { this(context, AndroidShell.get(context)::require); }
    AndroidAppPortal(Context context, ShellProvider shellProvider) {
        this.context = context.getApplicationContext();
        this.shellProvider = shellProvider;
    }

    void start() throws IOException {
        File directory = new File(context.getFilesDir(), "r");
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Cannot create runtime directory");
        path = new File(directory, "android.sock");
        if (path.exists() && !path.delete()) throw new IOException("Cannot clear old portal socket");
        binding = new LocalSocket();
        binding.bind(new LocalSocketAddress(path.toString(), LocalSocketAddress.Namespace.FILESYSTEM));
        server = new LocalServerSocket(binding.getFileDescriptor());
        try { Os.chmod(path.toString(), 0600); } catch (Exception e) { close(); throw new IOException(e); }
        new Thread(() -> {
            while (!closed) {
                try {
                    LocalSocket socket = server.accept();
                    if (socket.getPeerCredentials().getUid() != Process.myUid() || connections.size() >= 4) { socket.close(); continue; }
                    socket.setSoTimeout(15000);
                    connections.add(socket);
                    try { workers.execute(() -> serve(socket)); }
                    catch (RejectedExecutionException stopped) {
                        connections.remove(socket);
                        socket.close();
                    }
                } catch (IOException e) { if (!closed) android.util.Log.w("OmamobilePortal", "accept", e); }
            }
        }, "android-window-portal").start();
    }

    private void serve(LocalSocket socket) {
        Hosted hosted = null;
        try (socket; var in = new DataInputStream(socket.getInputStream()); var out = new DataOutputStream(socket.getOutputStream())) {
            while (!closed) {
                int size = in.readInt();
                if (size < 2 || size > 16384) throw new IOException("Invalid request size");
                byte[] data = new byte[size]; in.readFully(data);
                JSONObject request = new JSONObject(new String(data, StandardCharsets.UTF_8));
                JSONObject reply = new JSONObject();
                byte[] image = new byte[0];
                try {
                    String op = request.getString("op");
                    if (op.equals("list")) {
                        JSONArray apps = new JSONArray();
                        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                        for (var app : context.getPackageManager().queryIntentActivities(launcher, 0)) {
                            if (app.activityInfo.exported && !app.activityInfo.packageName.equals(context.getPackageName()))
                                apps.put(new JSONObject().put("package", app.activityInfo.packageName).put("name", app.loadLabel(context.getPackageManager())));
                        }
                        reply.put("apps", apps);
                    } else if (op.equals("open")) {
                        if (hosted != null) throw new IOException("A connection already owns a display");
                        if (windows.incrementAndGet() > 3) { windows.decrementAndGet(); throw new IOException("At most three Android windows"); }
                        // One worker owns allocation through release. A slow
                        // helper cannot block Android's main looper or return a
                        // lease into an abandoned timed-out FutureTask.
                        try { hosted = new Hosted(request.getString("package"), dimension(request, "width"), dimension(request, "height")); }
                        catch (Exception e) { windows.decrementAndGet(); throw e; }
                        reply.put("display", hosted.display);
                    } else {
                        if (hosted == null) throw new IOException("Open an Android app first");
                        Hosted current = hosted;
                        int displayId = current.display;
                        switch (op) {
                            case "frame" -> {
                                current.capture();
                                Frame frame = current.latest;
                                reply.put("sequence", frame.sequence).put("width", current.width).put("height", current.height);
                                if (request.optLong("after", -1) != frame.sequence) image = frame.jpeg;
                            }
                            case "resize" -> current.resize(dimension(request, "width"), dimension(request, "height"));
                            case "gesture" -> current.gesture(request);
                            case "key" -> current.shell.key(displayId, switch (request.optString("key")) {
                                case "Return" -> android.view.KeyEvent.KEYCODE_ENTER;
                                case "BackSpace" -> android.view.KeyEvent.KEYCODE_DEL;
                                default -> 0;
                            }, request.optString("text"));
                            case "status" -> reply.put("display", displayId).put("package", current.packageName)
                                    .put("inputEnabled", current.shell.asBinder().isBinderAlive()).put("frames", current.latest.sequence);
                            default -> throw new IOException("Unknown portal operation");
                        }
                    }
                    reply.put("ok", true);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    reply = new JSONObject().put("ok", false).put("error", cause.toString());
                }
                byte[] json = reply.toString().getBytes(StandardCharsets.UTF_8);
                out.writeInt(json.length); out.write(json); out.writeInt(image.length); out.write(image); out.flush();
            }
        } catch (IOException e) {
            android.util.Log.i("OmamobilePortal", "client disconnected: " + e);
            // A disconnected Wayland client owns no remaining Android display.
        } catch (Exception e) { android.util.Log.w("OmamobilePortal", "client", e); }
        finally {
            connections.remove(socket);
            if (hosted != null) {
                try { hosted.close(); } finally { windows.decrementAndGet(); }
            }
        }
    }

    private static int dimension(JSONObject request, String key) throws Exception {
        int value = request.getInt(key);
        if (value < 240 || value > 1920) throw new IOException("Window dimensions must be 240..1920");
        return value;
    }

    private record Frame(long sequence, byte[] jpeg) { }

    private final class Hosted implements AutoCloseable {
        final String packageName;
        final int display;
        final IAndroidShell shell;
        final android.os.Binder owner = new android.os.Binder();
        int width, height;
        ImageReader reader;
        private boolean released;
        volatile Frame latest = new Frame(0, new byte[0]);
        Hosted(String packageName, int width, int height) throws Exception {
            this.packageName = packageName;
            if (!packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || packageName.equals(context.getPackageName()))
                throw new IOException("Choose another installed Android application");
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) throw new IOException("No visible launcher activity for " + packageName);
            shell = shellProvider.require();
            this.width = width; this.height = height;
            reader = makeReader(width, height);
            try { display = shell.create(width, height, reader.getSurface(), owner); }
            catch (Exception e) { reader.close(); throw e; }
            try {
                checkOpen();
                shell.launch(display, launch.getComponent().flattenToString());
                checkOpen();
            } catch (Exception e) { close(); throw e; }
        }
        private void checkOpen() throws IOException {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Android window portal is stopping");
        }
        private ImageReader makeReader(int w, int h) { return ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3); }
        void capture() {
            // Pull the latest queued frame when the Linux client asks. This
            // bounds encoding work and retains the final frame of a burst.
            try (Image image = reader.acquireLatestImage()) {
                if (image == null) return;
                Image.Plane plane = image.getPlanes()[0];
                int paddedWidth = plane.getRowStride() / plane.getPixelStride();
                Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(plane.getBuffer());
                Bitmap crop = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                crop.compress(Bitmap.CompressFormat.JPEG, 85, encoded);
                if (crop != padded) crop.recycle(); padded.recycle();
                latest = new Frame(latest.sequence + 1, encoded.toByteArray());
            } catch (Exception e) { android.util.Log.w("OmamobilePortal", "frame", e); }
        }
        void resize(int w, int h) throws Exception {
            if (width == w && height == h) return;
            ImageReader old = reader;
            ImageReader next = makeReader(w, h);
            try { shell.resize(display, w, h, next.getSurface()); }
            catch (Exception error) { next.close(); throw error; }
            reader = next; width = w; height = h; old.close();
        }
        void gesture(JSONObject request) throws Exception {
            float x = (float) request.getDouble("x"), y = (float) request.getDouble("y");
            float endX = (float) request.optDouble("endX", x), endY = (float) request.optDouble("endY", y);
            long duration = Math.max(40, Math.min(1500, request.optLong("duration", 60)));
            int id = display;
            long down = SystemClock.uptimeMillis();
            shell.touch(id, android.view.MotionEvent.ACTION_DOWN, x, y, down);
            try {
                for (long t = 16; t < duration; t += 16) {
                    Thread.sleep(16);
                    float progress = (float) t / duration;
                    shell.touch(id, android.view.MotionEvent.ACTION_MOVE, x + (endX - x) * progress, y + (endY - y) * progress, down);
                }
            } finally { shell.touch(id, android.view.MotionEvent.ACTION_UP, endX, endY, down); }
        }
        @Override public void close() {
            if (released) return;
            released = true;
            try { shell.detach(display); } catch (Exception ignored) { }
            finally { reader.close(); }
        }
    }

    @Override public void close() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (binding != null) binding.close(); } catch (IOException ignored) { }
        for (LocalSocket socket : connections) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
        if (path != null && path.exists()) path.delete();
    }
}
