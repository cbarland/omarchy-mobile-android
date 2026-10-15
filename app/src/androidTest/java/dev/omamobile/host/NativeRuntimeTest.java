package dev.omamobile.host;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeRuntimeTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Path nativeFile(String name) { return new File(context.getApplicationInfo().nativeLibraryDir, name).toPath(); }

    private void entry(TarArchiveOutputStream out, String name, byte[] bytes) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(name); entry.setSize(bytes.length); entry.setMode(0755);
        out.putArchiveEntry(entry); out.write(bytes); out.closeArchiveEntry();
    }

    @Test public void nativeGuestReadsRootAndSharesFilesWithoutAndroidRoot() throws Exception { exercise(false); }
    @Test public void andoExecutesOnAndroidAsAppUid() throws Exception { exercise(true); }

    private void exercise(boolean withAndo) throws Exception {
        assertTrue("Instrumentation must run as a regular app UID", android.os.Process.myUid() >= 10000);
        Path base = Files.createTempDirectory(context.getFilesDir().toPath(), "native-contract-");
        AndroidBroker broker = null;
        try {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (TarArchiveOutputStream tar = new TarArchiveOutputStream(archive)) {
                entry(tar, "usr/bin/runtime-probe", Files.readAllBytes(nativeFile("libruntime_probe.so")));
                TarArchiveEntry alias = new TarArchiveEntry("usr/bin/probe-alias", (byte) '1');
                alias.setLinkName("usr/bin/runtime-probe");
                tar.putArchiveEntry(alias); tar.closeArchiveEntry();
                entry(tar, "etc/omamobile-probe", "guest-root\n".getBytes(StandardCharsets.UTF_8));
                // Official Arch ARM archives contain literal systemd unit escapes.
                entry(tar, "usr/lib/systemd/system-systemd\\x2dveritysetup.slice", new byte[]{42});
                TarArchiveEntry unit = new TarArchiveEntry("usr/lib/systemd/unit-alias", (byte) '2');
                unit.setLinkName("system-systemd\\x2dveritysetup.slice");
                tar.putArchiveEntry(unit); tar.closeArchiveEntry();
                TarArchiveEntry bin = new TarArchiveEntry("bin", (byte) '2'); bin.setLinkName("/usr/bin");
                tar.putArchiveEntry(bin); tar.closeArchiveEntry();
                TarArchiveEntry shell = new TarArchiveEntry("usr/bin/sh", (byte) '2'); shell.setLinkName("runtime-probe");
                tar.putArchiveEntry(shell); tar.closeArchiveEntry();
            }
            int machine = android.os.Build.SUPPORTED_ABIS[0].equals("arm64-v8a") ? 183 : 62;
            HostRuntime.importer().install(new ByteArrayInputStream(archive.toByteArray()), base, machine, p -> {});
            Path root = base.resolve("rootfs");
            assertArrayEquals(new byte[]{42}, Files.readAllBytes(root.resolve("usr/lib/systemd/unit-alias")));
            Path shared = Files.createDirectory(base.resolve("shared"));
            Path readonly = Files.createDirectory(base.resolve("readonly"));
            List<RuntimeSpec.Bind> binds = new ArrayList<>();
            binds.add(new RuntimeSpec.Bind(shared.toString(), "/android/shared", false));
            binds.add(new RuntimeSpec.Bind(readonly.toString(), "/android/readonly", true));
            binds.add(new RuntimeSpec.Bind("/proc", "/proc", false));
            List<String> command = new ArrayList<>(List.of("/usr/bin/probe-alias"));
            if (withAndo) {
                broker = new AndroidBroker(nativeFile("libando.so").toFile(), base.resolve("ando").toFile());
                broker.start();
                binds.add(new RuntimeSpec.Bind(broker.directory.toString(), "/run/omamobile-ando", false));
                Path bin = Files.createDirectory(base.resolve("adapter-bin"));
                Files.copy(nativeFile("libando.so"), bin.resolve("ando"));
                HostRuntime.chmod(bin.resolve("ando"), 0755);
                binds.add(new RuntimeSpec.Bind(bin.toString(), "/opt/omamobile/bin", true));
                command.add("--android");
            }
            ProcessBuilder builder = new ProcessBuilder(RuntimeSpec.command(nativeFile("libtawcroot.so"), root, binds, command))
                    .redirectErrorStream(true).directory(root.toFile());
            builder.environment().clear(); builder.environment().putAll(RuntimeSpec.environment(withAndo));
            Process process = builder.start();
            process.getOutputStream().close();
            boolean completed = process.waitFor(20, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("Guest timed out: " + output, completed);
            assertEquals(output, 0, process.exitValue());
            assertTrue(output, output.contains("ROOTFS_OK BIND_RW_OK BIND_RO_OK guest_uid=0"));
            assertEquals("shared-runtime\n", new String(Files.readAllBytes(shared.resolve("from-linux.txt")), StandardCharsets.UTF_8));
            assertFalse(Files.exists(readonly.resolve("forbidden")));
            if (withAndo) assertTrue(output, output.contains("uid=" + android.os.Process.myUid() + "("));
        } finally {
            if (broker != null) broker.close();
            GuestPaths.deleteTree(base);
        }
    }

    @Test public void symlinkAncestorArchiveIsRejectedInEitherOrder() throws Exception {
        for (boolean linkFirst : new boolean[]{true, false}) {
            Path base = Files.createTempDirectory(context.getFilesDir().toPath(), "bad-archive-");
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
                    TarArchiveEntry link = new TarArchiveEntry("dir", (byte) '2'); link.setLinkName("/tmp");
                    if (linkFirst) { tar.putArchiveEntry(link); tar.closeArchiveEntry(); }
                    entry(tar, "dir/payload", new byte[]{1});
                    if (!linkFirst) { tar.putArchiveEntry(link); tar.closeArchiveEntry(); }
                }
                assertThrows(java.io.IOException.class, () -> HostRuntime.importer()
                        .install(new ByteArrayInputStream(bytes.toByteArray()), base, 62, p -> {}));
                assertFalse(Files.exists(base.resolve("rootfs")));
            } finally { GuestPaths.deleteTree(base); }
        }
    }
}
