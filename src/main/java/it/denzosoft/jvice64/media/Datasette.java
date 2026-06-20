package it.denzosoft.jvice64.media;

/**
 * Models datasette (cassette) playback feeding the C64 cassette read line.
 *
 * <p>A {@link Tap} provides an ordered list of pulse lengths in PHI2 cycles.
 * While the motor is on, the tape advances one cycle at a time; at the very end
 * of each pulse the cassette read line produces a falling edge. The C64 wires
 * that read line to the CIA1 {@code FLAG} input, so {@link #clock(int)} reports
 * the cycle on which an edge occurred.</p>
 *
 * <p>Playback is purely deterministic: it depends only on the inserted tape and
 * the sequence of {@code clock}/{@code setMotor} calls.</p>
 */
public final class Datasette {

    private int[] pulses;       // current tape pulses (null when no tape)
    private int index;          // index of the pulse currently being played
    private long remaining;     // PHI2 cycles left in the current pulse
    private boolean motor;      // cassette motor on/off

    /** Create an empty datasette with no tape and the motor off. */
    public Datasette() {
        this.pulses = null;
        this.index = 0;
        this.remaining = 0;
        this.motor = false;
    }

    /**
     * Insert a tape, rewinding to its start. The motor state is preserved.
     *
     * @param tap the tape image to play; {@code null} ejects any current tape
     */
    public void insert(Tap tap) {
        if (tap == null) {
            eject();
            return;
        }
        this.pulses = tap.pulses();
        rewind();
    }

    /** Remove the tape. Playback state is cleared; the motor state is kept. */
    public void eject() {
        this.pulses = null;
        this.index = 0;
        this.remaining = 0;
    }

    /** @return {@code true} if a tape is currently inserted. */
    public boolean hasTape() {
        return pulses != null;
    }

    /**
     * Set the cassette motor state. Driven by the C64 from CIA1 PRA bit 5.
     *
     * @param on {@code true} to run the motor, {@code false} to stop it
     */
    public void setMotor(boolean on) {
        this.motor = on;
    }

    /** Rewind to the start of the tape and switch the motor off. */
    public void reset() {
        this.motor = false;
        rewind();
    }

    /**
     * Advance the tape by {@code cycles} PHI2 cycles while the motor is on.
     *
     * <p>The cassette read line emits a falling edge at the end of each tape
     * pulse. This returns {@code true} if at least one such edge occurred during
     * the advance. When the motor is off, no tape is present, or the tape has
     * reached its end, the tape does not move and {@code false} is returned.</p>
     *
     * @param cycles number of PHI2 cycles to advance (non-positive does nothing)
     * @return {@code true} if a read-line falling edge happened during this step
     */
    public boolean clock(int cycles) {
        if (!motor || pulses == null || cycles <= 0) {
            return false;
        }

        boolean edge = false;
        long left = cycles;
        while (left > 0 && index < pulses.length) {
            if (remaining <= left) {
                // Consume the rest of the current pulse: edge at its end.
                left -= remaining;
                remaining = 0;
                edge = true;
                index++;
                if (index < pulses.length) {
                    remaining = pulses[index];
                }
            } else {
                remaining -= left;
                left = 0;
            }
        }
        return edge;
    }

    /** @return {@code true} once all pulses have been played out. */
    public boolean atEnd() {
        return pulses == null || index >= pulses.length;
    }

    private void rewind() {
        this.index = 0;
        this.remaining = (pulses != null && pulses.length > 0) ? pulses[0] : 0;
    }
}
