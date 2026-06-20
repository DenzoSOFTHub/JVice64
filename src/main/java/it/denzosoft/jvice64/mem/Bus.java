package it.denzosoft.jvice64.mem;

/**
 * The CPU's view of the address space (already bank-switched).
 * All values are unsigned: addresses 0x0000-0xFFFF, data 0x00-0xFF.
 */
public interface Bus {
    /** Read a byte. Returns 0..255. */
    int read(int address);

    /** Write a byte. {@code value} is masked to 0..255. */
    void write(int address, int value);
}
