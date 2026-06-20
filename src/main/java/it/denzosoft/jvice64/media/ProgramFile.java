package it.denzosoft.jvice64.media;

/**
 * A program extracted from a PRG/P00/T64/D64 container, ready to be injected
 * into C64 RAM. {@code loadAddress} is the target start address (little-endian
 * load address taken from the file's first two bytes); {@code data} is the body
 * WITHOUT the 2-byte load address.
 */
public final class ProgramFile {
    public final String name;       // PETSCII-ish display name (may be empty)
    public final int loadAddress;   // 0x0000-0xFFFF
    public final byte[] data;       // program bytes, load address stripped

    public ProgramFile(String name, int loadAddress, byte[] data) {
        this.name = name == null ? "" : name;
        this.loadAddress = loadAddress & 0xFFFF;
        this.data = data;
    }

    /** End address (exclusive) where the last byte+1 lands in RAM. */
    public int endAddress() {
        return (loadAddress + data.length) & 0xFFFF;
    }

    @Override
    public String toString() {
        return String.format("%s ($%04X-$%04X, %d bytes)",
                name, loadAddress, endAddress(), data.length);
    }
}
