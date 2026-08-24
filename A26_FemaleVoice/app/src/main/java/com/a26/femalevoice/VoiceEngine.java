package com.a26.femalevoice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

public final class VoiceEngine {
    public interface ErrorSink { void onError(String message); }

    private static final int SR = 48000;
    private static final int BLOCK = 480;          // 10 ms
    private static final int RING = 16384;         // power of two
    private static final int RING_MASK = RING - 1;
    private static final int WINDOW = 3840;        // 80 ms granular window
    private static final int MIN_DELAY = 320;

    private volatile boolean running = false;
    private volatile float semitones = 3.2f;
    private Thread thread;

    public boolean isRunning() { return running; }
    public void setSemitones(float s) { semitones = Math.max(0f, Math.min(7f, s)); }

    public synchronized void start(ErrorSink errors) {
        if (running) return;
        running = true;
        thread = new Thread(() -> loop(errors), "A26Audio");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(1200); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            thread = null;
        }
    }

    private void loop(ErrorSink errors) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        AudioTrack out = null;
        NoiseSuppressor ns = null;
        AcousticEchoCanceler aec = null;

        try {
            int minIn = AudioRecord.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minOut = AudioTrack.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minIn <= 0 || minOut <= 0) throw new IllegalStateException("48 kHz audio not available");
            int ioBuffer = Math.max(BLOCK * 8 * 2, Math.max(minIn, minOut));

            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    ioBuffer * 2);

            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(rec.getAudioSessionId());
                    if (ns != null) ns.setEnabled(true);
                } catch (Throwable ignored) {}
            }
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(rec.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                } catch (Throwable ignored) {}
            }

            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            out = new AudioTrack.Builder()
                    .setAudioFormat(fmt)
                    .setAudioAttributes(attrs)
                    .setBufferSizeInBytes(ioBuffer * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (rec.getState() != AudioRecord.STATE_INITIALIZED ||
                    out.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Audio device init failed");
            }

            short[] in = new short[BLOCK];
            short[] processed = new short[BLOCK];
            float[] ring = new float[RING];
            int write = 0;
            double phase = 0.0;
            float dcX1 = 0f, dcY1 = 0f;

            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Microphone did not start");
            }
            out.play();

            while (running) {
                int n = rec.read(in, 0, in.length, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioRecord read error " + n);
                if (n == 0) continue;

                float semi = semitones;
                double ratio = Math.pow(2.0, semi / 12.0);
                double phaseStep = (ratio - 1.0) / WINDOW;
                boolean bypass = semi < 0.05f;

                for (int i = 0; i < n; i++) {
                    // DC blocker. Helps with low-frequency rumble without boosting hiss.
                    float x = in[i];
                    float hp = x - dcX1 + 0.995f * dcY1;
                    dcX1 = x;
                    dcY1 = hp;

                    ring[write] = hp;
                    write = (write + 1) & RING_MASK;

                    float y;
                    if (bypass) {
                        y = hp;
                    } else {
                        phase += phaseStep;
                        if (phase >= 1.0) phase -= 1.0;

                        double p1 = phase;
                        double p2 = p1 + 0.5;
                        if (p2 >= 1.0) p2 -= 1.0;

                        float y1 = readHead(ring, write, p1);
                        float y2 = readHead(ring, write, p2);
                        double s1 = Math.sin(Math.PI * p1);
                        double s2 = Math.sin(Math.PI * p2);
                        float w1 = (float)(s1 * s1);
                        float w2 = (float)(s2 * s2);
                        y = y1 * w1 + y2 * w2;
                    }

                    // Keep headroom so speech transients do not clip into crackle.
                    int v = Math.round(y * 0.82f);
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    processed[i] = (short) v;
                }

                int written = out.write(processed, 0, n, AudioTrack.WRITE_BLOCKING);
                if (written < 0) throw new IllegalStateException("AudioTrack write error " + written);
            }
        } catch (Throwable t) {
            if (errors != null) errors.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            running = false;
            try { if (rec != null) rec.stop(); } catch (Throwable ignored) {}
            try { if (out != null) out.stop(); } catch (Throwable ignored) {}
            try { if (ns != null) ns.release(); } catch (Throwable ignored) {}
            try { if (aec != null) aec.release(); } catch (Throwable ignored) {}
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            try { if (out != null) out.release(); } catch (Throwable ignored) {}
        }
    }

    private static float readHead(float[] ring, int write, double phase) {
        double delay = MIN_DELAY + (1.0 - phase) * WINDOW;
        double pos = write - delay;
        int base = (int)Math.floor(pos);
        float frac = (float)(pos - Math.floor(pos));
        float a = ring[base & RING_MASK];
        float b = ring[(base + 1) & RING_MASK];
        return a + (b - a) * frac;
    }
}
