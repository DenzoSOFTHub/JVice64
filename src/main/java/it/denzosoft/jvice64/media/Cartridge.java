package it.denzosoft.jvice64.media;

/**
 * A parsed cartridge image. {@code exromLine}/{@code gameLine} are the EXROM and
 * GAME line states (0 = asserted/low, 1 = inactive/high) selecting the PLA
 * memory configuration. {@code romlBanks}/{@code romhBanks} are the per-bank ROM
 * images ($8000-$9FFF / $A000-$BFFF or $E000-$FFFF). Single-bank carts have one
 * entry; bank-switching carts (Ocean type 5, Magic Desk type 0x10, …) have many,
 * selected at runtime by writes to $DE00.
 */
public final class Cartridge {
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

    @Override
    public String toString() {
        return String.format("Cartridge '%s' type=%d exrom=%d game=%d banks=%d",
                name, type, exromLine, gameLine, romlBanks.length);
    }
}
