package it.denzosoft.jvice64.media;

/**
 * Loader for raw .PRG files: the first two bytes are the little-endian load
 * address, the remaining bytes are the program body.
 */
public final class Prg {

    private Prg() {
    }

    /**
     * Parse raw PRG bytes into a {@link ProgramFile}.
     *
     * @param raw  full file contents (at least 2 bytes)
     * @param name display name to attach (may be {@code null})
     * @return the loaded program
     */
    public static ProgramFile load(byte[] raw, String name) {
        if (raw == null || raw.length < 2) {
            throw new IllegalArgumentException("PRG too short: needs >= 2 bytes");
        }
        int loadAddress = (raw[0] & 0xFF) | ((raw[1] & 0xFF) << 8);
        byte[] data = new byte[raw.length - 2];
        System.arraycopy(raw, 2, data, 0, data.length);
        return new ProgramFile(name == null ? "" : name, loadAddress, data);
    }
}
