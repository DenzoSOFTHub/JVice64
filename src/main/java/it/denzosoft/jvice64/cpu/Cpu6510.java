package it.denzosoft.jvice64.cpu;

import it.denzosoft.jvice64.mem.Bus;

/**
 * MOS 6510 / 6502 CPU core. Instruction-stepped: {@link #step()} executes one
 * instruction (or services a pending interrupt) and returns the number of PHI2
 * cycles consumed. Documented opcode set plus the common "illegal" opcodes used
 * by C64 software. Decimal mode is implemented.
 *
 * IRQ is level-sensitive (see {@link #setIrq}); NMI is edge-sensitive
 * (see {@link #setNmi}). The processor-port at $00/$01 is handled by the memory
 * (Bus); the CPU itself only sees a flat banked address space.
 */
public final class Cpu6510 {

    private final Bus bus;

    // Registers (unsigned).
    public int a, x, y;
    public int sp = 0xFF;
    public int pc;

    // Status flags.
    public boolean fc, fz, fi, fd, fb, fv, fn;

    // Interrupt lines.
    private int irqLine;          // bitmask of asserting sources; non-zero => IRQ asserted
    private boolean nmiState;     // current level of the (wired-OR) NMI line
    private boolean nmiPending;   // latched falling edge awaiting service

    public Cpu6510(Bus bus) {
        this.bus = bus;
    }

    // ---- Interrupt control -------------------------------------------------

    /** Assert/deassert an IRQ source. {@code mask} identifies the source bit. */
    public void setIrq(int mask, boolean asserted) {
        if (asserted) irqLine |= mask;
        else irqLine &= ~mask;
    }

    /** Drive the NMI line; a high->low transition latches an NMI. */
    public void setNmi(boolean asserted) {
        if (asserted && !nmiState) nmiPending = true;
        nmiState = asserted;
    }

    /** Pulse the SO (set-overflow) input — sets the V flag (1541 byte-ready). */
    public void setOverflow() { fv = true; }

    public boolean irqAsserted() { return irqLine != 0; }

    // ---- Reset -------------------------------------------------------------

    public void reset() {
        a = x = y = 0;
        sp = 0xFD;
        fc = fz = fd = fb = fv = fn = false;
        fi = true;
        irqLine = 0;
        nmiState = false;
        nmiPending = false;
        pc = read16(0xFFFC);
    }

    // ---- Snapshot ----------------------------------------------------------

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        o.writeByte(a); o.writeByte(x); o.writeByte(y); o.writeByte(sp);
        o.writeShort(pc); o.writeByte(getP(false));
        o.writeInt(irqLine); o.writeBoolean(nmiState); o.writeBoolean(nmiPending);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        a = in.readUnsignedByte(); x = in.readUnsignedByte();
        y = in.readUnsignedByte(); sp = in.readUnsignedByte();
        pc = in.readUnsignedShort(); setP(in.readUnsignedByte());
        irqLine = in.readInt(); nmiState = in.readBoolean(); nmiPending = in.readBoolean();
    }

    // ---- Status byte -------------------------------------------------------

    private int getP(boolean brk) {
        return (fc ? 0x01 : 0) | (fz ? 0x02 : 0) | (fi ? 0x04 : 0)
             | (fd ? 0x08 : 0) | (brk ? 0x10 : 0) | 0x20
             | (fv ? 0x40 : 0) | (fn ? 0x80 : 0);
    }

    private void setP(int p) {
        fc = (p & 0x01) != 0;
        fz = (p & 0x02) != 0;
        fi = (p & 0x04) != 0;
        fd = (p & 0x08) != 0;
        fv = (p & 0x40) != 0;
        fn = (p & 0x80) != 0;
    }

    // ---- Memory helpers ----------------------------------------------------

    /** Per-cycle sink: ticked once per bus access (and for lumped internal cycles). */
    public interface CycleSink { void tick(); }
    private CycleSink cycleSink;
    private int accesses;
    public void setCycleSink(CycleSink s) { this.cycleSink = s; }

    /**
     * The RDY/AEC side of the bus (VIC-II bad lines): consulted before every
     * cycle the CPU wants, it returns only once the CPU may have the bus. A READ
     * cycle stops as soon as BA is low, a WRITE cycle only once AEC is low (the
     * 6502 cannot stop in a write). Optional; only used together with a sink.
     */
    public interface RdyLine { void waitBus(boolean write); }
    private RdyLine rdyLine;
    public void setRdyLine(RdyLine r) { this.rdyLine = r; }

    /**
     * Execute one instruction, ticking the cycle sink cycle-by-cycle. Internal
     * cycles that come BEFORE a bus access (indexing, stack pointer, interrupt
     * entry) are ticked in place by {@link #idle()}; only the cycles that really
     * are last in the instruction (implied ops, taken branches, RTS) are lumped
     * here, so every read/write lands on its real cycle.
     */
    public int stepClocked() {
        if (cycleSink == null) return step();
        accesses = 0;
        int cyc = step();
        for (int i = accesses; i < cyc; i++) {                     // trailing internal cycles
            if (rdyLine != null) rdyLine.waitBus(false);            // (dummy reads on the real chip)
            cycleSink.tick();
        }
        return cyc;
    }

    /** An internal cycle the 6502 spends before its next access (dummy read). */
    private void idle() {
        if (cycleSink != null) {
            if (rdyLine != null) rdyLine.waitBus(false);
            cycleSink.tick(); accesses++;
        }
    }

    private int read(int addr) {
        if (cycleSink != null) {
            if (rdyLine != null) rdyLine.waitBus(false);
            cycleSink.tick(); accesses++;
        }
        return bus.read(addr & 0xFFFF) & 0xFF;
    }
    private void write(int addr, int val) {
        if (cycleSink != null) {
            if (rdyLine != null) rdyLine.waitBus(true);
            cycleSink.tick(); accesses++;
        }
        bus.write(addr & 0xFFFF, val & 0xFF);
    }

    /**
     * Read-modify-write read: reads the value and writes it straight back
     * unmodified (the 6502's dummy write cycle). This makes RMW cycle-exact and
     * reproduces the I/O quirks — e.g. {@code INC $D019} / {@code INC $DC0D}
     * acknowledge the interrupt via that dummy write of the old value.
     */
    private int rmwRead(int addr) {
        int v = read(addr);
        write(addr, v);   // dummy write-back (one extra machine cycle)
        return v;
    }

    private int read16(int addr) {
        return read(addr) | (read(addr + 1) << 8);
    }

    /** 6502 page-bug indirect read used by JMP (ind). */
    private int read16bug(int addr) {
        int lo = read(addr);
        int hi = read((addr & 0xFF00) | ((addr + 1) & 0x00FF));
        return lo | (hi << 8);
    }

    private int fetch() {
        int v = read(pc);
        pc = (pc + 1) & 0xFFFF;
        return v;
    }

    private int fetch16() {
        int lo = fetch();
        int hi = fetch();
        return lo | (hi << 8);
    }

    private void push(int v) {
        write(0x0100 | sp, v);
        sp = (sp - 1) & 0xFF;
    }

    private int pop() {
        sp = (sp + 1) & 0xFF;
        return read(0x0100 | sp);
    }

    // ---- Flag helpers ------------------------------------------------------

    private void setNZ(int v) {
        fz = (v & 0xFF) == 0;
        fn = (v & 0x80) != 0;
    }

    // page-cross tracking for the current addressing-mode resolution
    private boolean pageCrossed;

    private int addrZp()  { return fetch(); }
    private int addrZpX() { int zp = fetch(); idle(); return (zp + x) & 0xFF; }
    private int addrZpY() { int zp = fetch(); idle(); return (zp + y) & 0xFF; }
    private int addrAbs() { return fetch16(); }

    /*
     * Indexed modes come in two flavours. READ flavour (addrAbsX/addrAbsY/addrIndY):
     * the extra cycle (high-byte fix-up) only exists when the page is crossed.
     * WRITE flavour (the ...w helpers, for stores and read-modify-write): the 6502
     * always spends that cycle before the access. Either way the extra cycle comes
     * BEFORE the final read/write, so it is ticked here, not lumped at the end.
     */
    private int addrAbsX() {
        int base = fetch16();
        int a2 = (base + x) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (a2 & 0xFF00);
        if (pageCrossed) idle();
        return a2;
    }

    private int addrAbsY() {
        int base = fetch16();
        int a2 = (base + y) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (a2 & 0xFF00);
        if (pageCrossed) idle();
        return a2;
    }

    private int addrAbsXw() {
        int base = fetch16();
        int a2 = (base + x) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (a2 & 0xFF00);
        idle();
        return a2;
    }

    private int addrAbsYw() {
        int base = fetch16();
        int a2 = (base + y) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (a2 & 0xFF00);
        idle();
        return a2;
    }

    private int addrIndX() {
        int zp = fetch();
        idle();                                   // pointer + X
        zp = (zp + x) & 0xFF;
        return read(zp) | (read((zp + 1) & 0xFF) << 8);
    }

    private int addrIndY() {
        int zp = fetch();
        int base = read(zp) | (read((zp + 1) & 0xFF) << 8);
        int a2 = (base + y) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (a2 & 0xFF00);
        if (pageCrossed) idle();
        return a2;
    }

    private int addrIndYw() {
        int zp = fetch();
        int base = read(zp) | (read((zp + 1) & 0xFF) << 8);
        int a2 = (base + y) & 0xFFFF;
        pageCrossed = (base & 0xFF00) != (a2 & 0xFF00);
        idle();
        return a2;
    }

    // ---- ALU ops -----------------------------------------------------------

    private void adc(int m) {
        if (fd) {
            int al = (a & 0x0F) + (m & 0x0F) + (fc ? 1 : 0);
            int ah = (a >> 4) + (m >> 4);
            if (al > 9) { al += 6; ah++; }
            fz = ((a + m + (fc ? 1 : 0)) & 0xFF) == 0;
            fn = ((ah << 4) & 0x80) != 0;
            fv = (((ah << 4) ^ a) & 0x80) != 0 && ((a ^ m) & 0x80) == 0;
            if (ah > 9) ah += 6;
            fc = ah > 15;
            a = ((ah << 4) | (al & 0x0F)) & 0xFF;
        } else {
            int sum = a + m + (fc ? 1 : 0);
            fc = sum > 0xFF;
            fv = ((a ^ sum) & (m ^ sum) & 0x80) != 0;
            a = sum & 0xFF;
            setNZ(a);
        }
    }

    private void sbc(int m) {
        if (fd) {
            int al = (a & 0x0F) - (m & 0x0F) - (fc ? 0 : 1);
            int ah = (a >> 4) - (m >> 4);
            if ((al & 0x10) != 0) { al -= 6; ah--; }
            if ((ah & 0x10) != 0) ah -= 6;
            int bin = a - m - (fc ? 0 : 1);
            fc = (bin & 0x100) == 0;
            fz = (bin & 0xFF) == 0;
            fn = (bin & 0x80) != 0;
            fv = ((a ^ m) & (a ^ bin) & 0x80) != 0;
            a = ((ah << 4) | (al & 0x0F)) & 0xFF;
        } else {
            int diff = a - m - (fc ? 0 : 1);
            fc = (diff & 0x100) == 0;
            fv = ((a ^ m) & (a ^ diff) & 0x80) != 0;
            a = diff & 0xFF;
            setNZ(a);
        }
    }

    private void cmp(int reg, int m) {
        int t = (reg - m) & 0x1FF;
        fc = reg >= m;
        setNZ(t & 0xFF);
    }

    private int aslMem(int v) { fc = (v & 0x80) != 0; v = (v << 1) & 0xFF; setNZ(v); return v; }
    private int lsrMem(int v) { fc = (v & 0x01) != 0; v = (v >> 1) & 0xFF; setNZ(v); return v; }
    private int rolMem(int v) { int c = fc ? 1 : 0; fc = (v & 0x80) != 0; v = ((v << 1) | c) & 0xFF; setNZ(v); return v; }
    private int rorMem(int v) { int c = fc ? 0x80 : 0; fc = (v & 0x01) != 0; v = ((v >> 1) | c) & 0xFF; setNZ(v); return v; }

    private void branch(boolean cond) {
        int off = fetch();
        if (off >= 0x80) off -= 0x100;
        if (cond) {
            int npc = (pc + off) & 0xFFFF;
            extraCycles += ((pc & 0xFF00) != (npc & 0xFF00)) ? 2 : 1;
            pc = npc;
        }
    }

    private int extraCycles;

    // ---- Interrupt service -------------------------------------------------

    private int serviceNmi() {
        idle(); idle();                           // two internal cycles before the pushes
        push(pc >> 8);
        push(pc & 0xFF);
        push(getP(false));
        fi = true;
        pc = read16(0xFFFA);
        return 7;
    }

    private int serviceIrq() {
        idle(); idle();
        push(pc >> 8);
        push(pc & 0xFF);
        push(getP(false));
        fi = true;
        pc = read16(0xFFFE);
        return 7;
    }

    // ---- Main step ---------------------------------------------------------

    public long steps;
    public int step() {
        steps++;
        if (nmiPending) {
            nmiPending = false;
            return serviceNmi();
        }
        if (irqLine != 0 && !fi) {
            return serviceIrq();
        }

        pageCrossed = false;
        extraCycles = 0;
        int op = fetch();
        int addr, v;

        switch (op) {
            // ---- LDA ----
            case 0xA9: a = fetch();        setNZ(a); return 2;
            case 0xA5: a = read(addrZp());  setNZ(a); return 3;
            case 0xB5: a = read(addrZpX()); setNZ(a); return 4;
            case 0xAD: a = read(addrAbs());  setNZ(a); return 4;
            case 0xBD: a = read(addrAbsX()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0xB9: a = read(addrAbsY()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0xA1: a = read(addrIndX()); setNZ(a); return 6;
            case 0xB1: a = read(addrIndY()); setNZ(a); return 5 + (pageCrossed ? 1 : 0);
            // ---- LDX ----
            case 0xA2: x = fetch();        setNZ(x); return 2;
            case 0xA6: x = read(addrZp());  setNZ(x); return 3;
            case 0xB6: x = read(addrZpY()); setNZ(x); return 4;
            case 0xAE: x = read(addrAbs());  setNZ(x); return 4;
            case 0xBE: x = read(addrAbsY()); setNZ(x); return 4 + (pageCrossed ? 1 : 0);
            // ---- LDY ----
            case 0xA0: y = fetch();        setNZ(y); return 2;
            case 0xA4: y = read(addrZp());  setNZ(y); return 3;
            case 0xB4: y = read(addrZpX()); setNZ(y); return 4;
            case 0xAC: y = read(addrAbs());  setNZ(y); return 4;
            case 0xBC: y = read(addrAbsX()); setNZ(y); return 4 + (pageCrossed ? 1 : 0);
            // ---- STA ----
            case 0x85: write(addrZp(), a);  return 3;
            case 0x95: write(addrZpX(), a); return 4;
            case 0x8D: write(addrAbs(), a);  return 4;
            case 0x9D: write(addrAbsXw(), a); return 5;
            case 0x99: write(addrAbsYw(), a); return 5;
            case 0x81: write(addrIndX(), a); return 6;
            case 0x91: write(addrIndYw(), a); return 6;
            // ---- STX ----
            case 0x86: write(addrZp(), x);  return 3;
            case 0x96: write(addrZpY(), x); return 4;
            case 0x8E: write(addrAbs(), x);  return 4;
            // ---- STY ----
            case 0x84: write(addrZp(), y);  return 3;
            case 0x94: write(addrZpX(), y); return 4;
            case 0x8C: write(addrAbs(), y);  return 4;
            // ---- Transfers ----
            case 0xAA: x = a; setNZ(x); return 2;          // TAX
            case 0xA8: y = a; setNZ(y); return 2;          // TAY
            case 0x8A: a = x; setNZ(a); return 2;          // TXA
            case 0x98: a = y; setNZ(a); return 2;          // TYA
            case 0xBA: x = sp; setNZ(x); return 2;         // TSX
            case 0x9A: sp = x; return 2;                   // TXS
            // ---- Stack ----
            case 0x48: idle(); push(a); return 3;                  // PHA
            case 0x68: idle(); idle(); a = pop(); setNZ(a); return 4; // PLA
            case 0x08: idle(); push(getP(true)); return 3;         // PHP
            case 0x28: idle(); idle(); setP(pop()); return 4;      // PLP
            // ---- Logical ----
            case 0x29: a &= fetch();        setNZ(a); return 2;
            case 0x25: a &= read(addrZp());  setNZ(a); return 3;
            case 0x35: a &= read(addrZpX()); setNZ(a); return 4;
            case 0x2D: a &= read(addrAbs());  setNZ(a); return 4;
            case 0x3D: a &= read(addrAbsX()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0x39: a &= read(addrAbsY()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0x21: a &= read(addrIndX()); setNZ(a); return 6;
            case 0x31: a &= read(addrIndY()); setNZ(a); return 5 + (pageCrossed ? 1 : 0);

            case 0x09: a |= fetch();        setNZ(a); return 2;
            case 0x05: a |= read(addrZp());  setNZ(a); return 3;
            case 0x15: a |= read(addrZpX()); setNZ(a); return 4;
            case 0x0D: a |= read(addrAbs());  setNZ(a); return 4;
            case 0x1D: a |= read(addrAbsX()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0x19: a |= read(addrAbsY()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0x01: a |= read(addrIndX()); setNZ(a); return 6;
            case 0x11: a |= read(addrIndY()); setNZ(a); return 5 + (pageCrossed ? 1 : 0);

            case 0x49: a ^= fetch();        setNZ(a); return 2;
            case 0x45: a ^= read(addrZp());  setNZ(a); return 3;
            case 0x55: a ^= read(addrZpX()); setNZ(a); return 4;
            case 0x4D: a ^= read(addrAbs());  setNZ(a); return 4;
            case 0x5D: a ^= read(addrAbsX()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0x59: a ^= read(addrAbsY()); setNZ(a); return 4 + (pageCrossed ? 1 : 0);
            case 0x41: a ^= read(addrIndX()); setNZ(a); return 6;
            case 0x51: a ^= read(addrIndY()); setNZ(a); return 5 + (pageCrossed ? 1 : 0);
            // ---- BIT ----
            case 0x24: v = read(addrZp());  fz = (a & v) == 0; fn = (v & 0x80) != 0; fv = (v & 0x40) != 0; return 3;
            case 0x2C: v = read(addrAbs());  fz = (a & v) == 0; fn = (v & 0x80) != 0; fv = (v & 0x40) != 0; return 4;
            // ---- ADC ----
            case 0x69: adc(fetch());        return 2;
            case 0x65: adc(read(addrZp()));  return 3;
            case 0x75: adc(read(addrZpX())); return 4;
            case 0x6D: adc(read(addrAbs()));  return 4;
            case 0x7D: adc(read(addrAbsX())); return 4 + (pageCrossed ? 1 : 0);
            case 0x79: adc(read(addrAbsY())); return 4 + (pageCrossed ? 1 : 0);
            case 0x61: adc(read(addrIndX())); return 6;
            case 0x71: adc(read(addrIndY())); return 5 + (pageCrossed ? 1 : 0);
            // ---- SBC ----
            case 0xE9: sbc(fetch());        return 2;
            case 0xEB: sbc(fetch());        return 2; // illegal alias
            case 0xE5: sbc(read(addrZp()));  return 3;
            case 0xF5: sbc(read(addrZpX())); return 4;
            case 0xED: sbc(read(addrAbs()));  return 4;
            case 0xFD: sbc(read(addrAbsX())); return 4 + (pageCrossed ? 1 : 0);
            case 0xF9: sbc(read(addrAbsY())); return 4 + (pageCrossed ? 1 : 0);
            case 0xE1: sbc(read(addrIndX())); return 6;
            case 0xF1: sbc(read(addrIndY())); return 5 + (pageCrossed ? 1 : 0);
            // ---- CMP ----
            case 0xC9: cmp(a, fetch());        return 2;
            case 0xC5: cmp(a, read(addrZp()));  return 3;
            case 0xD5: cmp(a, read(addrZpX())); return 4;
            case 0xCD: cmp(a, read(addrAbs()));  return 4;
            case 0xDD: cmp(a, read(addrAbsX())); return 4 + (pageCrossed ? 1 : 0);
            case 0xD9: cmp(a, read(addrAbsY())); return 4 + (pageCrossed ? 1 : 0);
            case 0xC1: cmp(a, read(addrIndX())); return 6;
            case 0xD1: cmp(a, read(addrIndY())); return 5 + (pageCrossed ? 1 : 0);
            // ---- CPX ----
            case 0xE0: cmp(x, fetch());       return 2;
            case 0xE4: cmp(x, read(addrZp())); return 3;
            case 0xEC: cmp(x, read(addrAbs())); return 4;
            // ---- CPY ----
            case 0xC0: cmp(y, fetch());       return 2;
            case 0xC4: cmp(y, read(addrZp())); return 3;
            case 0xCC: cmp(y, read(addrAbs())); return 4;
            // ---- INC / DEC ----
            case 0xE6: addr = addrZp();  v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); setNZ(v); return 5;
            case 0xF6: addr = addrZpX(); v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); setNZ(v); return 6;
            case 0xEE: addr = addrAbs();  v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); setNZ(v); return 6;
            case 0xFE: addr = addrAbsXw(); v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); setNZ(v); return 7;
            case 0xC6: addr = addrZp();  v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); setNZ(v); return 5;
            case 0xD6: addr = addrZpX(); v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); setNZ(v); return 6;
            case 0xCE: addr = addrAbs();  v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); setNZ(v); return 6;
            case 0xDE: addr = addrAbsXw(); v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); setNZ(v); return 7;
            case 0xE8: x = (x + 1) & 0xFF; setNZ(x); return 2; // INX
            case 0xC8: y = (y + 1) & 0xFF; setNZ(y); return 2; // INY
            case 0xCA: x = (x - 1) & 0xFF; setNZ(x); return 2; // DEX
            case 0x88: y = (y - 1) & 0xFF; setNZ(y); return 2; // DEY
            // ---- Shifts (accumulator) ----
            case 0x0A: a = aslMem(a); return 2;
            case 0x4A: a = lsrMem(a); return 2;
            case 0x2A: a = rolMem(a); return 2;
            case 0x6A: a = rorMem(a); return 2;
            // ---- Shifts (memory) ----
            case 0x06: addr = addrZp();  write(addr, aslMem(rmwRead(addr))); return 5;
            case 0x16: addr = addrZpX(); write(addr, aslMem(rmwRead(addr))); return 6;
            case 0x0E: addr = addrAbs();  write(addr, aslMem(rmwRead(addr))); return 6;
            case 0x1E: addr = addrAbsXw(); write(addr, aslMem(rmwRead(addr))); return 7;
            case 0x46: addr = addrZp();  write(addr, lsrMem(rmwRead(addr))); return 5;
            case 0x56: addr = addrZpX(); write(addr, lsrMem(rmwRead(addr))); return 6;
            case 0x4E: addr = addrAbs();  write(addr, lsrMem(rmwRead(addr))); return 6;
            case 0x5E: addr = addrAbsXw(); write(addr, lsrMem(rmwRead(addr))); return 7;
            case 0x26: addr = addrZp();  write(addr, rolMem(rmwRead(addr))); return 5;
            case 0x36: addr = addrZpX(); write(addr, rolMem(rmwRead(addr))); return 6;
            case 0x2E: addr = addrAbs();  write(addr, rolMem(rmwRead(addr))); return 6;
            case 0x3E: addr = addrAbsXw(); write(addr, rolMem(rmwRead(addr))); return 7;
            case 0x66: addr = addrZp();  write(addr, rorMem(rmwRead(addr))); return 5;
            case 0x76: addr = addrZpX(); write(addr, rorMem(rmwRead(addr))); return 6;
            case 0x6E: addr = addrAbs();  write(addr, rorMem(rmwRead(addr))); return 6;
            case 0x7E: addr = addrAbsXw(); write(addr, rorMem(rmwRead(addr))); return 7;
            // ---- Jumps / calls ----
            case 0x4C: pc = fetch16(); return 3;                         // JMP abs
            case 0x6C: pc = read16bug(fetch16()); return 5;              // JMP (ind)
            case 0x20: {                                                // JSR
                // Real access order: low operand, internal cycle, push PCH, push
                // PCL, high operand. pc then points at the high operand byte,
                // which is exactly the "return address - 1" the 6502 pushes.
                int lo = fetch();
                idle();
                push(pc >> 8); push(pc & 0xFF);
                int hi = fetch();
                pc = lo | (hi << 8); return 6;
            }
            case 0x60: { idle(); idle(); int lo = pop(); int hi = pop(); pc = ((lo | (hi << 8)) + 1) & 0xFFFF; return 6; } // RTS
            case 0x40: { idle(); idle(); setP(pop()); int lo = pop(); int hi = pop(); pc = lo | (hi << 8); return 6; }     // RTI
            case 0x00: {                                                // BRK
                fetch();                                                // padding byte (really read)
                push(pc >> 8); push(pc & 0xFF);
                push(getP(true));
                fi = true;
                pc = read16(0xFFFE);
                return 7;
            }
            // ---- Branches ----
            case 0x10: branch(!fn); return 2 + extraCycles; // BPL
            case 0x30: branch(fn);  return 2 + extraCycles; // BMI
            case 0x50: branch(!fv); return 2 + extraCycles; // BVC
            case 0x70: branch(fv);  return 2 + extraCycles; // BVS
            case 0x90: branch(!fc); return 2 + extraCycles; // BCC
            case 0xB0: branch(fc);  return 2 + extraCycles; // BCS
            case 0xD0: branch(!fz); return 2 + extraCycles; // BNE
            case 0xF0: branch(fz);  return 2 + extraCycles; // BEQ
            // ---- Flags ----
            case 0x18: fc = false; return 2; // CLC
            case 0x38: fc = true;  return 2; // SEC
            case 0x58: fi = false; return 2; // CLI
            case 0x78: fi = true;  return 2; // SEI
            case 0xB8: fv = false; return 2; // CLV
            case 0xD8: fd = false; return 2; // CLD
            case 0xF8: fd = true;  return 2; // SED
            // ---- NOPs (documented + illegal) ----
            case 0xEA: return 2;
            case 0x1A: case 0x3A: case 0x5A: case 0x7A: case 0xDA: case 0xFA: return 2;
            case 0x80: case 0x82: case 0x89: case 0xC2: case 0xE2: fetch(); return 2;        // NOP #imm
            // The illegal NOPs with an operand really read it (NOP $DC0D acks the ICR).
            case 0x04: case 0x44: case 0x64: read(addrZp()); return 3;                              // NOP zp
            case 0x14: case 0x34: case 0x54: case 0x74: case 0xD4: case 0xF4: read(addrZpX()); return 4; // NOP zp,x
            case 0x0C: read(addrAbs()); return 4;                                                   // NOP abs
            case 0x1C: case 0x3C: case 0x5C: case 0x7C: case 0xDC: case 0xFC:                       // NOP abs,x
                read(addrAbsX()); return 4 + (pageCrossed ? 1 : 0);

            // ---- Illegal: LAX (LDA+LDX) ----
            case 0xA7: v = read(addrZp());  a = x = v; setNZ(v); return 3;
            case 0xB7: v = read(addrZpY()); a = x = v; setNZ(v); return 4;
            case 0xAF: v = read(addrAbs());  a = x = v; setNZ(v); return 4;
            case 0xBF: v = read(addrAbsY()); a = x = v; setNZ(v); return 4 + (pageCrossed ? 1 : 0);
            case 0xA3: v = read(addrIndX()); a = x = v; setNZ(v); return 6;
            case 0xB3: v = read(addrIndY()); a = x = v; setNZ(v); return 5 + (pageCrossed ? 1 : 0);
            // ---- Illegal: SAX (store A&X) ----
            case 0x87: write(addrZp(), a & x);  return 3;
            case 0x97: write(addrZpY(), a & x); return 4;
            case 0x8F: write(addrAbs(), a & x);  return 4;
            case 0x83: write(addrIndX(), a & x); return 6;
            // ---- Illegal: DCP (DEC + CMP) ----
            case 0xC7: addr = addrZp();  v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); return 5;
            case 0xD7: addr = addrZpX(); v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); return 6;
            case 0xCF: addr = addrAbs();  v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); return 6;
            case 0xDF: addr = addrAbsXw(); v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); return 7;
            case 0xDB: addr = addrAbsYw(); v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); return 7;
            case 0xC3: addr = addrIndX(); v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); return 8;
            case 0xD3: addr = addrIndYw(); v = (rmwRead(addr) - 1) & 0xFF; write(addr, v); cmp(a, v); return 8;
            // ---- Illegal: ISC (INC + SBC) ----
            case 0xE7: addr = addrZp();  v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); sbc(v); return 5;
            case 0xF7: addr = addrZpX(); v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); sbc(v); return 6;
            case 0xEF: addr = addrAbs();  v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); sbc(v); return 6;
            case 0xFF: addr = addrAbsXw(); v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); sbc(v); return 7;
            case 0xFB: addr = addrAbsYw(); v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); sbc(v); return 7;
            case 0xE3: addr = addrIndX(); v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); sbc(v); return 8;
            case 0xF3: addr = addrIndYw(); v = (rmwRead(addr) + 1) & 0xFF; write(addr, v); sbc(v); return 8;
            // ---- Illegal: SLO (ASL + ORA) ----
            case 0x07: addr = addrZp();  v = aslMem(rmwRead(addr)); write(addr, v); a |= v; setNZ(a); return 5;
            case 0x17: addr = addrZpX(); v = aslMem(rmwRead(addr)); write(addr, v); a |= v; setNZ(a); return 6;
            case 0x0F: addr = addrAbs();  v = aslMem(rmwRead(addr)); write(addr, v); a |= v; setNZ(a); return 6;
            case 0x1F: addr = addrAbsXw(); v = aslMem(rmwRead(addr)); write(addr, v); a |= v; setNZ(a); return 7;
            case 0x1B: addr = addrAbsYw(); v = aslMem(rmwRead(addr)); write(addr, v); a |= v; setNZ(a); return 7;
            case 0x03: addr = addrIndX(); v = aslMem(rmwRead(addr)); write(addr, v); a |= v; setNZ(a); return 8;
            case 0x13: addr = addrIndYw(); v = aslMem(rmwRead(addr)); write(addr, v); a |= v; setNZ(a); return 8;
            // ---- Illegal: RLA (ROL + AND) ----
            case 0x27: addr = addrZp();  v = rolMem(rmwRead(addr)); write(addr, v); a &= v; setNZ(a); return 5;
            case 0x37: addr = addrZpX(); v = rolMem(rmwRead(addr)); write(addr, v); a &= v; setNZ(a); return 6;
            case 0x2F: addr = addrAbs();  v = rolMem(rmwRead(addr)); write(addr, v); a &= v; setNZ(a); return 6;
            case 0x3F: addr = addrAbsXw(); v = rolMem(rmwRead(addr)); write(addr, v); a &= v; setNZ(a); return 7;
            case 0x3B: addr = addrAbsYw(); v = rolMem(rmwRead(addr)); write(addr, v); a &= v; setNZ(a); return 7;
            case 0x23: addr = addrIndX(); v = rolMem(rmwRead(addr)); write(addr, v); a &= v; setNZ(a); return 8;
            case 0x33: addr = addrIndYw(); v = rolMem(rmwRead(addr)); write(addr, v); a &= v; setNZ(a); return 8;
            // ---- Illegal: SRE (LSR + EOR) ----
            case 0x47: addr = addrZp();  v = lsrMem(rmwRead(addr)); write(addr, v); a ^= v; setNZ(a); return 5;
            case 0x57: addr = addrZpX(); v = lsrMem(rmwRead(addr)); write(addr, v); a ^= v; setNZ(a); return 6;
            case 0x4F: addr = addrAbs();  v = lsrMem(rmwRead(addr)); write(addr, v); a ^= v; setNZ(a); return 6;
            case 0x5F: addr = addrAbsXw(); v = lsrMem(rmwRead(addr)); write(addr, v); a ^= v; setNZ(a); return 7;
            case 0x5B: addr = addrAbsYw(); v = lsrMem(rmwRead(addr)); write(addr, v); a ^= v; setNZ(a); return 7;
            case 0x43: addr = addrIndX(); v = lsrMem(rmwRead(addr)); write(addr, v); a ^= v; setNZ(a); return 8;
            case 0x53: addr = addrIndYw(); v = lsrMem(rmwRead(addr)); write(addr, v); a ^= v; setNZ(a); return 8;
            // ---- Illegal: RRA (ROR + ADC) ----
            case 0x67: addr = addrZp();  v = rorMem(rmwRead(addr)); write(addr, v); adc(v); return 5;
            case 0x77: addr = addrZpX(); v = rorMem(rmwRead(addr)); write(addr, v); adc(v); return 6;
            case 0x6F: addr = addrAbs();  v = rorMem(rmwRead(addr)); write(addr, v); adc(v); return 6;
            case 0x7F: addr = addrAbsXw(); v = rorMem(rmwRead(addr)); write(addr, v); adc(v); return 7;
            case 0x7B: addr = addrAbsYw(); v = rorMem(rmwRead(addr)); write(addr, v); adc(v); return 7;
            case 0x63: addr = addrIndX(); v = rorMem(rmwRead(addr)); write(addr, v); adc(v); return 8;
            case 0x73: addr = addrIndYw(); v = rorMem(rmwRead(addr)); write(addr, v); adc(v); return 8;
            // ---- Illegal: immediate-AND family ----
            case 0x0B: case 0x2B: a &= fetch(); setNZ(a); fc = (a & 0x80) != 0; return 2;     // ANC
            case 0x4B: a &= fetch(); a = lsrMem(a); return 2;                                  // ALR
            case 0x6B: { a &= fetch(); int c = fc ? 0x80 : 0; boolean oldb6 = (a & 0x40) != 0; // ARR (approx)
                         a = (a >> 1) | c; setNZ(a);
                         fc = (a & 0x40) != 0; fv = ((a & 0x40) != 0) ^ ((a & 0x20) != 0); return 2; }
            case 0xCB: { int t = (a & x) - fetch(); fc = (t & 0x100) == 0; x = t & 0xFF; setNZ(x); return 2; } // AXS/SBX
            case 0xAB: { v = fetch(); a = x = (a | 0xEE) & v; setNZ(a); return 2; }            // LAX #imm (unstable)
            case 0x8B: { v = fetch(); a = (a | 0xEE) & x & v; setNZ(a); return 2; }            // ANE/XAA #imm (unstable)

            // ---- Illegal: store reg AND (target high byte + 1) (unstable) ----
            case 0x9F: { addr = addrAbsYw(); v = a & x & (((addr >> 8) + 1) & 0xFF); write(addr, v); return 5; } // SHA/AHX abs,y
            case 0x93: { addr = addrIndYw(); v = a & x & (((addr >> 8) + 1) & 0xFF); write(addr, v); return 6; } // SHA/AHX (zp),y
            case 0x9E: { addr = addrAbsYw(); v = x & (((addr >> 8) + 1) & 0xFF); write(addr, v); return 5; }     // SHX abs,y
            case 0x9C: { addr = addrAbsXw(); v = y & (((addr >> 8) + 1) & 0xFF); write(addr, v); return 5; }     // SHY abs,x
            case 0x9B: { addr = addrAbsYw(); sp = a & x; v = sp & (((addr >> 8) + 1) & 0xFF); write(addr, v); return 5; } // TAS/SHS abs,y
            case 0xBB: { addr = addrAbsY(); v = read(addr) & sp; a = x = sp = v; setNZ(v); return 4 + (pageCrossed ? 1 : 0); } // LAS abs,y

            default:
                // Unknown/JAM opcode: treat as 2-cycle NOP to keep running.
                return 2;
        }
    }
}
