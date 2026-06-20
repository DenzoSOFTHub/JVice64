package it.denzosoft.jvice64.media;

/**
 * Helper for turning PETSCII filename bytes into a readable display String.
 * Trailing 0xA0 (CBM directory pad) and 0x00 (P00/T64 pad) bytes are trimmed;
 * printable bytes are mapped to ASCII, anything else becomes '?'.
 */
final class Petscii {

    private Petscii() {
    }

    /** Convert a PETSCII byte buffer slice to a readable String. */
    static String toReadable(byte[] buf, int off, int len) {
        if (buf == null || len <= 0) {
            return "";
        }
        int end = off + len;
        if (end > buf.length) {
            end = buf.length;
        }
        // Trim trailing 0xA0 / 0x00 padding.
        int last = end;
        while (last > off) {
            int b = buf[last - 1] & 0xFF;
            if (b == 0xA0 || b == 0x00) {
                last--;
            } else {
                break;
            }
        }
        StringBuilder sb = new StringBuilder(last - off);
        for (int i = off; i < last; i++) {
            int b = buf[i] & 0xFF;
            if (b == 0xA0 || b == 0x00) {
                // interior padding -> treat as space for readability
                sb.append(' ');
            } else if (b >= 0x20 && b <= 0x5F) {
                // PETSCII shares ASCII for this range (digits, upper, symbols)
                sb.append((char) b);
            } else if (b >= 0x60 && b <= 0x7A) {
                // PETSCII lowercase-shifted letters land here; map to ASCII A-Z
                sb.append((char) (b - 0x60 + 'A'));
            } else if (b >= 0xC1 && b <= 0xDA) {
                // PETSCII uppercase graphics letters
                sb.append((char) (b - 0xC1 + 'A'));
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}
