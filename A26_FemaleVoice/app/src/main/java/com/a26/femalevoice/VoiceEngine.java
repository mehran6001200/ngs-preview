package com.a26.femalevoice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import java.util.Arrays;

public final class VoiceEngine {
    public interface ErrorSink { void onError(String message); }
    public interface DoneSink { void onDone(); }

    private static final int SR = 48000;
    private static final int BLOCK = 480;          // 10 ms
    private static final int RING = 16384;         // power of two
    private static final int RING_MASK = RING - 1;
    private static final int WINDOW = 3840;        // 80 ms
    private static final int MIN_DELAY = 320;

    private volatile boolean busy = false;
    private volatile boolean stopRequested = false;
    private volatile float semitones = 3.2f;
    private Thread thread;
    private volatile short[] lastProcessed;

    public boolean isRunning() { return busy; }
    public boolean hasRecording() { return lastProcessed != null && lastProcessed.length > 0; }
    public void setSemitones(float s) { semitones = Math.max(0f, Math.min(7f, s)); }

    public synchronized void recordSeconds(int seconds, ErrorSink errors, DoneSink done) {
        if (busy) return;
        busy = true;
        stopRequested = false;
        int safeSeconds = Math.max(2, Math.min(12, seconds));
        thread = new Thread(() -> captureLoop(safeSeconds, errors, done), "A26Capture");
        thread.start();
    }

    public synchronized void playLast(ErrorSink errors, DoneSink done) {
        if (busy || !hasRecording()) return;
        busy = true;
        stopRequested = false;
        short[] snapshot = lastProcessed;
        thread = new Thread(() -> playbackLoop(snapshot, errors, done), "A26Playback");
        thread.start();
    }

    public synchronized void stop() {
        stopRequested = true;
        if (thread != null) {
            try { thread.join(1500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            thread = null;
        }
        busy = false;
    }

    private void captureLoop(int seconds, ErrorSink errors, DoneSink done) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        NoiseSuppressor ns = null;
        try {
            int minIn = AudioRecord.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minIn <= 0) throw new IllegalStateException("48 kHz microphone is not available");
            int ioBuffer = Math.max(BLOCK * 8 * 2, minIn);

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

            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Microphone initialization failed");
            }

            final int maxSamples = SR * seconds;
            short[] captured = new short[maxSamples];
            short[] in = new short[BLOCK];
            short[] processed = new short[BLOCK];
            float[] ring = new float[RING];
            int write = 0;
            int total = 0;
            double phase = 0.0;
            float dcX1 = 0f, dcY1 = 0f;

            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Microphone did not start");
            }

            while (!stopRequested && total < maxSamples) {
                int wanted = Math.min(BLOCK, maxSamples - total);
                int n = rec.read(in, 0, wanted, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioRecord read error " + n);
                if (n == 0) continue;

                float semi = semitones;
                boolean bypass = semi < 0.05f;
                double ratio = Math.pow(2.0, semi / 12.0);
                double phaseStep = (ratio - 1.0) / WINDOW;

                for (int i = 0; i < n; i++) {
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

                    int v = Math.round(y * 0.86f);
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    processed[i] = (short)v;
                }

                System.arraycopy(processed, 0, captured, total, n);
                total += n;
            }

            lastProcessed = Arrays.copyOf(captured, total);
        } catch (Throwable t) {
            if (errors != null) errors.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try { if (rec != null) rec.stop(); } catch (Throwable ignored) {}
            try { if (ns != null) ns.release(); } catch (Throwable ignored) {}
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            busy = false;
            thread = null;
            if (done != null) done.onDone();
        }
    }

    private void playbackLoop(short[] audio, ErrorSink errors, DoneSink done) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack out = null;
        try {
            int minOut = AudioTrack.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minOut <= 0) throw new IllegalStateException("48 kHz playback is not available");

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
                    .setBufferSizeInBytes(Math.max(minOut * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (out.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Speaker initialization failed");
            }

            out.play();
            int offset = 0;
            while (!stopRequested && offset < audio.length) {
                int count = Math.min(2048, audio.length - offset);
                int n = out.write(audio, offset, count, AudioTrack.WRITE_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioTrack write error " + n);
                offset += n;
            }
        } catch (Throwable t) {
            if (errors != null) errors.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try { if (out != null) out.stop(); } catch (Throwable ignored) {}
            try { if (out != null) out.release(); } catch (Throwable ignored) {}
            busy = false;
            thread = null;
            if (done != null) done.onDone();
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
