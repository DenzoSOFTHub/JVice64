package it.denzosoft.jvice64.media;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for the CCS64 ".CRT" cartridge container. Handles the common generic
 * cartridges (hardware type 0): 8K, 16K and Ultimax. Bank-switching carts are
 * read as their first bank only.
 *
 * <pre>
 * Header (>=64 bytes): "C64 CARTRIDGE   "(16), header length(4 BE),
 *   version(2), hardware type(2 BE), EXROM(1), GAME(1), reserved(6), name(32).
 * CHIP packets: "CHIP"(4), total length(4 BE), chip type(2 BE), bank(2 BE),
 *   load address(2 BE), image size(2 BE), then image data.
 * </pre>
 */
public final class Crt {

    private static final String SIGNATURE = "C64 CARTRIDGE   ";

    public static Cartridge load(byte[] raw) {
        if (raw == null || raw.length < 0x40) {
            throw new IllegalArgumentException("Not a CRT file (too small)");
        }
        for (int i = 0; i < 16; i++) {
            if ((raw[i] & 0xFF) != SIGNATURE.charAt(i)) {
                throw new IllegalArgumentException("Bad CRT signature");
            }
        }
        int headerLen = be32(raw, 0x10);
        if (headerLen < 0x40) headerLen = 0x40;
        int type = be16(raw, 0x16);
        int exrom = raw[0x18] & 0xFF;
        int game = raw[0x19] & 0xFF;
        String name = ascii(raw, 0x20, 32).trim();

        Map<Integer, byte[]> romlByBank = new HashMap<Integer, byte[]>();
        Map<Integer, byte[]> romhByBank = new HashMap<Integer, byte[]>();
        int maxBank = 0;

        int off = headerLen;
        while (off + 0x10 <= raw.length) {
            if (!(raw[off] == 'C' && raw[off + 1] == 'H' && raw[off + 2] == 'I' && raw[off + 3] == 'P')) {
                break;
            }
            int packetLen = be32(raw, off + 4);
            int bank = be16(raw, off + 0x0A);
            int loadAddr = be16(raw, off + 0x0C);
            int imageSize = be16(raw, off + 0x0E);
            int dataStart = off + 0x10;
            int avail = Math.min(imageSize, raw.length - dataStart);
            byte[] data = Arrays.copyOfRange(raw, dataStart, dataStart + Math.max(0, avail));

            if (loadAddr == 0x8000) {
                if (data.length > 0x2000) {                 // 16K chip: split ROML/ROMH
                    romlByBank.put(bank, Arrays.copyOfRange(data, 0, 0x2000));
                    romhByBank.put(bank, Arrays.copyOfRange(data, 0x2000, Math.min(0x4000, data.length)));
                } else {
                    romlByBank.put(bank, data);
                }
            } else if (loadAddr >= 0xA000) {                // ROMH ($A000 or $E000)
                romhByBank.put(bank, data);
            }
            maxBank = Math.max(maxBank, bank);

            if (packetLen <= 0) break;
            off += packetLen;
        }

        int n = maxBank + 1;
        byte[][] romlBanks = new byte[n][];
        byte[][] romhBanks = new byte[n][];
        for (int i = 0; i < n; i++) {
            byte[] rl = romlByBank.get(i);
            byte[] rh = romhByBank.get(i);
            romlBanks[i] = rl != null ? rl : new byte[0];
            romhBanks[i] = rh != null ? rh : new byte[0];
        }

        return new Cartridge(exrom, game, type, romlBanks, romhBanks, name);
    }

    private static int be16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
             | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static String ascii(byte[] b, int o, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && o + i < b.length; i++) {
            int c = b[o + i] & 0xFF;
            if (c == 0) break;
            sb.append((char) (c < 0x80 ? c : '?'));
        }
        return sb.toString();
    }
}
