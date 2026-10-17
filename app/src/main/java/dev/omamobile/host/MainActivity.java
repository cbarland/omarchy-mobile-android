package dev.omamobile.host;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Installation and display controls. The desktop belongs to upstream Linux/Omarchy. */
public final class MainActivity extends Activity {
    static final String SHOW_DISPLAY_SETTINGS = "dev.omamobile.host.SHOW_DISPLAY_SETTINGS";
    private HostRuntime runtime;
    private TextView status, console;
    private EditText command;
    private Button run, importImage, install, downloadInstall, exportData, recoverData, screens, stop;
    private CheckBox ando, androidDns;
    private DisplaySettings displaySettings;
    private android.app.AlertDialog credits;
    private final Runnable refresh = this::render;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Older builds exposed each output as another app in Recents. Remove
        // only those legacy screen tasks; the new hosts are explicitly hidden.
        for (var task : getSystemService(ActivityManager.class).getAppTasks()) {
            var info = task.getTaskInfo();
            var component = info.baseIntent.getComponent();
            if (component != null && (component.getClassName().equals(PhoneActivity.class.getName())
                    || component.getClassName().equals(DesktopActivity.class.getName()))
                    && (info.baseIntent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0)
                task.finishAndRemoveTask();
        }
        runtime = HostRuntime.get(this);
        displaySettings = new DisplaySettings(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 48, 24, 32);
        layout.setBackgroundColor(Color.rgb(24, 24, 28));
        TextView title = text("Omarchy Mobile / Android host", 24);
        layout.addView(title);
        layout.addView(text("Preview · one shared Linux installation\nOmarchy Mobile on the phone, Omarchy desktop on an external display.", 15));
        Button attribution = new Button(this);
        attribution.setText("Built on Omarchy · Omarchy Mobile · TAWC — credits");
        attribution.setOnClickListener(v -> showCredits());
        layout.addView(attribution);
        status = text("", 14);
        status.setTextIsSelectable(true);
        layout.addView(status);
        downloadInstall = new Button(this);
        downloadInstall.setText("Download and install authenticated Arch base");
        downloadInstall.setOnClickListener(v -> startOperation(new Intent(this, LinuxService.class)
                .putExtra("downloadBootstrap", true)));
        layout.addView(downloadInstall);
        install = new Button(this);
        install.setText("Install Omarchy from Linux archive");
        install.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Choose a verified Arch Linux archive"), 12));
        layout.addView(install);
        stop = new Button(this);
        stop.setText("Cancel operation");
        stop.setOnClickListener(v -> runtime.stop());
        layout.addView(stop);
        layout.addView(text("First setup: download an Arch base with publisher-signature verification, or select an archive you have verified. Setup installs signed Linux packages over the internet and can take several minutes and multiple GB of storage. Existing Linux data is never replaced.", 14));
        exportData = new Button(this);
        exportData.setText("Export Linux user data");
        exportData.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/gzip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "omamobile-linux-data.tar.gz"), 13));
        layout.addView(exportData);
        recoverData = new Button(this);
        recoverData.setText("Recover user data as separate copy");
        recoverData.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("application/gzip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "Choose an Omamobile Linux user-data archive"), 14));
        layout.addView(recoverData);
        screens = new Button(this);
        screens.setText(R.string.start_screens);
        screens.setOnClickListener(v -> startForegroundService(new Intent(this, DesktopService.class)));
        layout.addView(screens);
        Button stopScreens = new Button(this);
        stopScreens.setText(R.string.stop_screens);
        stopScreens.setOnClickListener(v -> startService(new Intent(this, DesktopService.class).setAction(DesktopService.STOP_SCREENS)));
        layout.addView(stopScreens);
        Button displayControls = new Button(this);
        displayControls.setText("Android displays");
        displayControls.setOnClickListener(v -> displaySettings.show());
        layout.addView(displayControls);
        Button androidInput = new Button(this);
        androidInput.setText("Authorize Android windows with Shizuku");
        androidInput.setOnClickListener(v -> { AndroidShell.get(this).authorize(); status.postDelayed(this::render, 1200); });
        layout.addView(androidInput);
        importImage = new Button(this);
        importImage.setText("Import rootfs only (advanced)");
        importImage.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 10));
        layout.addView(importImage);
        ando = new CheckBox(this);
        ando.setText(R.string.enable_ando);
        ando.setTextColor(Color.LTGRAY);
        layout.addView(ando);
        androidDns = new CheckBox(this);
        androidDns.setText(R.string.android_dns);
        androidDns.setTextColor(Color.LTGRAY);
        layout.addView(androidDns);
        command = new EditText(this);
        command.setText(R.string.default_command);
        command.setTextColor(Color.WHITE);
        command.setHint("Linux shell command");
        command.setMinLines(2);
        layout.addView(command);
        LinearLayout actions = new LinearLayout(this);
        run = new Button(this);
        run.setText(R.string.run_command);
        run.setOnClickListener(v -> startOperation(new Intent(this, LinuxService.class)
                .putExtra("command", command.getText().toString()).putExtra("ando", ando.isChecked())
                .putExtra("androidDns", androidDns.isChecked())));
        actions.addView(run, new LinearLayout.LayoutParams(0, -2, 1));
        layout.addView(actions);
        console = text("", 13);
        console.setTypeface(android.graphics.Typeface.MONOSPACE);
        console.setTextIsSelectable(true);
        layout.addView(console, new LinearLayout.LayoutParams(-1, -2));
        // External-display modes can leave the phone in landscape. Keep every
        // setup control reachable instead of squeezing the console to zero.
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.addView(layout);
        setContentView(page);
        showRequestedDisplaySettings();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showRequestedDisplaySettings();
    }
    private void showRequestedDisplaySettings() {
        if (SHOW_DISPLAY_SETTINGS.equals(getIntent().getAction()) && displaySettings != null) {
            getWindow().getDecorView().post(displaySettings::show);
            getIntent().setAction(null);
        }
    }
    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(Color.LTGRAY); view.setPadding(0, 8, 0, 8);
        return view;
    }
    private void startOperation(Intent intent) { startForegroundService(intent); }
    private void showCredits() {
        try (var in = getAssets().open("CREDITS.md")) {
            var bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            TextView content = new TextView(this);
            content.setTextSize(15);
            content.setPadding(32, 16, 32, 16);
            content.setText(android.text.Html.fromHtml(bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()),
                    android.text.Html.FROM_HTML_MODE_LEGACY));
            content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            ScrollView page = new ScrollView(this);
            page.addView(content);
            credits = new android.app.AlertDialog.Builder(this).setTitle("Credits and upstream projects")
                    .setView(page).setPositiveButton("Close", null).show();
        } catch (java.io.IOException error) {
            android.util.Log.e("OmamobileCredits", "Cannot read packaged credits", error);
        }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 13 && result == RESULT_OK && data != null && data.getData() != null) {
            startOperation(new Intent(this, LinuxService.class).setData(data.getData()).putExtra("exportData", true)
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } else if (request == 14 && result == RESULT_OK && data != null && data.getData() != null) {
            startOperation(new Intent(this, LinuxService.class).setData(data.getData()).putExtra("recoverData", true)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } else if ((request == 10 || request == 12) && result == RESULT_OK && data != null && data.getData() != null) {
            startOperation(new Intent(this, LinuxService.class).setData(data.getData()).putExtra("installOmarchy", request == 12)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }
    }
    @Override protected void onResume() { super.onResume(); runtime.observe(refresh); render(); }
    @Override protected void onPause() { runtime.unobserve(refresh); super.onPause(); }
    @Override protected void onDestroy() {
        displaySettings.dismiss();
        if (credits != null) credits.dismiss();
        super.onDestroy();
    }
    private void render() {
        StringBuilder info = new StringBuilder(runtime.graphicsReady() ? "Omarchy is installed" : runtime.installed()
                ? "Linux imported · Omarchy setup not complete" : "Ready for first installation");
        info.append("\nVersion ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE)
                .append(")\nSource revision: ").append(BuildConfig.SOURCE_REVISION);
        if (!runtime.installerAvailable()) info.append("\nFull graphical APK required for Omarchy installation");
        info.append(" · ").append(Build.SUPPORTED_ABIS[0]).append(runtime.busy() ? " · busy\n" : " · idle\n");
        runtime.displays.snapshot().values().forEach(output -> info.append("Display ").append(output.id()).append(": ").append(output.profile()).append("\n"));
        info.append("All displays share one Linux installation.\nShared folder: ").append(runtime.shared);
        info.append("\n").append(AndroidShell.get(this).state());
        status.setText(info);
        console.setText(runtime.output());
        run.setEnabled(runtime.installed() && !runtime.busy());
        importImage.setEnabled(!runtime.installed() && !runtime.busy());
        install.setEnabled(runtime.installerAvailable() && !runtime.installed() && !runtime.busy());
        downloadInstall.setEnabled(runtime.installerAvailable() && !runtime.installed() && !runtime.busy());
        screens.setEnabled(runtime.graphicsReady() && !runtime.busy());
        ando.setEnabled(!runtime.busy());
        androidDns.setEnabled(!runtime.busy());
        stop.setEnabled(runtime.busy());
        exportData.setEnabled(runtime.installed() && !runtime.busy());
        recoverData.setEnabled(runtime.installed() && !runtime.busy());
        displaySettings.refresh();
    }
}
