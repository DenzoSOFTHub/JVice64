package it.denzosoft.jvice64.drive;

import it.denzosoft.jvice64.cpu.Cpu6510;
import it.denzosoft.jvice64.mem.Bus;

/**
 * A Commodore 1541 disk drive: a 6502 running the native DOS ROM with 2KB RAM
 * and two 6522 VIAs (VIA1 = serial IEC at $1800, VIA2 = disk controller at
 * $1C00). The drive shares the {@link IecBus} with the C64 and is clocked in
 * lockstep with the main CPU.
 *
 * Memory map: $0000-$07FF RAM (mirrored to $17FF), $1800-$1BFF VIA1,
 * $1C00-$1FFF VIA2, $8000-$FFFF = 16KB DOS ROM (mirrored).
 */
public final class Drive1541 {

    private static final int IRQ_VIA1 = 0x01, IRQ_VIA2 = 0x02;

    private final byte[] ram = new byte[0x0800];
    private final byte[] rom;                 // 16KB DOS
    private final Cpu6510 cpu;
    private final DriveIecVia via1;
    private final DriveDiskVia via2;
    private final Gcr gcr = new Gcr();
    private final IecBus bus;

    private int cycleDebt;
    private int byteTimer;
    private static final int BYTE_CYCLES = 30;   // ~1 GCR byte per 30 cycles @1MHz

    public Drive1541(byte[] dosRom, IecBus bus) {
        this.rom = dosRom;
        this.bus = bus;
        cpu = new Cpu6510(new DriveBus());
        via1 = new DriveIecVia(asserted -> cpu.setIrq(IRQ_VIA1, asserted), bus);
        via2 = new DriveDiskVia(asserted -> cpu.setIrq(IRQ_VIA2, asserted), gcr);
        reset();
    }

    public void reset() {
        via1.reset();
        via2.reset();
        cpu.reset();
        cycleDebt = 0;
    }

    /**
     * Insert (or swap) a disk. The DOS keeps the BAM and disk ID in its RAM and
     * only re-reads them after a write-protect transition, so a swap is signalled
     * on that line; without it a different disk gives 29,DISK ID MISMATCH.
     */
    public void insertDisk(byte[] d64) {
        gcr.insert(d64);
        via2.diskChanged();
    }

    /** Run the drive for {@code cycles} PHI2 cycles (carrying any overshoot). */
    public void clock(int cycles) {
        cycleDebt += cycles;
        while (cycleDebt > 0) {
            via1.update();          // sample ATN + drive IEC (incl. auto-ack)
            int c = cpu.step();
            via1.clock(c);
            via2.clock(c);
            // Advance the GCR head at the byte rate; byte-ready pulses 6502 SO.
            byteTimer += c;
            while (byteTimer >= BYTE_CYCLES) {
                byteTimer -= BYTE_CYCLES;
                // Head always advances (disk spins). Byte-ready (SO) fires only for
                // non-sync bytes when SOE (CA2) is on — matching the real bit counter
                // being held in reset over a sync mark, which keeps post-sync bytes aligned.
                if (via2.headByte() && via2.ca2Output() && !via2.sync()) cpu.setOverflow();
            }
            cycleDebt -= c;
        }
    }

    public Cpu6510 cpu() { return cpu; }
    public int peekRam(int addr) { return ram[addr & 0x07FF] & 0xFF; }
    public DriveIecVia via1() { return via1; }
    public Gcr gcr() { return gcr; }
    public DriveDiskVia via2() { return via2; }

    // ---- Snapshot ----------------------------------------------------------

    /**
     * Serialize the entire drive state (RAM, 6502 CPU, both VIAs, GCR head and
     * the drive-level cycle counters) into a self-contained blob. The mounted
     * disk image (and the DOS ROM) is NOT serialized and must stay attached.
     */
    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        for (int i = 0; i < ram.length; i++) o.writeByte(ram[i] & 0xFF);
        cpu.saveState(o);
        via1.saveState(o);
        via2.saveState(o);
        gcr.saveState(o);
        o.writeInt(cycleDebt);
        o.writeInt(byteTimer);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < ram.length; i++) ram[i] = (byte) in.readUnsignedByte();
        cpu.loadState(in);
        via1.loadState(in);
        via2.loadState(in);
        gcr.loadState(in);
        cycleDebt = in.readInt();
        byteTimer = in.readInt();
    }

    private final class DriveBus implements Bus {
        @Override public int read(int address) {
            int a = address & 0xFFFF;
            if (a >= 0x8000) return rom[a & 0x3FFF] & 0xFF;
            if ((a & 0x1C00) == 0x1C00) return via2.read(a & 0x0F);
            if ((a & 0x1C00) == 0x1800) return via1.read(a & 0x0F);
            return ram[a & 0x07FF] & 0xFF;
        }
        @Override public void write(int address, int value) {
            int a = address & 0xFFFF;
            int v = value & 0xFF;
            if (a >= 0x8000) return;                       // ROM
            if ((a & 0x1C00) == 0x1C00) { via2.write(a & 0x0F, v); return; }
            if ((a & 0x1C00) == 0x1800) { via1.write(a & 0x0F, v); return; }
            ram[a & 0x07FF] = (byte) v;
        }
    }
}
