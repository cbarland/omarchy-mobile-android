package dev.omamobile.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Small Android boundary panel; upstream Omarchy still owns compositor monitor settings. */
final class DisplaySettings {
    private final Activity activity;
    private AlertDialog dialog;
    private LinearLayout content;
    private List<DisplayCatalog.Output> shown;
    private String shownPreference;
    private DisplayPreviewController preview;
    private final BroadcastReceiver previewChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { shown = null; refresh(); }
    };

    DisplaySettings(Activity activity) { this.activity = activity; }

    void show() {
        if (dialog != null) return;
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog = new AlertDialog.Builder(activity).setTitle("Android displays")
                .setView(scroll).setPositiveButton("Done", null).create();
        dialog.setOnDismissListener(ignored -> {
            activity.unregisterReceiver(previewChanged);
            dialog = null; shown = null;
        });
        IntentFilter changes = new IntentFilter(DesktopService.DISPLAY_SETTINGS_CHANGED);
        if (android.os.Build.VERSION.SDK_INT >= 33) activity.registerReceiver(previewChanged, changes, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyReceiver(changes);
        refresh();
        dialog.show();
    }

    void refresh() {
        if (dialog == null) return;
        List<DisplayCatalog.Output> outputs = DisplayCatalog.read(activity);
        String saved = DisplayCatalog.savedSignature(activity);
        if (outputs.equals(shown) && saved.equals(shownPreference)) return;
        shown = outputs;
        shownPreference = saved;
        content.removeAllViews();
        addText("The phone and external desktop share one compositor; windows can move between them.");
        addText("For display scaling, open Omarchy’s Display panel: Super + Ctrl + D. Android modes and rotation are read-only here.");
        preview = new DisplayPreviewController(activity);
        addScaleControls();
        addText(DisplayCatalog.selectionSummary(activity, outputs));
        addText("Choose the external display. Apply with Stop screens, then Start screens. The phone and desktop keep independent display profiles.");
        RadioGroup choices = new RadioGroup(activity);
        RadioButton automatic = new RadioButton(activity);
        automatic.setText("Automatic external display");
        automatic.setChecked(saved.isEmpty());
        choices.addView(automatic);
        automatic.setOnClickListener(ignored -> { DisplayCatalog.remember(activity, null); refresh(); });
        var candidates = DisplayCatalog.candidates(outputs);
        for (var output : outputs) {
            if (!output.candidate().eligible()) continue;
            RadioButton choice = new RadioButton(activity);
            boolean unique = DisplaySelection.canRemember(candidates, output.candidate());
            choice.setText(output.name() + " · ID " + output.candidate().id() + (unique ? "" : " · indistinguishable; cannot save"));
            choice.setEnabled(unique);
            choice.setChecked(unique && saved.equals(output.candidate().signature()));
            choices.addView(choice);
            choice.setOnClickListener(ignored -> {
                // Hotplug may race a tap. Never persist a stale or now-ambiguous target.
                var current = DisplayCatalog.read(activity);
                var match = current.stream().filter(item -> item.candidate().equals(output.candidate())).findFirst();
                if (match.isPresent() && DisplaySelection.canRemember(DisplayCatalog.candidates(current), match.get().candidate()))
                    DisplayCatalog.remember(activity, match.get());
                refresh();
            });
        }
        content.addView(choices);
        addText("Saved targets match by name and modes. Identical monitors cannot be distinguished; changed or missing targets may need reselection.");
        for (var output : outputs) addText(output.name() + "\n" + output.details());
    }

    void dismiss() { if (dialog != null) dialog.dismiss(); }
    private void addScaleControls() {
        addText("Desktop scale preview applies for 30 seconds. Keep it or revert it; custom monitor files are preserved.");
        if (preview.hasPending()) {
            Button keep = new Button(activity); keep.setText("Keep desktop scale preview");
            keep.setOnClickListener(v -> sendPreviewAction(DesktopService.KEEP_PREVIEW, -1));
            content.addView(keep);
            Button revert = new Button(activity); revert.setText("Revert desktop scale preview");
            revert.setOnClickListener(v -> sendPreviewAction(DesktopService.REVERT_PREVIEW, -1)); content.addView(revert);
            return;
        }
        for (int scale : DisplayPreviewController.SCALES) {
            Button button = new Button(activity);
            button.setText("Preview desktop scale " + (scale / 100f) + "×");
            button.setOnClickListener(v -> sendPreviewAction(DesktopService.PREVIEW_SCALE, scale));
            content.addView(button);
        }
    }
    private void sendPreviewAction(String action, int scale) {
        try { activity.startService(new Intent(activity, DesktopService.class).setAction(action).putExtra("scale", scale)); }
        catch (Exception error) { addText("Preview unavailable: " + error.getMessage()); }
    }
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyReceiver(IntentFilter changes) {
        activity.registerReceiver(previewChanged, changes, "dev.omamobile.host.permission.CONTROL_SCREENS", null);
    }
    private void addText(String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(Color.LTGRAY);
        view.setPadding(0, 8, 0, 12);
        content.addView(view);
    }
}
