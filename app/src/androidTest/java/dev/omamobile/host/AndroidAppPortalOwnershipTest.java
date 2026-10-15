package dev.omamobile.host;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Blocking local helper doubles exercise ownership without Shizuku or real app launches. */
public class AndroidAppPortalOwnershipTest {
    @Test public void disconnectedSlowLaunchReleasesOnceAndNeverBlocksMain() throws Exception {
        FakeShell shell = new FakeShell(false, true);
        try (Connection connection = new Connection(shell)) {
            send(connection.client, open());
            assertTrue("Launch did not begin", shell.entered.await(3, TimeUnit.SECONDS));
            CountDownLatch mainResponsive = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(mainResponsive::countDown);
            assertTrue("A blocking Android launch blocked the main looper", mainResponsive.await(3, TimeUnit.SECONDS));
            connection.client.close();
            shell.proceed.countDown();
            connection.awaitFinished();
            assertFalse("Helper work ran on Android's main looper", shell.usedMain.get());
            assertEquals(1, shell.creates.get());
            assertEquals(1, shell.detaches.get());
            assertTrue(shell.leases.isEmpty());
            assertEquals(0, connection.windows());
        } finally { shell.proceed.countDown(); }
    }

    @Test public void portalCloseReleasesLateCreateWithoutLaunching() throws Exception {
        FakeShell shell = new FakeShell(true, false);
        try (Connection connection = new Connection(shell)) {
            send(connection.client, open());
            assertTrue("Create did not begin", shell.entered.await(3, TimeUnit.SECONDS));
            connection.portal.close();
            shell.proceed.countDown();
            connection.awaitFinished();
            assertEquals("A stopped portal launched a late-created display", 0, shell.launches.get());
            assertEquals(1, shell.detaches.get());
            assertTrue(shell.leases.isEmpty());
            assertEquals(0, connection.windows());
            assertFalse(shell.usedMain.get());
        } finally { shell.proceed.countDown(); }
    }

    @Test public void launchFailureReleasesCapacityAndResizeStaysOnWorker() throws Exception {
        FakeShell shell = new FakeShell(false, false);
        shell.failLaunch = true;
        try (Connection connection = new Connection(shell)) {
            send(connection.client, open());
            assertFalse(reply(connection.client).getBoolean("ok"));
            assertEquals(1, shell.detaches.get());
            assertEquals(0, connection.windows());
            shell.failLaunch = false;
            send(connection.client, open());
            assertTrue(reply(connection.client).getBoolean("ok"));
            assertEquals(1, connection.windows());
            send(connection.client, new JSONObject().put("op", "resize").put("width", 800).put("height", 900));
            assertTrue(reply(connection.client).getBoolean("ok"));
            assertEquals(1, shell.resizes.get());
            connection.client.close();
            connection.awaitFinished();
            assertEquals(2, shell.creates.get());
            assertEquals(2, shell.detaches.get());
            assertTrue(shell.leases.isEmpty());
            assertEquals(0, connection.windows());
            assertFalse("Create/launch/resize/detach must stay off the main looper", shell.usedMain.get());
        }
    }

    private static JSONObject open() throws Exception {
        return new JSONObject().put("op", "open").put("package", "com.android.settings")
                .put("width", 720).put("height", 800);
    }

    private static void send(LocalSocket socket, JSONObject request) throws Exception {
        byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }

    private static JSONObject reply(LocalSocket socket) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        byte[] json = new byte[input.readInt()]; input.readFully(json);
        int image = input.readInt();
        assertEquals("Control replies should not carry frame bytes", 0, image);
        return new JSONObject(new String(json, StandardCharsets.UTF_8));
    }

    private static final class Connection implements AutoCloseable {
        final AndroidAppPortal portal;
        final LocalSocket client = new LocalSocket();
        final LocalSocket accepted;
        final Thread worker;
        final FakeShell shell;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Connection(FakeShell shell) throws Exception {
            this.shell = shell;
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            portal = new AndroidAppPortal(context, () -> shell);
            // Use a test-owned abstract socket pair. Do not replace the running
            // DesktopService's filesystem endpoint or alter its helper binding.
            String name = "omamobile-portal-test-" + UUID.randomUUID();
            try (LocalServerSocket listener = new LocalServerSocket(name)) {
                client.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                accepted = listener.accept();
            }
            client.setSoTimeout(5000);
            var serve = AndroidAppPortal.class.getDeclaredMethod("serve", LocalSocket.class);
            serve.setAccessible(true);
            worker = new Thread(() -> {
                try { serve.invoke(portal, accepted); }
                catch (java.lang.reflect.InvocationTargetException error) { failure.set(error.getCause()); }
                catch (Exception error) { failure.set(error); }
            }, "portal-ownership-test");
            worker.start();
        }
        int windows() throws Exception {
            var field = AndroidAppPortal.class.getDeclaredField("windows");
            field.setAccessible(true);
            return ((AtomicInteger) field.get(portal)).get();
        }
        void awaitFinished() throws Exception {
            worker.join(5000);
            assertFalse("Connection cleanup did not finish", worker.isAlive());
            assertNull("Connection worker failed", failure.get());
        }
        @Override public void close() throws Exception {
            // Always unblock the double, including after a failed responsiveness assertion.
            shell.proceed.countDown();
            try { client.close(); }
            finally {
                accepted.close();
                portal.close();
                worker.join(5000);
            }
        }
    }

    private static final class FakeShell extends IAndroidShell.Stub {
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        final AtomicInteger creates = new AtomicInteger(), launches = new AtomicInteger(),
                detaches = new AtomicInteger(), resizes = new AtomicInteger();
        final AtomicBoolean usedMain = new AtomicBoolean();
        final Set<Integer> leases = ConcurrentHashMap.newKeySet();
        final boolean blockCreate, blockLaunch;
        volatile boolean failLaunch;
        FakeShell(boolean blockCreate, boolean blockLaunch) { this.blockCreate = blockCreate; this.blockLaunch = blockLaunch; }
        private void threadCheck() { if (Looper.myLooper() == Looper.getMainLooper()) usedMain.set(true); }
        private void block() {
            entered.countDown();
            boolean interrupted = false;
            // Model a synchronous Binder transaction which does not disappear
            // merely because its waiting caller was interrupted.
            for (;;) {
                try { proceed.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        @Override public int uid() { return 2000; }
        @Override public int create(int width, int height, Surface surface, IBinder owner) {
            threadCheck();
            assertTrue(surface.isValid());
            int display = 1000 + creates.incrementAndGet();
            assertTrue(leases.add(display));
            if (blockCreate) block();
            return display;
        }
        @Override public String launch(int display, String component) {
            threadCheck(); launches.incrementAndGet();
            if (blockLaunch) block();
            if (failLaunch) throw new IllegalStateException("test launch failed after creating its display");
            return "test launch complete";
        }
        @Override public void resize(int display, int width, int height, Surface surface) { threadCheck(); resizes.incrementAndGet(); assertTrue(surface.isValid()); }
        @Override public void detach(int display) {
            threadCheck();
            assertTrue("Lease released more than once or never owned: " + display, leases.remove(display));
            detaches.incrementAndGet();
        }
        @Override public void touch(int display, int action, float x, float y, long downTime) { }
        @Override public void key(int display, int code, String text) { }
        @Override public void destroy() { }
    }
}
