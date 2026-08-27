package com.a26.femalevoice;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 10;
    private VoiceEngine engine;
    private TextView status, profileLabel;
    private Button recordButton, playButton, stopButton;
    private String profileName = "Natural Female";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new VoiceEngine();
        setContentView(buildUi());
        choose("Natural Female", 3.0f, 1.2f, false);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(17, 19, 23));

        TextView title = label("A26 FEMALE VOICE v0.8", 24, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = label("Formant-aware voice conversion", 14, 0xffb8bdc7);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(5), 0, dp(12));
        root.addView(sub);

        status = label("Ready", 15, 0xff7fe29a);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        profileLabel = label("", 14, 0xff8fc9ff);
        profileLabel.setGravity(Gravity.CENTER);
        profileLabel.setPadding(0, dp(6), 0, dp(12));
        root.addView(profileLabel);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button raw = button("RAW");
        Button natural = button("Natural Female");
        raw.setOnClickListener(v -> choose("RAW", 0f, 0f, true));
        natural.setOnClickListener(v -> choose("Natural Female", 3.0f, 1.2f, false));
        row1.addView(raw, weight());
        row1.addView(natural, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button soft = button("Soft Female");
        Button mature = button("Mature Female");
        soft.setOnClickListener(v -> choose("Soft Female", 3.6f, 1.45f, false));
        mature.setOnClickListener(v -> choose("Mature Female", 2.35f, 0.9f, false));
        row2.addView(soft, weight());
        row2.addView(mature, weight());
        root.addView(row2);

        TextView help = label(
                "This version does not simply raise pitch. It moves pitch and vocal formants separately. No headset is needed: recording is silent, playback happens afterwards.",
                13, 0xffffcf75);
        help.setPadding(0, dp(14), 0, dp(14));
        root.addView(help);

        recordButton = button("RECORD 6 SEC + PROCESS");
        root.addView(recordButton, new LinearLayout.LayoutParams(-1, dp(60)));
        recordButton.setOnClickListener(v -> recordSample());

        playButton = button("PLAY RESULT");
        playButton.setEnabled(false);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-1, dp(58));
        p2.setMargins(0, dp(8), 0, 0);
        root.addView(playButton, p2);
        playButton.setOnClickListener(v -> playSample());

        stopButton = button("STOP");
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(-1, dp(50));
        p3.setMargins(0, dp(8), 0, 0);
        root.addView(stopButton, p3);
        stopButton.setOnClickListener(v -> {
            engine.stop();
            status.setText("Stopped");
            status.setTextColor(0xffffcf75);
            updateButtons();
        });

        return root;
    }

    private void choose(String name, float pitch, float formant, boolean raw) {
        profileName = name;
        engine.setProfile(pitch, formant, raw);
        if (profileLabel != null) {
            if (raw) profileLabel.setText("RAW / no conversion");
            else profileLabel.setText(name + "   Pitch +" + pitch + "   Formant +" + formant);
        }
        if (status != null && !engine.isRunning()) {
            status.setText("Profile selected: " + name);
            status.setTextColor(0xff7fe29a);
        }
    }

    private void recordSample() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (engine.isRunning()) return;

        status.setText("Recording 6 sec, then processing " + profileName + "...");
        status.setTextColor(0xffffcf75);
        updateButtons();

        engine.recordSeconds(6,
                msg -> runOnUiThread(() -> showError(msg)),
                () -> runOnUiThread(() -> {
                    if (engine.hasRecording()) {
                        status.setText("Processed. Tap PLAY RESULT.");
                        status.setTextColor(0xff7fe29a);
                    }
                    updateButtons();
                }));
    }

    private void playSample() {
        if (engine.isRunning() || !engine.hasRecording()) return;
        status.setText("Playing " + profileName + "...");
        status.setTextColor(0xff8fc9ff);
        updateButtons();
        engine.playLast(
                msg -> runOnUiThread(() -> showError(msg)),
                () -> runOnUiThread(() -> {
                    status.setText("Ready");
                    status.setTextColor(0xff7fe29a);
                    updateButtons();
                }));
    }

    private void showError(String msg) {
        status.setText(msg);
        status.setTextColor(0xffff8190);
        updateButtons();
    }

    private void updateButtons() {
        boolean busy = engine.isRunning();
        if (recordButton != null) recordButton.setEnabled(!busy);
        if (playButton != null) playButton.setEnabled(!busy && engine.hasRecording());
        if (stopButton != null) stopButton.setEnabled(busy);
    }

    private TextView label(String s, int sp, int c) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(c);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1f);
        p.setMargins(dp(3), dp(4), dp(3), dp(4));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            status.setText("Microphone permission granted");
            status.setTextColor(0xff7fe29a);
        }
    }

    @Override protected void onDestroy() {
        engine.stop();
        super.onDestroy();
    }
}
