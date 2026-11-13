package it.denzosoft.jvice64.cia;

/**
 * MOS 6526 CIA core: two 16-bit interval timers (A, B), a BCD time-of-day
 * clock, two 8-bit ports and the interrupt control register. Enough behaviour
 * for the C64 KERNAL jiffy IRQ, keyboard scanning and typical software.
 *
 * Timers are clocked per PHI2 cycle. Timer B can count Timer A underflows.
 * The TOD is driven off the system clock (deterministic) at 10 ticks/second.
 */
public abstract class Cia {

    protected final int clockHz;
    protected final IrqLine irq;

    // Ports.
    protected int pra, prb, ddra, ddrb;

    // Timers.
    protected int taLatch = 0xFFFF, tbLatch = 0xFFFF;
    protected int ta = 0xFFFF, tb = 0xFFFF;
    protected int cra, crb;

    // Interrupt control.
    protected int icrData;     // bits0-4 latched event flags
    protected int icrMask;     // bits0-4 enable mask
    private boolean irqAsserted;

    // Time-of-day (BCD). 10ths, seconds, minutes, hours(+AM/PM in bit7).
    protected int todTenth, todSec, todMin, todHour = 0x01;
    protected int latchTenth, latchSec, latchMin, latchHour;
    protected boolean todLatched;
    protected boolean todHalted;
    protected int todAlarmTenth, todAlarmSec, todAlarmMin, todAlarmHour;
    private long todCycleAcc;

    /* Serial shift register ($DC0C/$DD0C). Only the OUTPUT direction is modelled:
     * a stock C64 has nothing driving the CNT pin, so in input mode a real chip
     * shifts nothing either — but in output mode software does wait on the SP
     * interrupt (ICR bit 3), and returning 0 forever used to hang it. Not part of
     * the snapshot: the shift lasts a handful of cycles, and adding fields would
     * break the existing save-state format. */
    private int sdr;                 // last value written / held in the register
    private boolean sdrFull;         // a byte is queued for transmission
    private boolean serialActive;    // a byte is being shifted out
    private int serialBits;          // bits shifted so far
    private int serialHalf;          // Timer A underflows inside the current bit (CNT = TA/2)
    /** Cycles per TOD tick (a tenth of a second), precomputed: clock() runs per cycle. */
    private final long todPeriod;

    protected Cia(int clockHz, IrqLine irq) {
        this.clockHz = clockHz;
        this.todPeriod = clockHz / 10;
        this.irq = irq;
    }

    public void reset() {
        pra = prb = ddra = ddrb = 0;
        taLatch = tbLatch = 0xFFFF;
        ta = tb = 0xFFFF;
        cra = crb = 0;
        icrData = icrMask = 0;
        irqAsserted = false;
        irq.set(false);
        todTenth = todSec = todMin = 0;
        todHour = 0x01;
        todLatched = todHalted = false;
        todCycleAcc = 0;
        sdr = 0;
        sdrFull = serialActive = false;
        serialBits = serialHalf = 0;
    }

    // Subclasses supply the value present on the input pins of each port.
    protected abstract int readPortAInput();
    protected abstract int readPortBInput();

    /** Current value driven on port A (output bits from latch, input bits from pins). */
    public int getPortA() { return ((pra & ddra) | (readPortAInput() & ~ddra)) & 0xFF; }
    public int getPortB() { return ((prb & ddrb) | (readPortBInput() & ~ddrb)) & 0xFF; }

    // ---- Snapshot ----------------------------------------------------------

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        o.writeByte(pra); o.writeByte(prb); o.writeByte(ddra); o.writeByte(ddrb);
        o.writeShort(taLatch); o.writeShort(tbLatch); o.writeInt(ta); o.writeInt(tb);
        o.writeByte(cra); o.writeByte(crb); o.writeByte(icrData); o.writeByte(icrMask);
        o.writeBoolean(irqAsserted);
        o.writeByte(todTenth); o.writeByte(todSec); o.writeByte(todMin); o.writeByte(todHour);
        o.writeByte(todAlarmTenth); o.writeByte(todAlarmSec); o.writeByte(todAlarmMin); o.writeByte(todAlarmHour);
        o.writeBoolean(todLatched); o.writeBoolean(todHalted); o.writeLong(todCycleAcc);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        pra = in.readUnsignedByte(); prb = in.readUnsignedByte();
        ddra = in.readUnsignedByte(); ddrb = in.readUnsignedByte();
        taLatch = in.readUnsignedShort(); tbLatch = in.readUnsignedShort();
        ta = in.readInt(); tb = in.readInt();
        cra = in.readUnsignedByte(); crb = in.readUnsignedByte();
        icrData = in.readUnsignedByte(); icrMask = in.readUnsignedByte();
        irqAsserted = in.readBoolean();
        todTenth = in.readUnsignedByte(); todSec = in.readUnsignedByte();
        todMin = in.readUnsignedByte(); todHour = in.readUnsignedByte();
        todAlarmTenth = in.readUnsignedByte(); todAlarmSec = in.readUnsignedByte();
        todAlarmMin = in.readUnsignedByte(); todAlarmHour = in.readUnsignedByte();
        todLatched = in.readBoolean(); todHalted = in.readBoolean(); todCycleAcc = in.readLong();
        // The serial shift register is not in the snapshot: drop a shift that was in
        // progress in the session being replaced, so it cannot finish in the restored
        // machine and raise a spurious SP interrupt.
        sdr = 0;
        sdrFull = serialActive = false;
        serialBits = serialHalf = 0;
        // The TOD read latch contents are not in the snapshot either: a restored
        // todLatched would otherwise return the replaced session's time. The restored
        // clock is the best value available (no format change).
        if (todLatched) latchAll();
        irq.set(irqAsserted);
    }

    /** FLAG input (cassette read on CIA1): a falling edge latches ICR bit4. */
    public void setFlag() {
        icrData |= 0x10;
        updateIrq();
    }

    // ---- Register interface -------------------------------------------------

    public int read(int reg) {
        switch (reg & 0x0F) {
            case 0x0: return getPortA();
            case 0x1: return getPortB();
            case 0x2: return ddra;
            case 0x3: return ddrb;
            case 0x4: return ta & 0xFF;
            case 0x5: return (ta >> 8) & 0xFF;
            case 0x6: return tb & 0xFF;
            case 0x7: return (tb >> 8) & 0xFF;
            case 0x8: return todRead(0);
            case 0x9: return todRead(1);
            case 0xA: return todRead(2);
            case 0xB: return todRead(3);
            case 0xC: return sdr & 0xFF;                                // SDR
            case 0xD: {
                int v = icrData | (irqAsserted ? 0x80 : 0);
                icrData = 0;
                setIrq(false);
                return v;
            }
            case 0xE: return cra;
            case 0xF: return crb;
        }
        return 0;
    }

    public void write(int reg, int val) {
        val &= 0xFF;
        switch (reg & 0x0F) {
            case 0x0: pra = val; break;
            case 0x1: prb = val; break;
            case 0x2: ddra = val; break;
            case 0x3: ddrb = val; break;
            case 0x4: taLatch = (taLatch & 0xFF00) | val; break;
            case 0x5: taLatch = (taLatch & 0x00FF) | (val << 8);
                      if ((cra & 0x01) == 0) ta = taLatch; break;       // reload when stopped
            case 0x6: tbLatch = (tbLatch & 0xFF00) | val; break;
            case 0x7: tbLatch = (tbLatch & 0x00FF) | (val << 8);
                      if ((crb & 0x01) == 0) tb = tbLatch; break;
            case 0x8: todWrite(0, val); break;
            case 0x9: todWrite(1, val); break;
            case 0xA: todWrite(2, val); break;
            case 0xB: todWrite(3, val); break;
            case 0xC:                                                   // SDR
                sdr = val;
                sdrFull = true;             // starts on the next Timer A underflow
                break;
            case 0xD: {                                                 // ICR mask
                if ((val & 0x80) != 0) icrMask |= (val & 0x1F);
                else icrMask &= ~(val & 0x1F);
                updateIrq();
                break;
            }
            case 0xE:                                                   // CRA
                cra = val;
                if ((val & 0x10) != 0) ta = taLatch;                    // force load
                cra &= ~0x10;
                break;
            case 0xF:                                                   // CRB
                crb = val;
                if ((val & 0x10) != 0) tb = tbLatch;
                crb &= ~0x10;
                break;
        }
    }

    // ---- Timer / TOD clocking ----------------------------------------------

    public void clock(int cycles) {
        for (int i = 0; i < cycles; i++) tickTimers();
        tickTod(cycles);
    }

    /**
     * Advance exactly one PHI2 cycle. Called from the CPU's cycle sink, which
     * ticks BEFORE each bus access — so a mid-instruction read of $DC04/$DC05
     * sees the timer value of that very cycle instead of one up to ~6 cycles
     * stale, which is what CIA-timer raster stabilisation depends on.
     */
    public void clockOne() {
        tickTimers();
        if (todHalted) return;
        if (++todCycleAcc >= todPeriod) { todCycleAcc -= todPeriod; advanceTod(); }
    }

    private void tickTimers() {
        boolean taUnderflow = false;

        // Timer A (PHI2 input mode only; CNT input not wired in this model).
        if ((cra & 0x01) != 0 && (cra & 0x20) == 0) {
            ta--;
            if (ta < 0) {
                ta = taLatch;
                taUnderflow = true;
                icrData |= 0x01;
                if ((cra & 0x08) != 0) cra &= ~0x01;                    // one-shot stop
            }
        }

        clockSerial(taUnderflow);

        // Timer B (PHI2 mode, or counting Timer A underflows).
        if ((crb & 0x01) != 0) {
            int bmode = (crb >> 5) & 0x03;
            boolean tick = (bmode == 0) || ((bmode == 2 || bmode == 3) && taUnderflow);
            if (tick) {
                tb--;
                if (tb < 0) {
                    tb = tbLatch;
                    icrData |= 0x02;
                    if ((crb & 0x08) != 0) crb &= ~0x01;
                }
            }
        }

        updateIrq();
    }

    /**
     * Output-mode serial shift: the CNT pin toggles at half the Timer A underflow
     * rate, so a bit leaves every 2 underflows and the SP interrupt (ICR bit 3)
     * fires after 8 bits. In input mode nothing happens — there is no CNT source.
     */
    private void clockSerial(boolean taUnderflow) {
        if (!taUnderflow || (cra & 0x40) == 0) return;      // no underflow, or input mode
        if (!serialActive) {
            if (!sdrFull) return;
            serialActive = true;
            sdrFull = false;
            serialBits = serialHalf = 0;
        }
        if (++serialHalf < 2) return;
        serialHalf = 0;
        if (++serialBits >= 8) {
            serialActive = false;
            serialBits = 0;
            icrData |= 0x08;                                // SP (serial port) interrupt
        }
    }

    private void updateIrq() {
        if ((icrData & icrMask & 0x1F) != 0) setIrq(true);
    }

    private void setIrq(boolean asserted) {
        if (asserted != irqAsserted) {
            irqAsserted = asserted;
            irq.set(asserted);
        }
    }

    // ---- Time of day --------------------------------------------------------

    private void tickTod(int cycles) {
        if (todHalted) return;
        todCycleAcc += cycles;
        while (todCycleAcc >= todPeriod) {
            todCycleAcc -= todPeriod;
            advanceTod();
        }
    }

    private void advanceTod() {
        todTenth = bcdInc(todTenth);
        if (todTenth <= 0x09) { checkAlarm(); return; }
        todTenth = 0;
        todSec = bcdInc(todSec);
        if (todSec > 0x59) { todSec = 0; todMin = bcdInc(todMin);
            if (todMin > 0x59) { todMin = 0; advanceHour(); } }
        checkAlarm();
    }

    private void advanceHour() {
        int pm = todHour & 0x80;
        int h = todHour & 0x1F;
        h = bcdInc(h);
        if (h == 0x12) pm ^= 0x80;               // 11->12 toggles AM/PM
        if (h > 0x12) h = 0x01;
        todHour = pm | h;
    }

    private static int bcdInc(int v) {
        int lo = (v & 0x0F) + 1;
        int hi = v & 0xF0;
        if (lo > 9) { lo = 0; hi += 0x10; }
        return hi | lo;
    }

    private void checkAlarm() {
        if (todTenth == todAlarmTenth && todSec == todAlarmSec
                && todMin == todAlarmMin && todHour == todAlarmHour) {
            icrData |= 0x04;
            updateIrq();
        }
    }

    private int todRead(int which) {
        // Reading hours latches the whole time until tenths is read.
        if (which == 3) { latchAll(); todLatched = true; return latchHour; }
        if (todLatched) {
            int v;
            switch (which) { case 0: v = latchTenth; break; case 1: v = latchSec; break; default: v = latchMin; }
            if (which == 0) todLatched = false;
            return v;
        }
        switch (which) { case 0: return todTenth; case 1: return todSec; default: return todMin; }
    }

    private void latchAll() {
        latchTenth = todTenth; latchSec = todSec; latchMin = todMin; latchHour = todHour;
    }

    private void todWrite(int which, int val) {
        // CRB bit7 selects whether writes set the alarm or the clock.
        boolean alarm = (crb & 0x80) != 0;
        switch (which) {
            case 0: if (alarm) todAlarmTenth = val & 0x0F; else { todTenth = val & 0x0F; todHalted = false; } break;
            case 1: if (alarm) todAlarmSec = val & 0x7F; else todSec = val & 0x7F; break;
            case 2: if (alarm) todAlarmMin = val & 0x7F; else todMin = val & 0x7F; break;
            case 3: if (alarm) todAlarmHour = val & 0x9F; else { todHour = val & 0x9F; todHalted = true; } break;
        }
    }
}
