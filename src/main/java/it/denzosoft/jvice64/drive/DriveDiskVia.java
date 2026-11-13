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
 * <p>Writing is NOT implemented (no GCR write path), so the write-protect sensor
 * (PB4, 0 = protected) reports a protected disk: the DOS then answers
 * {@code 26,WRITE PROTECT ON} instead of "writing" blocks that never reach the
 * image. A disk change is signalled the way the DOS detects it, by a transition
 * on that same line: see {@link #diskChanged()}.</p>
 */
public final class DriveDiskVia extends Via6522 {

    private final Gcr gcr;
    private int headByte = 0x00;
    private boolean motorOn;

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

    /** Advance the head by one GCR byte (called at the byte rate). Returns true if a byte was read. */
    public boolean headByte() {
        if (!motorOn || gcr == null) return false;
        headByte = gcr.nextByte();
        return true;
    }

    public boolean motorOn() { return motorOn; }
    public boolean sync() { return motorOn && gcr != null && gcr.atSync(); }

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
        // PB4 write-protect: 0 = protected (steady state, no write support);
        // 1 during the first half of a disk change, so the DOS sees the swap.
        if (changeCycles > CHANGE_CYCLES / 2) v |= 0x10;
        if (!sync()) v |= 0x80;      // PB7 SYNC: low (0) while over a sync mark
        return v;
    }

    @Override
    protected void portBChanged() {
        int out = (orb & ddrb);
        motorOn = (out & 0x04) != 0; // PB2 = motor on
        if (gcr != null) gcr.selectTrack((out >> 0) & 0x03, out); // stepper bits PB0/PB1
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
