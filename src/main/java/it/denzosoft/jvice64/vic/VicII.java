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
     * Whether the current rasterline is a "bad line" — the VIC fetches the video
     * matrix and stalls the CPU (AEC). True when DEN is set, the line is in
     * 0x30..0xF7 and (raster &amp; 7) == YSCROLL.
     */
    public boolean isBadline() {
        if ((reg[0x11] & 0x10) == 0) return false;          // DEN clear
        return rasterY >= 0x30 && rasterY <= 0xF7
                && (rasterY & 0x07) == (reg[0x11] & 0x07);
    }

    /**
     * Cycles the VIC steals from the CPU for sprite DMA on the current line
     * (~2 per active sprite — the s-accesses while BA/AEC hold the CPU off the
     * bus). Combined with the bad-line stall this models sprite-heavy timing.
     */
    public int spriteDmaCycles() {
        int enable = reg[0x15];
        if (enable == 0) return 0;
        int yexpAll = reg[0x17];
        int count = 0;
        for (int i = 0; i < 8; i++) {
            if ((enable & (1 << i)) == 0) continue;
            int sy = reg[0x01 + 2 * i];
            int height = ((yexpAll >> i) & 1) != 0 ? 42 : 21;
            if (rasterY >= sy && rasterY < sy + height) count++;
        }
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
            case 0x13: return reg[0x13];
            case 0x14: return reg[0x14];
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
            case 0x1E: case 0x1F:
                return;                                                 // read-only collision latches
            case 0x11: case 0x12: {
                // The raster compare is edge-triggered on "RASTER == compare": a new
                // compare value equal to the line the beam is on raises the IRQ now,
                // not only at the start of a line. Rewriting the same value does not.
                int oldCmp = reg[0x12] | ((reg[0x11] & 0x80) << 1);
                reg[r] = v;
                int newCmp = reg[0x12] | ((reg[0x11] & 0x80) << 1);
                if (newCmp != oldCmp && newCmp == rasterY) { irqLatch |= 0x01; updateIrq(); }
                if (r == 0x11) ysmooth = v & 0x07;   // re-latch yscroll (enables mid-line bad-line tricks)
                return;
            }
            default:
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
    private final int[] vmBuf = new int[40];      // video-matrix (char codes) for the line
    private final int[] colorBuf = new int[40];   // colour RAM for the line

    /** Advance the VIC by one PHI2 cycle, rendering 8 pixels with the live registers. */
    public void tick() {
        if (cycle == 0) startLine();
        stepCounters(cycle);          // VC/RC update (runs on every line, even off-screen)
        renderCycle(cycle);
        cycle++;
        if (cycle >= cyclesPerLine) {
            endLine();
            cycle = 0;
            rasterY++;
            if (rasterY >= lines) { rasterY = 0; frameDone = true; }
            if (rasterY == 0) { vcBase = 0; vc = 0; rc = 0; idleState = true; }
        }
    }

    /** VC/RC counter updates at the VICE cycle boundaries (vc snap at 13, rc wrap at 57). */
    private void stepCounters(int c) {
        if (c == 13) {                // UpdateVc
            badLine = badLineNow();   // live (a mid-line $D011 write may have changed ysmooth)
            vc = vcBase;
            vmli = 0;
            if (badLine) rc = 0;
        } else if (c == 57) {         // UpdateRc
            badLine = badLineNow();   // VSP: an aborted bad line here desyncs vcBase/rc
            if (rc == 7) { idleState = true; vcBase = vc; }
            if (!idleState || badLine) { rc = (rc + 1) & 7; idleState = false; }
        }
        // vc/vmli advance per drawn char happens in renderChar (cycle 16..55).
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

        // Bad-line enable latches on at line 0x30 (iff DEN), off after 0xF7.
        if (rasterY == 0x30) allowBadLines = den;
        if (rasterY == 0xF8) allowBadLines = false;
        badLine = allowBadLines && rasterY >= 0x30 && rasterY <= 0xF7
                && (rasterY & 0x07) == ysmooth;

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
        lineDisplay = den && !vbFF;

        // Video-matrix fetch (c-access) at line start; the per-character graphics
        // fetch (g-access) happens per cycle so mid-line $D018/mode changes apply.
        int yscroll = reg[0x11] & 0x07;
        int dispY = rasterY - (48 + yscroll);
        lineTextRow = dispY >> 3;
        lineSub = dispY & 0x07;
        lineHasContent = lineDisplay && lineFbY >= 0 && lineFbY < HEIGHT
                && lineTextRow >= 0 && lineTextRow <= 24;

        if (lineHasContent) {
            // c-access from the video counter base (== lineTextRow*40 in steady
            // state; diverges on a VSP/bad-line-abort frame, shifting the screen).
            int vmBase = ((reg[0x18] >> 4) & 0x0F) << 10;
            for (int col = 0; col < 40; col++) {
                vmBuf[col] = mem.vicRead(vmBase + ((vcBase + col) & 0x3FF));
                colorBuf[col] = mem.colorRead((vcBase + col) & 0x3FF);
            }
        } else if (lineDisplay && lineFbY >= 0 && lineFbY < HEIGHT) {
            int bg0 = reg[0x21] & 0x0F;
            for (int px = 0; px < 320; px++) { lineColor[px] = bg0; lineFg[px] = false; lineBgReg[px] = 0; }
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
                }
            }
            framebuffer[lineRow + fx] = color;
        }
    }

    private void endLine() {
        if (lineDisplay && lineFbY >= 0 && lineFbY < HEIGHT) {
            renderSprites(rasterY, lineFbY, lineWinLeft, lineWinRight);
        }
    }

    /** Render one display character (column {@code col}) using the LIVE registers. */
    private void renderChar(int col) {
        // Counter-derived addressing. In steady state vc==lineTextRow*40+col,
        // rc==lineSub, vmli==col, so output is byte-identical to the old path.
        if (VC_ASSERT && (vcBase != lineTextRow * 40 || rc != lineSub || vc != vcBase + col)) {
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

        int screenCode = vmBuf[matrixIx];
        int color = colorBuf[matrixIx];
        int base = col * 8;

        switch (mode) {
                case 0: {   // standard text
                    int bits = mem.vicRead(charBase + screenCode * 8 + sub);
                    for (int p = 0; p < 8; p++) {
                        boolean on = (bits & (0x80 >> p)) != 0;
                        lineColor[base + p] = on ? color : bg0;
                        lineFg[base + p] = on;
                        lineBgReg[base + p] = on ? -1 : 0;
                    }
                    break;
                }
                case 1: {   // multicolor text
                    int bits = mem.vicRead(charBase + screenCode * 8 + sub);
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
                    int bits = mem.vicRead(bmpBase + (vc << 3) + sub);
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
                    int bits = mem.vicRead(bmpBase + (vc << 3) + sub);
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
                    int bits = mem.vicRead(charBase + (screenCode & 0x3F) * 8 + sub);
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

        // Advance the video counters per drawn char (VICE vicii_fetch_graphics).
        vmli++;
        vc = (vc + 1) & 0x3FF;
    }

    private void renderSprites(int ry, int fbY, int winLeftFb, int winRightFb) {
        for (int x = 0; x < WIDTH; x++) spriteOwner[x] = -1;
        int rowBase = fbY * WIDTH;
        int enable = reg[0x15];
        int xexpAll = reg[0x1D];
        int yexpAll = reg[0x17];
        int mcAll = reg[0x1C];
        int priAll = reg[0x1B];
        int msbAll = reg[0x10];
        int vmBase = ((reg[0x18] >> 4) & 0x0F) << 10;
        int mc0 = reg[0x25] & 0x0F;
        int mc1 = reg[0x26] & 0x0F;

        // Draw sprite 7 first so sprite 0 wins (lowest number highest priority).
        for (int i = 7; i >= 0; i--) {
            if ((enable & (1 << i)) == 0) continue;
            int sy = reg[0x01 + 2 * i];
            boolean yexp = (yexpAll & (1 << i)) != 0;
            int height = yexp ? 42 : 21;
            int spriteRow = spriteRow(ry, sy, height);
            if (spriteRow < 0) continue;
            if (yexp) spriteRow >>= 1;

            int pointer = mem.vicRead(vmBase + 0x3F8 + i);
            int dataAddr = pointer * 64 + spriteRow * 3;
            int b0 = mem.vicRead(dataAddr);
            int b1 = mem.vicRead(dataAddr + 1);
            int b2 = mem.vicRead(dataAddr + 2);
            int data = (b0 << 16) | (b1 << 8) | b2;

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

    /**
     * The sprite line (0..height-1, before Y expansion) shown on raster line
     * {@code ry}, or -1. The chip compares sprite Y with the LOW 8 bits of RASTER in
     * cycles 55/56 of line L and fetches the data at the end of that line, so the
     * first sprite line is displayed on L+1 (sprite Y=50 lines up with the first
     * text line, raster 51). Because the compare is 8-bit, Y also matches on line
     * Y+256 when the frame has that many lines (PAL: Y < 56); a sprite started near
     * the end of the frame continues on line 0.
     */
    private int spriteRow(int ry, int sy, int height) {
        for (int start = sy; start < lines; start += 256) {
            int d = ry - 1 - start;
            if (d < 0) d += lines;
            if (d < height) return d;
        }
        return -1;
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
