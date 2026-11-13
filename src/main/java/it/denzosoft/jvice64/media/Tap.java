package it.denzosoft.jvice64.media;

import java.util.Arrays;

/**
 * Parser for the Commodore datasette ".TAP" raw-pulse tape image.
 *
 * <p>The file records the raw stream of pulses captured from a real cassette,
 * each measured in PHI2 (CPU) cycles. A {@code Tap} decodes that stream into an
 * ordered array of pulse lengths which {@link Datasette} replays onto the
 * cassette read line.</p>
 *
 * <pre>
 * Header (20 bytes):
 *   "C64-TAPE-RAW" (12 bytes signature)
 *   version        (1 byte  @ offset 12)
 *   reserved       (3 bytes @ offset 13..15)
 *   data length    (4 bytes little-endian @ offset 16) -- size of the pulse data
 *
 * Data (one or more pulse records):
 *   byte B != 0 : a pulse of B * 8 PHI2 cycles.
 *   byte B == 0 :
 *       version 0 -> "longer than 255 * 8 cycles, length unknown": usually a
 *                    pause between blocks, replayed as {@link #V0_OVERFLOW} cycles
 *                    (VICE's default gap) instead of a 2 ms blip.
 *       version 1/2 -> followed by a 24-bit little-endian value giving the
 *                    exact pulse length in cycles (3 more bytes, LSB first).
 * Version 2 (C16/+4 style) stores HALF waves: each pair is merged back into one
 * full wave, so one entry is again one falling edge. Later versions are rejected.
 * </pre>
 */
public final class Tap {

    private static final String SIGNATURE = "C64-TAPE-RAW";
    private static final int HEADER_SIZE = 20;
    private static final int VERSION_OFFSET = 12;
    private static final int DATA_LENGTH_OFFSET = 16;
    /** Cycles replayed for a version-0 overflow byte (length unknown). */
    static final int V0_OVERFLOW = 20000;

    private final int version;
    private final int[] pulses;

    private Tap(int version, int[] pulses) {
        this.version = version;
        this.pulses = pulses;
    }

    /**
     * Validate the signature and parse the pulse stream of a ".TAP" image.
     *
     * @param raw the full file contents
     * @return a decoded {@code Tap}
     * @throws IllegalArgumentException if the data is null, too short, or does
     *                                  not carry the {@code "C64-TAPE-RAW"} signature
     */
    public static Tap load(byte[] raw) {
        if (raw == null || raw.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Not a TAP file (too small)");
        }
        for (int i = 0; i < SIGNATURE.length(); i++) {
            if ((raw[i] & 0xFF) != SIGNATURE.charAt(i)) {
                throw new IllegalArgumentException("Bad TAP signature");
            }
        }

        int version = raw[VERSION_OFFSET] & 0xFF;
        if (version > 2) {
            throw new IllegalArgumentException("Unsupported TAP version " + version + " (0, 1 or 2 expected)");
        }

        // Trust the declared data length but never read past the buffer.
        int declared = le32(raw, DATA_LENGTH_OFFSET);
        int end = HEADER_SIZE + declared;
        if (declared < 0 || end < HEADER_SIZE || end > raw.length) {
            end = raw.length;
        }

        // Worst case one pulse per data byte.
        int[] tmp = new int[end - HEADER_SIZE];
        int n = 0;
        int pos = HEADER_SIZE;
        while (pos < end) {
            int b = raw[pos++] & 0xFF;
            int length;
            if (b != 0) {
                length = b * 8;
            } else if (version == 0) {
                length = V0_OVERFLOW;
            } else {
                // versions 1 and 2: exact 24-bit little-endian cycle count.
                if (pos + 3 > end) {
                    break;                       // truncated overflow record
                }
                int lo = raw[pos] & 0xFF;
                int mid = raw[pos + 1] & 0xFF;
                int hi = raw[pos + 2] & 0xFF;
                pos += 3;
                length = (hi << 16) | (mid << 8) | lo;
            }
            tmp[n++] = length;
        }

        if (version == 2) {
            // Half waves -> full waves (a trailing odd half wave stays on its own).
            int m = 0;
            for (int i = 0; i < n; i += 2) {
                long sum = (long) tmp[i] + (i + 1 < n ? tmp[i + 1] : 0);
                tmp[m++] = (int) Math.min(Integer.MAX_VALUE, sum);
            }
            n = m;
        }
        int[] pulses = (n == tmp.length) ? tmp : Arrays.copyOf(tmp, n);
        return new Tap(version, pulses);
    }

    /** @return the TAP format version byte (0, 1 or 2). */
    public int version() {
        return version;
    }

    /** @return the number of decoded pulses. */
    public int pulseCount() {
        return pulses.length;
    }

    /**
     * @return a copy of the decoded pulse lengths, in PHI2 cycles, in tape order
     */
    public int[] pulses() {
        return pulses.clone();
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF)
             | ((b[o + 1] & 0xFF) << 8)
             | ((b[o + 2] & 0xFF) << 16)
             | ((b[o + 3] & 0xFF) << 24);
    }
}
