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
 */
public final class DriveDiskVia extends Via6522 {

    private final Gcr gcr;
    private int headByte = 0x00;
    private boolean motorOn;

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

    @Override
    protected int readPortBInput() {
        int v = 0x6F;                // misc lines high
        v |= 0x10;                   // PB4 write-protect = 1 (writable / disk present)
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
