package it.denzosoft.jvice64.media;

/**
 * A parsed cartridge image. {@code exromLine}/{@code gameLine} are the EXROM and
 * GAME line states (0 = asserted/low, 1 = inactive/high) selecting the PLA
 * memory configuration. {@code romlBanks}/{@code romhBanks} are the per-bank ROM
 * images ($8000-$9FFF / $A000-$BFFF or $E000-$FFFF). Single-bank carts have one
 * entry; bank-switching carts have many, selected at runtime by the cartridge
 * hardware (see {@code C64Memory}).
 *
 * <p>The type numbers are the CRT "hardware type" field as defined by the CCS64
 * CRT format and VICE {@code src/cartridge.h} ({@code CARTRIDGE_CRT_*}).
 */
public final class Cartridge {

    // ---- CRT hardware types (CCS64 CRT format / VICE cartridge.h) -----------
    public static final int TYPE_NORMAL        = 0;   // generic 8K / 16K / Ultimax
    public static final int TYPE_SIMONS_BASIC  = 4;
    public static final int TYPE_OCEAN         = 5;   // Ocean type 1
    public static final int TYPE_FUNPLAY       = 7;   // Fun Play, Power Play
    public static final int TYPE_SUPER_GAMES   = 8;
    public static final int TYPE_EPYX_FASTLOAD = 10;
    public static final int TYPE_WESTERMANN    = 11;  // Westermann Learning
    public static final int TYPE_REX           = 12;  // Rex Utility
    public static final int TYPE_GS            = 15;  // C64 Game System, System 3
    public static final int TYPE_WARPSPEED     = 16;
    public static final int TYPE_DINAMIC       = 17;
    public static final int TYPE_ZAXXON        = 18;  // Zaxxon, Super Zaxxon (SEGA)
    public static final int TYPE_MAGIC_DESK    = 19;  // Magic Desk, Domark, HES Australia
    public static final int TYPE_EASYFLASH     = 32;
    public static final int TYPE_RGCD          = 57;

    /** Types whose hardware is emulated (everything else is refused by {@link Crt#load}). */
    private static final int[] SUPPORTED = {
        TYPE_NORMAL, TYPE_SIMONS_BASIC, TYPE_OCEAN, TYPE_FUNPLAY, TYPE_SUPER_GAMES,
        TYPE_EPYX_FASTLOAD, TYPE_WESTERMANN, TYPE_REX, TYPE_GS, TYPE_WARPSPEED,
        TYPE_DINAMIC, TYPE_ZAXXON, TYPE_MAGIC_DESK, TYPE_EASYFLASH, TYPE_RGCD };

    /** CRT hardware type names, index = type number (VICE cartridge.h order). */
    private static final String[] NAMES = {
        "Normal cartridge", "Action Replay", "KCS Power Cartridge", "Final Cartridge III",
        "Simons' BASIC", "Ocean type 1", "Expert Cartridge", "Fun Play, Power Play",
        "Super Games", "Atomic Power", "Epyx FastLoad", "Westermann Learning",
        "Rex Utility", "Final Cartridge I", "Magic Formel", "C64 Game System, System 3",
        "WarpSpeed", "Dinamic", "Zaxxon, Super Zaxxon (SEGA)", "Magic Desk, Domark, HES Australia",
        "Super Snapshot V5", "Comal-80", "Structured BASIC", "Ross",
        "Dela EP64", "Dela EP7x8", "Dela EP256", "Rex EP256",
        "Mikro Assembler", "Final Cartridge Plus", "Action Replay 4", "Stardos",
        "EasyFlash", "EasyFlash Xbank", "Capture", "Action Replay 3",
        "Retro Replay", "MMC64", "MMC Replay", "IDE64",
        "Super Snapshot V4", "IEEE-488", "Game Killer", "Prophet64",
        "EXOS", "Freeze Frame", "Freeze Machine", "Snapshot64",
        "Super Explode V5.0", "Magic Voice", "Action Replay 2", "MACH 5",
        "Diashow-Maker", "Pagefox", "Kingsoft", "Silverrock 128K",
        "Formel 64", "RGCD", "RR-Net MK3", "EasyCalc",
        "GMod2", "MAX Basic", "GMod3", "ZIPP-CODE 48",
        "Blackbox V8", "Blackbox V3", "Blackbox V4", "REX RAM-Floppy",
        "BIS-Plus", "SD-BOX", "MultiMAX", "Blackbox V9",
        "Lt. Kernal Host Adaptor", "RAMLink", "H.E.R.O.", "IEEE Flash! 64",
        "Turtle Graphics II", "Freeze Frame MK2" };

    public final int exromLine;
    public final int gameLine;
    public final int type;            // CRT hardware type
    public final byte[][] romlBanks;  // >=1 entry, each possibly empty
    public final byte[][] romhBanks;  // parallel to romlBanks
    public final String name;

    public Cartridge(int exromLine, int gameLine, int type,
                     byte[][] romlBanks, byte[][] romhBanks, String name) {
        this.exromLine = exromLine & 1;
        this.gameLine = gameLine & 1;
        this.type = type;
        this.romlBanks = (romlBanks == null || romlBanks.length == 0)
                ? new byte[][]{ new byte[0] } : romlBanks;
        this.romhBanks = (romhBanks == null || romhBanks.length == 0)
                ? new byte[][]{ new byte[0] } : romhBanks;
        this.name = name == null ? "" : name;
    }

    public int banks() { return romlBanks.length; }
    public boolean bankable() { return romlBanks.length > 1; }
    public boolean ultimax() { return exromLine == 1 && gameLine == 0; }

    /** True when the hardware of this CRT type is emulated. */
    public static boolean isSupported(int type) {
        for (int t : SUPPORTED) if (t == type) return true;
        return false;
    }

    /** Human-readable name of a CRT hardware type ("type N" when unknown). */
    public static String typeName(int type) {
        return type >= 0 && type < NAMES.length ? NAMES[type] : "unknown type " + type;
    }

    /** Comma-separated list of the supported types, for error messages. */
    public static String supportedList() {
        StringBuilder sb = new StringBuilder();
        for (int t : SUPPORTED) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(t).append(' ').append(typeName(t));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return String.format("Cartridge '%s' type=%d (%s) exrom=%d game=%d banks=%d",
                name, type, typeName(type), exromLine, gameLine, romlBanks.length);
    }
}
