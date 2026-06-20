package it.denzosoft.jvice64.media;

import java.util.ArrayList;
import java.util.List;

/**
 * Reader for .D64 (1541 5.25" floppy) disk images.
 *
 * <p>Supports 35-track (174848 bytes) and 40-track (196608 bytes) images, plus
 * the error-info variants (175531 / 197376 bytes) whose trailing per-sector
 * error bytes are simply ignored.</p>
 *
 * <pre>
 * Sectors per track: 1-17 = 21, 18-24 = 19, 25-30 = 18, 31-40 = 17.
 * Directory lives on track 18; BAM at 18/0; first dir sector at 18/1.
 * Dir sector: bytes 0-1 = next track/sector (0/0 = end), then 8 x 32-byte slots.
 * Slot: +0 type (bits0-2 file type, bit7 = closed/valid),
 *       +1/+2 first file track/sector,
 *       +3..+18 16-byte PETSCII name (0xA0 padded),
 *       +30/+31 size in blocks (lo/hi).
 * File data: follow the T/S chain from the first T/S. Each sector's first two
 * bytes are the next T/S; if track == 0 this is the last sector and byte 1 is
 * the number of valid bytes (so byte1 - 1 data bytes follow at offset 2),
 * otherwise 254 data bytes follow at offset 2.
 * </pre>
 */
public final class D64 {

    /** 1541 file type codes (low 3 bits of the slot type byte). */
    public static final int FT_DEL = 0;
    public static final int FT_SEQ = 1;
    public static final int FT_PRG = 2;
    public static final int FT_USR = 3;
    public static final int FT_REL = 4;

    private static final int DIR_TRACK = 18;
    private static final int FIRST_DIR_SECTOR = 1;
    private static final int SECTOR_SIZE = 256;

    private final byte[] raw;
    private final int tracks;

    /** A directory entry (file slot) on the disk. */
    public static final class Entry {
        public final String name;
        public final int fileType;     // FT_DEL..FT_REL
        public final int sizeBlocks;
        public final boolean closed;   // bit7 of the type byte
        public final int firstTrack;
        public final int firstSector;

        Entry(String name, int fileType, int sizeBlocks, boolean closed,
              int firstTrack, int firstSector) {
            this.name = name;
            this.fileType = fileType;
            this.sizeBlocks = sizeBlocks;
            this.closed = closed;
            this.firstTrack = firstTrack;
            this.firstSector = firstSector;
        }

        @Override
        public String toString() {
            return name + " (type=" + fileType + ", " + sizeBlocks + " blocks)";
        }
    }

    private D64(byte[] raw, int tracks) {
        this.raw = raw;
        this.tracks = tracks;
    }

    /**
     * Open a D64 image from its raw bytes.
     *
     * @param raw full file contents
     * @return a reader bound to the image
     */
    public static D64 open(byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("D64 data is null");
        }
        int tracks;
        switch (raw.length) {
            case 174848:        // 35 tracks
            case 175531:        // 35 tracks + error info
                tracks = 35;
                break;
            case 196608:        // 40 tracks
            case 197376:        // 40 tracks + error info
                tracks = 40;
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported D64 size: " + raw.length + " bytes");
        }
        return new D64(raw, tracks);
    }

    /** Disk name from the BAM (track 18 sector 0, offset $90), 0xA0-trimmed. */
    public String diskName() {
        return Petscii.toReadable(raw, offset(DIR_TRACK, 0) + 0x90, 16);
    }

    /** Two-character disk ID from the BAM (offset $A2). */
    public String diskId() {
        return Petscii.toReadable(raw, offset(DIR_TRACK, 0) + 0xA2, 2);
    }

    /** Number of sectors on the given 1-based track. */
    private static int sectorsPerTrack(int track) {
        if (track >= 1 && track <= 17) {
            return 21;
        }
        if (track >= 18 && track <= 24) {
            return 19;
        }
        if (track >= 25 && track <= 30) {
            return 18;
        }
        return 17; // 31-40
    }

    /** Byte offset within the image of the given track (1-based) / sector. */
    private int offset(int track, int sector) {
        int blocks = 0;
        for (int t = 1; t < track; t++) {
            blocks += sectorsPerTrack(t);
        }
        return (blocks + sector) * SECTOR_SIZE;
    }

    private boolean validTs(int track, int sector) {
        if (track < 1 || track > tracks) {
            return false;
        }
        if (sector < 0 || sector >= sectorsPerTrack(track)) {
            return false;
        }
        return offset(track, sector) + SECTOR_SIZE <= raw.length;
    }

    /**
     * Walk the directory and return every entry slot that holds a name.
     *
     * @return directory entries in on-disk order
     */
    public List<Entry> directory() {
        List<Entry> out = new ArrayList<Entry>();
        int track = DIR_TRACK;
        int sector = FIRST_DIR_SECTOR;
        int guard = 0;
        int maxSectors = 0;
        for (int t = 1; t <= tracks; t++) {
            maxSectors += sectorsPerTrack(t);
        }
        while (track != 0 && validTs(track, sector) && guard++ <= maxSectors) {
            int base = offset(track, sector);
            int nextTrack = raw[base] & 0xFF;
            int nextSector = raw[base + 1] & 0xFF;

            for (int e = 0; e < 8; e++) {
                // Slot fields are offset by 2 (the first two bytes of slot 0 hold
                // the directory sector link): VICE SLOT_TYPE_OFFSET=2, FIRST_TRACK=3,
                // FIRST_SECTOR=4, NAME_OFFSET=5, NR_BLOCKS=30.
                int slot = base + e * 32;
                int typeByte = raw[slot + 2] & 0xFF;
                int fileType = typeByte & 0x07;
                boolean closed = (typeByte & 0x80) != 0;
                int firstTrack = raw[slot + 3] & 0xFF;
                int firstSector = raw[slot + 4] & 0xFF;
                String name = Petscii.toReadable(raw, slot + 5, 16);

                // Skip wholly empty slots (no type, no name).
                if (typeByte == 0 && name.isEmpty()) {
                    continue;
                }
                int sizeBlocks = (raw[slot + 30] & 0xFF) | ((raw[slot + 31] & 0xFF) << 8);
                out.add(new Entry(name, fileType, sizeBlocks, closed,
                        firstTrack, firstSector));
            }

            track = nextTrack;
            sector = nextSector;
        }
        return out;
    }

    /**
     * Read a file by (PETSCII-rendered) name.
     *
     * @param name file name to match; {@code null} or {@code "*"} selects the
     *             first usable PRG. A trailing {@code '*'} acts as a wildcard.
     * @return the loaded program
     */
    public ProgramFile read(String name) {
        Entry chosen = null;
        List<Entry> dir = directory();

        if (name == null || name.equals("*")) {
            for (Entry e : dir) {
                if (isUsablePrg(e)) {
                    chosen = e;
                    break;
                }
            }
        } else {
            String wanted = name.trim().toUpperCase();
            boolean wildcard = wanted.endsWith("*");
            String prefix = wildcard
                    ? wanted.substring(0, wanted.length() - 1)
                    : wanted;
            for (Entry e : dir) {
                String cand = e.name.trim().toUpperCase();
                boolean match = wildcard ? cand.startsWith(prefix) : cand.equals(prefix);
                if (match && e.firstTrack != 0) {
                    chosen = e;
                    break;
                }
            }
        }

        if (chosen == null) {
            throw new IllegalArgumentException(
                    "File not found in D64: " + (name == null ? "*" : name));
        }
        return readEntry(chosen);
    }

    /**
     * Read the first usable PRG on the disk.
     *
     * @return the loaded program
     */
    public ProgramFile readFirst() {
        return read(null);
    }

    /**
     * Read a file's raw bytes WITHOUT stripping a load address (for sequential
     * files opened via OPEN/CHRIN). Returns {@code null} if not found.
     */
    public byte[] readRaw(String name) {
        String want = (name == null || name.isEmpty()) ? "*" : name.trim().toUpperCase();
        boolean wild = want.endsWith("*");
        String prefix = wild ? want.substring(0, want.length() - 1) : want;
        for (Entry e : directory()) {
            if (e.firstTrack == 0) continue;
            String cand = e.name.trim().toUpperCase();
            boolean match = want.equals("*") || (wild ? cand.startsWith(prefix) : cand.equals(want));
            if (match) return readChain(e.firstTrack, e.firstSector);
        }
        return null;
    }

    private static boolean isUsablePrg(Entry e) {
        return e.fileType == FT_PRG && e.firstTrack != 0;
    }

    private ProgramFile readEntry(Entry e) {
        byte[] body = readChain(e.firstTrack, e.firstSector);
        if (body.length < 2) {
            throw new IllegalArgumentException(
                    "D64 file too short to hold a load address: " + e.name);
        }
        int loadAddress = (body[0] & 0xFF) | ((body[1] & 0xFF) << 8);
        byte[] data = new byte[body.length - 2];
        System.arraycopy(body, 2, data, 0, data.length);
        return new ProgramFile(e.name, loadAddress, data);
    }

    /** Follow the sector chain and return the raw concatenated bytes. */
    private byte[] readChain(int track, int sector) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int guard = 0;
        int maxSectors = 0;
        for (int t = 1; t <= tracks; t++) {
            maxSectors += sectorsPerTrack(t);
        }
        while (track != 0) {
            if (!validTs(track, sector) || guard++ > maxSectors) {
                break; // corrupt or looping chain
            }
            int base = offset(track, sector);
            int nextTrack = raw[base] & 0xFF;
            int nextSector = raw[base + 1] & 0xFF;

            if (nextTrack == 0) {
                // last sector: byte 1 = number of used bytes incl. the 2 link bytes
                int used = nextSector;
                int count = used - 1; // data bytes after offset 2
                if (count < 0) {
                    count = 0;
                }
                if (count > 254) {
                    count = 254;
                }
                out.write(raw, base + 2, count);
                break;
            } else {
                out.write(raw, base + 2, 254);
            }
            track = nextTrack;
            sector = nextSector;
        }
        return out.toByteArray();
    }

    // ========================= Writing =========================

    /** The raw image bytes (mutated in place by {@link #writeFile}). */
    public byte[] image() { return raw; }

    private int bamEntry(int track) { return offset(DIR_TRACK, 0) + 4 + (track - 1) * 4; }

    private boolean sectorFree(int t, int s) {
        return (raw[bamEntry(t) + 1 + (s / 8)] & (1 << (s % 8))) != 0;
    }

    private void allocate(int t, int s) {
        int e = bamEntry(t), bi = e + 1 + (s / 8), bit = 1 << (s % 8);
        if ((raw[bi] & bit) != 0) { raw[bi] &= ~bit; raw[e] = (byte) ((raw[e] & 0xFF) - 1); }
    }

    private void freeSector(int t, int s) {
        int e = bamEntry(t), bi = e + 1 + (s / 8), bit = 1 << (s % 8);
        if ((raw[bi] & bit) == 0) { raw[bi] |= bit; raw[e] = (byte) ((raw[e] & 0xFF) + 1); }
    }

    /** Total free sectors across the data tracks (excludes the directory track). */
    public int freeBlocks() {
        int free = 0;
        for (int t = 1; t <= tracks; t++) {
            if (t == DIR_TRACK) continue;
            free += raw[bamEntry(t)] & 0xFF;
        }
        return free;
    }

    /** Find a free data sector, spiralling out from track 17 and skipping track 18. */
    private int[] findFreeData() {
        for (int d = 0; d <= tracks; d++) {
            int below = 17 - d, above = 18 + d;
            for (int t : new int[]{below, above}) {
                if (t < 1 || t > tracks || t == DIR_TRACK) continue;
                for (int s = 0; s < sectorsPerTrack(t); s++) {
                    if (sectorFree(t, s)) return new int[]{t, s};
                }
            }
        }
        return null;
    }

    private int[] findFreeDirSector() {
        for (int s = 1; s < sectorsPerTrack(DIR_TRACK); s++) {
            if (sectorFree(DIR_TRACK, s)) return new int[]{DIR_TRACK, s};
        }
        return null;
    }

    private int findDirSlotByName(String want) {
        String w = want.trim().toUpperCase();
        int t = DIR_TRACK, s = FIRST_DIR_SECTOR, guard = 0;
        while (t != 0 && validTs(t, s) && guard++ < 200) {
            int base = offset(t, s);
            for (int e = 0; e < 8; e++) {
                int slot = base + e * 32;
                if ((raw[slot + 2] & 0xFF) == 0) continue;
                String nm = Petscii.toReadable(raw, slot + 5, 16).trim().toUpperCase();
                if (nm.equals(w)) return slot;
            }
            t = raw[base] & 0xFF;
            s = raw[base + 1] & 0xFF;
        }
        return -1;
    }

    /** Find a free directory slot, allocating and linking a new dir sector if needed. */
    private int allocFreeDirSlot() {
        int t = DIR_TRACK, s = FIRST_DIR_SECTOR, guard = 0, lastBase = -1;
        while (t != 0 && validTs(t, s) && guard++ < 200) {
            int base = offset(t, s);
            lastBase = base;
            for (int e = 0; e < 8; e++) {
                int slot = base + e * 32;
                if ((raw[slot + 2] & 0xFF) == 0) return slot;
            }
            int nt = raw[base] & 0xFF, ns = raw[base + 1] & 0xFF;
            if (nt == 0) break;
            t = nt; s = ns;
        }
        int[] nd = findFreeDirSector();
        if (nd == null || lastBase < 0) return -1;
        allocate(nd[0], nd[1]);
        int nbase = offset(nd[0], nd[1]);
        for (int i = 0; i < SECTOR_SIZE; i++) raw[nbase + i] = 0;
        raw[nbase] = 0;
        raw[nbase + 1] = (byte) 0xFF;                 // new sector is end of chain
        raw[lastBase] = (byte) nd[0];                 // link previous dir sector here
        raw[lastBase + 1] = (byte) nd[1];
        return nbase;
    }

    private void scratchSlot(int slot) {
        int t = raw[slot + 3] & 0xFF, s = raw[slot + 4] & 0xFF, guard = 0;
        while (t != 0 && validTs(t, s) && guard++ < 1000) {
            int base = offset(t, s);
            int nt = raw[base] & 0xFF, ns = raw[base + 1] & 0xFF;
            freeSector(t, s);
            if (nt == 0) break;
            t = nt; s = ns;
        }
        raw[slot + 2] = 0;   // mark slot deleted
    }

    /**
     * Write (or overwrite) a file. The {@code prg} bytes include the 2-byte load
     * address. Existing files of the same name are replaced.
     *
     * @throws IllegalStateException with "DISK FULL" / "DIRECTORY FULL"
     */
    public void writeFile(String name, int fileType, byte[] prg) {
        String n = name;
        if (n.startsWith("@")) n = n.substring(1);
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        n = n.trim();

        int existing = findDirSlotByName(n);
        if (existing >= 0) scratchSlot(existing);     // overwrite

        int needed = Math.max(1, (prg.length + 253) / 254);
        if (needed > freeBlocks()) throw new IllegalStateException("DISK FULL");

        int[][] secs = new int[needed][];
        for (int i = 0; i < needed; i++) {
            int[] fs = findFreeData();
            if (fs == null) {                          // roll back on failure
                for (int j = 0; j < i; j++) freeSector(secs[j][0], secs[j][1]);
                throw new IllegalStateException("DISK FULL");
            }
            allocate(fs[0], fs[1]);
            secs[i] = fs;
        }

        int pos = 0;
        for (int i = 0; i < needed; i++) {
            int base = offset(secs[i][0], secs[i][1]);
            int chunk = Math.min(254, prg.length - pos);
            if (i < needed - 1) {
                raw[base] = (byte) secs[i + 1][0];
                raw[base + 1] = (byte) secs[i + 1][1];
            } else {
                raw[base] = 0;
                raw[base + 1] = (byte) (chunk + 1);    // last sector: #used bytes
            }
            for (int j = 0; j < 254; j++) raw[base + 2 + j] = (j < chunk) ? prg[pos + j] : 0;
            pos += chunk;
        }

        int slot = allocFreeDirSlot();
        if (slot < 0) {
            for (int[] sec : secs) freeSector(sec[0], sec[1]);
            throw new IllegalStateException("DIRECTORY FULL");
        }
        raw[slot + 2] = (byte) (0x80 | (fileType & 0x07));   // closed file
        raw[slot + 3] = (byte) secs[0][0];
        raw[slot + 4] = (byte) secs[0][1];
        byte[] nm = padName(n);
        for (int j = 0; j < 16; j++) raw[slot + 5 + j] = nm[j];
        for (int j = 21; j <= 29; j++) raw[slot + j] = 0;    // side-sector / unused
        raw[slot + 30] = (byte) (needed & 0xFF);
        raw[slot + 31] = (byte) ((needed >> 8) & 0xFF);
    }

    private static byte[] padName(String n) {
        byte[] out = new byte[16];
        String u = n.toUpperCase();
        for (int i = 0; i < 16; i++) {
            out[i] = (i < u.length()) ? (byte) (u.charAt(i) & 0xFF) : (byte) 0xA0;
        }
        return out;
    }
}
