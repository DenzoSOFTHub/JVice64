package it.denzosoft.jvice64.drive;

import it.denzosoft.jvice64.cia.IrqLine;

/**
 * 1541 VIA #1 at $1800 — the serial IEC interface.
 * Port B: PB0 DATA in, PB1 DATA out, PB2 CLK in, PB3 CLK out, PB4 ATNA,
 * PB5/PB6 device-address jumpers (device 8), PB7 ATN in. CA1 = ATN in (IRQ).
 * Line convention on the bus: {@code true = LOW}; a port bit reads 0 when its
 * line is low.
 */
public final class DriveIecVia extends Via6522 {

    private final IecBus bus;

    public DriveIecVia(IrqLine irq, IecBus bus) {
        super(irq);
        this.bus = bus;
    }

    /** Re-sample ATN (CA1) and re-drive CLK/DATA (incl. auto-ack); call each step. */
    public void update() {
        // ATN is inverted to CA1 on the 1541: line LOW (asserted) -> CA1 HIGH.
        setCa1(bus.atn());
        driveBus();
    }

    @Override
    protected int readPortBInput() {
        // Per VICE via1d1541 read_prb: inputs DATA(PB0)/CLK(PB2)/ATN(PB7) are all
        // inverted (1 = line LOW / asserted). PB5/PB6 (drive number) read 0 for a
        // single device-8 1541.
        int v = 0;
        if (bus.data()) v |= 0x01;   // DATA in (PB0)
        if (bus.clk())  v |= 0x04;   // CLK in  (PB2)
        if (bus.atn())  v |= 0x80;   // ATN in  (PB7)
        return v;
    }

    @Override
    protected int readPortAInput() { return 0xFF; }

    @Override
    protected void portBChanged() { driveBus(); }

    private void driveBus() {
        int out = (orb & ddrb);
        boolean clkLow = (out & 0x08) != 0;          // PB3 CLK out
        boolean dataLow = (out & 0x02) != 0;         // PB1 DATA out
        // ATN auto-acknowledge: if ATN is asserted and ATNA(PB4) is low, pull DATA.
        boolean atna = (out & 0x10) != 0;
        if (bus.atn() && !atna) dataLow = true;
        bus.setDrive(clkLow, dataLow);
    }
}
