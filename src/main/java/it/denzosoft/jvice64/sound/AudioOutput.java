package it.denzosoft.jvice64.sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Thin wrapper around a {@link SourceDataLine} for 16-bit signed mono PCM.
 * The emulator pushes SID samples here once per frame.
 */
public final class AudioOutput {

    private final int sampleRate;
    private SourceDataLine line;
    private byte[] byteBuf = new byte[0];
    private boolean ok;
    private boolean started;
    private volatile float gain = 1.0f;        // playback volume 0..1 (set from the Settings slider)

    /** Set the playback volume, 0..100 (host-side gain applied to the speaker output). */
    public void setVolume(int percent) {
        gain = (percent < 0 ? 0 : (percent > 100 ? 100 : percent)) / 100f;
    }

    public AudioOutput(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public boolean open() {
        try {
            AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false); // signed, little-endian
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, sampleRate / 5 * 2);   // ~200ms buffer
            // NOTE: do NOT start() here. A started but unfed line under-runs and
            // motorboats ("galloping" background hum) while audio is disabled. The
            // line is started lazily on the first start() once audio is enabled.
            ok = true;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.err.println("Audio unavailable: " + e.getMessage());
            ok = false;
        }
        return ok;
    }

    /** Begin playback (idempotent). Called when audio is enabled. */
    public void start() {
        if (ok && !started) { line.start(); started = true; }
    }

    /** Pause playback and discard any buffered audio (idempotent) — instant, clean silence. */
    public void stop() {
        if (ok && started) { line.stop(); line.flush(); started = false; }
    }

    /**
     * Write {@code count} signed 16-bit samples, blocking until the line has
     * accepted them all.
     *
     * The old behaviour clamped the write to {@code line.available()} and dropped
     * the surplus when the buffer was full. That capped the buffer at the top but
     * let it run dry at the bottom on any frame-timing jitter, so the line kept
     * under-running — the "audio comes and goes" symptom. A blocking full write
     * never loses samples: the ~200 ms line buffer fills into a cushion that
     * absorbs jitter, and the audio clock then paces the emulator (the write only
     * blocks while we are ahead of real time, which is exactly the pacing we want).
     */
    public void write(short[] samples, int count) {
        if (!ok || !started || count <= 0) return;
        int need = count * 2;
        if (byteBuf.length < need) byteBuf = new byte[need];
        float g = gain;                              // volume scaling (volatile; read once)
        for (int i = 0; i < count; i++) {
            int s = (g == 1.0f) ? samples[i] : (int) (samples[i] * g);
            if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
            byteBuf[i * 2] = (byte) (s & 0xFF);
            byteBuf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        line.write(byteBuf, 0, need);   // blocking: never drop; the line paces us
    }

    /** True once the line opened OK — i.e. writes will actually pace on the audio clock. */
    public boolean isActive() { return ok; }

    public void close() {
        if (line != null) { line.drain(); line.stop(); line.close(); }
    }
}
