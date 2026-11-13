package it.denzosoft.jvice64.drive;

import it.denzosoft.jvice64.cia.IrqLine;

/**
 * MOS 6522 VIA. Models the two interval timers (T1 free-run/one-shot, T2),
 * the two 8-bit ports with data-direction registers, the CA1/CB1 input edges
 * and the interrupt flag/enable registers — enough to run the 1541 DOS (which
 * relies on VIA1 Timer 1 IRQs and the ATN-in edge on CA1, and VIA2 for the disk
 * controller). The shift register is stubbed.
 *
 * Subclasses supply the values present on the port input pins and may react to
 * port-output writes (e.g. stepper / IEC line driving).
 */
public abstract class Via6522 {

    // IFR/IER bit positions.
    protected static final int I_CA2 = 0x01, I_CA1 = 0x02, I_SR = 0x04, I_CB2 = 0x08;
    protected static final int I_CB1 = 0x10, I_T2 = 0x20, I_T1 = 0x40, I_IRQ = 0x80;

    private final IrqLine irq;

    protected int ora, orb, ddra, ddrb;
    private int t1c, t1l;          // timer 1 counter / latch (16-bit)
    private int t2c, t2l;          // timer 2 counter / low latch
    private boolean t1Running = true, t2Running = true;
    private int acr, pcr, sr;
    private int ifr, ier;
    private boolean ca1Level, cb1Level;
    private boolean irqAsserted;

    protected Via6522(IrqLine irq) {
        this.irq = irq;
    }

    public void reset() {
        ora = orb = ddra = ddrb = 0;
        t1c = t1l = 0xFFFF;
        t2c = t2l = 0xFFFF;
        acr = pcr = sr = 0;
        ifr = ier = 0;
        ca1Level = cb1Level = false;
        irqAsserted = false;
        irq.set(false);
    }

    // ---- Snapshot ----------------------------------------------------------

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        o.writeByte(ora); o.writeByte(orb); o.writeByte(ddra); o.writeByte(ddrb);
        o.writeShort(t1c); o.writeShort(t1l); o.writeShort(t2c); o.writeShort(t2l);
        o.writeBoolean(t1Running); o.writeBoolean(t2Running);
        o.writeByte(acr); o.writeByte(pcr); o.writeByte(sr);
        o.writeByte(ifr); o.writeByte(ier);
        o.writeBoolean(ca1Level); o.writeBoolean(cb1Level);
        o.writeBoolean(irqAsserted);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        ora = in.readUnsignedByte(); orb = in.readUnsignedByte();
        ddra = in.readUnsignedByte(); ddrb = in.readUnsignedByte();
        t1c = in.readUnsignedShort(); t1l = in.readUnsignedShort();
        t2c = in.readUnsignedShort(); t2l = in.readUnsignedShort();
        t1Running = in.readBoolean(); t2Running = in.readBoolean();
        acr = in.readUnsignedByte(); pcr = in.readUnsignedByte(); sr = in.readUnsignedByte();
        ifr = in.readUnsignedByte(); ier = in.readUnsignedByte();
        ca1Level = in.readBoolean(); cb1Level = in.readBoolean();
        irqAsserted = in.readBoolean();
        irq.set(irqAsserted);
    }

    protected abstract int readPortAInput();
    protected abstract int readPortBInput();
    /** Called after ORB/DDRB change so subclasses can drive external lines. */
    protected void portBChanged() { }
    protected void portAChanged() { }

    public int portAOut() { return (ora & ddra) | (readPortAInput() & ~ddra) & 0xFF; }
    public int portBOut() { return ((orb & ddrb) | (readPortBInput() & ~ddrb)) & 0xFF; }

    // ---- CA1 / CB1 input edges (ATN, byte-ready) ---------------------------

    /** Drive the CA1 input; an active edge (per PCR bit0) latches IFR CA1. */
    public void setCa1(boolean level) {
        boolean positive = (pcr & 0x01) != 0;
        if (level != ca1Level) {
            if (level == positive) { ifr |= I_CA1; updateIrq(); }
        }
        ca1Level = level;
    }

    public void setCb1(boolean level) {
        boolean positive = (pcr & 0x10) != 0;
        if (level != cb1Level) {
            if (level == positive) { ifr |= I_CB1; updateIrq(); }
        }
        cb1Level = level;
    }

    // ---- Register access ----------------------------------------------------

    public int read(int reg) {
        switch (reg & 0x0F) {
            case 0x0: ifr &= ~(I_CB1 | I_CB2); updateIrq(); return portBOut();
            case 0x1: ifr &= ~(I_CA1 | I_CA2); updateIrq(); return portAOut();
            case 0x2: return ddrb;
            case 0x3: return ddra;
            case 0x4: ifr &= ~I_T1; updateIrq(); return t1c & 0xFF;
            case 0x5: return (t1c >> 8) & 0xFF;
            case 0x6: return t1l & 0xFF;
            case 0x7: return (t1l >> 8) & 0xFF;
            case 0x8: ifr &= ~I_T2; updateIrq(); return t2c & 0xFF;
            case 0x9: return (t2c >> 8) & 0xFF;
            case 0xA: return sr;
            case 0xB: return acr;
            case 0xC: return pcr;
            case 0xD: return ifr | ((ifr & ier & 0x7F) != 0 ? I_IRQ : 0);
            case 0xE: return ier | 0x80;
            case 0xF: return portAOut();   // ORA no handshake
        }
        return 0;
    }

    public void write(int reg, int val) {
        val &= 0xFF;
        switch (reg & 0x0F) {
            case 0x0: orb = val; ifr &= ~(I_CB1 | I_CB2); updateIrq(); portBChanged(); break;
            case 0x1: ora = val; ifr &= ~(I_CA1 | I_CA2); updateIrq(); portAChanged(); break;
            case 0x2: ddrb = val; portBChanged(); break;
            case 0x3: ddra = val; portAChanged(); break;
            case 0x4: t1l = (t1l & 0xFF00) | val; break;
            case 0x5: t1l = (t1l & 0x00FF) | (val << 8); t1c = t1l; t1Running = true;
                      ifr &= ~I_T1; updateIrq(); break;
            case 0x6: t1l = (t1l & 0xFF00) | val; break;
            case 0x7: t1l = (t1l & 0x00FF) | (val << 8); ifr &= ~I_T1; updateIrq(); break;
            case 0x8: t2l = (t2l & 0xFF00) | val; break;
            case 0x9: t2l = (t2l & 0x00FF) | (val << 8); t2c = t2l; t2Running = true;
                      ifr &= ~I_T2; updateIrq(); break;
            case 0xA: sr = val; break;
            case 0xB: acr = val; break;
            case 0xC: pcr = val; break;
            case 0xD: ifr &= ~(val & 0x7F); updateIrq(); break;      // clear flags
            case 0xE:                                                // enable/disable
                if ((val & 0x80) != 0) ier |= (val & 0x7F);
                else ier &= ~(val & 0x7F);
                updateIrq();
                break;
            case 0xF: ora = val; portAChanged(); break;   // ORA, no handshake
        }
    }

    // ---- Clocking -----------------------------------------------------------

    /** CA2 output level (manual output modes only). Used as the 1541 SOE gate. */
    public boolean ca2Output() { return (pcr & 0x0E) == 0x0E; }

    public int dbgIfr() { return ifr; }
    public int dbgIer() { return ier; }
    public int dbgPcr() { return pcr; }
    public boolean dbgCa1() { return ca1Level; }

    public void clock(int cycles) {
        for (int i = 0; i < cycles; i++) tick();
    }

    private void tick() {
        // Timer 1
        t1c--;
        if (t1c < 0) {
            if ((acr & 0x40) != 0) {            // free-run: reload from latch
                t1c = t1l;
                ifr |= I_T1;
            } else {                            // one-shot: fire once
                t1c = 0xFFFF;
                if (t1Running) { ifr |= I_T1; t1Running = false; }
            }
            updateIrq();
        }
        // Timer 2 (timed one-shot when ACR bit5 = 0)
        if ((acr & 0x20) == 0) {
            t2c--;
            if (t2c < 0) {
                t2c = 0xFFFF;
                if (t2Running) { ifr |= I_T2; t2Running = false; updateIrq(); }
            }
        }
    }

    private void updateIrq() {
        boolean a = (ifr & ier & 0x7F) != 0;
        if (a != irqAsserted) { irqAsserted = a; irq.set(a); }
    }
}
