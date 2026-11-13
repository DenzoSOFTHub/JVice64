package it.denzosoft.jvice64;

import it.denzosoft.jvice64.cia.Cia1;
import it.denzosoft.jvice64.cia.Cia2;
import it.denzosoft.jvice64.cpu.Cpu6510;
import it.denzosoft.jvice64.drive.Drive1541;
import it.denzosoft.jvice64.drive.IecBus;
import it.denzosoft.jvice64.io.Joystick;
import it.denzosoft.jvice64.io.Keyboard;
import it.denzosoft.jvice64.io.KernalTrap;
import it.denzosoft.jvice64.media.Cartridge;
import it.denzosoft.jvice64.media.Crt;
import it.denzosoft.jvice64.media.Datasette;
import it.denzosoft.jvice64.media.ProgramFile;
import it.denzosoft.jvice64.media.Tap;
import it.denzosoft.jvice64.mem.C64Memory;
import it.denzosoft.jvice64.sid.Sid;
import it.denzosoft.jvice64.sound.AudioOutput;
import it.denzosoft.jvice64.ui.Display;
import it.denzosoft.jvice64.vic.VicII;

import java.util.concurrent.locks.LockSupport;

/**
 * Top-level machine: wires the 6510, banked memory, VIC-II, SID and the two
 * CIAs together and runs the PAL frame loop (312 rasterlines × 63 cycles,
 * ~50 Hz). Handles the combined NMI line (CIA2 + RESTORE) and a simple
 * keyboard-buffer autostart for loaded programs.
 */
public final class C64 {

    public static final int CLOCK_HZ = 985248;     // PAL PHI2
    public static final int SAMPLE_RATE = 44100;

    private static final int IRQ_CIA1 = 0x01;
    private static final int IRQ_VIC  = 0x02;
    // Bad-line bus window in VicII's 0-based cycle numbering (one less than the
    // usual 1-based VIC-II numbering): BA goes low at 11, the c-accesses (AEC low)
    // run 14..53, the CPU gets the bus back at 54.
    private static final int BA_LOW_CYCLE = 11, AEC_LOW_CYCLE = 14, BA_HIGH_CYCLE = 54;

    // 16-colour Pepto palettes (0xAARRGGBB), one per video standard.
    private static final int[] PALETTE_PAL = {
        0xFF000000, 0xFFFFFFFF, 0xFF68372B, 0xFF70A4B2,
        0xFF6F3D86, 0xFF588D43, 0xFF352879, 0xFFB8C76F,
        0xFF6F4F25, 0xFF433900, 0xFF9A6759, 0xFF444444,
        0xFF6C6C6C, 0xFF9AD284, 0xFF6C5EB5, 0xFF959595
    };
    private static final int[] PALETTE_NTSC = {
        0xFF000000, 0xFFFFFFFF, 0xFF67372B, 0xFF70A3B1,
        0xFF6F3D86, 0xFF588C42, 0xFF342879, 0xFFB7C66E,
        0xFF6F4E25, 0xFF423800, 0xFF996659, 0xFF434343,
        0xFF6B6B6B, 0xFF9AD183, 0xFF6B5EB5, 0xFF959595
    };

    private final C64Memory mem;
    private final Cpu6510 cpu;
    private final VicII vic;
    private final Sid sid;
    private final Cia1 cia1;
    private final Cia2 cia2;
    private final Keyboard keyboard;
    private final Joystick joystick;
    private final AudioOutput audio;
    private volatile boolean audioEnabled = false;   // audio off at startup; toggled from Controls menu

    /** A sink for the raw SID sample stream (e.g. a video recorder). Fed regardless of output state. */
    public interface AudioTap { void samples(short[] buf, int count); }
    private volatile AudioTap audioTap;
    /**
     * Install/remove the tap. Never blocks: it only sets a volatile field and queues
     * the SID gate change (runOnMachine), so it may be called with a UI lock held -
     * the recorder calls it inside its own lock, which the emulation thread also
     * takes when it feeds samples through the tap.
     */
    public void setAudioTap(AudioTap t) { this.audioTap = t; updateSidAudioNeeded(); }

    /** Notified when the emulation thread dies on an unexpected error. */
    public interface CrashHandler { void crashed(Throwable t); }

    private volatile CrashHandler crashHandler;
    public void setOnCrash(CrashHandler h) { this.crashHandler = h; }

    /**
     * Notified on the EMULATION thread after every queued snapshot save/load
     * ({@link #requestSaveSnapshot}/{@link #requestLoadSnapshot}): {@code error}
     * is null on success. A UI must hop to its own thread to show anything.
     */
    public interface SnapshotListener { void done(java.io.File f, boolean save, java.io.IOException error); }
    private volatile SnapshotListener snapshotListener;
    public void setOnSnapshot(SnapshotListener l) { this.snapshotListener = l; }
    private final Display display;
    private final KernalTrap trap;

    // Optional true 1541 drive on the serial bus.
    private IecBus iecBus;
    private Drive1541 drive;
    private volatile boolean trueDrive;
    /** Told when the true drive cannot save written sectors to the host .d64. */
    private volatile java.util.function.Consumer<String> diskErrorListener;

    /** The cartridge inserted by {@link #attach} (null: none), and a CRC of its ROM (snapshot check). */
    private volatile Cartridge insertedCart;
    private long insertedCartCrc;

    // Datasette (cassette read line -> CIA1 FLAG).
    private final Datasette datasette = new Datasette();
    private volatile boolean tapeLoaded;   // a .tap is mounted -> feed the FLAG line per cycle

    private final Region region;

    // NMI line is the wired-OR of CIA2 and the RESTORE key.
    private boolean nmiCia2, nmiRestore;

    private int cycleDebt;
    private final short[] audioBuf = new short[SAMPLE_RATE];

    private volatile boolean running = true;
    private volatile boolean paused;

    /** A program waiting for BASIC to become idle; set and cleared on the emulation thread only. */
    private static final class Pending {
        final ProgramFile prg;
        final boolean autorun;
        Pending(ProgramFile prg, boolean autorun) { this.prg = prg; this.autorun = autorun; }
    }
    private Pending pending;

    /*
     * Every change the UI makes to the machine (reset, attach, load, drive and SID
     * options) is a task run by the emulation thread between two frames, in the
     * order it was requested. Before, attach() reset the CPU/VIC/CIAs from the EDT
     * in the middle of an instruction, and a load() could be injected into the
     * machine that a queued reset was about to wipe.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> machineTasks =
            new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
    private volatile Thread emuThread;   // the thread inside run(), or null

    /**
     * Run {@code r} on the emulation thread before the next frame. Runs it at once
     * when no emulation thread is running (headless harness, startup) or when
     * called from the emulation thread itself.
     */
    public void runOnMachine(Runnable r) {
        Thread t = emuThread;
        if (t == null || t == Thread.currentThread()) r.run();
        else machineTasks.add(r);
    }

    private void drainMachineTasks() {
        for (Runnable r; (r = machineTasks.poll()) != null; ) {
            // A failing request (e.g. a host file that changed under a re-insert)
            // must not stop an emulation that is itself fine.
            try { r.run(); }
            catch (RuntimeException e) {
                System.err.println("JVice64: machine request failed: " + e);
                e.printStackTrace();
            }
        }
    }

    public C64(byte[] basic, byte[] kernal, byte[] chargen,
               Keyboard keyboard, Joystick joystick, Display display, AudioOutput audio) {
        this(basic, kernal, chargen, keyboard, joystick, display, audio, Region.PAL);
    }

    public C64(byte[] basic, byte[] kernal, byte[] chargen,
               Keyboard keyboard, Joystick joystick, Display display, AudioOutput audio,
               Region region) {
        this.region = region;
        this.keyboard = keyboard;
        this.joystick = joystick;
        this.display = display;
        this.audio = audio;

        int clk = region.clockHz;
        int[] palette = (region == Region.NTSC) ? PALETTE_NTSC : PALETTE_PAL;
        mem = new C64Memory(basic, kernal, chargen);
        cpu = new Cpu6510(mem);
        vic = new VicII(mem, asserted -> cpu.setIrq(IRQ_VIC, asserted), palette,
                        region.lines, region.cyclesPerLine);
        sid = new Sid(clk, SAMPLE_RATE);
        updateSidAudioNeeded();                                  // audio off at startup -> skip the output chain
        if (joystick != null) sid.setPotY(joystick::readPotY);   // joystick 2nd fire on POTY
        cia1 = new Cia1(clk, asserted -> cpu.setIrq(IRQ_CIA1, asserted), keyboard, joystick);
        cia2 = new Cia2(clk, asserted -> { nmiCia2 = asserted; updateNmi(); });
        mem.attach(vic, sid, cia1, cia2);
        mem.setDatasette(datasette);
        cpu.setCycleSink(this::tickCycle);   // CPU drives the VIC + CIAs + datasette per bus cycle
        cpu.setRdyLine(this::waitBus);       // bad lines hold the CPU off the bus (BA/AEC)
        trap = new KernalTrap(cpu, mem);

        reset();
    }

    public void reset() {
        mem.reset();
        sid.reset();
        cia1.reset();
        cia2.reset();
        vic.reset();
        if (joystick != null) joystick.reset();
        if (trap != null) trap.reset();
        if (drive != null) drive.reset();
        if (iecBus != null) iecBus.reset();
        nmiCia2 = nmiRestore = false;
        cpu.reset();
        cycleDebt = 0;
    }

    private void updateNmi() {
        cpu.setNmi(nmiCia2 || nmiRestore);
    }

    // ---- Program loading ----------------------------------------------------

    /** Queue a program to be injected and (optionally) RUN once BASIC is idle. */
    public void load(ProgramFile prg, boolean autorun) {
        final Pending p = new Pending(prg, autorun);
        runOnMachine(() -> {
            // The program is injected at the BASIC prompt. A cartridge that never
            // returns to BASIC (a game) would keep it waiting forever, so it is
            // pulled out here; a utility cartridge that boots to BASIC stays in.
            // Mounting a disk alone (attach) never ejects anything.
            if (mem.hasCartridge() && !cartridgeReachesBasic()) {
                mem.removeCartridge();
                insertedCart = null;
                reset();
            }
            pending = p;
        });
    }

    /**
     * True when the inserted cartridge boots to the BASIC prompt, so a program
     * can be loaded and run with it in (Simons' BASIC, Epyx FastLoad, Westermann,
     * Rex Utility, WarpSpeed). False with no cartridge or any other type: an
     * autostart ({@link #load}) then removes the cartridge first.
     */
    public boolean cartridgeReachesBasic() {
        Cartridge c = insertedCart;
        if (c == null || !mem.hasCartridge()) return false;
        switch (c.type) {
            case Cartridge.TYPE_SIMONS_BASIC:
            case Cartridge.TYPE_EPYX_FASTLOAD:
            case Cartridge.TYPE_WESTERMANN:
            case Cartridge.TYPE_REX:
            case Cartridge.TYPE_WARPSPEED:
                return true;
            default:
                return false;
        }
    }

    /**
     * Mount an image on device 8 (disk/program) or insert a cartridge (.crt).
     * Inserting a cartridge resets the machine so the KERNAL boots it.
     */
    public void attach(java.io.File f) throws java.io.IOException {
        // Read and parse on the caller's thread (errors reach the caller), then
        // hand only the state change to the emulation thread.
        String lower = f == null ? "" : f.getName().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".crt")) {
            final Cartridge cart = Crt.load(java.nio.file.Files.readAllBytes(f.toPath()));
            final long crc = romCrc(cart);
            runOnMachine(() -> {
                mem.insertCartridge(cart.romlBanks, cart.romhBanks, cart.exromLine, cart.gameLine, cart.type);
                insertedCart = cart;
                insertedCartCrc = crc;
                reset();
            });
            return;
        }
        if (lower.endsWith(".tap")) {
            final Tap tap = Tap.load(java.nio.file.Files.readAllBytes(f.toPath()));
            runOnMachine(() -> { datasette.insert(tap); tapeLoaded = true; });
            return;
        }
        // Sectors the true drive wrote but has not saved yet must reach the host
        // file before it is read again (re-attaching the same image).
        flushDrive();
        final KernalTrap.Mount mount = KernalTrap.prepare(f);
        // Also feed a D64 to the real drive so it works in true-drive mode
        // (with its host file, so what the drive writes is saved there).
        final boolean isD64 = lower.endsWith(".d64");
        final byte[] d64 = isD64 ? java.nio.file.Files.readAllBytes(f.toPath()) : null;
        // A cartridge stays in: "attach disk" must work with a utility cartridge.
        // Only an autostart (load) pulls out a cartridge that never reaches BASIC.
        runOnMachine(() -> {
            KernalTrap.Mount m = mount;
            byte[] img = d64;
            // The bytes above were read on the caller's thread while the machine
            // kept running. If this image is the one already in use, the drive or
            // the trap may have written to it since: save what the drive still
            // holds, then read it again so neither side gets an older copy.
            flushDrive();
            if (isSameFile(f, trap.mountedDisk()) || (drive != null && isSameFile(f, drive.hostFile()))) {
                try {
                    KernalTrap.Mount fresh = KernalTrap.prepare(f);
                    byte[] freshImg = isD64 ? java.nio.file.Files.readAllBytes(f.toPath()) : null;
                    m = fresh;
                    img = freshImg;
                } catch (java.io.IOException | RuntimeException e) {
                    System.err.println("JVice64: cannot re-read " + f + ", using the copy read before: " + e.getMessage());
                }
            }
            trap.install(m);
            if (img != null) driveInsert(img, f);
        });
    }

    private static boolean isSameFile(java.io.File a, java.io.File b) {
        if (a == null || b == null) return false;
        try { return a.getCanonicalFile().equals(b.getCanonicalFile()); }
        catch (java.io.IOException e) { return a.getAbsoluteFile().equals(b.getAbsoluteFile()); }
    }

    /** CRC-32 over every ROM bank of a cartridge: tells two cartridges of the same type apart. */
    private static long romCrc(Cartridge c) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        for (byte[][] chips : new byte[][][]{ c.romlBanks, c.romhBanks }) {
            if (chips == null) continue;
            for (byte[] b : chips) if (b != null) crc.update(b, 0, b.length);
        }
        return crc.getValue();
    }

    /** Pull the cartridge out (if any) and reset into plain BASIC. Queued like every machine change. */
    public void detachCartridge() {
        runOnMachine(() -> {
            if (mem.hasCartridge()) { mem.removeCartridge(); insertedCart = null; reset(); }
        });
    }

    /** True while a cartridge is inserted (for enabling a "Detach cartridge" menu item). */
    public boolean hasCartridge() { return mem.hasCartridge(); }

    /** Rewind the mounted tape to its start (motor off), on the emulation thread. */
    public void rewindTape() { runOnMachine(datasette::reset); }
    public boolean hasTape() { return tapeLoaded; }

    public boolean isCartridge(java.io.File f) {
        return f != null && f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".crt");
    }

    public boolean hasDisk() { return trap.hasMedia(); }

    // ---- True 1541 drive ----------------------------------------------------

    /** Attach a real 1541 (boots the given DOS ROM) on the serial bus. */
    public void attachDrive(byte[] dosRom) {
        flushDrive();                              // a replaced drive saves what it wrote
        iecBus = new IecBus();
        drive = new Drive1541(dosRom, iecBus);
        drive.setOnWriteError(msg -> {
            java.util.function.Consumer<String> l = diskErrorListener;
            if (l != null) l.accept(msg);
        });
        cia2.setIecBus(iecBus);
    }

    /**
     * Listener for true-drive host write failures (the .d64 is locked, read-only,
     * gone, the disk is full...). Called once per failing streak, on the emulation
     * thread or the exit hook: a UI must hop to its own thread. The sectors stay
     * queued and are retried on the next disk access and on exit.
     */
    public void setOnDiskError(java.util.function.Consumer<String> l) { diskErrorListener = l; }

    /** Enable cycle-stepped true-drive emulation (disables the KERNAL LOAD trap). */
    public void setTrueDrive(boolean on) {
        runOnMachine(() -> {
            boolean now = on && drive != null;
            if (now != trueDrive) switchDriveMode(now);
        });
    }

    /**
     * Hand device 8 over between the KERNAL trap and the true drive (emulation
     * thread). They keep separate copies of the D64: save what the active side
     * wrote and reload the other side from the host file, so neither works on
     * (and later rewrites the file from) a stale copy. Leaving true-drive mode
     * also lets go of the bus lines the drive was holding (it is no longer
     * clocked, it would hold CLK/DATA forever); entering it reboots the drive
     * instead of resuming a transfer frozen at the last switch-off.
     */
    private void switchDriveMode(boolean now) {
        if (trueDrive) {
            flushDrive();
            trap.remount();
            if (iecBus != null) iecBus.setDrive(false, false);
        } else if (drive != null) {
            drive.reset();                          // flushes first
            java.io.File f = trap.mountedDisk();
            if (f != null && f.isFile()) {
                try { drive.insertDisk(java.nio.file.Files.readAllBytes(f.toPath()), f); }
                catch (java.io.IOException e) { System.err.println("True drive: cannot reload " + f + ": " + e.getMessage()); }
            }
        }
        trueDrive = now;
    }

    /** Select the SID chip model: Sid.MODEL_6581 (default) or Sid.MODEL_8580. */
    public void setSidModel(int m) { runOnMachine(() -> sid.setModel(m)); }
    public boolean isTrueDrive() { return trueDrive; }
    public boolean hasDrive() { return drive != null; }

    /** Insert a D64 image into the real drive (writes stay in memory). */
    public void driveInsert(byte[] d64) { if (drive != null) drive.insertDisk(d64); }

    /** Insert a D64 image into the real drive; sectors it writes are saved back to {@code host}. */
    public void driveInsert(byte[] d64, java.io.File host) { if (drive != null) drive.insertDisk(d64, host); }

    /**
     * Save to the host .d64 every sector the true drive has written so far.
     * Thread-safe; call it on exit after the emulation thread stopped (the drive
     * also flushes on head step, motor stop, reset, disk change, snapshot save
     * and from a JVM shutdown hook).
     */
    public void flushDrive() { Drive1541 d = drive; if (d != null) d.flush(); }
    public Drive1541 drive() { return drive; }
    public IecBus iecBus() { return iecBus; }
    public Sid sid() { return sid; }

    /** Reset the machine on the emulation thread, in order with the other queued requests. */
    public void requestReset() { runOnMachine(this::reset); }

    /** Toggle pause (freezes the emulation + silences audio); returns the new state. */
    public boolean togglePause() { paused = !paused; return paused; }
    public boolean isPaused() { return paused; }

    /** Enable/disable audio output (off at startup). Returns the new state. */
    public boolean toggleAudio() { audioEnabled = !audioEnabled; updateSidAudioNeeded(); return audioEnabled; }
    public void setAudioEnabled(boolean on) { audioEnabled = on; updateSidAudioNeeded(); }
    public boolean isAudioEnabled() { return audioEnabled; }

    /**
     * The SID only has to run its mix/filter/anti-alias chain when something
     * actually consumes the samples: the speaker, or a recording tap (F11/F12
     * records even with output muted). That chain is by far the most expensive
     * part of the emulator, and audio is off by default — skipping it roughly
     * doubles emulation speed. Oscillators/envelopes keep running either way, so
     * $D41B/$D41C stay exact.
     *
     * NOTE for headless probes: a harness that reads samples out of {@link #sid()}
     * must call {@code setAudioEnabled(true)} (or install an audio tap) first,
     * otherwise the SID ring stays empty by design.
     */
    private void updateSidAudioNeeded() {
        runOnMachine(() -> sid.setAudioNeeded(audioEnabled || audioTap != null));
    }
    public void stop() { running = false; }

    // ---- Snapshots ----------------------------------------------------------

    private static final int SNAP_MAGIC = 0x4A563634;   // "JV64"
    // v2 SID + true-1541 drive, v3 video standard, v4 cartridge RAM/timer,
    // v5 cartridge descriptor (after the region byte) + disk CRC (end of the drive blob)
    private static final int SNAP_VERSION = 5;

    /**
     * Queue a snapshot save/load: an ordinary machine task, so it runs in order
     * with the other requests (a "launch game" then "load state" is never
     * swapped) and none is lost. The result goes to the {@link SnapshotListener}.
     */
    public void requestSaveSnapshot(java.io.File f) { runOnMachine(() -> doSnapshot(f, true)); }
    public void requestLoadSnapshot(java.io.File f) { runOnMachine(() -> doSnapshot(f, false)); }

    private void doSnapshot(java.io.File f, boolean save) {
        java.io.IOException err = null;
        try {
            if (save) saveSnapshot(f);
            else loadSnapshot(f);                   // atomic: a failure leaves the machine as it was
        }
        catch (java.io.IOException e) { err = e; }
        catch (RuntimeException e) { err = new java.io.IOException(String.valueOf(e.getMessage()), e); }
        if (err != null) System.err.println((save ? "Save" : "Load") + " state failed: " + err.getMessage());
        notifySnapshot(f, save, err);
    }

    /**
     * Write the machine state. The state is serialised in memory first and then
     * written to a temporary file that replaces {@code f} only when complete, so
     * a failed save (disk full, read-only folder) never destroys an older one.
     */
    public void saveSnapshot(java.io.File f) throws java.io.IOException {
        byte[] state = stateBytes();
        try {
            Settings.writeAtomically(f, state);     // temp file + sync + rename: never a truncated state
        } catch (java.io.IOException e) {
            throw new java.io.IOException("cannot write " + f.getName() + ": " + ioReason(e), e);
        }
    }

    /**
     * Load a snapshot atomically: on any failure (bad header, truncated or
     * corrupt file, out-of-range value, 1541 state without a drive) the machine
     * is put back exactly as it was and an {@link java.io.IOException} is thrown.
     */
    public void loadSnapshot(java.io.File f) throws java.io.IOException {
        byte[] data;
        try {
            data = java.nio.file.Files.readAllBytes(f.toPath());
        } catch (java.io.IOException e) {
            throw new java.io.IOException("cannot read " + f.getName() + ": " + ioReason(e), e);
        }
        byte[] backup = stateBytes();
        // readState may pull out the cartridge (state of a cartridge-less session);
        // the backup cannot hold the ROM, so keep it to put it back on failure.
        Cartridge cartBefore = mem.hasCartridge() ? insertedCart : null;
        long cartCrcBefore = insertedCartCrc;
        try {
            readState(new java.io.DataInputStream(new java.io.ByteArrayInputStream(data)));
        } catch (java.io.IOException | RuntimeException e) {
            if (cartBefore != null && !mem.hasCartridge()) {
                mem.insertCartridge(cartBefore.romlBanks, cartBefore.romhBanks,
                                    cartBefore.exromLine, cartBefore.gameLine, cartBefore.type);
                insertedCart = cartBefore;
                insertedCartCrc = cartCrcBefore;
            }
            try {
                readState(new java.io.DataInputStream(new java.io.ByteArrayInputStream(backup)));
            } catch (java.io.IOException | RuntimeException restore) {
                e.addSuppressed(restore);   // should not happen: we just wrote it
            }
            String why = e instanceof java.io.EOFException ? "file is truncated or corrupt"
                       : e.getMessage() != null ? e.getMessage() : e.toString();
            throw new java.io.IOException(why, e);
        }
    }

    /** A readable cause: NIO exceptions often carry only the path as their message. */
    private static String ioReason(java.io.IOException e) {
        if (e instanceof java.nio.file.NoSuchFileException) return "file or folder not found";
        if (e instanceof java.nio.file.AccessDeniedException) return "permission denied";
        if (e instanceof java.nio.file.FileSystemException) {
            String r = ((java.nio.file.FileSystemException) e).getReason();
            if (r != null) return r;
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private byte[] stateBytes() throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(80 * 1024);
        java.io.DataOutputStream o = new java.io.DataOutputStream(bos);
        writeState(o);
        o.flush();
        return bos.toByteArray();
    }

    private void writeState(java.io.DataOutputStream o) throws java.io.IOException {
        o.writeInt(SNAP_MAGIC);
        o.writeInt(SNAP_VERSION);
        o.writeByte(region.ordinal());                  // v3: PAL/NTSC (raster geometry differs)
        boolean cart = mem.hasCartridge();              // v5: which cartridge the state needs
        o.writeBoolean(cart);
        if (cart) {
            Cartridge c = insertedCart;
            o.writeInt(c != null ? c.type : -1);
            o.writeLong(c != null ? insertedCartCrc : -1L);
        }
        cpu.saveState(o);
        mem.saveState(o);
        vic.saveState(o);
        cia1.saveState(o);
        cia2.saveState(o);
        sid.saveState(o);
        o.writeBoolean(trueDrive);
        boolean hasDrive = drive != null;
        o.writeBoolean(hasDrive);
        if (hasDrive) drive.saveState(o);
        mem.saveCartExtra(o);                           // v4: EasyFlash RAM + Epyx capacitor
    }

    private void readState(java.io.DataInputStream in) throws java.io.IOException {
        if (in.readInt() != SNAP_MAGIC) throw new java.io.IOException("Not a JVice64 snapshot");
        int v = in.readInt();
        if (v < 1 || v > SNAP_VERSION) throw new java.io.IOException("Unsupported snapshot version " + v);
        if (v >= 3) {
            int r = in.readUnsignedByte();
            Region[] all = Region.values();
            if (r >= all.length) throw new java.io.IOException("Snapshot has an unknown video standard (" + r + ")");
            if (all[r] != region) throw new java.io.IOException(regionMismatch(all[r].name()));
        }
        if (v >= 5) checkCartridge(in);
        cpu.loadState(in);
        mem.loadState(in);
        vic.loadState(in);
        // v1/v2 do not record the region: a raster line the current geometry does
        // not have can only come from the other standard (PAL 312 > NTSC 263 lines).
        if (vic.getRasterLine() >= region.lines)
            throw new java.io.IOException(regionMismatch(region == Region.NTSC ? "PAL" : "another"));
        cia1.loadState(in);
        cia2.loadState(in);
        if (v >= 2) {
            sid.loadState(in);
            boolean td = in.readBoolean();
            boolean hasDrive = in.readBoolean();
            if (hasDrive && drive == null)
                throw new java.io.IOException("Snapshot has 1541 state but no drive attached");
            // Trap and drive keep separate disk copies: a mode change goes through
            // the same hand-over as the menu (flush the active side, reload the
            // other from the host file) BEFORE the drive state is restored over it.
            boolean now = td && drive != null;
            if (now != trueDrive) switchDriveMode(now);
            // Re-reads BAM + ID in the DOS when the disk changed since the save.
            if (hasDrive) drive.loadState(in, v >= 5);
            if (!trueDrive && iecBus != null) iecBus.setDrive(false, false);   // drive not clocked: lines free
        }
        if (v >= 4) mem.loadCartExtra(in);
    }

    /**
     * v5 cartridge descriptor: refuse a state that needs a cartridge which is not
     * in (or another one), instead of running it on a mapper that is not there;
     * pull the cartridge out for the state of a cartridge-less session (it is put
     * back by {@link #loadSnapshot} if the load fails later).
     */
    private void checkCartridge(java.io.DataInputStream in) throws java.io.IOException {
        boolean snapCart = in.readBoolean();
        if (snapCart) {
            int type = in.readInt();
            long crc = in.readLong();
            String name = type < 0 ? "unknown type" : Cartridge.typeName(type);
            if (!mem.hasCartridge())
                throw new java.io.IOException("This state was saved with a cartridge (" + name + "): insert it first");
            Cartridge c = insertedCart;
            if (c != null && type >= 0 && (c.type != type || insertedCartCrc != crc))
                throw new java.io.IOException("This state was saved with another cartridge (" + name + ")");
        } else if (mem.hasCartridge()) {
            mem.removeCartridge();
            insertedCart = null;
        }
    }

    private String regionMismatch(String other) {
        return "Snapshot was made in " + other + " mode; this session runs " + region.name();
    }

    private void injectProgram(ProgramFile p, boolean autorun) {
        mem.loadIntoRam(p.loadAddress, p.data);
        int end = (p.loadAddress + p.data.length) & 0xFFFF;
        if (p.loadAddress == 0x0801) {
            // Fix up the BASIC program pointers so RUN/LIST work.
            poke16(0x2D, end); poke16(0x2F, end); poke16(0x31, end);
            poke16(0xAE, end);
            if (autorun) queueKeys("RUN\r");
        } else if (autorun) {
            queueKeys("SYS" + p.loadAddress + "\r");
        }
    }

    private void poke16(int addr, int val) {
        mem.pokeRam(addr, val & 0xFF);
        mem.pokeRam(addr + 1, (val >> 8) & 0xFF);
    }

    /** Stuff PETSCII characters into the KERNAL keyboard buffer ($0277, count $C6). */
    private void queueKeys(String s) {
        int n = Math.min(s.length(), 10);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int petscii = (c == '\r') ? 0x0D : Character.toUpperCase(c) & 0xFF;
            mem.pokeRam(0x0277 + i, petscii);
        }
        mem.pokeRam(0x00C6, n);
    }

    private boolean basicIdle() {
        // KERNAL main "wait for key" loop sits around $E5CD..$E5D5.
        int pc = cpu.pc;
        return pc >= 0xE5CD && pc <= 0xE5D5 && mem.kernalVisible();
    }

    // ---- Execution ----------------------------------------------------------

    private void runFrame() {
        // Cycle-exact frame: the CPU drives the VIC (one VIC cycle per bus access),
        // so register writes take effect mid-line. The frame ends when the VIC
        // wraps its raster counter. Bad lines hold the CPU inside its bus cycles
        // (waitBus, cycles 11..53); sprite DMA is still lumped at the line start.
        boolean tape = datasette.hasTape();
        vic.clearFrameComplete();
        int lastStallLine = -1;
        int guard = 0;
        int maxLines = vic.lines() + 4;

        while (!vic.isFrameComplete() && guard < maxLines * 80) {
            int line = vic.getRasterLine();
            if (line != lastStallLine) {
                lastStallLine = line;
                guard++;
                if (tape) datasette.setMotor(mem.cassetteMotorOn());
                // CPU stalled (BA/AEC) for sprite DMA.
                int stall = vic.spriteDmaCycles();
                if (stall > 0) advanceChips(stall, tape);
            }

            if (!trueDrive && trap.serviceIfTrapped()) { advanceChips(8, tape); continue; }

            int c = cpu.stepClocked();            // ticks the VIC + CIAs + datasette per cycle via the sink
            sid.clock(c);
            if (trueDrive) drive.clock(c);
        }

        // RESTORE key -> NMI edge.
        boolean r = keyboard.restorePressed();
        if (r != nmiRestore) { nmiRestore = r; updateNmi(); }

        // Autostart once BASIC reaches its idle loop.
        Pending p = pending;
        if (p != null && basicIdle()) {
            pending = null;
            injectProgram(p.prg, p.autorun);
        }
    }

    /** One bus cycle of the chips the CPU drives: the VIC, plus the cassette
     *  read line per cycle so a tape FLAG edge lands on the exact cycle (needed
     *  by turbo/timing-exact tape loaders). Used as the CPU cycle sink. */
    private void tickCycle() {
        vic.tick();
        trackVicCycle();
        cia1.clockOne();                          // per-cycle: $DC04/$DC05 reads land on the exact cycle
        cia2.clockOne();
        mem.clockCartridge(1);                    // real-cycle time base (Epyx FastLoad capacitor)
        if (tapeLoaded && datasette.clock(1)) cia1.setFlag();
    }

    /** Advance the VIC and the other chips by {@code n} cycles with the CPU frozen. */
    private void advanceChips(int n, boolean tape) {
        if (tapeLoaded) {
            for (int i = 0; i < n; i++) tickCycle();   // VIC + CIAs + datasette per cycle
        } else {
            for (int i = 0; i < n; i++) { vic.tick(); trackVicCycle(); }
            cia1.clock(n);                             // CPU is frozen here, so no mid-stall
            cia2.clock(n);                             // register reads can observe the batch
            mem.clockCartridge(n);                     // (the tape branch counts it in tickCycle)
        }
        sid.clock(n);
        if (trueDrive) drive.clock(n);
        cpu.sampleInterrupts();   // a CPU halted by RDY keeps sampling IRQ/NMI
    }

    /*
     * Mirror of the VIC's position inside the raster line (= VicII's private
     * cycle counter: cycles already run on the current line). Updated after
     * every VIC tick; it resynchronises itself on every line change, so after a
     * reset/snapshot load it can be off for at most the rest of one line.
     */
    private int vicCycle, vicLine = -1;

    private void trackVicCycle() {
        int ry = vic.getRasterLine();
        if (ry != vicLine) { vicLine = ry; vicCycle = 0; }
        else vicCycle++;
    }

    /**
     * RDY/AEC for the CPU (called before each of its bus cycles). On a bad line
     * BA goes low at cycle 11: a READ cycle from then on waits, a WRITE cycle can
     * still complete until the c-accesses take the bus at 14 (the 6502 never
     * stops in a write). The CPU resumes at cycle 54 — 40 to 43 cycles lost,
     * depending on where the instruction was, as on the real machine. The
     * bad-line test is the VIC's own (VicII.badLineNow): YSCROLL is live, so a bad
     * line created or cancelled mid-line (FLD, line crunch) stalls or not
     * accordingly, while DEN is the value latched during raster line $30. An IRQ raised on a bad line
     * is therefore serviced after the fetch, not before it.
     */
    private void waitBus(boolean write) {
        int c = vicCycle;
        if (c < (write ? AEC_LOW_CYCLE : BA_LOW_CYCLE) || c >= BA_HIGH_CYCLE) return;
        if (!vic.isBadline()) return;
        advanceChips(BA_HIGH_CYCLE - c, datasette.hasTape());
    }

    public void run() {
        // First: from now on requests are queued for this thread, also while the
        // audio line opens (that can take a while) - none runs on the caller's.
        emuThread = Thread.currentThread();
        // The real frame period of the emulated clock (PAL 19656 cycles at 985248 Hz
        // = 50.125 Hz), not the nominal 50/60 Hz: otherwise PAL runs 0.25% slow and
        // NTSC 0.29% fast, and the speed changes when the audio clock takes over.
        long frameNanos = region.frameNanos();
        try {
        if (audio != null) audio.open();      // inside the try: a failure clears emuThread below
        long next = System.nanoTime();
        while (running) {
            drainMachineTasks();               // reset / attach / load / options / snapshots, in request order

            // Start/stop the sound line with the enabled+running state. A started but
            // unfed line under-runs and motorboats ("galloping" hum), so while audio
            // is off (or paused) we keep the line stopped + flushed = clean silence.
            boolean audioLive = audio != null && audioEnabled && !paused && audio.isActive();
            if (audio != null) { if (audioLive) audio.start(); else audio.stop(); }

            if (!paused) {
                runFrame();
                drainAudio();
            }
            if (display != null) display.updateFrame(vic.getFramebuffer());

            // Pacing. When audio output is live it blocks inside drainAudio() until
            // the sound card accepts the frame's samples, so the audio clock already
            // paces us — AND, crucially, we must NOT also floor the frame to one
            // frame-period here: that floor is one-sided (a hiccup can drain the line
            // buffer but a fast frame can never refill it), so the cushion erodes and
            // the line under-runs ("audio comes and goes"). Skipping the floor lets a
            // fast frame run ahead and refill the buffer after jitter; the blocking
            // write caps us back to real time once it is full. With audio off/muted/
            // paused (or no device) fall back to wall-clock frame pacing.
            if (audioLive) {
                next = System.nanoTime();        // audio is the pacer; keep 'next' fresh
            } else {
                next += frameNanos;
                long sleep = next - System.nanoTime();
                if (sleep > 0) LockSupport.parkNanos(sleep);
                else next = System.nanoTime();   // running behind; resync
            }
        }
        } catch (Throwable t) {
            // Without this the thread would die silently and the window would just
            // freeze — indistinguishable from the emulated program hanging. Report
            // it, then stop cleanly so the UI can say what happened.
            running = false;
            System.err.println("JVice64: emulation stopped on an unexpected error "
                    + "(PC=$" + String.format("%04X", cpu.pc) + ", raster=" + vic.getRasterLine() + ")");
            t.printStackTrace();
            CrashHandler h = crashHandler;
            if (h != null) {
                try { h.crashed(t); } catch (Throwable ignored) { /* never mask the original */ }
            }
        } finally {
            emuThread = null;                  // later requests run on the caller's thread
            if (audio != null) audio.close();
        }
    }

    private void notifySnapshot(java.io.File f, boolean save, java.io.IOException err) {
        SnapshotListener l = snapshotListener;
        if (l == null) return;
        try { l.done(f, save, err); }
        catch (RuntimeException e) { System.err.println("Snapshot listener failed: " + e); }
    }

    private void drainAudio() {
        if (audio == null) return;
        int n = sid.available();
        if (n > 0) {
            int got = sid.read(audioBuf, 0, Math.min(n, audioBuf.length));
            AudioTap t = audioTap;                     // capture the full SID stream (e.g. video recording),
            if (t != null) t.samples(audioBuf, got);   // independent of whether speaker output is enabled
            // Always drain the SID ring (avoids backpressure); only feed the line when enabled.
            if (audioEnabled) audio.write(audioBuf, got);
        }
    }

    /** Run a single PAL frame (for headless harnesses / tests). */
    public void stepFrame() { runFrame(); }

    /** Step N main-CPU instructions with full lockstep (debug; skips VIC). */
    public void debugStep(int instrs) {
        for (int i = 0; i < instrs; i++) {
            // stepClocked() drives the cycle sink, which already advances the VIC
            // and both CIAs per cycle — clocking them again here would double-count.
            int c = cpu.stepClocked();
            sid.clock(c);
            if (trueDrive) drive.clock(c);
        }
    }

    // Accessors for tests / headless harness.
    public C64Memory memory() { return mem; }
    public Cpu6510 cpu() { return cpu; }
    public VicII vic() { return vic; }
}
