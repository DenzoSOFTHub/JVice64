package it.denzosoft.jvice64.media;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Parser for the CCS64 ".CRT" cartridge container. Every CHIP packet is kept,
 * indexed by its bank number; the bank-switching hardware itself is emulated by
 * {@code C64Memory}. A hardware type that is not emulated (freezers such as
 * Action Replay / Final Cartridge III / Retro Replay / Super Snapshot, and the
 * rarer boards) is refused with a message naming the type, instead of booting
 * its first bank as if it were a plain cartridge.
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
    /** Largest bank number of any emulated board (Magic Desk 1M = 128 x 8K). */
    private static final int MAX_BANK = 255;

    public static Cartridge load(byte[] raw) {
        if (raw == null || raw.length < 0x40) {
            throw new IllegalArgumentException("Not a CRT file (too small)");
        }
        for (int i = 0; i < 16; i++) {
            if ((raw[i] & 0xFF) != SIGNATURE.charAt(i)) {
                throw new IllegalArgumentException("Bad CRT signature");
            }
        }
        long headerLen = be32(raw, 0x10) & 0xFFFFFFFFL;   // unsigned: no negative wrap
        if (headerLen < 0x40) headerLen = 0x40;
        if (headerLen > raw.length) {
            throw new IllegalArgumentException("Corrupt CRT (header length " + headerLen
                    + " exceeds the file size " + raw.length + ")");
        }
        int type = be16(raw, 0x16);
        if (!Cartridge.isSupported(type)) {
            throw new IllegalArgumentException("Unsupported cartridge hardware type " + type
                    + " (" + Cartridge.typeName(type) + "). Supported types: "
                    + Cartridge.supportedList() + ".");
        }
        int exrom = raw[0x18] & 0xFF;
        int game = raw[0x19] & 0xFF;
        String name = ascii(raw, 0x20, 32).trim();

        Map<Integer, byte[]> romlByBank = new HashMap<Integer, byte[]>();
        Map<Integer, byte[]> romhByBank = new HashMap<Integer, byte[]>();
        int maxBank = 0;

        // Offsets in long: a packet length near 2^31 must not wrap the offset negative.
        long off = headerLen;
        int romTotal = 0;                                  // bytes of ROM image data (Ocean mode)
        while (off <= raw.length - 0x10) {
            int o = (int) off;
            if (!(raw[o] == 'C' && raw[o + 1] == 'H' && raw[o + 2] == 'I' && raw[o + 3] == 'P')) {
                break;
            }
            long packetLen = be32(raw, o + 4) & 0xFFFFFFFFL;
            int chipType = be16(raw, o + 0x08);   // 0 ROM, 1 RAM (no data), 2 flash, 3 EEPROM
            int bank = be16(raw, o + 0x0A);
            if (type == Cartridge.TYPE_FUNPLAY) {
                // The bank field holds the raw $DE00 value that selects the chip
                // ($00,$08,..,$38,$01,$09,..,$39): convert it the way the latch
                // decodes it (VICE funplay.c), so bank N is the chip $DE00 maps to N.
                bank = ((bank >> 3) & 7) | ((bank & 1) << 3);
            }
            if (bank > MAX_BANK) {
                throw new IllegalArgumentException("CRT chip bank " + bank + " out of range (max " + MAX_BANK + ")");
            }
            int loadAddr = be16(raw, o + 0x0C);
            int imageSize = be16(raw, o + 0x0E);
            int dataLen = chipType == 1 ? 0 : imageSize;   // a RAM chip packet carries no data
            // VICE crt.c: a packet length shorter than header + image is a known
            // broken-file case and is corrected to header + image.
            if (packetLen < 0x10 + dataLen) packetLen = 0x10 + dataLen;
            int dataStart = o + 0x10;
            boolean truncated = (long) dataStart + dataLen > raw.length;
            if (!truncated && packetLen > raw.length - off) {
                throw new IllegalArgumentException("Corrupt CRT (bad CHIP packet length " + packetLen
                        + " at offset " + off + ")");
            }
            int avail = Math.min(dataLen, raw.length - dataStart);
            byte[] data = Arrays.copyOfRange(raw, dataStart, dataStart + Math.max(0, avail));
            romTotal += data.length;

            // Magic Desk and RGCD are 8K-only boards: VICE places chips loaded at
            // $8000 OR $A000 in the ROML bank array, so an $A000 chip is ROML too.
            boolean romlOnly = type == Cartridge.TYPE_MAGIC_DESK || type == Cartridge.TYPE_RGCD;
            if (data.length == 0) {
                // RAM chip / empty packet: nothing to map
            } else if (loadAddr == 0x8000 || (romlOnly && loadAddr == 0xA000)) {
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

            if (truncated) break;                           // last, cut-off packet: keep what is there
            off += packetLen;
        }

        if (romlByBank.isEmpty() && romhByBank.isEmpty()) {
            throw new IllegalArgumentException("CRT file contains no ROM data");
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

        if (type == Cartridge.TYPE_OCEAN) {
            // VICE ocean.c ignores the header lines: a 512K image runs in 8K mode,
            // every other size in 16K mode (ROMH = the same bank as ROML).
            exrom = 0;
            game = romTotal == 0x80000 ? 1 : 0;
            mirrorOcean(romlBanks, romhBanks, exrom, game);
        }
        return new Cartridge(exrom, game, type, romlBanks, romhBanks, name);
    }

    /**
     * Ocean boards have ONE bank-numbered ROM space that the $DE00 latch selects
     * for both ROML and ROMH (VICE ocean.c reads ROMH from the same bank array).
     * The 256K/512K images store the upper banks as $A000 chips, so a bank that
     * has only one half shows the same 8K in the other half, and in 16K mode an
     * 8K-only bank is visible at $A000 too.
     */
    private static void mirrorOcean(byte[][] roml, byte[][] romh, int exrom, int game) {
        boolean sixteenK = (exrom & 1) == 0 && (game & 1) == 0;
        for (int i = 0; i < roml.length; i++) {
            if (roml[i].length == 0 && romh[i].length > 0) roml[i] = romh[i];
            else if (sixteenK && romh[i].length == 0 && roml[i].length > 0) romh[i] = roml[i];
        }
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
