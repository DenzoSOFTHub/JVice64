package it.denzosoft.jvice64.mem;

import it.denzosoft.jvice64.cia.Cia1;
import it.denzosoft.jvice64.cia.Cia2;
import it.denzosoft.jvice64.media.Cartridge;
import it.denzosoft.jvice64.sid.Sid;
import it.denzosoft.jvice64.vic.VicII;
import it.denzosoft.jvice64.vic.VicMemory;

/**
 * C64 memory map with PLA bank switching for a cartridge-less machine.
 *
 * Banking is controlled by the 6510 processor port at $00/$01:
 *   LORAM  = bit0, HIRAM = bit1, CHAREN = bit2.
 * Visibility (derived from VICE c64meminit configs 0-7):
 *   BASIC  ($A000-$BFFF) iff LORAM &amp; HIRAM
 *   KERNAL ($E000-$FFFF) iff HIRAM
 *   I/O    ($D000-$DFFF) iff CHAREN &amp; (LORAM | HIRAM)
 *   CHARROM($D000-$DFFF) iff !CHAREN &amp; (LORAM | HIRAM)
 *   otherwise RAM.
 * With a cartridge the full 32-entry PLA tables below apply (e.g. a 16K
 * cartridge maps the character ROM only when HIRAM is set).
 */
public final class C64Memory implements Bus, VicMemory {

    private final byte[] ram = new byte[0x10000];
    private final byte[] colorRam = new byte[0x0400];   // low nibble used
    private final byte[] basicRom;                       // 8 KB
    private final byte[] kernalRom;                      // 8 KB
    private final byte[] charRom;                        // 4 KB

    // 6510 processor port ($00 = data direction, $01 = data).
    private int portDir = 0x2F;
    private int portData = 0x37;
    // Level last driven on each port pin (derived, not in the snapshot): an input
    // pin without a pull-up keeps it for a while (bit 3, the cassette write line).
    private int portOut = 0x37;

    // Cartridge state (EXROM/GAME lines: 0 = asserted/low, 1 = inactive/high).
    // exromLine/gameLine are the CURRENT lines (boards such as Simons' BASIC,
    // Epyx, Super Games or EasyFlash drive them at run time); bootExrom/bootGame
    // are the CRT header's values, the state the lines take on a reset.
    private int exromLine = 1, gameLine = 1, cartType = 0;
    private int bootExrom = 1, bootGame = 1;
    private boolean cartInserted = false;
    private byte[][] romlBanks = { new byte[0] };
    private byte[][] romhBanks = { new byte[0] };
    private int currentBank = 0, numBanks = 1;    // currentBank = ROML bank
    private int romhBank = 0;                     // = currentBank except on Zaxxon
    private boolean cartDisabled = false;         // Magic Desk / Fun Play / RGCD "off" latch
    private boolean cartLocked = false;           // Super Games / RGCD: latch frozen until reset
    private int cartReg = 0;                      // EasyFlash $DE02 control (bits 0-2 + LED bit 7)
    private final byte[] cartRam = new byte[256]; // EasyFlash RAM at $DF00-$DFFF
    private int cartLastWrite = 0;                // WarpSpeed: last I/O area written (0 none, 1 I/O1, 2 I/O2)
    // EasyFlash: the two AM29F040 flash chips (ROML / ROMH) over in-memory copies
    // of the bank arrays. Null for every other board.
    private Flash040 flashLo, flashHi;
    // Fast-path flags (derived from cartType): ROML reads with side effects, and
    // ROMH reads that need the flash chip (EasyFlash command/autoselect mode).
    private boolean romlHook = false, romhHook = false;

    // Epyx FastLoad capacitor: the ROM stays visible for EPYX_CYCLES after the
    // last ROML read or $DE00-$DEFF access. Time base = cartClock(): CPU bus
    // accesses counted here, or real cycles once C64 feeds clockCartridge().
    private static final int EPYX_CYCLES = 512;
    private long epyxTrigger;
    private long busClock, extClock;
    private boolean extClocked;

    // Derived banking state.
    private boolean loram, hiram, charen;
    private int memConfig;          // 0..31 PLA index
    private boolean ultimax;        // GAME low, EXROM high (configs 16..23)

    // PLA configuration tables (from VICE c64/c64meminit.c). Index 0..31 =
    // (!game<<4) | (!exrom<<3) | (charen<<2) | (hiram<<1) | (loram<<0).
    private static final int[] IO_CONFIG = {
        0,0,0,0,0,1,1,1, 0,0,0,0,0,1,1,1, 2,2,2,2,2,2,2,2, 0,0,0,0,0,1,1,1 };
    private static final int[] ROML_CONFIG = {
        0,0,0,0,0,0,0,0, 0,0,0,1,0,0,0,1, 1,1,1,1,1,1,1,1, 0,0,0,1,0,0,0,1 };
    private static final int[] ROMH_CONFIG = {
        0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0, 1,1,1,1,1,1,1,1, 0,0,1,1,0,0,1,1 };
    private static final int[] ROMH_MAP = {     // 0xA0 -> $A000, 0xE0 -> $E000 (0 = unused)
        0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0,
        0xE0,0xE0,0xE0,0xE0,0xE0,0xE0,0xE0,0xE0, 0,0,0xA0,0xA0,0,0,0xA0,0xA0 };
    private static final int[] BASIC_CONFIG = { // BASIC at $A000 for configs 3,7,11,15
        0,0,0,1,0,0,0,1, 0,0,0,1,0,0,0,1, 0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0 };
    private static final int[] KERNAL_CONFIG = {
        0,0,1,1,0,0,1,1, 0,0,1,1,0,0,1,1, 0,0,0,0,0,0,0,0, 0,0,1,1,0,0,1,1 };
    // Character ROM at $D000 (VICE: memory configs 1, 2, 3, 9, 10, 11, 26, 27).
    // With a 16K cartridge (24-31) the PLA needs HIRAM, LORAM alone is not enough.
    private static final int[] CHARGEN_CONFIG = {
        0,1,1,1,0,0,0,0, 0,1,1,1,0,0,0,0, 0,0,0,0,0,0,0,0, 0,0,1,1,0,0,0,0 };

    // What the CPU sees in each ROM-capable area, derived from the tables above
    // in updateBanking() (which runs on every $00/$01, cartridge and bank change)
    // so read0() tests one boolean instead of indexing tables per access.
    private boolean romlOn, basicOn, romhLoOn, ioOn, charOn, kernalOn, romhHiOn;
    private byte[] roml = new byte[0], romh = new byte[0];

    // Open-bus latch (last byte seen on the data bus) for colour-RAM high nibble.
    private int lastBus = 0;

    // Attached chips.
    private VicII vic;
    private Sid sid;
    private Cia1 cia1;
    private Cia2 cia2;

    public C64Memory(byte[] basicRom, byte[] kernalRom, byte[] charRom) {
        this.basicRom = basicRom;
        this.kernalRom = kernalRom;
        this.charRom = charRom;
        updateBanking();
    }

    public void attach(VicII vic, Sid sid, Cia1 cia1, Cia2 cia2) {
        this.vic = vic;
        this.sid = sid;
        this.cia1 = cia1;
        this.cia2 = cia2;
    }

    /**
     * The RESET line also clears the cartridge's bank latch / disable flip-flop:
     * otherwise a reset would present whatever bank was selected last at $8000
     * and the KERNAL's CBM80 check would fail, or a cartridge that switched
     * itself off would stay invisible.
     */
    public void reset() {
        portDir = 0x2F;
        portData = 0x37;
        portOut = 0x37;
        cartReset();
        updateBanking();
    }

    /**
     * Power-on / RESET state of the cartridge hardware (VICE *_config_init):
     * bank 0 everywhere, latches unlocked, lines from the CRT header except for
     * the boards whose logic forces a mode at reset. The EasyFlash RAM is not
     * cleared (a reset does not touch it on the real board either).
     */
    private void cartReset() {
        if (flashLo != null) flashLo.reset();          // the flash chips leave command mode
        if (flashHi != null) flashHi.reset();
        currentBank = 0;
        romhBank = 0;
        cartDisabled = false;
        cartLocked = false;
        cartReg = 0;
        cartLastWrite = 0;
        exromLine = bootExrom;
        gameLine = bootGame;
        applyType();
        if (!cartInserted) return;
        switch (cartType) {
            case Cartridge.TYPE_SIMONS_BASIC:
            case Cartridge.TYPE_SUPER_GAMES:
            case Cartridge.TYPE_WESTERMANN:
            case Cartridge.TYPE_WARPSPEED:
            case Cartridge.TYPE_ZAXXON:
                setMode(MODE_16K); break;
            case Cartridge.TYPE_FUNPLAY:
            case Cartridge.TYPE_REX:
            case Cartridge.TYPE_GS:
            case Cartridge.TYPE_DINAMIC:
            case Cartridge.TYPE_MAGIC_DESK:
            case Cartridge.TYPE_RGCD:
                setMode(MODE_8K); break;
            case Cartridge.TYPE_EPYX_FASTLOAD:
                setMode(MODE_8K);                     // capacitor discharged at power-on
                epyxTrigger = cartClock();
                break;
            case Cartridge.TYPE_EASYFLASH:
                easyFlashControl(0); break;           // $DE02 = 0: Ultimax, boots from ROMH bank 0
            default:
                break;                                // Normal, Ocean: header lines
        }
    }

    // VICE cartridge modes: what a board drives on EXROM/GAME.
    private static final int MODE_8K = 0, MODE_16K = 1, MODE_OFF = 2, MODE_ULTIMAX = 3;

    private void setMode(int mode) {
        exromLine = (mode == MODE_8K || mode == MODE_16K) ? 0 : 1;
        gameLine  = (mode == MODE_16K || mode == MODE_ULTIMAX) ? 0 : 1;
    }

    /** Select ROML+ROMH bank {@code b} (out-of-range numbers wrap, as undecoded address lines do). */
    private void setBank(int b) {
        b = b % numBanks;
        currentBank = b;
        romhBank = b;
    }

    /**
     * EasyFlash $DE02: bit 2 = MODE (0 = GAME from the boot jumper, 1 = from bit 0),
     * bit 1 = EXROM, bit 0 = GAME (1 = line asserted), bit 7 = LED. Table from VICE
     * easyflash.c with the jumper in its default (boot) position.
     */
    private static final int[] EF_MODE = { MODE_ULTIMAX, MODE_ULTIMAX, MODE_16K, MODE_16K,
                                            MODE_OFF, MODE_ULTIMAX, MODE_8K, MODE_16K };

    private void easyFlashControl(int v) {
        cartReg = v & 0x87;
        setMode(EF_MODE[cartReg & 0x07]);
    }

    /** Time base for the Epyx capacitor (see {@link #clockCartridge}). */
    private long cartClock() { return extClocked ? extClock : busClock; }

    /**
     * Optional cycle feed for cartridge timing (Epyx FastLoad capacitor). Call it
     * with the number of elapsed PHI2 cycles (e.g. once per cycle from the CPU
     * cycle sink, plus the stall cycles). Until it is first called, CPU bus
     * accesses are counted instead, which is close enough for the ~512-cycle
     * timeout (internal/stall cycles are then not counted).
     */
    public void clockCartridge(int cycles) {
        extClock += cycles;
        extClocked = true;
    }

    private void updateBanking() {
        int eff = (portData & portDir) | (~portDir & 0xFF); // input pins float high
        loram = (eff & 0x01) != 0;
        hiram = (eff & 0x02) != 0;
        charen = (eff & 0x04) != 0;
        int low3 = (charen ? 4 : 0) | (hiram ? 2 : 0) | (loram ? 1 : 0);
        int ex = cartDisabled ? 1 : exromLine;
        int gm = cartDisabled ? 1 : gameLine;
        memConfig = (gm == 0 ? 0x10 : 0) | (ex == 0 ? 0x08 : 0) | low3;
        int idx = memConfig;
        ultimax = (memConfig & 0x18) == 0x10;
        roml = romlBanks[currentBank];
        romh = romhBanks[romhBank];
        romlOn   = ROML_CONFIG[idx] != 0 && roml.length > 0;
        basicOn  = BASIC_CONFIG[idx] != 0;
        romhLoOn = ROMH_CONFIG[idx] != 0 && ROMH_MAP[idx] == 0xA0 && romh.length > 0;
        ioOn     = IO_CONFIG[idx] != 0;
        charOn   = CHARGEN_CONFIG[idx] != 0;
        kernalOn = KERNAL_CONFIG[idx] != 0;
        romhHiOn = ROMH_CONFIG[idx] != 0 && ROMH_MAP[idx] == 0xE0 && romh.length > 0;
    }

    /**
     * True when the KERNAL ROM is mapped at $E000-$FFFF. The KERNAL traps must
     * only fire then: a program that banks the KERNAL out and runs its own code
     * from the RAM underneath can legitimately execute at a trap address.
     */
    public boolean kernalVisible() { return kernalOn; }

    /**
     * Insert a (possibly bank-switching) cartridge and rebank. {@code type} is the
     * CRT hardware type (see {@link Cartridge}); the bank arrays are indexed by the
     * CHIP packets' bank numbers and padded to the same length.
     */
    public void insertCartridge(byte[][] romlBanks, byte[][] romhBanks,
                                int exromLine, int gameLine, int type) {
        byte[][] rl = (romlBanks == null || romlBanks.length == 0) ? new byte[][]{ new byte[0] } : romlBanks;
        byte[][] rh = (romhBanks == null || romhBanks.length == 0) ? new byte[][]{ new byte[0] } : romhBanks;
        int n = Math.max(rl.length, rh.length);
        if (type == Cartridge.TYPE_EASYFLASH) {
            // Two 512K flash chips = 64 banks of 8K each, erased cells read $FF.
            // Copies, so programming never touches the parsed Cartridge.
            n = EF_BANKS;
            rl = flashImage(rl, n);
            rh = flashImage(rh, n);
            flashLo = new Flash040(rl);
            flashHi = new Flash040(rh);
        } else {
            flashLo = flashHi = null;
        }
        this.romlBanks = pad(rl, n);
        this.romhBanks = pad(rh, n);
        this.numBanks = n;
        this.bootExrom = exromLine & 1;
        this.bootGame = gameLine & 1;
        this.cartType = type;
        this.cartInserted = true;
        java.util.Arrays.fill(cartRam, (byte) 0);
        applyType();
        cartReset();
        updateBanking();
    }

    private static final int EF_BANKS = 64;

    /** {@code n} full 8K banks: the CRT's chip data, $FF (erased flash) elsewhere. */
    private static byte[][] flashImage(byte[][] banks, int n) {
        byte[][] out = new byte[n][];
        for (int i = 0; i < n; i++) {
            byte[] b = new byte[0x2000];
            java.util.Arrays.fill(b, (byte) 0xFF);
            if (i < banks.length && banks[i] != null) {
                System.arraycopy(banks[i], 0, b, 0, Math.min(0x2000, banks[i].length));
            }
            out[i] = b;
        }
        return out;
    }

    private static byte[][] pad(byte[][] banks, int n) {
        if (banks.length == n) return banks;
        byte[][] out = java.util.Arrays.copyOf(banks, n);
        for (int i = banks.length; i < n; i++) out[i] = new byte[0];
        return out;
    }

    public void removeCartridge() {
        romlBanks = new byte[][]{ new byte[0] };
        romhBanks = new byte[][]{ new byte[0] };
        numBanks = 1;
        cartType = 0;
        cartInserted = false;
        flashLo = flashHi = null;
        bootExrom = 1;
        bootGame = 1;
        applyType();
        cartReset();
        updateBanking();
    }

    public boolean hasCartridge() {
        return cartInserted;
    }

    private void applyType() {
        romlHook = cartInserted && (cartType == Cartridge.TYPE_EPYX_FASTLOAD
                                 || cartType == Cartridge.TYPE_ZAXXON
                                 || flashBusy(flashLo));
        romhHook = cartInserted && flashBusy(flashHi);
    }

    private static boolean flashBusy(Flash040 f) { return f != null && f.state != Flash040.READ; }

    /**
     * CPU write to $DE00-$DEFF (I/O1, {@code io2} false) or $DF00-$DFFF (I/O2).
     * Bank-switching schemes per CRT type, after VICE src/c64/cart/*.c.
     */
    private void cartIoWrite(int a, int v, boolean io2) {
        if (!cartInserted) return;
        switch (cartType) {
            case Cartridge.TYPE_SIMONS_BASIC:              // write I/O1: 16K (BASIC hidden)
                if (!io2) setMode(MODE_16K);
                break;
            case Cartridge.TYPE_OCEAN:                     // $DE00: bank (bits 0-5)
                if (!io2) setBank(v & 0x3F);
                break;
            case Cartridge.TYPE_FUNPLAY:                   // $DE00: bank = b5..b3 + b0 as bit 3
                if (!io2) {
                    setBank(((v >> 3) & 7) | ((v & 1) << 3));
                    if ((v & 0xC6) == 0x86) cartDisabled = true;        // $86 switches the ROM off
                    else if ((v & 0xC6) == 0x00) cartDisabled = false;
                }
                break;
            case Cartridge.TYPE_SUPER_GAMES:               // $DF00: b0-1 bank, b2 off, b3 lock
                if (io2 && !cartLocked) {
                    setBank(v & 3);
                    setMode((v & 0x04) != 0 ? MODE_OFF : MODE_16K);
                    if ((v & 0x08) != 0) cartLocked = true;
                }
                break;
            case Cartridge.TYPE_EPYX_FASTLOAD:             // any I/O1 access discharges the capacitor
                if (!io2) epyxTrigger();
                break;
            case Cartridge.TYPE_GS:                        // write $DE00+n: bank n
                if (!io2) setBank(a & 0x3F);
                break;
            case Cartridge.TYPE_WARPSPEED:
                // VICE warpspeed.c: the board remembers which area was written
                // last. I/O1 switches the ROM on only after a previous I/O1 write;
                // I/O2 switches it off, or toggles it right after an I/O1 write.
                if (!io2) {
                    if (cartLastWrite == 1) setMode(MODE_16K);
                    cartLastWrite = 1;
                } else {
                    if (cartLastWrite == 1) setMode(exromLine == 0 ? MODE_OFF : MODE_16K);
                    else setMode(MODE_OFF);
                    cartLastWrite = 2;
                }
                break;
            case Cartridge.TYPE_MAGIC_DESK:                // $DE00: b0-6 bank, b7 = ROM off
                if (!io2) {
                    setBank(v & 0x7F);
                    cartDisabled = (v & 0x80) != 0;
                }
                break;
            case Cartridge.TYPE_EASYFLASH:                 // $DE00 bank, $DE02 control, $DF00 RAM
                if (io2) cartRam[a & 0xFF] = (byte) v;
                else if ((a & 2) == 0) setBank(v & 0x3F);
                else easyFlashControl(v);
                break;
            case Cartridge.TYPE_RGCD:                      // $DE00: b0-2 bank, b3 = off until reset
                if (!io2 && !cartLocked) {
                    setBank(v & 7);
                    if ((v & 0x08) != 0) { cartDisabled = true; cartLocked = true; }
                }
                break;
            default:                                       // Normal, Westermann, Rex, Dinamic, Zaxxon
                return;
        }
        updateBanking();
    }

    /**
     * CPU read of $DE00-$DEFF / $DF00-$DFFF. Returns the byte the cartridge
     * drives, or open bus ({@code lastBus}) when it drives nothing.
     */
    private int cartIoRead(int a, boolean io2) {
        if (!cartInserted) return lastBus;
        int r = lastBus;
        switch (cartType) {
            case Cartridge.TYPE_SIMONS_BASIC:              // read I/O1: 8K (BASIC visible)
                if (io2) return r;
                setMode(MODE_8K);
                break;
            case Cartridge.TYPE_EPYX_FASTLOAD:
                if (io2) return romByte(romlBanks[0], 0x1F00 | (a & 0xFF));  // last ROM page, always
                epyxTrigger();
                break;
            case Cartridge.TYPE_WESTERMANN:                // read I/O2: 8K
                if (!io2) return r;
                setMode(MODE_8K);
                break;
            case Cartridge.TYPE_REX:                       // read $DF00-$DFBF: off, $DFC0-$DFFF: on
                if (!io2) return r;
                setMode((a & 0xFF) < 0xC0 ? MODE_OFF : MODE_8K);
                break;
            case Cartridge.TYPE_GS:                        // read $DE00+n: bank n (VICE gs.c)
                if (io2) return r;
                setBank(a & 0x3F);
                break;
            case Cartridge.TYPE_WARPSPEED:                 // I/O1/I/O2 mirror ROM $9E00-$9FFF
                return romByte(romlBanks[0], (io2 ? 0x1F00 : 0x1E00) | (a & 0xFF));
            case Cartridge.TYPE_DINAMIC:                   // read $DE00-$DE0F: bank n (VICE dinamic.c)
                if (io2 || (a & 0xF0) != 0) return r;
                setBank(a & 0x0F);
                break;
            case Cartridge.TYPE_EASYFLASH:
                return io2 ? cartRam[a & 0xFF] & 0xFF : r;
            default:
                return r;
        }
        updateBanking();
        return r;
    }

    private static int romByte(byte[] rom, int off) {
        return rom.length == 0 ? 0xFF : rom[off % rom.length] & 0xFF;
    }

    private void epyxTrigger() {
        epyxTrigger = cartClock();
        setMode(MODE_8K);
    }

    /**
     * ROML read with side effects (only while ROML is mapped). Epyx: an expired
     * capacitor means the ROM went away before this access (it then reads the RAM
     * underneath and does NOT retrigger); otherwise the read recharges it. Zaxxon:
     * A12 of the ROML access selects the ROMH bank.
     */
    private int romlRead(int a) {
        if (flashLo != null) return flashLo.read(currentBank, a & 0x1FFF);
        if (cartType == Cartridge.TYPE_EPYX_FASTLOAD) {
            long now = cartClock();
            if (now - epyxTrigger > EPYX_CYCLES) {
                setMode(MODE_OFF);
                updateBanking();
                return read0(a);
            }
            epyxTrigger = now;
        } else {                                           // Zaxxon
            int b = (a & 0x1000) != 0 ? 1 : 0;
            if (b < numBanks && b != romhBank) { romhBank = b; updateBanking(); }
        }
        return roml[(a - 0x8000) % roml.length] & 0xFF;
    }

    // ---- CPU bus ------------------------------------------------------------

    @Override
    public int read(int address) {
        int a = address & 0xFFFF;
        busClock++;
        int r = read0(a);
        lastBus = r;
        return r;
    }

    private int read0(int a) {
        if (a < 0x8000) {                       // plain RAM: the vast majority of reads
            if (a >= 2) {
                if (ultimax && a >= 0x1000) return lastBus;   // Ultimax: only $0000-$0FFF is RAM
                return ram[a] & 0xFF;
            }
            return a == 0 ? portDir : readPort01();
        }
        switch (a & 0xF000) {
            case 0x8000: case 0x9000:
                if (romlOn) {
                    if (romlHook) return romlRead(a);
                    return roml[(a - 0x8000) % roml.length] & 0xFF;
                }
                return ram[a] & 0xFF;
            case 0xA000: case 0xB000:
                if (basicOn) return basicRom[a - 0xA000] & 0xFF;
                if (romhLoOn) {
                    if (romhHook) return flashHi.read(romhBank, a & 0x1FFF);
                    return romh[(a - 0xA000) % romh.length] & 0xFF;
                }
                return ultimax ? lastBus : ram[a] & 0xFF;
            case 0xD000:
                if (ioOn) return readIo(a);
                if (charOn) return charRom[a - 0xD000] & 0xFF;
                return ram[a] & 0xFF;
            case 0xE000: case 0xF000:
                if (kernalOn) return kernalRom[a - 0xE000] & 0xFF;
                if (romhHiOn) {
                    if (romhHook) return flashHi.read(romhBank, a & 0x1FFF);
                    return romh[(a - 0xE000) % romh.length] & 0xFF;
                }
                return ram[a] & 0xFF;
            default:                                // $C000-$CFFF
                return ultimax ? lastBus : ram[a] & 0xFF;
        }
    }

    @Override
    public void write(int address, int value) {
        int a = address & 0xFFFF;
        int v = value & 0xFF;
        lastBus = v;
        busClock++;
        if (a < 2) {
            if (a == 0) portDir = v;
            else portData = v;
            portOut = (portOut & ~portDir) | (portData & portDir);
            updateBanking();
            return;
        }
        // $D000-$DFFF I/O is the only region whose writes are intercepted.
        if ((a & 0xF000) == 0xD000 && ioOn) {
            writeIo(a, v);
            return;
        }
        if (a >= 0x1000 && (ultimax || flashLo != null) && !cartWrite(a, v)) return;
        // Everything else writes to the RAM that lies under any ROM/charrom.
        ram[a] = (byte) v;
    }

    /**
     * Cartridge side of a CPU write above $0FFF while in Ultimax mode or with an
     * EasyFlash inserted. Returns false when the C64 RAM must NOT be written.
     * Ultimax: $1000-$7FFF and $A000-$CFFF are unmapped, the write is lost.
     * EasyFlash: a write where the PLA maps ROML or ROMH goes to that flash chip
     * (VICE easyflash.c), and in 8K/16K mode to the RAM underneath as well (in
     * Ultimax the RAM is not selected there). Other boards keep writing the RAM
     * under ROML/ROMH, as before.
     */
    private boolean cartWrite(int a, int v) {
        int idx = memConfig;
        switch (a & 0xE000) {
            case 0x8000:
                if (flashLo == null || ROML_CONFIG[idx] == 0) return true;
                flashLo.write(currentBank, a & 0x1FFF, v);
                applyType();
                return !ultimax;
            case 0xA000:
                if (flashHi != null && ROMH_CONFIG[idx] != 0 && ROMH_MAP[idx] == 0xA0) {
                    flashHi.write(romhBank, a & 0x1FFF, v);
                    applyType();
                    return true;                      // 16K mode: the RAM is written too
                }
                return !ultimax;
            case 0xE000:
                if (flashHi != null && ROMH_CONFIG[idx] != 0 && ROMH_MAP[idx] == 0xE0) {
                    flashHi.write(romhBank, a & 0x1FFF, v);
                    applyType();
                    return false;                     // only Ultimax maps ROMH here
                }
                return true;
            default:                                  // $1000-$7FFF, $C000-$DFFF
                return !ultimax || a >= 0xD000;
        }
    }

    /**
     * Value read from $01 (VICE c64pla.c). Output bits read their latch. Only
     * bits 0-2 (LORAM/HIRAM/CHAREN) and 4 (cassette sense) have pull-ups and read
     * 1 as inputs; bit 3 as an input keeps the level it was last driven to; bit 5
     * (cassette motor) and bits 6-7 (not bonded out on the 6510) read 0 as inputs.
     * So with the power-on $00=$2F / $01=$37 a read gives $37, as on a real C64.
     */
    private int readPort01() {
        int in = ~portDir & 0xFF;
        int v = (portData & portDir)       // outputs read their latch
              | (in & 0x17)                // pulled-up inputs
              | (in & portOut & 0x08);     // bit 3 input: last driven level
        // Cassette sense (bit 4): pulled low when a tape is in ("play" held);
        // only visible while bit 4 is an input.
        if ((portDir & 0x10) == 0 && datasette != null && datasette.hasTape()) v &= ~0x10;
        return v & 0xFF;
    }

    private it.denzosoft.jvice64.media.Datasette datasette;
    public void setDatasette(it.denzosoft.jvice64.media.Datasette d) { this.datasette = d; }

    /** Cassette motor is on when processor-port bit5 is low. */
    public boolean cassetteMotorOn() {
        int eff = (portData & portDir) | (~portDir & 0xFF);
        return (eff & 0x20) == 0;
    }

    // ---- I/O space ($D000-$DFFF) -------------------------------------------

    private int readIo(int a) {
        int page = a & 0x0F00;
        switch (page) {
            case 0x000: case 0x100: case 0x200: case 0x300:   // VIC-II ($D000-$D3FF)
                return vic.readRegister(a & 0x3F);
            case 0x400: case 0x500: case 0x600: case 0x700:   // SID ($D400-$D7FF)
                return sid.read(a & 0x1F);
            case 0x800: case 0x900: case 0xA00: case 0xB00:   // Colour RAM ($D800-$DBFF)
                return (colorRam[a & 0x03FF] & 0x0F) | (lastBus & 0xF0);
            case 0xC00:                                       // CIA1 ($DC00)
                return cia1.read(a & 0x0F);
            case 0xD00:                                       // CIA2 ($DD00)
                return cia2.read(a & 0x0F);
            case 0xE00:                                       // I/O1 ($DE00) cartridge
                return cartIoRead(a, false);
            default:                                          // I/O2 ($DF00) cartridge
                return cartIoRead(a, true);
        }
    }

    private void writeIo(int a, int v) {
        int page = a & 0x0F00;
        switch (page) {
            case 0x000: case 0x100: case 0x200: case 0x300:
                vic.writeRegister(a & 0x3F, v); break;
            case 0x400: case 0x500: case 0x600: case 0x700:
                sid.write(a & 0x1F, v); break;
            case 0x800: case 0x900: case 0xA00: case 0xB00:
                colorRam[a & 0x03FF] = (byte) (v & 0x0F); break;
            case 0xC00:
                cia1.write(a & 0x0F, v); break;
            case 0xD00:
                cia2.write(a & 0x0F, v); break;
            case 0xE00:
                cartIoWrite(a, v, false); break;   // I/O1 $DE00-$DEFF
            default:
                cartIoWrite(a, v, true); break;    // I/O2 $DF00-$DFFF
        }
    }

    // ---- VIC-II memory view -------------------------------------------------

    @Override
    public int vicRead(int addr14) {
        int bank = cia2 != null ? cia2.vicBank() : 0;       // 0..3
        int full = (bank << 14) | (addr14 & 0x3FFF);
        if (ultimax) {
            // Ultimax (VICE generic_romh_phi1_read): the VIC sees ROMH $F000-$FFFF
            // at $3000-$3FFF of every bank; the character ROM is not mapped (the
            // PLA's VIC CHAROM term needs GAME high or EXROM low).
            if ((addr14 & 0x3000) == 0x3000 && romh.length > 0) {
                return romh[(0x1000 | (addr14 & 0x0FFF)) % romh.length] & 0xFF;
            }
            return ram[full] & 0xFF;
        }
        // Character ROM shadows VIC $1000-$1FFF in banks 0 and 2.
        if ((bank == 0 || bank == 2) && (addr14 & 0x3000) == 0x1000) {
            return charRom[addr14 & 0x0FFF] & 0xFF;
        }
        return ram[full] & 0xFF;
    }

    @Override
    public int colorRead(int index) {
        return colorRam[index & 0x03FF] & 0x0F;
    }

    // ---- Direct access for loaders / debugging -----------------------------

    // ---- Snapshot ----------------------------------------------------------

    /*
     * The stream layout is unchanged since v1. exromLine/gameLine are the CURRENT
     * lines. The "bank" int carries the extra board state packed above the ROML
     * bank, so older snapshots (a plain bank number) still load:
     *   bits 0-7 ROML bank, 8-15 ROMH bank, 16-23 EasyFlash $DE02, bit 24 latch
     *   locked, bits 25-26 WarpSpeed last I/O write, bit 30 = packed-format marker.
     * The EasyFlash flash contents (programmed/erased cells) are not saved: a
     * snapshot load keeps the flash as it is now.
     * The EasyFlash RAM and the Epyx timer are NOT in this block: see
     * saveCartExtra/loadCartExtra.
     */
    private static final int PACKED = 1 << 30;

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        o.write(ram);
        o.write(colorRam);
        o.writeByte(portDir);
        o.writeByte(portData);
        o.writeByte(exromLine);
        o.writeByte(gameLine);
        o.writeInt(cartType);
        o.writeInt(PACKED | (currentBank & 0xFF) | (romhBank & 0xFF) << 8
                   | (cartReg & 0xFF) << 16 | (cartLocked ? 1 << 24 : 0)
                   | (cartLastWrite & 3) << 25);
        o.writeBoolean(cartDisabled);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        in.readFully(ram);
        in.readFully(colorRam);
        portDir = in.readUnsignedByte();
        portData = in.readUnsignedByte();
        exromLine = in.readUnsignedByte();
        gameLine = in.readUnsignedByte();
        cartType = in.readInt();
        int bank = in.readInt();
        cartDisabled = in.readBoolean();
        if ((bank & PACKED) != 0) {
            currentBank = bank & 0xFF;
            romhBank = (bank >> 8) & 0xFF;
            cartReg = (bank >> 16) & 0x87;
            cartLocked = (bank & (1 << 24)) != 0;
            cartLastWrite = (bank >> 25) & 3;
        } else {                                  // older snapshot: a plain bank number
            currentBank = romhBank = bank;
            cartReg = 0;
            cartLocked = false;
            cartLastWrite = 0;
        }
        if (flashLo != null) flashLo.reset();
        if (flashHi != null) flashHi.reset();
        if (numBanks <= 0) numBanks = 1;
        if (currentBank < 0 || currentBank >= numBanks) currentBank = 0;
        if (romhBank < 0 || romhBank >= numBanks) romhBank = 0;
        applyType();
        epyxTrigger = cartClock();                // a live Epyx ROM gets a fresh capacitor window
        portOut = (portData & portDir) | (0x17 & ~portDir);
        updateBanking();
    }

    /**
     * Cartridge state that does not fit the v1 memory block: the EasyFlash's
     * 256 bytes of RAM and the cycles left on the Epyx capacitor. Meant to be
     * written at the END of a snapshot under a new snapshot version (older
     * snapshots simply skip {@link #loadCartExtra}, keeping the RAM as it is).
     */
    public void saveCartExtra(java.io.DataOutputStream o) throws java.io.IOException {
        o.write(cartRam);
        long left = EPYX_CYCLES - (cartClock() - epyxTrigger);
        o.writeShort((int) Math.max(-1, Math.min(EPYX_CYCLES, left)));
    }

    public void loadCartExtra(java.io.DataInputStream in) throws java.io.IOException {
        in.readFully(cartRam);
        int left = in.readShort();
        epyxTrigger = cartClock() - (EPYX_CYCLES - left);
    }

    public void pokeRam(int addr, int value) { ram[addr & 0xFFFF] = (byte) (value & 0xFF); }
    public int peekRam(int addr) { return ram[addr & 0xFFFF] & 0xFF; }

    /** Bulk-load program bytes straight into RAM (used by the autostart/loaders). */
    public void loadIntoRam(int start, byte[] data) {
        for (int i = 0; i < data.length; i++) {
            ram[(start + i) & 0xFFFF] = data[i];
        }
    }

    /**
     * AM29F040 command interface (after VICE flash040core.c, AM29F040 variant):
     * unlock $AA@$555 / $55@$2AA (address bits A0-A10), then $90 autoselect
     * (manufacturer $01, device $A4), $A0 byte program, $80+unlock+$10/$30 chip /
     * 64K-sector erase, $F0 reset. Program and erase complete at once, so a
     * driver that polls the cell (or DQ7) sees the final value on its first read.
     * The cells are the C64Memory bank arrays, so the CPU sees the result.
     */
    private static final class Flash040 {
        static final int READ = 0, MAGIC1 = 1, MAGIC2 = 2, AUTOSELECT = 3, PROGRAM = 4,
                         ERASE1 = 5, ERASE2 = 6, ERASE_SELECT = 7;
        private final byte[][] banks;
        int state = READ;
        private int base = READ;                 // READ or AUTOSELECT: where an aborted sequence returns

        Flash040(byte[][] banks) { this.banks = banks; }

        void reset() { state = base = READ; }

        int read(int bank, int off) {
            if (state == AUTOSELECT) {
                switch (off & 0xFF) {
                    case 0: return 0x01;                       // AMD
                    case 1: return 0xA4;                       // Am29F040
                    case 2: return 0x00;                       // sector not protected
                    default: break;
                }
            }
            return banks[bank % banks.length][off] & 0xFF;
        }

        void write(int bank, int off, int v) {
            int cmd = off & 0x7FF;
            switch (state) {
                case READ:
                case AUTOSELECT:
                    if (v == 0xF0) { state = base = READ; break; }
                    if (cmd == 0x555 && v == 0xAA) { base = state; state = MAGIC1; }
                    break;
                case MAGIC1:
                    state = (cmd == 0x2AA && v == 0x55) ? MAGIC2 : base;
                    break;
                case MAGIC2:
                    if (cmd != 0x555) { state = base; break; }
                    if (v == 0x90) state = base = AUTOSELECT;
                    else if (v == 0xA0) state = PROGRAM;
                    else if (v == 0x80) state = ERASE1;
                    else state = base = READ;
                    break;
                case PROGRAM: {                                // a program can only clear bits
                    byte[] b = banks[bank % banks.length];
                    b[off] = (byte) (b[off] & v);
                    state = base = READ;
                    break;
                }
                case ERASE1:
                    state = (cmd == 0x555 && v == 0xAA) ? ERASE2 : (base = READ);
                    break;
                case ERASE2:
                    state = (cmd == 0x2AA && v == 0x55) ? ERASE_SELECT : (base = READ);
                    break;
                case ERASE_SELECT:
                    if (cmd == 0x555 && v == 0x10) {           // chip erase
                        for (byte[] b : banks) java.util.Arrays.fill(b, (byte) 0xFF);
                    } else if (v == 0x30) {                    // sector erase: 64K = 8 banks
                        int first = (bank % banks.length) & ~7;
                        for (int i = first; i < first + 8 && i < banks.length; i++) {
                            java.util.Arrays.fill(banks[i], (byte) 0xFF);
                        }
                    }
                    state = base = READ;
                    break;
                default:
                    state = base = READ;
            }
        }
    }
}
