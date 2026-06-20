package it.denzosoft.jvice64.media;

import java.util.ArrayList;
import java.util.List;

/**
 * Loader for .T64 tape archive images.
 *
 * <pre>
 * Header (64 bytes):
 *   +0  : 32-byte signature ("C64 tape image file" / "C64S tape file" / ...)
 *   +32 : version (2 bytes, LE)
 *   +34 : max number of directory entries (2 bytes, LE)
 *   +36 : number of used entries (2 bytes, LE)
 *   +40 : 24-byte tape description
 * Directory entries (32 bytes each, starting at offset 64):
 *   +0  : entry type (0=free, 1=normal file, ...)
 *   +1  : C64 file type
 *   +2  : start address lo/hi (LE)
 *   +4  : end address lo/hi (LE)
 *   +8  : offset of file contents within the T64 (4 bytes, LE)
 *   +16 : 16-byte PETSCII name (space/0x20 padded, sometimes 0x00)
 * File length = end - start. If end <= start (buggy image) read to EOF.
 * </pre>
 */
public final class T64 {

    private static final int HDR_SIZE = 64;
    private static final int REC_SIZE = 32;
    private static final int HDR_NUMENTRIES_OFFSET = 34;

    private static final String[] MAGICS = {
            "C64 tape image file",
            "C64S tape file",
            "C64S tape image file",
            "C64 tape file"
    };

    private T64() {
    }

    /**
     * Read every normal program record from the T64 image.
     *
     * @param raw full file contents
     * @return list of programs (possibly empty)
     */
    public static List<ProgramFile> loadAll(byte[] raw) {
        if (raw == null || raw.length < HDR_SIZE) {
            throw new IllegalArgumentException("T64 too short");
        }
        if (!checkMagic(raw)) {
            throw new IllegalArgumentException("T64 bad signature");
        }

        int numEntries = u16(raw, HDR_NUMENTRIES_OFFSET);
        if (numEntries <= 0) {
            numEntries = 1; // many broken images report 0
        }

        List<ProgramFile> out = new ArrayList<ProgramFile>();
        for (int i = 0; i < numEntries; i++) {
            int rec = HDR_SIZE + i * REC_SIZE;
            if (rec + REC_SIZE > raw.length) {
                break;
            }
            int entryType = raw[rec] & 0xFF;
            if (entryType == 0) {
                continue; // free / unused record
            }
            int start = u16(raw, rec + 2);
            int end = u16(raw, rec + 4);
            int contents = u32(raw, rec + 8);
            String name = Petscii.toReadable(raw, rec + 16, 16);

            int len;
            if (end > start) {
                len = end - start;
            } else {
                // buggy end address: read to end of container
                len = raw.length - contents;
            }
            if (contents < 0 || contents > raw.length) {
                continue;
            }
            if (contents + len > raw.length) {
                len = raw.length - contents;
            }
            if (len < 0) {
                len = 0;
            }
            byte[] data = new byte[len];
            System.arraycopy(raw, contents, data, 0, len);
            out.add(new ProgramFile(name, start, data));
        }
        return out;
    }

    /**
     * Read only the first usable program record.
     *
     * @param raw full file contents
     * @return the first program
     */
    public static ProgramFile loadFirst(byte[] raw) {
        List<ProgramFile> all = loadAll(raw);
        if (all.isEmpty()) {
            throw new IllegalArgumentException("T64 contains no usable files");
        }
        return all.get(0);
    }

    private static boolean checkMagic(byte[] raw) {
        for (String m : MAGICS) {
            if (raw.length >= m.length()) {
                boolean ok = true;
                for (int i = 0; i < m.length(); i++) {
                    if ((raw[i] & 0xFF) != (m.charAt(i) & 0xFF)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }
}
