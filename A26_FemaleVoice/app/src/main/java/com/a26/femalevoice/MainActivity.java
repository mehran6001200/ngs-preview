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
    private Button startStop;

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
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(17,19,23));

        TextView title = label("A26 FEMALE VOICE", 26, Color.WHITE);
        title.setGravity(Gravity.CENTER); root.addView(title);
        TextView sub = label("تغییر زنده صدا برای تست روی Samsung A26", 14, 0xffb8bdc7);
        sub.setGravity(Gravity.CENTER); sub.setPadding(0,dp(6),0,dp(18)); root.addView(sub);

        status = label("آماده", 15, 0xff7fe29a); status.setGravity(Gravity.CENTER); root.addView(status);

        LinearLayout presets = new LinearLayout(this); presets.setOrientation(LinearLayout.HORIZONTAL);
        Button deep = button("زنانه بم"); Button natural = button("طبیعی"); Button soft = button("روشن");
        deep.setOnClickListener(v -> setPreset(2.2f)); natural.setOnClickListener(v -> setPreset(3.2f)); soft.setOnClickListener(v -> setPreset(4.2f));
        presets.addView(deep, weight()); presets.addView(natural, weight()); presets.addView(soft, weight()); root.addView(presets);

        pitchLabel = label("Pitch", 15, Color.WHITE); pitchLabel.setPadding(0,dp(18),0,0); root.addView(pitchLabel);
        pitch = new SeekBar(this); pitch.setMax(70); root.addView(pitch);
        pitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b,int p,boolean from){ float s=p/10f; engine.setSemitones(s); pitchLabel.setText(String.format(Locale.US,"Pitch +%.1f semitone",s)); }
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        });

        TextView tip = label("برای جلوگیری از سوت و فیدبک، هنگام تست از هندزفری USB-C یا Bluetooth استفاده کن.", 13, 0xffffcf75);
        tip.setPadding(0,dp(16),0,dp(12)); root.addView(tip);

        startStop = button("▶ شروع تست زنده"); root.addView(startStop, new LinearLayout.LayoutParams(-1,dp(62)));
        startStop.setOnClickListener(v -> toggle());
        return root;
    }

    private void toggle(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC); return; }
        if(engine.isRunning()){ engine.stop(); startStop.setText("▶ شروع تست زنده"); status.setText("متوقف شد"); }
        else { engine.start(msg -> runOnUiThread(() -> { status.setText(msg); status.setTextColor(0xffff8190); })); startStop.setText("■ توقف"); status.setText("صدای زنانه فعال است"); status.setTextColor(0xff7fe29a); }
    }

    private void setPreset(float s){ engine.setSemitones(s); if(pitch!=null) pitch.setProgress(Math.round(s*10)); if(pitchLabel!=null) pitchLabel.setText(String.format(Locale.US,"Pitch +%.1f semitone",s)); }
    private TextView label(String s,int sp,int c){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(c); return t; }
    private Button button(String s){ Button b=new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams weight(){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(56),1f); p.setMargins(dp(3),dp(4),dp(3),dp(4)); return p; }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy(){ engine.stop(); super.onDestroy(); }
}
