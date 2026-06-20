package it.denzosoft.jvice64.media;

/**
 * Loader for .P00 (PC64 / "C64File") files.
 *
 * <pre>
 * Header (26 bytes):
 *   +0  : magic "C64File" + 0x00          (8 bytes)
 *   +8  : 16-byte PETSCII filename (0x00 padded)
 *   +24 : (17th name byte, normally 0x00)
 *   +25 : record-size byte
 * Body: standard PRG (2-byte little-endian load address + program body)
 * </pre>
 */
public final class P00 {

    static final int HDR_LEN = 26;
    private static final byte[] MAGIC = {'C', '6', '4', 'F', 'i', 'l', 'e', 0x00};
    private static final int NAME_OFFSET = 8;
    private static final int NAME_LEN = 16;

    private P00() {
    }

    /**
     * Parse raw P00 bytes into a {@link ProgramFile}.
     *
     * @param raw full file contents
     * @return the loaded program
     */
    public static ProgramFile load(byte[] raw) {
        if (raw == null || raw.length < HDR_LEN + 2) {
            throw new IllegalArgumentException("P00 too short");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (raw[i] != MAGIC[i]) {
                throw new IllegalArgumentException("P00 bad magic (not C64File)");
            }
        }
        String name = Petscii.toReadable(raw, NAME_OFFSET, NAME_LEN);

        int loadAddress = (raw[HDR_LEN] & 0xFF) | ((raw[HDR_LEN + 1] & 0xFF) << 8);
        byte[] data = new byte[raw.length - HDR_LEN - 2];
        System.arraycopy(raw, HDR_LEN + 2, data, 0, data.length);
        return new ProgramFile(name, loadAddress, data);
    }
}
