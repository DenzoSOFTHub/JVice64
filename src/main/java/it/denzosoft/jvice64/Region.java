package it.denzosoft.jvice64;

/**
 * Video standard / timing region. Selects the PHI2 clock, the VIC-II raster
 * geometry (lines per frame and cycles per line) and the refresh rate.
 *
 * <p>NTSC needs an NTSC KERNAL ROM to be fully correct (the jiffy clock and a
 * few timing constants differ); with the PAL ROM it still boots but the system
 * clock runs at the NTSC rate.</p>
 */
public enum Region {
    // The ordinal is stored in snapshots (v3): only ever append new constants.
    PAL (985248,  312, 63, 50),
    NTSC (1022727, 263, 65, 60);

    public final int clockHz;
    public final int lines;
    public final int cyclesPerLine;
    public final int refreshHz;

    Region(int clockHz, int lines, int cyclesPerLine, int refreshHz) {
        this.clockHz = clockHz;
        this.lines = lines;
        this.cyclesPerLine = cyclesPerLine;
        this.refreshHz = refreshHz;
    }

    /** PHI2 cycles in one frame (PAL 19656, NTSC 17095). */
    public int cyclesPerFrame() { return lines * cyclesPerLine; }

    /**
     * Real frame rate of the emulated machine: PAL 50.125 Hz, NTSC 59.826 Hz.
     * {@link #refreshHz} is only the nominal 50/60 Hz.
     */
    public double frameHz() { return (double) clockHz / cyclesPerFrame(); }

    /** Exact duration of one emulated frame in nanoseconds. */
    public long frameNanos() { return 1_000_000_000L * cyclesPerFrame() / clockHz; }
}
