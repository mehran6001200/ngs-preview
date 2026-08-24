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
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 10;
    private VoiceEngine engine;
    private TextView status, pitchLabel;
    private SeekBar pitch;
    private Button recordButton, playButton, stopButton;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        engine = new VoiceEngine();
        setContentView(buildUi());
        setPreset(3.2f);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(26));
        root.setBackgroundColor(Color.rgb(17, 19, 23));

        TextView title = label("A26 VOICE LAB v0.7", 25, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = label("No-headset test: record first, then play", 14, 0xffb8bdc7);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        status = label("Ready", 15, 0xff7fe29a);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button raw = button("RAW");
        Button low = button("Female Low");
        raw.setOnClickListener(v -> setPreset(0.0f));
        low.setOnClickListener(v -> setPreset(2.2f));
        row1.addView(raw, weight());
        row1.addView(low, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button natural = button("Natural");
        Button bright = button("Bright");
        natural.setOnClickListener(v -> setPreset(3.2f));
        bright.setOnClickListener(v -> setPreset(4.2f));
        row2.addView(natural, weight());
        row2.addView(bright, weight());
        root.addView(row2);

        pitchLabel = label("Pitch", 15, Color.WHITE);
        pitchLabel.setPadding(0, dp(14), 0, 0);
        root.addView(pitchLabel);

        pitch = new SeekBar(this);
        pitch.setMax(70);
        root.addView(pitch);
        pitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean from) {
                float s = p / 10f;
                engine.setSemitones(s);
                pitchLabel.setText(String.format(Locale.US, "Pitch +%.1f semitone", s));
            }
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
        });

        TextView help = label(
                "No headphones needed. Tap RECORD, speak for 6 seconds, then tap PLAY. Start with RAW to verify the microphone is clean.",
                13, 0xffffcf75);
        help.setPadding(0, dp(14), 0, dp(14));
        root.addView(help);

        recordButton = button("RECORD 6 SEC");
        root.addView(recordButton, new LinearLayout.LayoutParams(-1, dp(58)));
        recordButton.setOnClickListener(v -> recordSample());

        playButton = button("PLAY LAST SAMPLE");
        playButton.setEnabled(false);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(-1, dp(58));
        p2.setMargins(0, dp(8), 0, 0);
        root.addView(playButton, p2);
        playButton.setOnClickListener(v -> playSample());

        stopButton = button("STOP");
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(-1, dp(52));
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

    private void recordSample() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (engine.isRunning()) return;

        status.setText("Recording... speak normally");
        status.setTextColor(0xffffcf75);
        updateButtons();
        engine.recordSeconds(6,
                msg -> runOnUiThread(() -> showError(msg)),
                () -> runOnUiThread(() -> {
                    if (engine.hasRecording()) {
                        status.setText("Recorded. Tap PLAY.");
                        status.setTextColor(0xff7fe29a);
                    }
                    updateButtons();
                }));
    }

    private void playSample() {
        if (engine.isRunning() || !engine.hasRecording()) return;
        status.setText("Playing processed sample...");
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

    private void setPreset(float s) {
        engine.setSemitones(s);
        if (pitch != null) pitch.setProgress(Math.round(s * 10));
        if (pitchLabel != null) pitchLabel.setText(String.format(Locale.US, "Pitch +%.1f semitone", s));
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(54), 1f);
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
