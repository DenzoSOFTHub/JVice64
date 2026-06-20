package it.denzosoft.jvice64.vic;

/**
 * The VIC-II's view of memory: 14-bit address within the currently selected
 * 16KB bank, plus direct access to the 1KB colour RAM nibbles.
 */
public interface VicMemory {
    /** Read a byte the VIC sees at {@code addr14} (0..0x3FFF) in its current bank. */
    int vicRead(int addr14);

    /** Read a colour-RAM nibble (0..15) at {@code index} 0..0x3FF. */
    int colorRead(int index);
}
