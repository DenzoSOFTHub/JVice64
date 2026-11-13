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
    /** Host .d64 the inserted image came from (written sectors go back there), or null. */
    private java.io.File hostFile;
    /** The last host write failed (report once per failing streak, not on every retry). */
    private boolean persistFailing;
    /** Told (once per failing streak) when written sectors cannot be saved to the host file. */
    private volatile java.util.function.Consumer<String> onWriteError;

    /** Live drives, flushed by one JVM shutdown hook so a quit right after a write loses nothing. */
    private static final java.util.Set<Drive1541> LIVE = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Drive1541, Boolean>()));
    static {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Drive1541[] all;
                synchronized (LIVE) { all = LIVE.toArray(new Drive1541[0]); }
                for (Drive1541 d : all) {
                    try { d.flush(); } catch (RuntimeException e) { e.printStackTrace(); }
                }
            }, "jvice-1541-flush"));
        } catch (RuntimeException ignored) { /* JVM already shutting down / not allowed */ }
    }
    private static final int BYTE_CYCLES = 30;   // ~1 GCR byte per 30 cycles @1MHz

    /**
     * @throws IllegalArgumentException if {@code dosRom} is not exactly 16 KB: a
     *         short ROM would throw out of every ROM read (first in the reset below)
     */
    public Drive1541(byte[] dosRom, IecBus bus) {
        if (dosRom == null || dosRom.length != 0x4000) {
            throw new IllegalArgumentException("1541 DOS ROM must be 16384 bytes, got "
                    + (dosRom == null ? "none" : dosRom.length + ""));
        }
        this.rom = dosRom;
        this.bus = bus;
        cpu = new Cpu6510(new DriveBus());
        via1 = new DriveIecVia(asserted -> cpu.setIrq(IRQ_VIA1, asserted), bus);
        via2 = new DriveDiskVia(asserted -> cpu.setIrq(IRQ_VIA2, asserted), gcr);
        via2.setWriteProtected(true);                  // no disk
        gcr.setCommitListener(this::persist);          // head left a written track
        via2.setOnMotorOff(this::flush);               // DOS idle: decode + save what it wrote
        via2.setOnWriteEnd(this::flush);               // a block was written: host file up to date at once
        LIVE.add(this);
        reset();
    }

    public void reset() {
        flush();                // a reset must not lose sectors written but not yet decoded
        via1.reset();
        via2.reset();
        cpu.reset();
        cycleDebt = 0;
    }

    /**
     * Insert (or swap) a disk with no host file: writes change only the
     * in-memory image. See {@link #insertDisk(byte[], java.io.File)}.
     */
    public void insertDisk(byte[] d64) { insertDisk(d64, null); }

    /**
     * Insert (or swap) a disk. The DOS keeps the BAM and disk ID in its RAM and
     * only re-reads them after a write-protect transition, so a swap is signalled
     * on that line; without it a different disk gives 29,DISK ID MISMATCH.
     *
     * <p>Sectors the DOS writes are decoded back into {@code d64} (updated in
     * place) and, when {@code host} is given, written to that file: when the
     * head leaves a written track, when the motor stops, on {@link #flush()},
     * reset and the next insert. The previous disk is flushed first. A host file
     * that exists but is read-only inserts the disk write-protected.</p>
     */
    public synchronized void insertDisk(byte[] d64, java.io.File host) {
        flush();
        int lost = gcr.pendingBlocks();                // the flush could not write them
        if (lost > 0) report(lost + " written sector(s) could not be saved to " + hostFile
                             + " before the disk was changed; they are lost");
        gcr.insert(d64);
        hostFile = host;
        persistFailing = false;                        // a new file: report its first failure again
        via2.setWriteProtected(d64 == null || (host != null && host.exists() && !host.canWrite()));
        via2.diskChanged();
    }

    /** Host file of the inserted disk (null: none / in-memory only). */
    public synchronized java.io.File hostFile() { return hostFile; }

    /**
     * Decode every track written since the last flush back into the image and
     * write the changed sectors to the host file. Safe from any thread (e.g. on
     * exit after the emulation thread stopped). Sectors whose GCR does not decode
     * cleanly keep their old content.
     */
    public synchronized void flush() {
        gcr.commit();
        persist();
    }

    /**
     * Write the sectors decoded so far to the host file (only those, in place).
     * A failed write keeps them queued: the next flush (head step, motor off,
     * block written, exit hook) tries again, so a transient failure (file locked
     * by a sync client, share hiccup) loses nothing. A host file that vanished
     * or got shorter is written whole, never re-created as a sparse fragment.
     */
    private synchronized void persist() {
        java.util.Map<Integer, byte[]> ch = gcr.takeChanged();
        if (ch.isEmpty() || hostFile == null) return;   // nothing new / in-memory disk
        try {
            byte[] whole = gcr.imageCopy();
            boolean rewrite = whole != null && (!hostFile.isFile() || hostFile.length() < whole.length);
            try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(hostFile, "rw")) {
                if (rewrite) {
                    f.seek(0);
                    f.write(whole);
                } else {
                    for (java.util.Map.Entry<Integer, byte[]> e : ch.entrySet()) {
                        f.seek(e.getKey() * 256L);
                        f.write(e.getValue());
                    }
                }
            }
            if (persistFailing) {
                persistFailing = false;
                System.err.println("1541: written sectors saved to " + hostFile + " again");
            }
        } catch (java.io.IOException | RuntimeException e) {
            gcr.requeue(ch.keySet());                    // retried by the next flush
            if (!persistFailing) {
                persistFailing = true;
                report("could not save written sectors to " + hostFile + ": " + e.getMessage()
                       + " (kept in memory, retried on the next disk access and on exit)");
            }
        }
    }

    private void report(String msg) {
        System.err.println("1541: " + msg);
        java.util.function.Consumer<String> l = onWriteError;
        if (l != null) {
            try { l.accept(msg); } catch (RuntimeException ignored) { /* a UI bug must not stop the drive */ }
        }
    }

    /** Listener for host-file write failures (called on the emulation or exit thread). */
    public void setOnWriteError(java.util.function.Consumer<String> l) { onWriteError = l; }

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
     * Written tracks are flushed first, so the host file holds every sector the
     * DOS wrote up to the snapshot. The disk content itself is not part of a
     * snapshot; a CRC-32 of it is (at the end), see {@link #loadState(java.io.DataInputStream, boolean)}.
     */
    public synchronized void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        flush();
        for (int i = 0; i < ram.length; i++) o.writeByte(ram[i] & 0xFF);
        cpu.saveState(o);
        via1.saveState(o);
        via2.saveState(o);
        gcr.saveState(o);
        o.writeInt(cycleDebt);
        o.writeInt(byteTimer);
        o.writeLong(gcr.imageCrc());                   // snapshot v5
    }

    /**
     * Restore a blob written by {@link #saveState}; {@code withImageCrc} is false
     * for snapshots older than v5, which end before the CRC.
     *
     * <p>The drive RAM holds the DOS's copy of the BAM, valid for the disk as it
     * was at save time. If the disk changed since (a later SAVE, another image),
     * that copy is stale and the next write would allocate blocks already in use
     * (cross-linked files). So the DOS is made to re-read BAM and ID, exactly as on
     * a disk swap, unless the CRC proves the disk is unchanged. Old snapshots have
     * no CRC: always re-read.</p>
     */
    public synchronized void loadState(java.io.DataInputStream in, boolean withImageCrc) throws java.io.IOException {
        flush();                                       // the live session's sectors reach the host first
        for (int i = 0; i < ram.length; i++) ram[i] = (byte) in.readUnsignedByte();
        cpu.loadState(in);
        via1.loadState(in);
        via2.loadState(in);
        gcr.loadState(in);
        cycleDebt = in.readInt();
        byteTimer = in.readInt();
        long savedCrc = withImageCrc ? in.readLong() : 0;
        if (!withImageCrc || savedCrc != gcr.imageCrc()) via2.diskChanged();
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
