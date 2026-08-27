package com.a26.femalevoice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;

import com.breakfastquay.rubberband.RubberBandStretcher;

import java.util.Arrays;

public final class VoiceEngine {
    public interface ErrorSink { void onError(String message); }
    public interface DoneSink { void onDone(); }

    private static final int SR = 48000;
    private static final int BLOCK = 960;
    private static final int DSP_CHUNK = 2048;

    private volatile boolean busy = false;
    private volatile boolean stopRequested = false;
    private volatile float pitchSemitones = 3.0f;
    private volatile float formantSemitones = 1.2f;
    private volatile boolean rawMode = false;
    private Thread thread;
    private volatile short[] lastProcessed;

    public boolean isRunning() { return busy; }
    public boolean hasRecording() { return lastProcessed != null && lastProcessed.length > 0; }

    public void setProfile(float pitchSemi, float formantSemi, boolean raw) {
        pitchSemitones = Math.max(-6f, Math.min(8f, pitchSemi));
        formantSemitones = Math.max(-4f, Math.min(5f, formantSemi));
        rawMode = raw;
    }

    public synchronized void recordSeconds(int seconds, ErrorSink errors, DoneSink done) {
        if (busy) return;
        busy = true;
        stopRequested = false;
        int safeSeconds = Math.max(2, Math.min(12, seconds));
        thread = new Thread(() -> captureAndProcess(safeSeconds, errors, done), "A26CaptureDSP");
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
            try { thread.join(1800); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            thread = null;
        }
        busy = false;
    }

    private void captureAndProcess(int seconds, ErrorSink errors, DoneSink done) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord rec = null;
        try {
            int minIn = AudioRecord.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minIn <= 0) throw new IllegalStateException("48 kHz microphone is not available");
            int ioBuffer = Math.max(minIn * 2, BLOCK * 8 * 2);

            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    ioBuffer);

            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("Microphone initialization failed");
            }

            int maxSamples = SR * seconds;
            short[] captured = new short[maxSamples];
            short[] in = new short[BLOCK];
            int total = 0;

            rec.startRecording();
            if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Microphone did not start");
            }

            while (!stopRequested && total < maxSamples) {
                int wanted = Math.min(BLOCK, maxSamples - total);
                int n = rec.read(in, 0, wanted, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IllegalStateException("AudioRecord read error " + n);
                if (n == 0) continue;
                System.arraycopy(in, 0, captured, total, n);
                total += n;
            }

            short[] raw = Arrays.copyOf(captured, total);
            try { rec.stop(); } catch (Throwable ignored) {}
            try { rec.release(); } catch (Throwable ignored) {}
            rec = null;

            if (rawMode || raw.length == 0) {
                lastProcessed = normalise(raw);
            } else {
                // Stage 1 deliberately moves both pitch and spectral envelope by the
                // desired formant amount. Stage 2 changes the remaining pitch while
                // preserving that envelope. This gives independent pitch/formant control
                // even on the older Android Rubber Band JNI API.
                float f = formantSemitones;
                float p = pitchSemitones;
                short[] stage1 = Math.abs(f) < 0.05f ? raw : rubberShift(raw, f, false);
                if (stopRequested) return;
                float remainingPitch = p - f;
                short[] stage2 = Math.abs(remainingPitch) < 0.05f
                        ? stage1 : rubberShift(stage1, remainingPitch, true);
                lastProcessed = normalise(stage2);
            }
        } catch (Throwable t) {
            lastProcessed = null;
            if (errors != null) {
                String msg = t.getMessage();
                if (msg == null || msg.trim().isEmpty()) msg = t.toString();
                errors.onError(t.getClass().getSimpleName() + ": " + msg);
            }
        } finally {
            try { if (rec != null) rec.stop(); } catch (Throwable ignored) {}
            try { if (rec != null) rec.release(); } catch (Throwable ignored) {}
            busy = false;
            thread = null;
            if (done != null) done.onDone();
        }
    }

    private short[] rubberShift(short[] input, float semitones, boolean preserveFormants) {
        if (input.length == 0 || Math.abs(semitones) < 0.01f) return input;

        double pitchScale = Math.pow(2.0, semitones / 12.0);
        int options = RubberBandStretcher.OptionProcessOffline
                | RubberBandStretcher.OptionPitchHighQuality
                | RubberBandStretcher.OptionWindowShort
                | RubberBandStretcher.OptionDetectorSoft
                | RubberBandStretcher.OptionSmoothingOn;
        if (preserveFormants) options |= RubberBandStretcher.OptionFormantPreserved;

        RubberBandStretcher rb = new RubberBandStretcher(SR, 1, options, 1.0, pitchScale);
        try {
            rb.setExpectedInputDuration(input.length);
            rb.setMaxProcessSize(DSP_CHUNK);

            float[][] samples = new float[1][input.length];
            for (int i = 0; i < input.length; i++) {
                samples[0][i] = input[i] / 32768.0f;
            }

            for (int off = 0; off < input.length && !stopRequested; off += DSP_CHUNK) {
                int n = Math.min(DSP_CHUNK, input.length - off);
                boolean last = off + n >= input.length;
                rb.study(samples, off, n, last);
            }

            short[] output = new short[Math.max(input.length + SR, input.length * 2)];
            int outPos = 0;
            float[][] temp = new float[1][8192];

            for (int off = 0; off < input.length && !stopRequested; off += DSP_CHUNK) {
                int n = Math.min(DSP_CHUNK, input.length - off);
                boolean last = off + n >= input.length;
                rb.process(samples, off, n, last);
                outPos = drain(rb, output, outPos, temp);
                if (outPos + temp[0].length >= output.length) {
                    output = Arrays.copyOf(output, output.length * 2);
                }
            }

            int guard = 0;
            while (!stopRequested && rb.available() > 0 && guard++ < 10000) {
                if (outPos + temp[0].length >= output.length) {
                    output = Arrays.copyOf(output, output.length * 2);
                }
                outPos = drain(rb, output, outPos, temp);
            }

            if (outPos == 0) throw new IllegalStateException("DSP produced no audio");
            return Arrays.copyOf(output, outPos);
        } finally {
            rb.dispose();
        }
    }

    private int drain(RubberBandStretcher rb, short[] output, int outPos, float[][] temp) {
        while (rb.available() > 0 && !stopRequested) {
            int want = Math.min(rb.available(), temp[0].length);
            int got = rb.retrieve(temp, 0, want);
            if (got <= 0) break;
            for (int i = 0; i < got; i++) {
                if (outPos >= output.length) break;
                float x = temp[0][i];
                int v = Math.round(x * 32767f);
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                output[outPos++] = (short)v;
            }
        }
        return outPos;
    }

    private short[] normalise(short[] input) {
        if (input == null || input.length == 0) return input;
        int peak = 1;
        for (short s : input) peak = Math.max(peak, Math.abs((int)s));
        float gain = Math.min(1.35f, (32767f * 0.88f) / peak);
        short[] out = new short[input.length];
        float x1 = 0f, y1 = 0f;
        for (int i = 0; i < input.length; i++) {
            float x = input[i] * gain;
            float hp = x - x1 + 0.996f * y1;
            x1 = x;
            y1 = hp;
            int v = Math.round(hp);
            if (v > 32767) v = 32767;
            if (v < -32768) v = -32768;
            out[i] = (short)v;
        }
        return out;
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
                    .setBufferSizeInBytes(Math.max(minOut * 2, 16384))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();

            if (out.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("Speaker initialization failed");
            }

            out.play();
            int offset = 0;
            while (!stopRequested && offset < audio.length) {
                int count = Math.min(4096, audio.length - offset);
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
}
