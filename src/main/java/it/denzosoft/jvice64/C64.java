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
    private static final int BADLINE_STALL = 40;   // CPU cycles stolen on a bad line

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
    public void setAudioTap(AudioTap t) { this.audioTap = t; updateSidAudioNeeded(); }

    /** Notified when the emulation thread dies on an unexpected error. */
    public interface CrashHandler { void crashed(Throwable t); }

    private volatile CrashHandler crashHandler;
    public void setOnCrash(CrashHandler h) { this.crashHandler = h; }
    private final Display display;
    private final KernalTrap trap;

    // Optional true 1541 drive on the serial bus.
    private IecBus iecBus;
    private Drive1541 drive;
    private boolean trueDrive;

    // Datasette (cassette read line -> CIA1 FLAG).
    private final Datasette datasette = new Datasette();
    private boolean tapeLoaded;   // a .tap is mounted -> feed the FLAG line per cycle

    private final Region region;

    // NMI line is the wired-OR of CIA2 and the RESTORE key.
    private boolean nmiCia2, nmiRestore;

    private int cycleDebt;
    private final short[] audioBuf = new short[SAMPLE_RATE];

    private volatile boolean running = true;
    private volatile boolean resetRequested;
    private volatile boolean paused;
    private volatile ProgramFile pending;
    private volatile boolean pendingAutorun;

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
        pendingAutorun = autorun;
        pending = prg;
    }

    /**
     * Mount an image on device 8 (disk/program) or insert a cartridge (.crt).
     * Inserting a cartridge resets the machine so the KERNAL boots it.
     */
    public void attach(java.io.File f) throws java.io.IOException {
        if (f != null && f.getName().toLowerCase().endsWith(".crt")) {
            Cartridge cart = Crt.load(java.nio.file.Files.readAllBytes(f.toPath()));
            mem.insertCartridge(cart.romlBanks, cart.romhBanks, cart.exromLine, cart.gameLine, cart.type);
            reset();
            return;
        }
        if (f != null && f.getName().toLowerCase().endsWith(".tap")) {
            datasette.insert(Tap.load(java.nio.file.Files.readAllBytes(f.toPath())));
            tapeLoaded = true;
            return;
        }
        trap.attach(f);
        // Also feed a D64 to the real drive so it works in true-drive mode.
        if (f != null && f.getName().toLowerCase().endsWith(".d64")) {
            driveInsert(java.nio.file.Files.readAllBytes(f.toPath()));
        }
    }

    public boolean isCartridge(java.io.File f) {
        return f != null && f.getName().toLowerCase().endsWith(".crt");
    }

    public boolean hasDisk() { return trap.hasMedia(); }

    // ---- True 1541 drive ----------------------------------------------------

    /** Attach a real 1541 (boots the given DOS ROM) on the serial bus. */
    public void attachDrive(byte[] dosRom) {
        iecBus = new IecBus();
        drive = new Drive1541(dosRom, iecBus);
        cia2.setIecBus(iecBus);
    }

    /** Enable cycle-stepped true-drive emulation (disables the KERNAL LOAD trap). */
    public void setTrueDrive(boolean on) { trueDrive = on && drive != null; }

    /** Select the SID chip model: Sid.MODEL_6581 (default) or Sid.MODEL_8580. */
    public void setSidModel(int m) { sid.setModel(m); }
    public boolean isTrueDrive() { return trueDrive; }
    public boolean hasDrive() { return drive != null; }

    /** Insert a D64 image into the real drive. */
    public void driveInsert(byte[] d64) { if (drive != null) drive.insertDisk(d64); }
    public Drive1541 drive() { return drive; }
    public IecBus iecBus() { return iecBus; }
    public Sid sid() { return sid; }

    public void requestReset() { resetRequested = true; }

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
        sid.setAudioNeeded(audioEnabled || audioTap != null);
    }
    public void stop() { running = false; }

    // ---- Snapshots ----------------------------------------------------------

    private static final int SNAP_MAGIC = 0x4A563634;   // "JV64"
    private static final int SNAP_VERSION = 2;   // v2 adds SID + true-1541 drive state
    private volatile java.io.File pendingSaveSnap, pendingLoadSnap;

    /** Queue a snapshot save/load; performed on the emulation thread next frame. */
    public void requestSaveSnapshot(java.io.File f) { pendingSaveSnap = f; }
    public void requestLoadSnapshot(java.io.File f) { pendingLoadSnap = f; }

    public void saveSnapshot(java.io.File f) throws java.io.IOException {
        try (java.io.DataOutputStream o = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(f)))) {
            o.writeInt(SNAP_MAGIC);
            o.writeInt(SNAP_VERSION);
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
        }
    }

    public void loadSnapshot(java.io.File f) throws java.io.IOException {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(f)))) {
            if (in.readInt() != SNAP_MAGIC) throw new java.io.IOException("Not a JVice64 snapshot");
            int v = in.readInt();
            if (v < 1 || v > SNAP_VERSION) throw new java.io.IOException("Unsupported snapshot version " + v);
            cpu.loadState(in);
            mem.loadState(in);
            vic.loadState(in);
            cia1.loadState(in);
            cia2.loadState(in);
            if (v >= 2) {
                sid.loadState(in);
                boolean td = in.readBoolean();
                boolean hasDrive = in.readBoolean();
                if (hasDrive) {
                    if (drive != null) drive.loadState(in);
                    else throw new java.io.IOException("Snapshot has 1541 state but no drive attached");
                }
                setTrueDrive(td);
            }
        }
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
        return pc >= 0xE5CD && pc <= 0xE5D5;
    }

    // ---- Execution ----------------------------------------------------------

    private void runFrame() {
        // Cycle-exact frame: the CPU drives the VIC (one VIC cycle per bus access),
        // so register writes take effect mid-line. The frame ends when the VIC
        // wraps its raster counter. On each new bad line the VIC steals 40 cycles
        // (CPU frozen, chips keep running).
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
                // CPU stalled (BA/AEC) for the bad-line fetch AND sprite DMA.
                int stall = (vic.isBadline() ? BADLINE_STALL : 0) + vic.spriteDmaCycles();
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
        ProgramFile p = pending;
        if (p != null && basicIdle()) {
            injectProgram(p, pendingAutorun);
            pending = null;
        }
    }

    /** One bus cycle of the chips the CPU drives: the VIC, plus the cassette
     *  read line per cycle so a tape FLAG edge lands on the exact cycle (needed
     *  by turbo/timing-exact tape loaders). Used as the CPU cycle sink. */
    private void tickCycle() {
        vic.tick();
        cia1.clockOne();                          // per-cycle: $DC04/$DC05 reads land on the exact cycle
        cia2.clockOne();
        if (tapeLoaded && datasette.clock(1)) cia1.setFlag();
    }

    /** Advance the VIC and the other chips by {@code n} cycles with the CPU frozen. */
    private void advanceChips(int n, boolean tape) {
        if (tapeLoaded) {
            for (int i = 0; i < n; i++) tickCycle();   // VIC + CIAs + datasette per cycle
        } else {
            vic.tick(n);                               // CPU is frozen here, so no mid-stall
            cia1.clock(n);                             // register reads can observe the batch
            cia2.clock(n);
        }
        sid.clock(n);
        if (trueDrive) drive.clock(n);
    }

    public void run() {
        if (audio != null) audio.open();
        long frameNanos = 1_000_000_000L / region.refreshHz;
        long next = System.nanoTime();
        try {
        while (running) {
            if (resetRequested) { resetRequested = false; reset(); }
            if (pendingSaveSnap != null) {
                java.io.File f = pendingSaveSnap; pendingSaveSnap = null;
                try { saveSnapshot(f); } catch (java.io.IOException e) { System.err.println("Save state failed: " + e.getMessage()); }
            }
            if (pendingLoadSnap != null) {
                java.io.File f = pendingLoadSnap; pendingLoadSnap = null;
                try { loadSnapshot(f); } catch (java.io.IOException e) { System.err.println("Load state failed: " + e.getMessage()); }
            }

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
            if (audio != null) audio.close();
        }
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
