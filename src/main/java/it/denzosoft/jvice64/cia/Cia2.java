package it.denzosoft.jvice64.cia;

import it.denzosoft.jvice64.drive.IecBus;

/**
 * CIA #2 ($DD00). Port A bits 0-1 select the 16KB VIC-II memory bank (active
 * low). Bits 3-5 drive the serial IEC bus (ATN/CLK/DATA out, 1 = pull low) and
 * bits 6-7 read it (CLK/DATA in, 0 = line low). Its interrupt output is wired to
 * the CPU NMI line (RESTORE key and serial/RS232 events).
 *
 * When no {@link IecBus} is attached (or for trapped device-8 access) the serial
 * lines simply float high.
 */
public final class Cia2 extends Cia {

    private IecBus iec;

    public Cia2(int clockHz, IrqLine nmi) {
        super(clockHz, nmi);
    }

    public void setIecBus(IecBus bus) { this.iec = bus; }

    private int portAOut() { return ((pra & ddra) | (~ddra & 0xFF)) & 0xFF; }

    /** VIC-II bank 0..3 selected by the inverted low two bits of port A. */
    public int vicBank() {
        return (~portAOut()) & 0x03;
    }

    @Override
    public void write(int reg, int val) {
        super.write(reg, val);
        int r = reg & 0x0F;
        if (r == 0x0 || r == 0x2) pushIec();    // PRA or DDRA changed
    }

    /** A reset makes port A an input: release ATN/CLK/DATA on the bus too. */
    @Override
    public void reset() {
        super.reset();
        pushIec();
    }

    /** After a snapshot load, drive the IEC bus from the restored port A (the bus is not saved). */
    @Override
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        super.loadState(in);
        pushIec();
    }

    private void pushIec() {
        if (iec == null) return;
        int driven = pra & ddra;
        iec.setC64((driven & 0x08) != 0,        // ATN out
                   (driven & 0x10) != 0,        // CLK out
                   (driven & 0x20) != 0);       // DATA out
    }

    @Override
    protected int readPortAInput() {
        if (iec == null) return 0xFF;
        int v = 0xFF;
        if (iec.clk())  v &= ~0x40;             // CLK in  (bit6) low
        if (iec.data()) v &= ~0x80;             // DATA in (bit7) low
        return v;
    }

    @Override
    protected int readPortBInput() {
        return 0xFF;
    }
}
