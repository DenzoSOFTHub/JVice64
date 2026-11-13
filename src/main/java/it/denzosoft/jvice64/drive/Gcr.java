package it.denzosoft.jvice64.drive;

/**
 * GCR "head" over a D64 image. Encodes the currently-selected track to its raw
 * GCR byte stream (sync marks + headers + 4-to-5 encoded data blocks) and feeds
 * it to the disk VIA one byte at a time as the head rotates. Head position is
 * driven by the stepper (VIA2 PB0/PB1).
 */
public final class Gcr {

    // 4-bit nibble -> 5-bit GCR code.
    private static final int[] GCR = {
        0x0a, 0x0b, 0x12, 0x13, 0x0e, 0x0f, 0x16, 0x17,
        0x09, 0x19, 0x1a, 0x1b, 0x0d, 0x1d, 0x1e, 0x15
    };

    /** Highest track the head can reach (half-track 84 = track 42, like a real 1541). */
    private static final int MAX_HALFTRACK = 84;
    /** Bytes per encoded sector record: sync 5 + header 10 + gap 9 + sync 5 + data 325 + gap 8. */
    private static final int RECORD = 362;
    /** Offsets of the two 5-byte sync marks inside a record. */
    private static final int SYNC1 = 0, SYNC2 = 24;

    private byte[] d64;
    private int id1 = 0x30, id2 = 0x30;
    /** Tracks on the inserted image: 35, or 40 for a 40-track image. */
    private int maxTrack = 35;
    /** Encoded tracks, built on first use and kept until the next insert (the image is read-only here). */
    private byte[][] trackCache = new byte[MAX_HALFTRACK / 2 + 1][];
    /** Sync flags per byte, one array per sectors-per-track count (the layout depends on nothing else). */
    private static final boolean[][] SYNC_BY_SPT = new boolean[22][];

    private int halftrack = 36;     // track 18*2
    private int lastPhase = -1;
    private int currentTrack = -1;

    private byte[] gcrTrack = new byte[]{ (byte) 0x55 };
    private boolean[] gcrSync = new boolean[]{ false };
    private int pos;
    private boolean lastSync;   // sync status of the byte most recently read

    public void insert(byte[] d64Image) {
        this.d64 = d64Image;
        maxTrack = (d64 != null && d64.length >= 196608) ? 40 : 35;
        trackCache = new byte[MAX_HALFTRACK / 2 + 1][];
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
        // Rebuild the (immutable) GCR byte stream for the saved track, then
        // restore the head position within it.
        buildTrack(track);                 // sets currentTrack = track, pos = 0
        pos = (gcrTrack.length == 0) ? 0 : (p % gcrTrack.length);
        lastSync = sync;
    }

    private static int sectorsPerTrack(int t) {
        if (t <= 17) return 21;
        if (t <= 24) return 19;
        if (t <= 30) return 18;
        return 17;
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
        if (t != currentTrack) buildTrack(t);
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

    // ---- Track encoding -----------------------------------------------------

    /** Select the encoded stream for {@code track} (cached) and put the head at its start. */
    private void buildTrack(int track) {
        currentTrack = track;
        pos = 0;
        if (d64 == null || track < 1 || track > maxTrack) {
            gcrTrack = new byte[]{ (byte) 0x55 };      // unformatted / beyond the image
            gcrSync = new boolean[]{ false };
            return;
        }
        byte[] t = trackCache[track];
        if (t == null) trackCache[track] = t = encodeTrack(track);
        gcrTrack = t;
        gcrSync = syncFlags(sectorsPerTrack(track));
    }

    private static synchronized boolean[] syncFlags(int spt) {
        boolean[] f = SYNC_BY_SPT[spt];
        if (f == null) {
            f = new boolean[spt * RECORD];
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
        byte[] out = new byte[spt * RECORD];
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
        if (o != out.length) throw new IllegalStateException("GCR record size " + o);
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
