package it.denzosoft.jvice64.drive;

import java.io.ByteArrayOutputStream;

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

    private byte[] d64;
    private int id1 = 0x30, id2 = 0x30;

    private int halftrack = 36;     // track 18*2
    private int lastPhase = -1;
    private int currentTrack = -1;

    private byte[] gcrTrack = new byte[]{ (byte) 0x55 };
    private boolean[] gcrSync = new boolean[]{ false };
    private int pos;
    private boolean lastSync;   // sync status of the byte most recently read

    public void insert(byte[] d64Image) {
        this.d64 = d64Image;
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
            if (halftrack > 70) halftrack = 70;
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

    private void buildTrack(int track) {
        currentTrack = track;
        pos = 0;
        if (d64 == null || track < 1 || track > 35) {
            gcrTrack = new byte[]{ (byte) 0x55 };
            gcrSync = new boolean[]{ false };
            return;
        }
        int spt = sectorsPerTrack(track);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.util.List<Boolean> sync = new java.util.ArrayList<Boolean>();

        for (int s = 0; s < spt; s++) {
            // --- SYNC (5 x $FF) ---
            emit(out, sync, sync5(), true);
            // --- header block ---
            int hcs = s ^ track ^ id2 ^ id1;
            byte[] header = { 0x08, (byte) hcs, (byte) s, (byte) track,
                              (byte) id2, (byte) id1, 0x0F, 0x0F };
            emit(out, sync, encode(header), false);
            // --- header gap (9 x $55) ---
            emit(out, sync, gap(9), false);
            // --- SYNC (5 x $FF) ---
            emit(out, sync, sync5(), true);
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
            emit(out, sync, encode(data), false);
            // --- inter-sector gap (8 x $55) ---
            emit(out, sync, gap(8), false);
        }

        gcrTrack = out.toByteArray();
        gcrSync = new boolean[sync.size()];
        for (int i = 0; i < gcrSync.length; i++) gcrSync[i] = sync.get(i);
    }

    private static void emit(ByteArrayOutputStream out, java.util.List<Boolean> sync,
                             byte[] bytes, boolean isSync) {
        out.write(bytes, 0, bytes.length);
        for (int i = 0; i < bytes.length; i++) sync.add(isSync);
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
