package com.a26.femalevoice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

public final class VoiceEngine {
    public interface ErrorSink { void onError(String message); }
    private static final int SR = 48000;
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
            try { thread.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            thread = null;
        }
    }

    private void loop(ErrorSink errors) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        AudioTrack out = null;
        try {
            int minIn = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minOut = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(4096, Math.max(minIn, minOut));

            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 2);

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
                    .setBufferSizeInBytes(buf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            if (rec.getState() != AudioRecord.STATE_INITIALIZED ||
                    out.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Audio device init failed");
            }

            short[] in = new short[1024];
            short[] processed = new short[1024];
            rec.startRecording();
            out.play();
            double phase = 0.0;

            while (running) {
                int n = rec.read(in, 0, in.length, AudioRecord.READ_BLOCKING);
                if (n <= 1) continue;
                double ratio = Math.pow(2.0, semitones / 12.0);
                for (int i = 0; i < n; i++) {
                    double pos = phase + i * ratio;
                    int i0 = ((int) pos) % n;
                    int i1 = (i0 + 1) % n;
                    double f = pos - Math.floor(pos);
                    int v = (int) (in[i0] * (1.0 - f) + in[i1] * f);
                    processed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
                }
                phase = (phase + n * ratio) % n;
                out.write(processed, 0, n, AudioTrack.WRITE_BLOCKING);
            }
        } catch (Throwable t) {
            if (errors != null) errors.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            running = false;
            try { if (rec != null) rec.stop(); } catch (Throwable ignored) {}
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            try { if (out != null) out.stop(); } catch (Throwable ignored) {}
            try { if (out != null) out.release(); } catch (Throwable ignored) {}
        }
    }
}
