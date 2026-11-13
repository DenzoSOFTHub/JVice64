package it.denzosoft.jvice64.drive;

/**
 * GCR "head" over a D64 image. Encodes the currently-selected track to its raw
 * GCR byte stream (sync marks + headers + 4-to-5 encoded data blocks) and feeds
 * it to the disk VIA one byte at a time as the head rotates. Head position is
 * driven by the stepper (VIA2 PB0/PB1).
 *
 * <p><b>Writing.</b> In write mode the byte the DOS put on VIA2 port A replaces
 * the GCR byte under the head ({@link #writeByte}). The encoded track in the
 * cache is then the truth for that track: the DOS reads back exactly what it
 * wrote (sync marks included, whatever the layout). A written ("dirty") track is
 * decoded back to sectors by {@link #commit()} / when the head leaves it: every
 * header + data block pair that decodes cleanly (valid GCR codes, header and
 * data checksums) updates the D64 bytes; anything else leaves the old sector.
 * The sectors that really changed are queued for the host file
 * ({@link #takeChanged()}).</p>
 *
 * <p>Threading: the emulation thread drives everything; {@link #commit()} and
 * {@link #takeChanged()} may also run on another thread (flush on exit), so the
 * write path and the decoder share this object's lock. The read path is not
 * locked.</p>
 */
public final class Gcr {

    // 4-bit nibble -> 5-bit GCR code.
    private static final int[] GCR = {
        0x0a, 0x0b, 0x12, 0x13, 0x0e, 0x0f, 0x16, 0x17,
        0x09, 0x19, 0x1a, 0x1b, 0x0d, 0x1d, 0x1e, 0x15
    };
    /** 5-bit GCR code -> nibble, -1 for the 16 codes that are not valid GCR. */
    private static final int[] DECODE = new int[32];
    static {
        java.util.Arrays.fill(DECODE, -1);
        for (int i = 0; i < 16; i++) DECODE[GCR[i]] = i;
    }

    /** Highest track the head can reach (half-track 84 = track 42, like a real 1541). */
    private static final int MAX_HALFTRACK = 84;
    /** Bytes per encoded sector record: sync 5 + header 10 + gap 9 + sync 5 + data 325 + gap 8. */
    private static final int RECORD = 362;
    /** Offsets of the two 5-byte sync marks inside a record. */
    private static final int SYNC1 = 0, SYNC2 = 24;
    /** A data block's sync must follow its header's within this many bytes (normally 24). */
    private static final int MAX_HEADER_TO_DATA = 64;

    private byte[] d64;
    private int id1 = 0x30, id2 = 0x30;
    /** Tracks on the inserted image: 35, or 40 for a 40-track image. */
    private int maxTrack = 35;
    /** Encoded tracks, built on first use and kept (with whatever the DOS wrote) until the next insert. */
    private byte[][] trackCache = new byte[MAX_HALFTRACK / 2 + 1][];
    /** Per-track sync flags, own copy once the track was written (else the shared layout below). */
    private boolean[][] syncCache = new boolean[MAX_HALFTRACK / 2 + 1][];
    /** Tracks written since their last decode. */
    private final boolean[] dirty = new boolean[MAX_HALFTRACK / 2 + 1];
    private boolean anyDirty;
    /** D64 blocks (256-byte sector index) changed by a decode and not yet handed out by {@link #takeChanged()}. */
    private final java.util.BitSet changed = new java.util.BitSet();
    /** Sync flags per byte, one array per sectors-per-track count (the layout depends on nothing else). */
    private static final boolean[][] SYNC_BY_SPT = new boolean[22][];

    /** Run (outside the lock) after the head left a written track and it was decoded. */
    private Runnable commitListener;

    private int halftrack = 36;     // track 18*2
    private int lastPhase = -1;
    private int currentTrack = -1;

    private byte[] gcrTrack = new byte[]{ (byte) 0x55 };
    private boolean[] gcrSync = new boolean[]{ false };
    private int pos;
    private boolean lastSync;   // sync status of the byte most recently read

    public synchronized void insert(byte[] d64Image) {
        this.d64 = d64Image;
        maxTrack = (d64 != null && d64.length >= 196608) ? 40 : 35;
        trackCache = new byte[MAX_HALFTRACK / 2 + 1][];
        syncCache = new boolean[MAX_HALFTRACK / 2 + 1][];
        java.util.Arrays.fill(dirty, false);
        anyDirty = false;
        changed.clear();
        if (d64 != null && d64.length >= offset(18, 0) + 0xA4) {
            id1 = d64[offset(18, 0) + 0xA2] & 0xFF;
            id2 = d64[offset(18, 0) + 0xA3] & 0xFF;
        }
        currentTrack = -1;
        buildTrack(halftrack / 2);
    }

    public boolean hasDisk() { return d64 != null; }
    public int currentTrack() { return currentTrack; }
    public int gcrLen() { return gcrTrack.length; }
    /** The D64 bytes the head works on (updated in place by {@link #commit()}). */
    public byte[] image() { return d64; }

    /** Called (on the thread that moved the head) after a written track was decoded on leaving it. */
    public void setCommitListener(Runnable r) { commitListener = r; }

    // ---- Snapshot ----------------------------------------------------------

    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        o.writeInt(halftrack);
        o.writeInt(lastPhase);
        o.writeInt(currentTrack);
        o.writeInt(pos);
        o.writeBoolean(lastSync);
    }

    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        halftrack = in.readInt();
        lastPhase = in.readInt();
        int track = in.readInt();
        int p = in.readInt();
        boolean sync = in.readBoolean();
        // Select the GCR byte stream for the saved track (from the cache, so a
        // track written in this session keeps what was written), then restore
        // the head position within it.
        boolean committed = buildTrack(track);   // sets currentTrack = track, pos = 0
        pos = (gcrTrack.length == 0) ? 0 : (p % gcrTrack.length);
        lastSync = sync;
        if (committed) notifyCommit();
    }

    private static int sectorsPerTrack(int t) {
        if (t <= 17) return 21;
        if (t <= 24) return 19;
        if (t <= 30) return 18;
        return 17;
    }

    /**
     * Bytes on one revolution of a track in its speed zone (the same figures VICE
     * uses for G64 tracks). The sector records fill {@code spt * 362} of them; the
     * rest is tail gap. The DOS FORMAT measures the track and needs that slack:
     * with {@code spt * 362} exactly, its computed inter-sector gap drops below 4
     * and it gives up with an error.
     */
    private static int trackBytes(int spt) {
        switch (spt) {
            case 21: return 7692;
            case 19: return 7142;
            case 18: return 6666;
            default: return 6250;
        }
    }

    private static int offset(int track, int sector) {
        int blocks = 0;
        for (int t = 1; t < track; t++) blocks += sectorsPerTrack(t);
        return (blocks + sector) * 256;
    }

    /** Stepper update: PB0/PB1 form a 2-bit phase; +1 steps out, -1 steps in. */
    public void selectTrack(int phase, int portB) {
        phase &= 0x03;
        if (lastPhase >= 0) {
            int diff = (phase - lastPhase) & 0x03;
            if (diff == 1) halftrack++;
            else if (diff == 3) halftrack--;
            if (halftrack < 2) halftrack = 2;
            if (halftrack > MAX_HALFTRACK) halftrack = MAX_HALFTRACK;
        }
        lastPhase = phase;
        int t = halftrack / 2;
        if (t != currentTrack && buildTrack(t)) notifyCommit();
    }

    private void notifyCommit() {
        Runnable r = commitListener;
        if (r != null) r.run();
    }

    public int nextByte() {
        if (gcrTrack.length == 0) return 0xFF;
        int b = gcrTrack[pos] & 0xFF;
        lastSync = gcrSync[pos];
        pos = (pos + 1) % gcrTrack.length;
        return b;
    }

    /** Sync status of the byte currently under the head (most recently read). */
    public boolean atSync() {
        return lastSync;
    }

    // ---- Writing ------------------------------------------------------------

    /**
     * Write mode on a write-protected disk: the sensor also gates the write
     * current on the real drive, so nothing is recorded; the disk still turns.
     */
    public synchronized void skipByte() {
        int len = gcrTrack.length;
        lastSync = false;                      // the sync detector is off while writing
        pos = (len == 0) ? 0 : (pos + 1) % len;
    }

    /**
     * Write mode: {@code b} replaces the GCR byte under the head, which then
     * moves on (the disk spins). Off the image (no disk, beyond its last track)
     * the byte goes nowhere.
     */
    public synchronized void writeByte(int b) {
        int len = gcrTrack.length;
        lastSync = false;                      // the sync detector is off while writing
        if (d64 == null || currentTrack < 1 || currentTrack > maxTrack || len < 3) {
            pos = (len == 0) ? 0 : (pos + 1) % len;
            return;
        }
        if (syncCache[currentTrack] == null) {
            syncCache[currentTrack] = gcrSync.clone();   // stop sharing the static layout
            gcrSync = syncCache[currentTrack];
        }
        gcrTrack[pos] = (byte) b;
        refreshSync(pos == 0 ? len - 1 : pos - 1);
        refreshSync(pos);
        refreshSync(pos + 1 == len ? 0 : pos + 1);
        dirty[currentTrack] = true;
        anyDirty = true;
        pos = (pos + 1) % len;
    }

    /**
     * A sync mark is 10+ consecutive 1 bits. GCR data never holds more than 8
     * (so never two $FF bytes in a row), hence: a byte is sync when it is $FF and
     * a neighbour is $FF too. That is exactly the 5-byte marks of an encoded track,
     * and it follows whatever the DOS writes (5-6 $FF, a whole track of them...).
     */
    private void refreshSync(int i) {
        byte[] t = gcrTrack;
        int len = t.length;
        boolean s = false;
        if (t[i] == (byte) 0xFF) {
            s = t[i == 0 ? len - 1 : i - 1] == (byte) 0xFF || t[i + 1 == len ? 0 : i + 1] == (byte) 0xFF;
        }
        gcrSync[i] = s;
    }

    /** Decode every written track back into the D64 bytes. Returns the number of sectors that changed. */
    public synchronized int commit() {
        if (!anyDirty) return 0;
        int n = 0;
        for (int t = 1; t < dirty.length; t++) {
            if (dirty[t]) n += decodeTrack(t);
        }
        anyDirty = false;
        return n;
    }

    /**
     * Hand out (and forget) the sectors changed since the last call, as
     * {@code block index -> 256 bytes}, in block order.
     */
    public synchronized java.util.Map<Integer, byte[]> takeChanged() {
        java.util.Map<Integer, byte[]> out = new java.util.TreeMap<Integer, byte[]>();
        for (int b = changed.nextSetBit(0); b >= 0; b = changed.nextSetBit(b + 1)) {
            out.put(b, java.util.Arrays.copyOfRange(d64, b * 256, b * 256 + 256));
        }
        changed.clear();
        return out;
    }

    /**
     * Put blocks handed out by {@link #takeChanged()} back in the queue (their host
     * write failed): the next take copies them again, with the image's current bytes.
     */
    public synchronized void requeue(java.util.Collection<Integer> blocks) {
        for (int b : blocks) changed.set(b);
    }

    /** Blocks decoded but not yet handed out by {@link #takeChanged()}. */
    public synchronized int pendingBlocks() { return changed.cardinality(); }

    /** A copy of the image taken under the lock (a decode cannot change it half way). */
    public synchronized byte[] imageCopy() { return d64 == null ? null : d64.clone(); }

    /**
     * CRC-32 of the image (under the lock), -1 when no disk is in. Snapshots store
     * it to tell whether the disk changed since the drive's RAM (BAM) was saved.
     */
    public synchronized long imageCrc() {
        if (d64 == null) return -1L;
        java.util.zip.CRC32 c = new java.util.zip.CRC32();
        c.update(d64, 0, d64.length);
        return c.getValue();
    }

    /**
     * Decode track {@code t} from its cached GCR stream: pair each data block
     * with the header just before it, check both, and copy the sectors that
     * differ into the image. Returns how many sectors changed.
     */
    private int decodeTrack(int t) {
        dirty[t] = false;
        byte[] b = trackCache[t];
        if (b == null || d64 == null) return 0;
        int spt = sectorsPerTrack(t);
        boolean[] f = syncCache[t] != null ? syncCache[t] : syncFlags(spt);
        int len = b.length;

        // Header positions -> sector, then data blocks paired with the nearest header before them.
        int[] hdrPos = new int[64], hdrSec = new int[64];
        int nh = 0;
        java.util.List<int[]> dataPos = new java.util.ArrayList<int[]>();
        for (int i = 0; i < len; i++) {
            int next = (i + 1 == len) ? 0 : i + 1;
            if (!f[i] || f[next]) continue;           // not the last byte of a sync mark
            int[] first = decode(b, next, 5, 4);
            if (first == null) continue;
            if (first[0] == 0x08) {
                int[] h = decode(b, next, 10, 8);
                if (h == null) continue;
                int sec = h[2], trk = h[3];
                if (trk != t || sec >= spt || h[1] != (h[2] ^ h[3] ^ h[4] ^ h[5])) continue;
                if (nh == hdrPos.length) {
                    hdrPos = java.util.Arrays.copyOf(hdrPos, nh * 2);
                    hdrSec = java.util.Arrays.copyOf(hdrSec, nh * 2);
                }
                hdrPos[nh] = next;
                hdrSec[nh] = sec;
                nh++;
            } else if (first[0] == 0x07) {
                dataPos.add(new int[]{ next });
            }
        }
        int changedHere = 0;
        for (int[] dp : dataPos) {
            int p = dp[0];
            int best = -1, bestDist = Integer.MAX_VALUE;
            for (int h = 0; h < nh; h++) {
                int d = (p - hdrPos[h] + len) % len;
                if (d > 0 && d <= MAX_HEADER_TO_DATA && d < bestDist) { bestDist = d; best = h; }
            }
            if (best < 0) continue;
            int[] data = decode(b, p, 325, 260);
            if (data == null || data[0] != 0x07) continue;
            int cs = 0;
            for (int i = 1; i <= 256; i++) cs ^= data[i];
            if (cs != data[257]) continue;           // bad checksum: keep the old sector
            int base = offset(t, hdrSec[best]);
            if (base + 256 > d64.length) continue;
            boolean diff = false;
            for (int i = 0; i < 256; i++) {
                if ((d64[base + i] & 0xFF) != data[1 + i]) { diff = true; break; }
            }
            if (!diff) continue;
            for (int i = 0; i < 256; i++) d64[base + i] = (byte) data[1 + i];
            changed.set(base / 256);
            changedHere++;
        }
        return changedHere;
    }

    /**
     * GCR 5-to-4 decode of {@code gcrBytes} bytes starting at {@code start}
     * (wrapping round the track) into {@code outBytes} values; null if any
     * 5-bit group is not a valid GCR code.
     */
    private static int[] decode(byte[] track, int start, int gcrBytes, int outBytes) {
        int len = track.length;
        int[] out = new int[outBytes];
        int oi = 0;
        for (int g = 0; g < gcrBytes && oi < outBytes; g += 5) {
            long bits = 0;
            for (int j = 0; j < 5; j++) bits = (bits << 8) | (track[(start + g + j) % len] & 0xFF);
            for (int k = 0; k < 4 && oi < outBytes; k++) {
                int hi = DECODE[(int) (bits >> (35 - k * 10)) & 0x1F];
                int lo = DECODE[(int) (bits >> (30 - k * 10)) & 0x1F];
                if (hi < 0 || lo < 0) return null;
                out[oi++] = (hi << 4) | lo;
            }
        }
        return out;
    }

    // ---- Track encoding -----------------------------------------------------

    /**
     * Select the encoded stream for {@code track} (cached) and put the head at
     * its start. A written track the head is leaving is decoded first; returns
     * true when that changed the image (the caller then notifies, outside the lock).
     */
    private boolean buildTrack(int track) {
        boolean committed = false;
        int old = currentTrack;
        if (old >= 1 && old < dirty.length) committed = commitTrack(old);
        currentTrack = track;
        pos = 0;
        if (d64 == null || track < 1 || track > maxTrack) {
            gcrTrack = new byte[]{ (byte) 0x55 };      // unformatted / beyond the image
            gcrSync = new boolean[]{ false };
            return committed;
        }
        byte[] t = trackCache[track];
        if (t == null) trackCache[track] = t = encodeTrack(track);
        gcrTrack = t;
        gcrSync = syncCache[track] != null ? syncCache[track] : syncFlags(sectorsPerTrack(track));
        return committed;
    }

    private synchronized boolean commitTrack(int t) {
        if (!dirty[t]) return false;
        decodeTrack(t);
        anyDirty = false;
        for (int i = 1; i < dirty.length; i++) if (dirty[i]) { anyDirty = true; break; }
        return !changed.isEmpty();
    }

    private static synchronized boolean[] syncFlags(int spt) {
        boolean[] f = SYNC_BY_SPT[spt];
        if (f == null) {
            f = new boolean[trackBytes(spt)];
            for (int s = 0; s < spt; s++) {
                for (int i = 0; i < 5; i++) {
                    f[s * RECORD + SYNC1 + i] = true;
                    f[s * RECORD + SYNC2 + i] = true;
                }
            }
            SYNC_BY_SPT[spt] = f;
        }
        return f;
    }

    private byte[] encodeTrack(int track) {
        int spt = sectorsPerTrack(track);
        byte[] out = new byte[trackBytes(spt)];
        int o = 0;
        for (int s = 0; s < spt; s++) {
            // --- SYNC (5 x $FF) ---
            o = put(out, o, sync5());
            // --- header block ---
            int hcs = s ^ track ^ id2 ^ id1;
            byte[] header = { 0x08, (byte) hcs, (byte) s, (byte) track,
                              (byte) id2, (byte) id1, 0x0F, 0x0F };
            o = put(out, o, encode(header));
            // --- header gap (9 x $55) ---
            o = put(out, o, gap(9));
            // --- SYNC (5 x $FF) ---
            o = put(out, o, sync5());
            // --- data block ---
            byte[] data = new byte[260];
            data[0] = 0x07;
            int base = offset(track, s);
            int dcs = 0;
            for (int i = 0; i < 256; i++) {
                int v = (base + i < d64.length) ? (d64[base + i] & 0xFF) : 0;
                data[1 + i] = (byte) v;
                dcs ^= v;
            }
            data[257] = (byte) dcs;
            o = put(out, o, encode(data));
            // --- inter-sector gap (8 x $55) ---
            o = put(out, o, gap(8));
        }
        if (o != spt * RECORD) throw new IllegalStateException("GCR record size " + o);
        java.util.Arrays.fill(out, o, out.length, (byte) 0x55);   // tail gap up to the zone's track length
        return out;
    }

    private static int put(byte[] out, int o, byte[] bytes) {
        System.arraycopy(bytes, 0, out, o, bytes.length);
        return o + bytes.length;
    }

    private static byte[] sync5() {
        return new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
    }

    private static byte[] gap(int n) {
        byte[] g = new byte[n];
        java.util.Arrays.fill(g, (byte) 0x55);
        return g;
    }

    /** GCR 4-to-5 encode: every 4 input bytes (8 nibbles) -> 5 output bytes. */
    private static byte[] encode(byte[] raw) {
        int n = raw.length;
        int groups = (n + 3) / 4;
        byte[] out = new byte[groups * 5];
        int oi = 0;
        for (int i = 0; i < groups * 4; i += 4) {
            long bits = 0;
            for (int j = 0; j < 4; j++) {
                int b = (i + j < n) ? (raw[i + j] & 0xFF) : 0;
                bits = (bits << 5) | GCR[b >> 4];
                bits = (bits << 5) | GCR[b & 0x0F];
            }
            for (int j = 4; j >= 0; j--) { out[oi + j] = (byte) (bits & 0xFF); bits >>= 8; }
            oi += 5;
        }
        return out;
    }
}
