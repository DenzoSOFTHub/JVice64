package it.denzosoft.jvice64.mem;

import it.denzosoft.jvice64.cia.Cia1;
import it.denzosoft.jvice64.cia.Cia2;
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
    private int exromLine = 1, gameLine = 1, cartType = 0;
    private byte[][] romlBanks = { new byte[0] };
    private byte[][] romhBanks = { new byte[0] };
    private int currentBank = 0, numBanks = 1;
    private boolean cartDisabled = false;

    // Derived banking state.
    private boolean loram, hiram, charen;
    private int memConfig;          // 0..31 PLA index

    // PLA configuration tables (from VICE c64/c64meminit.c). Index 0..31 =
    // (!game<<4) | (!exrom<<3) | (charen<<2) | (hiram<<1) | (loram<<0).
    private static final int[] IO_CONFIG = {
        0,0,0,0,0,1,1,1, 0,0,0,0,0,1,1,1, 2,2,2,2,2,2,2,2, 0,0,0,0,0,1,1,1 };
    private static final int[] ROML_CONFIG = {
        0,0,0,0,0,0,0,0, 0,0,0,1,0,0,0,1, 1,1,1,1,1,1,1,1, 0,0,0,1,0,0,0,1 };
    private static final int[] ROMH_CONFIG = {
        0,0,0,0,0,0,0,0, 0,0,0,0,0,0,0,0, 1,1,1,1,1,1,1,1, 0,0,1,1,0,0,1,1 };
    private static final int[] ROMH_MAP = {     // 0x00 -> $A000, 0xE0 -> $E000
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
     * The RESET line also clears the cartridge's bank latch / disable flip-flop
     * (Ocean, Magic Desk): otherwise a reset would present whatever bank was
     * selected last at $8000 and the KERNAL's CBM80 check would fail, or a Magic
     * Desk cartridge that switched itself off would stay invisible.
     */
    public void reset() {
        portDir = 0x2F;
        portData = 0x37;
        portOut = 0x37;
        currentBank = 0;
        cartDisabled = false;
        updateBanking();
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
        roml = romlBanks[currentBank];
        romh = romhBanks[currentBank];
        romlOn   = ROML_CONFIG[idx] != 0 && roml.length > 0;
        basicOn  = BASIC_CONFIG[idx] != 0;
        romhLoOn = ROMH_CONFIG[idx] != 0 && ROMH_MAP[idx] == 0x00 && romh.length > 0;
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

    /** Insert a (possibly bank-switching) cartridge and rebank. */
    public void insertCartridge(byte[][] romlBanks, byte[][] romhBanks,
                                int exromLine, int gameLine, int type) {
        this.romlBanks = (romlBanks == null || romlBanks.length == 0)
                ? new byte[][]{ new byte[0] } : romlBanks;
        this.romhBanks = (romhBanks == null || romhBanks.length == 0)
                ? new byte[][]{ new byte[0] } : romhBanks;
        this.numBanks = this.romlBanks.length;
        this.exromLine = exromLine & 1;
        this.gameLine = gameLine & 1;
        this.cartType = type;
        this.currentBank = 0;
        this.cartDisabled = false;
        updateBanking();
    }

    public void removeCartridge() {
        romlBanks = new byte[][]{ new byte[0] };
        romhBanks = new byte[][]{ new byte[0] };
        numBanks = 1;
        currentBank = 0;
        cartDisabled = false;
        exromLine = 1;
        gameLine = 1;
        updateBanking();
    }

    public boolean hasCartridge() {
        return romlBanks[0].length > 0 || romhBanks[0].length > 0;
    }

    /** Bank-select write to $DE00 (I/O1) for bank-switching cartridges. */
    private void cartIoWrite(int v) {
        if (numBanks <= 1) return;
        if (cartType == 0x10) {                 // Magic Desk: bit7 disables, low bits = bank
            cartDisabled = (v & 0x80) != 0;
            currentBank = (v & 0x3F) & (numBanks - 1);
        } else {                                // Ocean / generic
            currentBank = v & (numBanks - 1);
        }
        updateBanking();
    }

    // ---- CPU bus ------------------------------------------------------------

    @Override
    public int read(int address) {
        int a = address & 0xFFFF;
        int r = read0(a);
        lastBus = r;
        return r;
    }

    private int read0(int a) {
        if (a < 0x8000) {                       // plain RAM: the vast majority of reads
            if (a >= 2) return ram[a] & 0xFF;
            return a == 0 ? portDir : readPort01();
        }
        switch (a & 0xF000) {
            case 0x8000: case 0x9000:
                if (romlOn) return roml[(a - 0x8000) % roml.length] & 0xFF;
                return ram[a] & 0xFF;
            case 0xA000: case 0xB000:
                if (basicOn) return basicRom[a - 0xA000] & 0xFF;
                if (romhLoOn) return romh[(a - 0xA000) % romh.length] & 0xFF;
                return ram[a] & 0xFF;
            case 0xD000:
                if (ioOn) return readIo(a);
                if (charOn) return charRom[a - 0xD000] & 0xFF;
                return ram[a] & 0xFF;
            case 0xE000: case 0xF000:
                if (kernalOn) return kernalRom[a - 0xE000] & 0xFF;
                if (romhHiOn) return romh[(a - 0xE000) % romh.length] & 0xFF;
                return ram[a] & 0xFF;
            default:
                return ram[a] & 0xFF;
        }
    }

    @Override
    public void write(int address, int value) {
        int a = address & 0xFFFF;
        int v = value & 0xFF;
        lastBus = v;
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
        // Everything else writes to the RAM that lies under any ROM/charrom.
        ram[a] = (byte) v;
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
            default:                                          // $DE00-$DFFF expansion
                return lastBus;
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
                cartIoWrite(v); break;   // $DE00 cartridge bank select
            default:
                break; // $DF00 expansion I/O ignored
        }
    }

    // ---- VIC-II memory view -------------------------------------------------

    @Override
    public int vicRead(int addr14) {
        int bank = cia2 != null ? cia2.vicBank() : 0;       // 0..3
        int full = (bank << 14) | (addr14 & 0x3FFF);
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

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        o.write(ram);
        o.write(colorRam);
        o.writeByte(portDir);
        o.writeByte(portData);
        o.writeByte(exromLine);
        o.writeByte(gameLine);
        o.writeInt(cartType);
        o.writeInt(currentBank);
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
        currentBank = in.readInt();
        cartDisabled = in.readBoolean();
        if (numBanks <= 0) numBanks = 1;
        if (currentBank < 0 || currentBank >= numBanks) currentBank = 0;
        portOut = (portData & portDir) | (0x17 & ~portDir);
        updateBanking();
    }

    public void pokeRam(int addr, int value) { ram[addr & 0xFFFF] = (byte) (value & 0xFF); }
    public int peekRam(int addr) { return ram[addr & 0xFFFF] & 0xFF; }

    /** Bulk-load program bytes straight into RAM (used by the autostart/loaders). */
    public void loadIntoRam(int start, byte[] data) {
        for (int i = 0; i < data.length; i++) {
            ram[(start + i) & 0xFFFF] = data[i];
        }
    }
}
