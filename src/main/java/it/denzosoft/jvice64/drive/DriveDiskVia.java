package it.denzosoft.jvice64.drive;

import it.denzosoft.jvice64.cia.IrqLine;

/**
 * 1541 VIA #2 at $1C00 — the disk controller.
 * Port A = the GCR byte read from / written to the head. Port B drives the
 * motor, LED, stepper (PB0/PB1) and reads the write-protect sensor (PB4) and
 * SYNC (PB7). The {@link Gcr} head supplies bytes and the byte-ready (CA1).
 *
 * Enough behaviour to read a track: the head advances one GCR byte at a time and
 * raises CA1/SYNC; the DOS reads $1C01 to get each byte.
 *
 * <p>Writing: PCR bits 7-5 = {@code 110} (CB2 low) put the head in write mode
 * (the DOS writes {@code $CE}/{@code $EE} to {@code $1C0C}). Then each byte
 * time the port A output is written under the head ({@link Gcr#writeByte}),
 * byte-ready keeps pulsing SO and the SYNC detector is off. The write-protect
 * sensor (PB4, 1 = writable) reports a writable disk unless the image was
 * inserted read-only ({@link #setWriteProtected}). A disk change is signalled
 * the way the DOS detects it, by a transition on that same line: see
 * {@link #diskChanged()}.</p>
 */
public final class DriveDiskVia extends Via6522 {

    private final Gcr gcr;
    private int headByte = 0x00;
    private boolean motorOn;
    /** Steady write-protect sensor state for the inserted disk (true = protected). */
    private boolean writeProtected;
    /** Run when the motor stops (the DOS is idle): a good moment to commit written tracks. */
    private Runnable onMotorOff;
    /** Run when the head leaves write mode (a block was written): commit it to the host file at once. */
    private Runnable onWriteEnd;

    /**
     * Cycles left in the disk-change sequence started by {@link #diskChanged()}:
     * PB4 reads "writable" (1) for the first half, as when the slot is open and
     * the sensor sees light, then the steady level. The DOS polls PB4 in its
     * controller IRQ (every ~10ms) and re-reads BAM + disk ID after a transition.
     * Not part of the snapshot: it lasts a fraction of a second.
     */
    private int changeCycles;
    static final int CHANGE_CYCLES = 300000;

    public DriveDiskVia(IrqLine irq, Gcr gcr) {
        super(irq);
        this.gcr = gcr;
    }

    /**
     * Advance the head by one GCR byte (called at the byte rate). Returns true if
     * a byte passed under the head: read into port A, or (write mode) the port A
     * output written onto the track.
     */
    public boolean headByte() {
        if (!motorOn || gcr == null) return false;
        if (writeMode()) {
            // The write-protect sensor also cuts the write current: drive code that
            // does not test PB4 (fast savers, copiers) records nothing on a protected
            // disk. The steady flag is used, not the swap pulse, so a write command
            // accepted inside the pulse changes nothing either.
            if (writeProtected) gcr.skipByte();
            else gcr.writeByte(portAOut());
        } else {
            headByte = gcr.nextByte();
        }
        return true;
    }

    public boolean motorOn() { return motorOn; }
    /** Head in write mode: CB2 driven low (PCR bits 7-5 = 110). */
    public boolean writeMode() { return (dbgPcr() & 0xE0) == 0xC0; }
    /** SYNC detected under the head; the detector only works in read mode. */
    public boolean sync() { return motorOn && gcr != null && !writeMode() && gcr.atSync(); }

    /** Steady write-protect state of the inserted disk (the swap pulse is the inverse). */
    public void setWriteProtected(boolean wp) { writeProtected = wp; }
    public boolean writeProtected() { return writeProtected; }
    public void setOnMotorOff(Runnable r) { onMotorOff = r; }
    public void setOnWriteEnd(Runnable r) { onWriteEnd = r; }

    /**
     * The DOS leaves write mode (PCR back to read, {@code $EE}) right after the
     * last byte of a block: that is when the block is complete, so commit it then
     * instead of when the head steps off the track or the motor stops, seconds
     * later (the directory and BAM writes end on track 18, where the head stays).
     */
    @Override
    public void write(int reg, int val) {
        boolean was = writeMode();
        super.write(reg, val);
        if (was && !writeMode() && onWriteEnd != null) onWriteEnd.run();
    }

    @Override
    protected int readPortAInput() { return headByte & 0xFF; }

    /** Signal a disk swap to the DOS (a write-protect transition, ~0.3 s). */
    public void diskChanged() { changeCycles = CHANGE_CYCLES; }

    /** True while the disk-change sequence is still running. */
    public boolean changePending() { return changeCycles > 0; }

    @Override
    public void clock(int cycles) {
        super.clock(cycles);
        if (changeCycles > 0) changeCycles = Math.max(0, changeCycles - cycles);
    }

    @Override
    public void reset() {
        super.reset();
        changeCycles = 0;
    }

    @Override
    protected int readPortBInput() {
        int v = 0x6F;                // misc lines high
        // PB4 write-protect sensor: 1 = writable. During the first half of a disk
        // change it reads the opposite of the steady state (the disk sliding in
        // covers the sensor), so the DOS sees a transition and re-reads BAM + ID.
        boolean writable = !writeProtected;
        if (changeCycles > CHANGE_CYCLES / 2) writable = !writable;
        if (writable) v |= 0x10;
        if (!sync()) v |= 0x80;      // PB7 SYNC: low (0) while over a sync mark
        return v;
    }

    @Override
    protected void portBChanged() {
        int out = (orb & ddrb);
        boolean wasOn = motorOn;
        motorOn = (out & 0x04) != 0; // PB2 = motor on
        if (gcr != null) gcr.selectTrack((out >> 0) & 0x03, out); // stepper bits PB0/PB1
        if (wasOn && !motorOn && onMotorOff != null) onMotorOff.run();
    }

    @Override
    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        super.saveState(o);
        o.writeByte(headByte);
        o.writeBoolean(motorOn);
    }

    @Override
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        super.loadState(in);
        headByte = in.readUnsignedByte();
        motorOn = in.readBoolean();
    }
}
