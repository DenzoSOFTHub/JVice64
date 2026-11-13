package it.denzosoft.jvice64.vic;

import it.denzosoft.jvice64.cia.IrqLine;

/**
 * MOS 6569 (PAL) VIC-II video chip. Per-rasterline renderer covering the
 * standard graphics modes (hires/multicolor text, ECM text, hires/multicolor
 * bitmap), all eight sprites (with expansion, multicolor, priority and
 * sprite-sprite / sprite-background collisions), the border, and the raster
 * compare interrupt. Not fully cycle-exact, but register changes between
 * rasterlines (raster split effects driven from the raster IRQ) are honoured.
 */
public final class VicII {

    public static final int WIDTH = 384;
    public static final int HEIGHT = 272;

    private final int lines;            // rasterlines per frame (312 PAL / 263 NTSC)
    private final int cyclesPerLine;    // 63 PAL / 65 NTSC

    public int lines() { return lines; }
    public int cyclesPerLine() { return cyclesPerLine; }

    // fb y = rasterY - RASTER_TOP
    private static final int RASTER_TOP = 16;

    private final VicMemory mem;
    private final IrqLine irq;
    private final int[] palette;                     // 16 ARGB entries
    private final int[] framebuffer = new int[WIDTH * HEIGHT];

    private final int[] reg = new int[0x40];

    // Raster / interrupt state.
    private int rasterY;
    private int irqLatch;     // $D019 bits 0-3
    private int irqEnable;    // $D01A bits 0-3
    private boolean irqAsserted;
    private boolean frameDone;

    // Per-line work buffers.
    private final int[] lineColor = new int[320];     // baked colour for fg / fixed pixels
    private final boolean[] lineFg = new boolean[320];
    private final int[] lineBgReg = new int[320];      // -1 = use lineColor, else bg reg 0..3 ($D021+n)
    private final int[] spriteOwner = new int[WIDTH];

    public VicII(VicMemory mem, IrqLine irq, int[] palette, int lines, int cyclesPerLine) {
        this.mem = mem;
        this.irq = irq;
        this.palette = palette;
        this.lines = lines;
        this.cyclesPerLine = cyclesPerLine;
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(reg, 0);
        reg[0x11] = 0x1B;   // default control 1 (DEN, RSEL, yscroll=3)
        reg[0x16] = 0xC8;   // default control 2 (CSEL, xscroll=0) + unused high bits
        reg[0x18] = 0x14;   // screen $0400, charset $1000
        rasterY = 0;
        irqLatch = irqEnable = 0;
        irqAsserted = false;
        resetLatchedState();
    }

    /**
     * Re-derive everything the chip latches from its registers at a frame start:
     * the yscroll copy used by the bad-line test, the line cycle, the VC/RC video
     * counters and the border flip-flops. Used by {@link #reset} and after
     * {@link #loadState} (a snapshot is taken between frames, where rasterY == 0 and
     * these are legitimately at their frame-start values). Without it a restored
     * program that never writes $D011 kept the yscroll of whatever ran before the
     * load, so rc was reset on the wrong lines and every text row was garbled.
     */
    private void resetLatchedState() {
        ysmooth = reg[0x11] & 0x07;
        cycle = 0;
        vc = vcBase = rc = vmli = 0;
        idleState = true;
        allowBadLines = false;
        badLine = false;
        vbFF = true;
        mbFF = true;
        frameDone = false;
        // Sprite DMA state is not in the snapshot (taken at raster 0): start idle.
        for (int i = 0; i < 8; i++) {
            sprDma[i] = sprDisp[i] = sprFetched[i] = false;
            sprMc[i] = sprMcBase[i] = 0;
            sprExpFlop[i] = true;
        }
    }

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        for (int r : reg) o.writeByte(r);
        o.writeShort(rasterY);
        o.writeByte(irqLatch);
        o.writeByte(irqEnable);
        o.writeBoolean(irqAsserted);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        for (int i = 0; i < reg.length; i++) reg[i] = in.readUnsignedByte();
        rasterY = in.readUnsignedShort();
        irqLatch = in.readUnsignedByte();
        irqEnable = in.readUnsignedByte();
        irqAsserted = in.readBoolean();
        resetLatchedState();      // derived state is not in the snapshot: rebuild it
        irq.set(irqAsserted);     // keep the CPU's VIC IRQ input in step with the latch
    }

    public int[] getFramebuffer() { return framebuffer; }
    public int getRasterLine() { return rasterY; }

    /**
     * Whether the bad-line condition holds right now — the VIC fetches the video
     * matrix and holds the CPU off the bus (BA/AEC). This is the SAME test the VIC
     * uses for its own fetches ({@link #badLineNow}): bad lines must be enabled for
     * the frame (DEN seen set during line $30, latched in {@code allowBadLines}),
     * the line is in $30..$F7 and (raster &amp; 7) == YSCROLL. YSCROLL is the live
     * value (re-latched on every $D011 write), so FLD / line crunch / VSP still
     * create or cancel a bad line mid-line; DEN, as on the real chip, does not:
     * clearing it after line $30 keeps the bad lines (and the stall) for the rest
     * of the frame, and setting it after line $30 creates none.
     */
    public boolean isBadline() {
        return badLineNow();
    }

    /**
     * Cycles the VIC steals from the CPU for sprite DMA on the current line
     * (~2 per active sprite — the s-accesses while BA/AEC hold the CPU off the
     * bus). Combined with the bad-line stall this models sprite-heavy timing.
     */
    public int spriteDmaCycles() {
        // Same rule as the drawing: a sprite steals its s-accesses while its DMA is
        // on (from the Y compare in cycle 55/56 until MCBASE reaches 63).
        int count = 0;
        for (int i = 0; i < 8; i++) if (sprDma[i]) count++;
        return count * 2;
    }

    /** True once per frame, on the line where the raster wraps to 0. */
    public boolean isFrameComplete() { return frameDone; }
    public void clearFrameComplete() { frameDone = false; }

    // ---- Register access ----------------------------------------------------

    public int readRegister(int r) {
        r &= 0x3F;
        switch (r) {
            case 0x11: return (reg[0x11] & 0x7F) | ((rasterY & 0x100) >> 1);
            case 0x12: return rasterY & 0xFF;
            case 0x13: return reg[0x13];                                 // light pen X (no pen: 0)
            case 0x14: return reg[0x14];                                 // light pen Y
            case 0x16: return reg[0x16] | 0xC0;                          // bits 6-7 unused, read 1
            case 0x18: return reg[0x18] | 0x01;                          // bit 0 unused, reads 1
            case 0x19: return irqLatch | (irqAsserted ? 0x80 : 0) | 0x70;
            case 0x1A: return irqEnable | 0xF0;
            case 0x1E: { int v = reg[0x1E]; reg[0x1E] = 0; return v; }   // sprite-sprite collision, cleared on read
            case 0x1F: { int v = reg[0x1F]; reg[0x1F] = 0; return v; }   // sprite-bg collision, cleared on read
            default:
                if (r >= 0x2F) return 0xFF;                              // unused registers
                if (r >= 0x20) return reg[r] | 0xF0;                     // colour regs: high nibble reads 1
                return reg[r];
        }
    }

    public void writeRegister(int r, int v) {
        r &= 0x3F;
        v &= 0xFF;
        switch (r) {
            case 0x19:                                                  // acknowledge interrupts
                irqLatch &= ~(v & 0x0F);
                updateIrq();
                return;
            case 0x1A:
                irqEnable = v & 0x0F;
                updateIrq();
                return;
            case 0x13: case 0x14:                                       // read-only light-pen latches
            case 0x1E: case 0x1F:
                return;                                                 // read-only collision latches
            case 0x17: {
                // While a sprite's Y-expansion bit is clear its expansion flip-flop is set.
                reg[r] = v;
                for (int i = 0; i < 8; i++) if ((v & (1 << i)) == 0) sprExpFlop[i] = true;
                return;
            }
            case 0x11: case 0x12: {
                boolean wasBad = badLineNow();
                // The raster compare is edge-triggered on "RASTER == compare": a new
                // compare value equal to the line the beam is on raises the IRQ now,
                // not only at the start of a line. Rewriting the same value does not.
                int oldCmp = reg[0x12] | ((reg[0x11] & 0x80) << 1);
                reg[r] = v;
                int newCmp = reg[0x12] | ((reg[0x11] & 0x80) << 1);
                if (newCmp != oldCmp && newCmp == rasterY) { irqLatch |= 0x01; updateIrq(); }
                if (r == 0x11) {
                    ysmooth = v & 0x07;   // re-latch yscroll (enables mid-line bad-line tricks)
                    // VICE viciisc check_badline: DEN set at ANY cycle of line $30
                    // enables bad lines for the frame (startLine only sees cycle 0).
                    // The write lands after this cycle's tick, so cycle == 0 means the
                    // cycle in progress was the last one of the previous line.
                    int lineNow = cycle == 0 ? rasterY - 1 : rasterY;
                    if ((v & 0x10) != 0 && lineNow == 0x30) allowBadLines = true;
                    if (!wasBad && badLineNow()) badLineStarted();
                }
                return;
            }
            default:
                if (r >= 0x2F) return;                                  // unused: no storage
                reg[r] = v;
        }
    }

    private void updateIrq() {
        boolean assert_ = (irqLatch & irqEnable & 0x0F) != 0;
        if (assert_ != irqAsserted) {
            irqAsserted = assert_;
            irq.set(assert_);
        } else if (assert_) {
            irq.set(true);
        }
    }

    // ---- Per-cycle stepping (cycle-exact) -----------------------------------

    private int cycle;                       // 0..cyclesPerLine-1
    private static final int FIRST_VIS_CYCLE = 12;   // cycle that outputs fb_x 0

    // Per-line latched state (set at the start of the line).
    private boolean lineDisplay, lineHasContent;
    private int lineWinLeft, lineWinRight, lineXscroll, lineFbY, lineRow;
    private int lineTextRow, lineSub;
    private boolean vbFF = true;                   // vertical border flip-flop
    private boolean mbFF = true;                    // main (side) border flip-flop

    // VC/RC video-counter model (mirror of VICE viciisc vc/vcbase/rc/vmli). Drives
    // the char addressing so mid-line bad-line tricks (VSP) work; in steady state
    // vcBase == lineTextRow*40 and rc == lineSub, so normal frames are unchanged.
    private int vc, vcBase, rc, vmli;
    private boolean idleState = true;
    private boolean allowBadLines;
    private int ysmooth = 3;                        // reg[0x11]&7, re-latched on $D011 write
    private boolean badLine;
    // Debug guard: -Djvice64.vic.vcAssert=true asserts the counters match the
    // old rasterY-derived addressing on every char (proves byte-identical output).
    private static final boolean VC_ASSERT = Boolean.getBoolean("jvice64.vic.vcAssert");
    // Sprite sequencer state (Bauer 3.8.1 / VICE viciisc): DMA on from the Y compare
    // until MCBASE reaches 63; Y and the enable bit are only looked at when a sprite
    // starts. Not in the snapshot (snapshots are taken at raster 0).
    private final boolean[] sprDma = new boolean[8];
    private final boolean[] sprDisp = new boolean[8];
    private final boolean[] sprExpFlop = new boolean[8];
    private final boolean[] sprFetched = new boolean[8];  // data for the next line is loaded
    private final int[] sprMc = new int[8], sprMcBase = new int[8];
    private final int[] sprData = new int[8];              // 24 bits fetched at the line end
    private final int[] vmBuf = new int[40];      // video-matrix (char codes) for the line
    private final int[] colorBuf = new int[40];   // colour RAM for the line

    /**
     * Cycles with sequencer work (bit c set): 0 = line start, 13 = UpdateVc,
     * 15 = sprite MCBASE / DMA end, 54/55 = sprite DMA start, 57 = UpdateRc.
     */
    private static final long EVENT_CYCLES =
            (1L << 0) | (1L << 13) | (1L << 15) | (1L << 54) | (1L << 55) | (1L << 57);

    /**
     * Advance the VIC by one PHI2 cycle, rendering 8 pixels with the live registers.
     * Kept small on purpose: it runs on every bus cycle and must stay inlinable into
     * the CPU's cycle sink. The sequencer work of the few event cycles and the line
     * end are out of line ({@link #cycleEvent}, {@link #nextLine}).
     */
    public void tick() {
        int c = cycle;
        // (A shift by 64 wraps to bit 0: cycleEvent's switch ignores cycle 64.)
        if (((EVENT_CYCLES >>> c) & 1L) != 0) cycleEvent(c);
        renderCycle(c);
        if (!idleState && c >= 16 && c < 56) {
            // VC and VMLI count after each g-access in display state only (on every
            // line, drawn or not): an idle line (FLD gap, before the first bad line)
            // must not move vcBase on.
            vmli++;
            vc = (vc + 1) & 0x3FF;
        }
        if (++cycle >= cyclesPerLine) nextLine();
    }

    private void nextLine() {
        endLine();
        cycle = 0;
        rasterY++;
        if (rasterY >= lines) { rasterY = 0; frameDone = true; }
        if (rasterY == 0) { vcBase = 0; vc = 0; rc = 0; idleState = true; }
    }

    /**
     * Sequencer work at the cycle boundaries (0-based cycles of this class: cycle n
     * here = cycle n+1 in Bauer's numbering). Video counters as in VICE viciisc:
     * VC snap at 13, RC wrap at 57. Sprites (Bauer 3.8.1): 15 = MCBASE takes MC if the
     * expansion flip-flop is set, DMA ends at 63; 54 = flip-flop inverted for
     * Y-expanded sprites, then the DMA start check; 55 = second DMA start check. The
     * display check, MC load and s-accesses are done in {@link #endLine} (cycle 58
     * onwards on the chip).
     */
    private void cycleEvent(int c) {
        switch (c) {
            case 0:
                startLine();
                break;
            case 13:                  // UpdateVc
                badLine = badLineNow();   // live (a mid-line $D011 write may have changed ysmooth)
                vc = vcBase;
                vmli = 0;
                if (badLine) {
                    rc = 0;
                    idleState = false;
                    fetchMatrix();    // c-accesses happen on bad lines only (CPU held off the bus)
                }
                break;
            case 15:
                for (int i = 0; i < 8; i++) {
                    if (!sprExpFlop[i]) continue;
                    sprMcBase[i] = sprMc[i];
                    if (sprMcBase[i] == 63) { sprDma[i] = false; sprDisp[i] = false; }
                }
                break;
            case 54: case 55: {
                int yexp = reg[0x17];
                int enable = reg[0x15];
                int ry = rasterY & 0xFF;
                for (int i = 0; i < 8; i++) {
                    int bit = 1 << i;
                    if (c == 54 && (yexp & bit) != 0) sprExpFlop[i] = !sprExpFlop[i];
                    if (!sprDma[i] && (enable & bit) != 0 && reg[0x01 + 2 * i] == ry) {
                        sprDma[i] = true;
                        sprMcBase[i] = 0;
                        if ((yexp & bit) != 0) sprExpFlop[i] = false;
                    }
                }
                break;
            }
            case 57:                  // UpdateRc
                badLine = badLineNow();   // VSP: an aborted bad line here desyncs vcBase/rc
                if (rc == 7) { idleState = true; vcBase = vc; }
                if (!idleState || badLine) { rc = (rc + 1) & 7; idleState = false; }
                break;
            default:
                break;
        }
    }

    /**
     * A $D011 write turned the bad-line condition on in the middle of the line. The
     * sequencer enters display state at once, and if the c-access window
     * (cycles 14..54) is not over, the remaining c-accesses fetch from VC on into the
     * next VMLI slots (VICE viciisc: the fetch starts at the cycle of the write). In
     * display state that is the row at vcBase for the columns not drawn yet; in idle
     * state VC/VMLI did not move, so the row starts at column 0 of the buffer =
     * shifted right (VSP). RC is not touched (it is only cleared at cycle 14).
     * Simplification: the first 3 accesses, which on the chip read $FF because the
     * CPU still owns the bus, fetch the real data.
     */
    private void badLineStarted() {
        badLine = true;
        idleState = false;
        if (cycle <= 13 || cycle > 55) return;   // cycle 13 fetches itself / window over
        int col0 = Math.max(0, cycle - 16);       // next character drawn
        int vmBase = ((reg[0x18] >> 4) & 0x0F) << 10;
        for (int k = 0; k < 40 - col0 && vmli + k < 40; k++) {
            int a = (vc + k) & 0x3FF;
            vmBuf[vmli + k] = mem.vicRead(vmBase + a);
            colorBuf[vmli + k] = mem.colorRead(a);
        }
    }

    /** End of line (cycle 58+): MC load, display check and the s-accesses for the next line. */
    private void fetchSprites() {
        int ry = rasterY & 0xFF;
        int vmBase = ((reg[0x18] >> 4) & 0x0F) << 10;
        for (int i = 0; i < 8; i++) {
            sprFetched[i] = false;
            if (!sprDma[i]) continue;
            sprMc[i] = sprMcBase[i];
            if (reg[0x01 + 2 * i] == ry) sprDisp[i] = true;
            int pointer = mem.vicRead(vmBase + 0x3F8 + i);
            int addr = pointer * 64 + sprMc[i];
            sprData[i] = (mem.vicRead(addr) << 16) | (mem.vicRead(addr + 1) << 8)
                    | mem.vicRead(addr + 2);
            sprMc[i] = (sprMc[i] + 3) & 0x3F;
            sprFetched[i] = sprDisp[i];
        }
    }

    private boolean badLineNow() {
        return allowBadLines && rasterY >= 0x30 && rasterY <= 0xF7
                && (rasterY & 0x07) == ysmooth;
    }

    /** Advance several cycles (e.g. while the CPU is stalled on a bad line). */
    public void tick(int n) { for (int i = 0; i < n; i++) tick(); }

    private void startLine() {
        int compare = reg[0x12] | ((reg[0x11] & 0x80) << 1);
        if (rasterY == compare) { irqLatch |= 0x01; updateIrq(); }

        lineFbY = rasterY - RASTER_TOP;
        lineRow = lineFbY * WIDTH;
        boolean den = (reg[0x11] & 0x10) != 0;

        // Bad-line enable latches on during line 0x30 (iff DEN at cycle 0 here, or
        // at any later cycle of the line via the $D011 write), off after 0xF7.
        if (rasterY == 0x30) allowBadLines = den;
        if (rasterY == 0xF8) allowBadLines = false;
        badLine = badLineNow();
        if (badLine) idleState = false;           // a bad line condition starts display state

        boolean rsel = (reg[0x11] & 0x08) != 0;
        boolean csel = (reg[0x16] & 0x08) != 0;
        lineWinLeft = csel ? 32 : 39;
        lineWinRight = csel ? 352 : 343;
        lineXscroll = reg[0x16] & 0x07;

        // Vertical border flip-flop: set at the bottom compare, cleared at the top
        // compare (when DEN). Toggling RSEL near a compare line "opens" the border.
        int topCmp = rsel ? 51 : 55;
        int botCmp = rsel ? 251 : 247;
        if (rasterY == botCmp) vbFF = true;
        if (rasterY == topCmp && den) vbFF = false;
        // The display window is open while the vertical border flip-flop is clear.
        // DEN only matters for the top compare (above) and the bad-line enable at
        // line $30: clearing it later in the frame does not blank the rest of it.
        lineDisplay = !vbFF;

        // The video matrix (c-access) is fetched on bad lines (fetchMatrix, cycle 13);
        // the per-character graphics fetch (g-access) happens per cycle so mid-line
        // $D018/mode changes apply.
        int yscroll = reg[0x11] & 0x07;
        int dispY = rasterY - (48 + yscroll);
        lineTextRow = dispY >> 3;
        lineSub = dispY & 0x07;
        // Every line of the open window gets its 40 g-accesses: in display state from
        // the fetched row, in idle state (no bad line behind it: FLD gaps, lines
        // before the first / after the last text row, DEN set late) from $3FFF.
        lineHasContent = lineDisplay && lineFbY >= 0 && lineFbY < HEIGHT;
    }

    /**
     * Video-matrix + colour fetch (c-access) for the 40 characters of a text row,
     * from the video counter base (== row*40 in steady state; diverges on a
     * VSP/bad-line-abort frame, shifting the screen). Done only on bad lines, as on
     * the chip: the other 7 lines of the row reuse the buffers, so a screen/colour
     * RAM write in the middle of a row shows from the next row on.
     */
    private void fetchMatrix() {
        int vmBase = ((reg[0x18] >> 4) & 0x0F) << 10;
        for (int col = 0; col < 40; col++) {
            vmBuf[col] = mem.vicRead(vmBase + ((vcBase + col) & 0x3FF));
            colorBuf[col] = mem.colorRead((vcBase + col) & 0x3FF);
        }
    }

    /** Render the 8 pixels output during {@code c} (fetching the display char live). */
    private void renderCycle(int c) {
        if (lineFbY < 0 || lineFbY >= HEIGHT) return;
        if (c < FIRST_VIS_CYCLE || c >= FIRST_VIS_CYCLE + 48) return;
        if (lineHasContent) {
            int col = c - 16;                         // one display char per cycle 16..55
            if (col >= 0 && col < 40) renderChar(col);
        }
        int border = palette[reg[0x20] & 0x0F];      // live border colour
        boolean csel = (reg[0x16] & 0x08) != 0;       // live CSEL (38/40 columns)
        int leftCmp = csel ? 32 : 39;
        int rightCmp = csel ? 352 : 343;
        int fbXBase = (c - FIRST_VIS_CYCLE) * 8;
        for (int i = 0; i < 8; i++) {
            int fx = fbXBase + i;
            // Main border flip-flop: set at the right compare, cleared at the left
            // compare when the vertical border is open. Toggling CSEL near a compare
            // "opens" the side border.
            if (fx == rightCmp) mbFF = true;
            if (fx == leftCmp && !vbFF) mbFF = false;

            int color = border;
            if (!mbFF) {
                int px = fx - 32 - lineXscroll;
                if (px >= 0 && px < 320) {
                    int bg = lineBgReg[px];
                    color = (bg >= 0) ? palette[reg[0x21 + bg] & 0x0F]   // live background
                                      : palette[lineColor[px] & 0x0F];   // baked fg / fixed
                } else if (px < 0) {
                    // XSCROLL > 0 with 40 columns: the sequencer has no data yet,
                    // the chip shows the background colour, not the border.
                    color = palette[reg[0x21] & 0x0F];
                }
            }
            framebuffer[lineRow + fx] = color;
        }
    }

    private void endLine() {
        // Draw this line from the data fetched at the end of the previous one, then
        // fetch for the next line (the sequencer runs on every line, drawn or not).
        if (lineDisplay && lineFbY >= 0 && lineFbY < HEIGHT) {
            renderSprites(lineFbY, lineWinLeft, lineWinRight);
        }
        fetchSprites();
    }

    /** Render one display character (column {@code col}) using the LIVE registers. */
    private void renderChar(int col) {
        // Counter-derived addressing. In steady state vc==lineTextRow*40+col,
        // rc==lineSub, vmli==col, so output is byte-identical to the old path.
        // Idle state (VICE viciisc / Bauer 3.7.1): c-data reads as 0 and the g-access
        // comes from $3FFF ($39FF with ECM) in every mode.
        boolean idle = idleState;
        if (VC_ASSERT && !idle && lineTextRow >= 0 && lineTextRow <= 24
                && (vcBase != lineTextRow * 40 || rc != lineSub || vc != vcBase + col)) {
            throw new IllegalStateException("VC desync ry=" + rasterY + " col=" + col
                    + " vcBase=" + vcBase + " want " + (lineTextRow * 40)
                    + " rc=" + rc + " want " + lineSub + " vc=" + vc + " want " + (vcBase + col));
        }
        int matrixIx = vmli & 0x3F;
        int sub = rc;

        int ctrl1 = reg[0x11];
        int bg0 = reg[0x21] & 0x0F;

        int ecm = (ctrl1 >> 6) & 1;
        int bmm = (ctrl1 >> 5) & 1;
        int mcm = (reg[0x16] >> 4) & 1;
        int mode = (ecm << 2) | (bmm << 1) | mcm;

        int charBase = ((reg[0x18] >> 1) & 0x07) << 11;
        int bmpBase = ((reg[0x18] & 0x08) != 0) ? 0x2000 : 0x0000;

        int screenCode = idle ? 0 : vmBuf[matrixIx];
        int color = idle ? 0 : colorBuf[matrixIx];
        int base = col * 8;
        int idleBits = idle ? mem.vicRead(ecm != 0 ? 0x39FF : 0x3FFF) : 0;

        switch (mode) {
                case 0: {   // standard text
                    int bits = idle ? idleBits : mem.vicRead(charBase + screenCode * 8 + sub);
                    for (int p = 0; p < 8; p++) {
                        boolean on = (bits & (0x80 >> p)) != 0;
                        lineColor[base + p] = on ? color : bg0;
                        lineFg[base + p] = on;
                        lineBgReg[base + p] = on ? -1 : 0;
                    }
                    break;
                }
                case 1: {   // multicolor text
                    int bits = idle ? idleBits : mem.vicRead(charBase + screenCode * 8 + sub);
                    if ((color & 0x08) != 0) {
                        int fg = color & 0x07;
                        for (int p = 0; p < 8; p += 2) {
                            int two = (bits >> (6 - p)) & 0x03;
                            int c; boolean fgFlag; int br;
                            switch (two) {
                                case 0: c = bg0; fgFlag = false; br = 0; break;   // $D021
                                case 1: c = reg[0x22] & 0x0F; fgFlag = false; br = 1; break; // $D022
                                case 2: c = reg[0x23] & 0x0F; fgFlag = true; br = 2; break;  // $D023
                                default: c = fg; fgFlag = true; br = -1;
                            }
                            lineColor[base + p] = c; lineColor[base + p + 1] = c;
                            lineFg[base + p] = fgFlag; lineFg[base + p + 1] = fgFlag;
                            lineBgReg[base + p] = br; lineBgReg[base + p + 1] = br;
                        }
                    } else {
                        for (int p = 0; p < 8; p++) {
                            boolean on = (bits & (0x80 >> p)) != 0;
                            lineColor[base + p] = on ? (color & 0x07) : bg0;
                            lineFg[base + p] = on;
                            lineBgReg[base + p] = on ? -1 : 0;
                        }
                    }
                    break;
                }
                case 2: {   // standard bitmap
                    int bits = idle ? idleBits : mem.vicRead(bmpBase + (vc << 3) + sub);
                    int fg = (screenCode >> 4) & 0x0F;
                    int bg = screenCode & 0x0F;
                    for (int p = 0; p < 8; p++) {
                        boolean on = (bits & (0x80 >> p)) != 0;
                        lineColor[base + p] = on ? fg : bg;
                        lineFg[base + p] = on;
                        lineBgReg[base + p] = -1;          // colours from screen RAM (fixed)
                    }
                    break;
                }
                case 3: {   // multicolor bitmap
                    int bits = idle ? idleBits : mem.vicRead(bmpBase + (vc << 3) + sub);
                    int c01 = (screenCode >> 4) & 0x0F;
                    int c10 = screenCode & 0x0F;
                    int c11 = color & 0x0F;
                    for (int p = 0; p < 8; p += 2) {
                        int two = (bits >> (6 - p)) & 0x03;
                        int c; boolean fgFlag; int br;
                        switch (two) {
                            case 0: c = bg0; fgFlag = false; br = 0; break;  // $D021
                            case 1: c = c01; fgFlag = false; br = -1; break;
                            case 2: c = c10; fgFlag = true; br = -1; break;
                            default: c = c11; fgFlag = true; br = -1;
                        }
                        lineColor[base + p] = c; lineColor[base + p + 1] = c;
                        lineFg[base + p] = fgFlag; lineFg[base + p + 1] = fgFlag;
                        lineBgReg[base + p] = br; lineBgReg[base + p + 1] = br;
                    }
                    break;
                }
                case 4: {   // ECM text
                    int bg = screenCode >> 6;
                    int bgColor = reg[0x21 + bg] & 0x0F;
                    int bits = idle ? idleBits : mem.vicRead(charBase + (screenCode & 0x3F) * 8 + sub);
                    for (int p = 0; p < 8; p++) {
                        boolean on = (bits & (0x80 >> p)) != 0;
                        lineColor[base + p] = on ? color : bgColor;
                        lineFg[base + p] = on;
                        lineBgReg[base + p] = on ? -1 : bg;   // $D021 + (screencode>>6)
                    }
                    break;
                }
                default: { // invalid modes -> black
                    for (int p = 0; p < 8; p++) { lineColor[base + p] = 0; lineFg[base + p] = false; lineBgReg[base + p] = -1; }
                }
            }
        // VC/VMLI advance in tick() (display state only, drawn or not).
    }

    private void renderSprites(int fbY, int winLeftFb, int winRightFb) {
        boolean any = false;
        for (int i = 0; i < 8; i++) any |= sprFetched[i];
        if (!any) return;                             // no sprite data: nothing to draw or collide
        for (int x = 0; x < WIDTH; x++) spriteOwner[x] = -1;
        int rowBase = fbY * WIDTH;
        int xexpAll = reg[0x1D];
        int mcAll = reg[0x1C];
        int priAll = reg[0x1B];
        int msbAll = reg[0x10];
        int mc0 = reg[0x25] & 0x0F;
        int mc1 = reg[0x26] & 0x0F;

        // Draw sprite 7 first so sprite 0 wins (lowest number highest priority).
        for (int i = 7; i >= 0; i--) {
            if (!sprFetched[i]) continue;
            int data = sprData[i];

            int sx = reg[0x00 + 2 * i] | (((msbAll >> i) & 1) << 8);
            int color = reg[0x27 + i] & 0x0F;
            boolean mc = (mcAll & (1 << i)) != 0;
            boolean behind = (priAll & (1 << i)) != 0;
            boolean xexp = (xexpAll & (1 << i)) != 0;
            int pixW = xexp ? 2 : 1;

            if (mc) {
                for (int pair = 0; pair < 12; pair++) {
                    int two = (data >> (22 - pair * 2)) & 0x03;
                    if (two == 0) continue;
                    int c = (two == 1) ? mc0 : (two == 2) ? color : mc1;
                    int w = pixW * 2;
                    int startFx = sx + 8 + pair * 2 * pixW;
                    drawSpritePix(i, startFx, w, c, behind, rowBase, winLeftFb, winRightFb);
                }
            } else {
                for (int p = 0; p < 24; p++) {
                    if ((data & (0x800000 >> p)) == 0) continue;
                    int startFx = sx + 8 + p * pixW;
                    drawSpritePix(i, startFx, pixW, color, behind, rowBase, winLeftFb, winRightFb);
                }
            }
        }
    }

    private void drawSpritePix(int sprite, int startFx, int width, int color,
                               boolean behind, int rowBase, int winLeftFb, int winRightFb) {
        for (int k = 0; k < width; k++) {
            int fx = startFx + k;
            if (fx < 0 || fx >= WIDTH) continue;

            // Sprite-sprite collision. The IRQ is armed only on the 0 -> non-zero
            // edge of $D01E (as on the real VIC-II / VICE): once the game has
            // acknowledged $D019, a further colliding pixel must NOT re-arm it
            // until $D01E has been read back (reading clears it). Latching per
            // pixel produced an IRQ storm — and called updateIrq() in the
            // renderer's hottest loop.
            if (spriteOwner[fx] >= 0) {
                if (reg[0x1E] == 0) { irqLatch |= 0x04; updateIrq(); }
                reg[0x1E] |= (1 << sprite) | (1 << spriteOwner[fx]);
            } else {
                spriteOwner[fx] = sprite;
            }

            // sprite-background collision (only over the display window). The
            // foreground mask is indexed like the background renderer does it
            // (renderCycle: px = fx - 32 - lineXscroll), so XSCROLL moves both.
            int dispPx = fx - 32 - lineXscroll;
            boolean overFg = dispPx >= 0 && dispPx < 320 && lineFg[dispPx];
            if (overFg) {                       // same 0 -> non-zero edge rule as $D01E
                if (reg[0x1F] == 0) { irqLatch |= 0x02; updateIrq(); }
                reg[0x1F] |= (1 << sprite);
            }

            if (fx < winLeftFb || fx >= winRightFb) continue;   // hidden by border
            if (behind && overFg) continue;                      // behind foreground graphics
            framebuffer[rowBase + fx] = palette[color];
        }
    }
}
