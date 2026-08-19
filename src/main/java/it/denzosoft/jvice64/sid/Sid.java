package it.denzosoft.jvice64.sid;

/**
 * MOS 6581/8580 SID sound chip emulation (pure Java 1.8, self-contained).
 *
 * <p>This is an integer SID modelled on VICE's "fastsid" engine, using the
 * reSID {@code rate_counter_period} table for the ADSR rates and the classic
 * piece-wise-linear exponential decay/release approximation (the envelope
 * counter is clocked through divisors 1,2,4,8,16,30 at the segment boundaries
 * 93/54/26/14/6). The chip is advanced one PHI2 cycle at a time so that the
 * 24-bit phase accumulators, the noise LFSR and the envelope generators behave
 * deterministically, and output samples are produced by integer downsampling
 * with a fractional accumulator.</p>
 *
 * <p>No external dependencies, no randomness, no wall-clock time: the same
 * sequence of {@code write}/{@code clock} calls always yields the same output.</p>
 */
public final class Sid {

    /* ------------------------------------------------------------------ */
    /* Constants                                                          */
    /* ------------------------------------------------------------------ */

    /* ADSR states (reSID merges decay+sustain into DECAY_SUSTAIN; FREEZED = held at 0) */
    private static final int ATTACK        = 0;
    private static final int DECAY_SUSTAIN = 1;
    private static final int RELEASE       = 2;
    private static final int FREEZED       = 3;

    /* Noise shift register seed (23-bit LFSR, all but a couple of bits set). */
    private static final int NSEED = 0x7ffff8;

    /*
     * reSID envelope rate counter periods, indexed by the 4-bit attack/decay/
     * release nibble. The rate counter is incremented once per SID clock; when
     * it reaches the period it is reset and the envelope counter is (possibly)
     * stepped.
     */
    private static final int[] RATE_PERIOD = {
            8, 31, 62, 94, 148, 219, 266, 312,
            391, 976, 1953, 3125, 3906, 11719, 19531, 31250
    };

    /* Sustain level: both nibbles of the 4-bit sustain replicated into 8 bits. */
    private static final int[] SUSTAIN_LEVEL = {
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
    };

    /* ------------------------------------------------------------------ */
    /* Clocking / sample rate                                             */
    /* ------------------------------------------------------------------ */

    private final int clockHz;
    private final int sampleRate;

    /*
     * Downsampling: we emit one output sample every clockHz/sampleRate PHI2
     * cycles. To stay exact we keep a fractional accumulator in fixed point:
     * cyclesPerSample = clockHz / sampleRate, tracked as a numerator that we add
     * sampleRate to each cycle and compare against clockHz.
     */
    private int sampleAcc;

    /* ------------------------------------------------------------------ */
    /* Register file                                                      */
    /* ------------------------------------------------------------------ */

    private final int[] reg = new int[0x20];

    /* ------------------------------------------------------------------ */
    /* Voice state                                                        */
    /* ------------------------------------------------------------------ */

    /* Oscillator: 24-bit phase accumulator and 16-bit frequency step. */
    private final int[] acc  = new int[3];   /* 24-bit phase accumulator */
    private final int[] freq = new int[3];   /* 16-bit frequency */
    private final int[] pw   = new int[3];   /* 12-bit pulse width */
    private final int[] ctrl = new int[3];   /* control register */
    private final boolean[] prevMsb = new boolean[3]; /* prior accumulator MSB (for sync edge) */

    /* Noise LFSR (23 bit) per voice. */
    private final int[] lfsr = new int[3];

    /* Envelope generator state (faithful reSID single-cycle model with the
     * pipelined 2-cycle state delays and the ADSR rate-counter delay bug). */
    private final int[] envState    = new int[3];  /* ATTACK/DECAY_SUSTAIN/RELEASE/FREEZED */
    private final int[] nextState    = new int[3];  /* pending state (loaded via stateChange) */
    private final int[] envCounter   = new int[3];  /* 8-bit envelope output value */
    private final int[] rateCounter  = new int[3];  /* 15-bit rate counter */
    private final int[] envRatePeriod = new int[3]; /* current rate_period (persistent) */
    private final int[] expCounter   = new int[3];  /* exponential divider counter */
    private final int[] expPeriod    = new int[3];  /* current exponential divisor */
    private final int[] newExpPeriod = new int[3];  /* delayed-load exponential divisor */
    private final int[] envPipeline  = new int[3];  /* envelope-step delay pipeline */
    private final int[] expPipeline  = new int[3];  /* exponential-reset delay pipeline */
    private final int[] statePipeline = new int[3]; /* state-change delay pipeline */
    private final boolean[] resetRateCounter = new boolean[3];
    private final boolean[] holdZero = new boolean[3];
    private final boolean[] gate = new boolean[3];

    /* ------------------------------------------------------------------ */
    /* Filter — reSID-measured cutoff curve + zero-delay-feedback (TPT) SVF */
    /* ------------------------------------------------------------------ */

    public static final int MODEL_6581 = 0;
    public static final int MODEL_8580 = 1;
    private int model = MODEL_6581;

    private double ic1eq, ic2eq;   /* TPT state-variable filter integrator state */

    /* Anti-alias decimation: 3 cascaded RBJ low-pass biquads (6th-order
     * Butterworth, fc=16 kHz) run at the SID clock, then pick output samples.
     * Direct-Form-II-Transposed. Coefficients computed in the constructor. */
    private final double[] bqB0 = new double[3], bqB1 = new double[3], bqB2 = new double[3];
    private final double[] bqA1 = new double[3], bqA2 = new double[3];
    private final double[] bqZ1 = new double[3], bqZ2 = new double[3];

    private void initBiquads() {
        double[] qs = { 0.5176380902, 0.7071067812, 1.9318516526 };   /* 6th-order Butterworth */
        for (int s = 0; s < 3; s++) {
            double w0 = 2.0 * Math.PI * 16000.0 / clockHz;
            double cs = Math.cos(w0), sn = Math.sin(w0), al = sn / (2.0 * qs[s]);
            double a0 = 1.0 + al;
            bqB0[s] = ((1.0 - cs) / 2.0) / a0;
            bqB1[s] = (1.0 - cs) / a0;
            bqB2[s] = bqB0[s];
            bqA1[s] = (-2.0 * cs) / a0;
            bqA2[s] = (1.0 - al) / a0;
        }
    }

    private double antiAlias(double x) {
        for (int s = 0; s < 3; s++) {
            double y = bqB0[s] * x + bqZ1[s];
            bqZ1[s] = bqB1[s] * x - bqA1[s] * y + bqZ2[s];
            bqZ2[s] = bqB2[s] * x - bqA2[s] * y;
            x = y;
        }
        return x;
    }

    private static final double SID_REF_CLOCK = 985248.0;   /* PAL clock the tables were measured at */

    /* 6581 cutoff f0 (Hz) = w0/(2*pi), measured from reSID's R4AR op-amp model at
     * the PAL clock, sampled every 32 FC steps (index = fc>>5, 0..64). Very
     * nonlinear: ~300 Hz floor to FC~600, a steep knee, up to ~19.5 kHz. */
    private static final double[] CUTOFF_6581 = {
        299.5, 299.5, 299.5, 299.5, 299.5, 299.5, 299.5, 299.5,
        299.5, 299.5, 299.5, 299.5, 299.5, 299.5, 309.7, 321.4,
        321.4, 345.1, 383.3, 452.2, 537.9, 668.7, 842.6, 1109.6,
        1437.7, 1758.0, 2223.8, 2600.6, 3089.1, 3544.6, 4087.3, 4660.5,
        4309.3, 4987.1, 5503.7, 6078.3, 6612.3, 7210.0, 7883.5, 8523.1,
        8748.4, 9441.6, 9993.0, 10524.4, 11103.7, 11708.7, 12095.8, 12744.3,
        12811.7, 13273.5, 13812.1, 14211.2, 14705.2, 15173.7, 15604.5, 16001.9,
        16299.7, 16685.3, 17090.1, 17613.9, 17915.5, 18344.6, 18690.5, 18968.7,
        19498.3
    };

    /* Resonance nibble -> 1/Q (the SVF damping term k), per chip. */
    private static final double[] INVQ_6581 = {
        1.875, 1.75, 1.625, 1.5, 1.375, 1.25, 1.125, 1.0,
        0.875, 0.75, 0.625, 0.5, 0.375, 0.25, 0.125, 0.02
    };
    private static final double[] INVQ_8580 = {
        1.4142, 1.2968, 1.1892, 1.0905, 1.0, 0.9170, 0.8409, 0.7711,
        0.7071, 0.6484, 0.5946, 0.5453, 0.5, 0.4585, 0.4204, 0.3856
    };

    /** Select the chip model (MODEL_6581 default / MODEL_8580): cutoff curve + resonance ladder. */
    public void setModel(int m) { this.model = (m == MODEL_8580) ? MODEL_8580 : MODEL_6581; }
    public int getModel() { return model; }

    /** 11-bit FC register -> cutoff frequency in Hz, scaled for the actual clock. */
    private double cutoffHz(int fc) {
        double hz;
        if (model == MODEL_8580) {
            hz = fc * 8.7405;                          // near-linear 8580, no floor
        } else {
            int i = fc >> 5;
            if (i >= 64) {
                hz = CUTOFF_6581[64];
            } else {
                double fr = (fc & 31) / 32.0;
                hz = CUTOFF_6581[i] + (CUTOFF_6581[i + 1] - CUTOFF_6581[i]) * fr;
            }
        }
        return hz * (clockHz / SID_REF_CLOCK);         // switched-cap clock dependence (NTSC +3.8%)
    }

    /* POTY line source (joystick second fire button), null if unwired. */
    private java.util.function.IntSupplier potY;
    public void setPotY(java.util.function.IntSupplier s) { this.potY = s; }

    /* ------------------------------------------------------------------ */
    /* Output ring buffer                                                 */
    /* ------------------------------------------------------------------ */

    /* When nothing consumes the audio (speaker off AND no recording tap), the
     * mix + filter + anti-alias chain is pure waste: it is ~85% of the emulator's
     * CPU time, and audio is off by default. The oscillators, noise LFSRs and
     * envelopes keep running regardless — games read $D41B/$D41C (voice 3
     * oscillator / envelope) for RNG and effects, so those must stay exact.
     * Producing no samples also leaves the ring empty, which is what a consumer
     * that is not listening expects. Set from C64.updateSidAudioNeeded(). */
    private volatile boolean audioNeeded = true;

    private final short[] ring;
    private final int ringCap;
    private int ringHead;   /* read index */
    private int ringTail;   /* write index */
    private int ringCount;  /* number of buffered samples */

    /* ------------------------------------------------------------------ */
    /* Construction                                                       */
    /* ------------------------------------------------------------------ */

    public Sid(int clockHz, int sampleRate) {
        if (clockHz <= 0) {
            clockHz = 985248;
        }
        if (sampleRate <= 0) {
            sampleRate = 44100;
        }
        this.clockHz = clockHz;
        this.sampleRate = sampleRate;

        /* Capacity >= sampleRate (one second of audio); +1 keeps the
         * head/tail distinguishable but we track count explicitly anyway. */
        this.ringCap = sampleRate + 1;
        this.ring = new short[ringCap];

        initBiquads();
        reset();
    }

    /* ------------------------------------------------------------------ */
    /* Reset                                                              */
    /* ------------------------------------------------------------------ */

    public void reset() {
        for (int i = 0; i < reg.length; i++) {
            reg[i] = 0;
        }
        for (int v = 0; v < 3; v++) {
            acc[v] = 0;
            freq[v] = 0;
            pw[v] = 0;
            ctrl[v] = 0;
            prevMsb[v] = false;
            lfsr[v] = NSEED;
            envState[v] = RELEASE;
            nextState[v] = RELEASE;
            envCounter[v] = 0;
            rateCounter[v] = 0;
            envRatePeriod[v] = RATE_PERIOD[0];   /* release nibble = 0 at reset */
            expCounter[v] = 0;
            expPeriod[v] = 1;
            newExpPeriod[v] = 0;
            envPipeline[v] = 0;
            expPipeline[v] = 0;
            statePipeline[v] = 0;
            resetRateCounter[v] = false;
            holdZero[v] = false;
            gate[v] = false;
        }
        ic1eq = 0.0;
        ic2eq = 0.0;
        for (int s = 0; s < 3; s++) { bqZ1[s] = 0.0; bqZ2[s] = 0.0; }
        dcLastIn = 0.0;
        dcLastOut = 0.0;
        sampleAcc = 0;
        ringHead = 0;
        ringTail = 0;
        ringCount = 0;
    }

    /* ------------------------------------------------------------------ */
    /* Register access                                                    */
    /* ------------------------------------------------------------------ */

    public void write(int r, int value) {
        r &= 0x1f;
        value &= 0xff;
        reg[r] = value;

        switch (r) {
            /* Voice 0 */
            case 0x00: freq[0] = (freq[0] & 0xff00) | value;            break;
            case 0x01: freq[0] = (freq[0] & 0x00ff) | (value << 8);     break;
            case 0x02: pw[0]   = (pw[0]   & 0x0f00) | value;            break;
            case 0x03: pw[0]   = (pw[0]   & 0x00ff) | ((value & 0x0f) << 8); break;
            case 0x04: writeControl(0, value);                          break;
            case 0x05: writeAD(0); break;
            case 0x06: writeSR(0); break;

            /* Voice 1 */
            case 0x07: freq[1] = (freq[1] & 0xff00) | value;            break;
            case 0x08: freq[1] = (freq[1] & 0x00ff) | (value << 8);     break;
            case 0x09: pw[1]   = (pw[1]   & 0x0f00) | value;            break;
            case 0x0a: pw[1]   = (pw[1]   & 0x00ff) | ((value & 0x0f) << 8); break;
            case 0x0b: writeControl(1, value);                          break;
            case 0x0c: writeAD(1); break;
            case 0x0d: writeSR(1); break;

            /* Voice 2 */
            case 0x0e: freq[2] = (freq[2] & 0xff00) | value;            break;
            case 0x0f: freq[2] = (freq[2] & 0x00ff) | (value << 8);     break;
            case 0x10: pw[2]   = (pw[2]   & 0x0f00) | value;            break;
            case 0x11: pw[2]   = (pw[2]   & 0x00ff) | ((value & 0x0f) << 8); break;
            case 0x12: writeControl(2, value);                          break;
            case 0x13: writeAD(2); break;
            case 0x14: writeSR(2); break;

            default:
                /* ADSR registers (0x05/0x06, 0x0c/0x0d, 0x13/0x14), the filter
                 * registers (0x15..0x18) and pots are read straight out of the
                 * register file when needed; nothing else to latch here. */
                break;
        }
    }

    private void writeControl(int v, int value) {
        boolean newGate = (value & 0x01) != 0;
        if (newGate != gate[v]) {
            /* The rate counter is NOT reset, so there is a delay before the
             * envelope starts; the state change itself takes 2 cycles. */
            nextState[v] = newGate ? ATTACK : RELEASE;
            statePipeline[v] = 2;
            gate[v] = newGate;
        }

        /* Test bit: while set, the oscillator and noise LFSR are reset. */
        if ((value & 0x08) != 0) {
            acc[v] = 0;
            prevMsb[v] = false;
            lfsr[v] = NSEED;
        }

        ctrl[v] = value;
    }

    public int read(int r) {
        r &= 0x1f;
        switch (r) {
            case 0x19:
                /* POTX: not emulated, return 0xff. */
                return 0xff;
            case 0x1a:
                /* POTY: carries the joystick's second fire button if wired. */
                return potY != null ? potY.getAsInt() & 0xff : 0xff;
            case 0x1b:
                /* Voice 3 oscillator: top 8 bits of its current waveform output. */
                return (oscOutput(2) >> 4) & 0xff;
            case 0x1c:
                /* Voice 3 envelope output (8-bit). */
                return envCounter[2] & 0xff;
            default:
                /* Other registers are write-only on a real SID; behave benignly. */
                return reg[r] & 0xff;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Clocking                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Whether the output chain must run. False = only the oscillator/envelope
     * state advances (still exact for $D41B/$D41C) and no samples are produced.
     * Re-enabling clears the anti-alias history so the first sample cannot carry
     * a stale value from before the gap.
     */
    public void setAudioNeeded(boolean needed) {
        if (needed && !audioNeeded) {
            for (int s = 0; s < 3; s++) { bqZ1[s] = 0.0; bqZ2[s] = 0.0; }
        }
        audioNeeded = needed;
    }

    public boolean isAudioNeeded() { return audioNeeded; }

    public void clock(int cpuCycles) {
        if (!audioNeeded) {
            for (int c = 0; c < cpuCycles; c++) clockOnce();
            return;
        }
        for (int c = 0; c < cpuCycles; c++) {
            clockOnce();

            /* Mix + filter every SID clock, run the result through the anti-alias
             * low-pass, then decimate: emit a band-limited output sample when the
             * fractional accumulator has advanced by one output period. */
            double cyc = antiAlias(mixCycle());
            sampleAcc += sampleRate;
            if (sampleAcc >= clockHz) {
                sampleAcc -= clockHz;
                push(finalizeSample(cyc));
            }
        }
    }

    /** Advance every oscillator, the noise LFSRs and all envelopes by one PHI2 cycle. */
    private void clockOnce() {
        /* --- Oscillators (with hard sync and ring mod handled at read time) --- */
        for (int v = 0; v < 3; v++) {
            boolean testBit = (ctrl[v] & 0x08) != 0;
            if (testBit) {
                /* Test bit holds the accumulator at zero and reseeds noise. */
                acc[v] = 0;
                prevMsb[v] = false;
                continue;
            }

            int prev = acc[v];
            int next = (prev + freq[v]) & 0xffffff;
            acc[v] = next;

            /* Noise LFSR is clocked on the rising edge of accumulator bit 19. */
            boolean bit19Before = (prev & 0x080000) != 0;
            boolean bit19After  = (next & 0x080000) != 0;
            if (!bit19Before && bit19After) {
                clockNoise(v);
            }
        }

        /* --- Hard sync: a voice with the sync bit set is reset when the
         * previous (modulator) voice's accumulator MSB has a rising edge. --- */
        for (int v = 0; v < 3; v++) {
            int src = (v + 2) % 3; /* previous voice (vprev) */
            boolean srcMsb = (acc[src] & 0x800000) != 0;
            boolean srcMsbPrev = prevMsb[src];
            if ((ctrl[v] & 0x02) != 0) {
                /* Rising edge on modulator MSB triggers hard sync. */
                if (!srcMsbPrev && srcMsb) {
                    acc[v] = 0;
                }
            }
        }
        for (int v = 0; v < 3; v++) {
            prevMsb[v] = (acc[v] & 0x800000) != 0;
        }

        /* --- Envelopes --- */
        for (int v = 0; v < 3; v++) {
            clockEnvelope(v);
        }
    }

    /** Clock the 23-bit Galois-style noise LFSR (Fibonacci taps at bits 22 and 17). */
    private void clockNoise(int v) {
        int s = lfsr[v];
        int bit0 = ((s >> 22) ^ (s >> 17)) & 0x01;
        lfsr[v] = ((s << 1) | bit0) & 0x7fffff;
    }

    /* ------------------------------------------------------------------ */
    /* Envelope generator                                                 */
    /* ------------------------------------------------------------------ */

    /** ADSR register write: update the active rate_period (reSID writeATTACK_DECAY). */
    private void writeAD(int v) {
        if (envState[v] == ATTACK)             envRatePeriod[v] = RATE_PERIOD[(reg[adReg(v)] >> 4) & 0x0f];
        else if (envState[v] == DECAY_SUSTAIN) envRatePeriod[v] = RATE_PERIOD[reg[adReg(v)] & 0x0f];
    }

    /** Sustain/release write: update rate_period if releasing (reSID writeSUSTAIN_RELEASE). */
    private void writeSR(int v) {
        if (envState[v] == RELEASE) envRatePeriod[v] = RATE_PERIOD[reg[srReg(v)] & 0x0f];
    }

    /**
     * One PHI2 clock of the envelope generator — a faithful port of reSID's
     * single-cycle {@code EnvelopeGenerator::clock()}: pipelined 2-cycle state
     * changes, the 1-cycle exponential decrement delay, and the ADSR rate-counter
     * delay bug (the counter wraps the full 15 bits when the period is lowered
     * below it).
     */
    private void clockEnvelope(int v) {
        if (newExpPeriod[v] > 0) {
            expPeriod[v] = newExpPeriod[v];
            newExpPeriod[v] = 0;
        }

        if (statePipeline[v] != 0) {
            stateChange(v);
        }

        /* Envelope step is delayed one cycle when the exp period != 1. */
        if (envPipeline[v] != 0 && --envPipeline[v] == 0) {
            if (!holdZero[v]) {
                if (envState[v] == ATTACK) {
                    envCounter[v] = (envCounter[v] + 1) & 0xff;
                    if (envCounter[v] == 0xff) { nextState[v] = DECAY_SUSTAIN; statePipeline[v] = 2; }
                } else if (envState[v] == DECAY_SUSTAIN || envState[v] == RELEASE) {
                    envCounter[v] = (envCounter[v] - 1) & 0xff;
                }
                setExpCounter(v);
            }
        }

        if (expPipeline[v] != 0 && --expPipeline[v] == 0) {
            expCounter[v] = 0;
            int sustain = (reg[srReg(v)] >> 4) & 0x0f;
            if ((envState[v] == DECAY_SUSTAIN && envCounter[v] != SUSTAIN_LEVEL[sustain])
                    || envState[v] == RELEASE) {
                envPipeline[v] = 1;
            }
        } else if (resetRateCounter[v]) {
            rateCounter[v] = 0;
            resetRateCounter[v] = false;
            if (envState[v] == ATTACK) {
                expCounter[v] = 0;
                envPipeline[v] = 2;
            } else {
                if (++expCounter[v] == expPeriod[v]) {
                    expPipeline[v] = (expPeriod[v] != 1) ? 2 : 1;
                }
            }
        }

        /* ADSR delay bug: if the period is set below the current counter, count
         * up to the 15-bit wrap before the envelope can step. */
        if (rateCounter[v] != envRatePeriod[v]) {
            rateCounter[v]++;
            if ((rateCounter[v] & 0x8000) != 0) rateCounter[v] = (rateCounter[v] + 1) & 0x7fff;
        } else {
            resetRateCounter[v] = true;
        }
    }

    /** Pipelined state transition (reSID state_change): the 2-cycle gate→state delay. */
    private void stateChange(int v) {
        statePipeline[v]--;
        int attack  = (reg[adReg(v)] >> 4) & 0x0f;
        int decay   =  reg[adReg(v)]       & 0x0f;
        int release =  reg[srReg(v)]       & 0x0f;
        switch (nextState[v]) {
            case ATTACK:
                if (statePipeline[v] == 1) {
                    envState[v] = ATTACK;
                    envRatePeriod[v] = RATE_PERIOD[decay];   /* decay "accidentally" active cycle 1 */
                } else if (statePipeline[v] == 0) {
                    envRatePeriod[v] = RATE_PERIOD[attack];
                    holdZero[v] = false;
                }
                break;
            case DECAY_SUSTAIN:
                if (statePipeline[v] == 0) {
                    envState[v] = DECAY_SUSTAIN;
                    envRatePeriod[v] = RATE_PERIOD[decay];
                }
                break;
            case RELEASE:
                if ((envState[v] == ATTACK && statePipeline[v] == 0)
                        || (envState[v] == DECAY_SUSTAIN && statePipeline[v] == 1)) {
                    envState[v] = RELEASE;
                    envRatePeriod[v] = RATE_PERIOD[release];
                }
                break;
            case FREEZED:
                if (statePipeline[v] == 0) { envState[v] = FREEZED; holdZero[v] = true; }
                break;
        }
    }

    /** Delayed-load of the exponential divider period at the segment breakpoints. */
    private void setExpCounter(int v) {
        switch (envCounter[v]) {
            case 0xff: newExpPeriod[v] = 1;  break;
            case 0x5d: newExpPeriod[v] = 2;  break;   /* 93 */
            case 0x36: newExpPeriod[v] = 4;  break;   /* 54 */
            case 0x1a: newExpPeriod[v] = 8;  break;   /* 26 */
            case 0x0e: newExpPeriod[v] = 16; break;   /* 14 */
            case 0x06: newExpPeriod[v] = 30; break;   /* 6  */
            case 0x00: newExpPeriod[v] = 1; nextState[v] = FREEZED; statePipeline[v] = 2; break;
            default: break;
        }
    }

    private static int adReg(int v) {
        return v == 0 ? 0x05 : (v == 1 ? 0x0c : 0x13);
    }

    private static int srReg(int v) {
        return v == 0 ? 0x06 : (v == 1 ? 0x0d : 0x14);
    }

    /* ------------------------------------------------------------------ */
    /* Oscillator / waveform output                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Compute the 12-bit waveform output for a voice (0..4095). Combined
     * waveforms are approximated by ANDing the contributing single waveforms,
     * which is the documented acceptable approximation.
     */
    /**
     * Waveform output using reSID's 6581 sample tables. Combined waveforms (the
     * tri/saw/pulse mixes) are looked up from the chip-measured tables rather
     * than approximated by AND; pure waveforms are computed. The selected table
     * is indexed by the top 12 bits of the (ring-modulated) accumulator, then
     * gated by the pulse and noise outputs.
     */
    private int oscOutput(int v) {
        int w = (ctrl[v] >> 4) & 0x0f;
        if (w == 0) return 0;
        int a = acc[v];

        int ix;
        if ((ctrl[v] & 0x04) != 0) {                  // ring modulation
            int src = (v + 2) % 3;
            int ringMask = ~acc[src] & 0x800000;
            ix = ((a ^ ringMask) >> 12) & 0x0fff;
        } else {
            ix = (a >> 12) & 0x0fff;
        }

        int out = MODEL_WAVE[w & 0x07][ix];
        if ((w & 0x04) != 0) out &= pulse(v, a);      // pulse gate
        if ((w & 0x08) != 0) out &= noiseOutput(v);   // noise gate (combos lock the LFSR)
        return out & 0x0fff;
    }

    // reSID 6581 waveform tables [waveform&7][12-bit index] -> 12-bit output.
    private static final int[][] MODEL_WAVE = buildModelWave();

    private static int[][] buildModelWave() {
        int[][] w = new int[8][4096];
        for (int i = 0; i < 4096; i++) {
            int acc = i << 12;                         // top 12 bits = i
            int msb = acc & 0x800000;
            w[0][i] = 0x0fff;                          // (none)
            w[1][i] = ((acc ^ (msb != 0 ? 0xFFFFFF : 0)) >> 11) & 0x0ffe; // triangle
            w[2][i] = (acc >> 12) & 0x0fff;            // sawtooth
            w[4][i] = 0x0fff;                          // pulse (gated separately)
        }
        w[3] = loadWaveDat("/sid/wave6581__ST.dat");   // saw + triangle
        w[5] = loadWaveDat("/sid/wave6581_P_T.dat");   // pulse + triangle
        w[6] = loadWaveDat("/sid/wave6581_PS_.dat");   // pulse + saw
        w[7] = loadWaveDat("/sid/wave6581_PST.dat");   // pulse + saw + triangle
        return w;
    }

    /** Load a 4096-byte reSID combined-waveform sample table (value << 4 -> 12 bit). */
    private static int[] loadWaveDat(String resource) {
        int[] table = new int[4096];
        try (java.io.InputStream in = Sid.class.getResourceAsStream(resource)) {
            byte[] raw = new byte[4096];
            if (in != null) {
                int off = 0, n;
                while (off < 4096 && (n = in.read(raw, off, 4096 - off)) > 0) off += n;
            }
            for (int i = 0; i < 4096; i++) table[i] = (raw[i] & 0xFF) << 4;
        } catch (java.io.IOException e) {
            for (int i = 0; i < 4096; i++) table[i] = 0;   // fall back to silence
        }
        return table;
    }

    private int triangle(int v, int a) {
        /* Triangle uses the top bits of the accumulator, with the MSB
         * controlling the up/down slope. Ring modulation XORs that MSB with the
         * modulator (previous) voice's MSB. */
        int msbSource = a;
        if ((ctrl[v] & 0x04) != 0) {
            /* Ring mod: replace this voice's MSB contribution with XOR of the
             * previous voice's MSB. */
            int prevV = (v + 2) % 3;
            if ((acc[prevV] & 0x800000) != 0) {
                msbSource ^= 0x800000;
            }
        }
        /* 11 bits from accumulator, folded by the MSB; result widened to 12 bits. */
        int t = (a >> 11) & 0x0fff;
        if ((msbSource & 0x800000) != 0) {
            t ^= 0x0fff;
        }
        return t & 0x0fff;
    }

    private int sawtooth(int a) {
        return (a >> 12) & 0x0fff;
    }

    private int pulse(int v, int a) {
        /* Pulse: high when the top 12 bits of the accumulator are >= the 12-bit
         * pulse-width threshold. A pw of 0 yields constant low; 0xfff constant
         * high. */
        int top = (a >> 12) & 0x0fff;
        return (top >= (pw[v] & 0x0fff)) ? 0x0fff : 0x0000;
    }

    private int noiseOutput(int v) {
        /* Build a 12-bit value from selected LFSR bits, matching the SID's
         * noise output tap layout (bits 22,20,16,13,11,7,4,2). */
        int s = lfsr[v];
        return ((s & 0x400000) >> 11)
             | ((s & 0x100000) >> 10)
             | ((s & 0x010000) >> 7)
             | ((s & 0x002000) >> 5)
             | ((s & 0x000800) >> 4)
             | ((s & 0x000080) >> 1)
             | ((s & 0x000010) << 1)
             | ((s & 0x000004) << 2);
    }

    /* ------------------------------------------------------------------ */
    /* Per-sample mixing + filter                                          */
    /* ------------------------------------------------------------------ */

    /** One SID-clock mix: voices*envelope, filter, master volume + digi DC. */
    private double mixCycle() {
        int filterMask = reg[0x17] & 0x07;
        boolean voice3off = (reg[0x18] & 0x80) != 0;

        double routedSum = 0.0;   /* sum of voices routed through the filter */
        double directSum = 0.0;   /* sum of voices bypassing the filter */

        for (int v = 0; v < 3; v++) {
            /* Signed waveform: centre the 12-bit unsigned output around 0. */
            int wf = oscOutput(v) - 0x800;   /* -2048..2047 */
            int env = envCounter[v] & 0xff;  /* 0..255 */
            double sample = (double) (wf * env); /* approx -522240..522240 */

            boolean routed = (filterMask & (1 << v)) != 0;

            if (v == 2 && voice3off && !routed) {
                /* Voice 3 disabled (3-OFF) only when not routed to the filter. */
                continue;
            }

            if (routed) {
                routedSum += sample;
            } else {
                directSum += sample;
            }
        }

        double filtered = applyFilter(routedSum);
        double mixed = filtered + directSum;

        /* Master volume (low nibble of 0x18), 0..15. */
        int vol = reg[0x18] & 0x0f;
        mixed = mixed * vol / 15.0;

        /* Scale the voice mix into 16-bit range. */
        double voiceScaled = mixed * (32767.0 / (3.0 * 2048.0 * 255.0)) * 3.0;

        /* Volume-DAC DC offset. Writing $D418 steps the output DC; rapidly
         * changing the volume with the voices silent is the classic 4-bit "digi"
         * technique. The anti-alias filter band-limits the steps; the DC blocker
         * in finalizeSample removes the steady part. */
        return voiceScaled + vol * DIGI_LEVEL;
    }

    /** Output-rate stage: DC-blocking high-pass + clamp to 16-bit. */
    private short finalizeSample(double pre) {
        double out = pre - dcLastIn + DC_R * dcLastOut;   /* y = x - x_prev + R*y_prev */
        dcLastIn = pre;
        dcLastOut = out;
        if (out > 32767.0) {
            out = 32767.0;
        } else if (out < -32768.0) {
            out = -32768.0;
        }
        return (short) Math.round(out);
    }

    private static final double DIGI_LEVEL = 1100.0;   /* per volume step */
    private static final double DC_R = 0.9985;          /* DC blocker pole */
    private double dcLastIn, dcLastOut;

    /**
     * Zero-delay-feedback (TPT / Cytomic) state-variable filter with the reSID-
     * measured 6581/8580 cutoff curve and resonance ladder. Cutoff from the
     * 11-bit FC register, resonance from the high nibble of 0x17, LP/BP/HP taps
     * selected by bits 4..6 of 0x18. Unity passband gain; no input pre-scaling.
     */
    private double applyFilter(double in) {
        int mode = reg[0x18] & 0x70;
        if (mode == 0) {
            return 0.0;   /* routed voices are silenced when no LP/BP/HP tap is set */
        }

        int fc = ((reg[0x16] & 0xff) << 3) | (reg[0x15] & 0x07);   /* 11-bit */
        int res = (reg[0x17] >> 4) & 0x0f;

        double f0 = cutoffHz(fc);
        double maxF = 0.49 * clockHz;
        if (f0 > maxF) f0 = maxF;

        double g = Math.tan(Math.PI * f0 / clockHz);   /* SVF runs at the SID clock */
        double k = (model == MODEL_8580 ? INVQ_8580 : INVQ_6581)[res];
        double a1 = 1.0 / (1.0 + g * (g + k));
        double a2 = g * a1;
        double a3 = g * a2;

        double v3 = in - ic2eq;
        double v1 = a1 * ic1eq + a2 * v3;
        double v2 = ic2eq + a2 * ic1eq + a3 * v3;
        ic1eq = 2.0 * v1 - ic1eq;
        ic2eq = 2.0 * v2 - ic2eq;

        double out = 0.0;
        if ((mode & 0x10) != 0) out += v2;             /* low-pass  */
        if ((mode & 0x20) != 0) out += v1;             /* band-pass */
        if ((mode & 0x40) != 0) out += in - k * v1 - v2; /* high-pass */
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Output ring buffer                                                  */
    /* ------------------------------------------------------------------ */

    private void push(short sample) {
        if (ringCount == ringCap) {
            /* Overflow: drop the oldest sample. */
            ringHead = (ringHead + 1) % ringCap;
            ringCount--;
        }
        ring[ringTail] = sample;
        ringTail = (ringTail + 1) % ringCap;
        ringCount++;
    }

    public int available() {
        return ringCount;
    }

    public int read(short[] dst, int off, int max) {
        if (dst == null || max <= 0 || off < 0 || off >= dst.length) {
            return 0;
        }
        int n = max;
        if (n > ringCount) {
            n = ringCount;
        }
        if (n > dst.length - off) {
            n = dst.length - off;
        }
        for (int i = 0; i < n; i++) {
            dst[off + i] = ring[ringHead];
            ringHead = (ringHead + 1) % ringCap;
        }
        ringCount -= n;
        return n;
    }

    /* ------------------------------------------------------------------ */
    /* Snapshot                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Serialize every mutable field that affects audio so a save→restore
     * reproduces the chip state exactly. Constant configuration set once in the
     * constructor (clockHz, sampleRate, lookup/wave tables, biquad coefficients)
     * is NOT written. The output ring buffer is pending output, not chip state,
     * and is cleared (not serialized) on load.
     */
    public void saveState(java.io.DataOutputStream o) throws java.io.IOException {
        /* Chip model + downsample accumulator. */
        o.writeByte(model);
        o.writeInt(sampleAcc);

        /* Register file. */
        for (int i = 0; i < reg.length; i++) o.writeByte(reg[i]);

        /* Per-voice oscillator state. */
        for (int v = 0; v < 3; v++) o.writeInt(acc[v]);
        for (int v = 0; v < 3; v++) o.writeShort(freq[v]);
        for (int v = 0; v < 3; v++) o.writeShort(pw[v]);
        for (int v = 0; v < 3; v++) o.writeByte(ctrl[v]);
        for (int v = 0; v < 3; v++) o.writeBoolean(prevMsb[v]);

        /* Noise LFSRs. */
        for (int v = 0; v < 3; v++) o.writeInt(lfsr[v]);

        /* Envelope generator state (full reSID single-cycle model). */
        for (int v = 0; v < 3; v++) o.writeByte(envState[v]);
        for (int v = 0; v < 3; v++) o.writeByte(nextState[v]);
        for (int v = 0; v < 3; v++) o.writeByte(envCounter[v]);
        for (int v = 0; v < 3; v++) o.writeShort(rateCounter[v]);
        for (int v = 0; v < 3; v++) o.writeInt(envRatePeriod[v]);
        for (int v = 0; v < 3; v++) o.writeInt(expCounter[v]);
        for (int v = 0; v < 3; v++) o.writeInt(expPeriod[v]);
        for (int v = 0; v < 3; v++) o.writeInt(newExpPeriod[v]);
        for (int v = 0; v < 3; v++) o.writeInt(envPipeline[v]);
        for (int v = 0; v < 3; v++) o.writeInt(expPipeline[v]);
        for (int v = 0; v < 3; v++) o.writeInt(statePipeline[v]);
        for (int v = 0; v < 3; v++) o.writeBoolean(resetRateCounter[v]);
        for (int v = 0; v < 3; v++) o.writeBoolean(holdZero[v]);
        for (int v = 0; v < 3; v++) o.writeBoolean(gate[v]);

        /* Filter state-variable integrator state. */
        o.writeDouble(ic1eq);
        o.writeDouble(ic2eq);

        /* Anti-alias biquad delay-line state (DF2T). */
        for (int s = 0; s < 3; s++) o.writeDouble(bqZ1[s]);
        for (int s = 0; s < 3; s++) o.writeDouble(bqZ2[s]);

        /* Output-rate DC-blocking high-pass state. */
        o.writeDouble(dcLastIn);
        o.writeDouble(dcLastOut);
    }

    /** Restore the state written by {@link #saveState}; byte order must match. */
    public void loadState(java.io.DataInputStream in) throws java.io.IOException {
        /* Chip model + downsample accumulator. */
        model = (in.readUnsignedByte() == MODEL_8580) ? MODEL_8580 : MODEL_6581;
        sampleAcc = in.readInt();

        /* Register file. */
        for (int i = 0; i < reg.length; i++) reg[i] = in.readUnsignedByte();

        /* Per-voice oscillator state. */
        for (int v = 0; v < 3; v++) acc[v] = in.readInt();
        for (int v = 0; v < 3; v++) freq[v] = in.readUnsignedShort();
        for (int v = 0; v < 3; v++) pw[v] = in.readUnsignedShort();
        for (int v = 0; v < 3; v++) ctrl[v] = in.readUnsignedByte();
        for (int v = 0; v < 3; v++) prevMsb[v] = in.readBoolean();

        /* Noise LFSRs. */
        for (int v = 0; v < 3; v++) lfsr[v] = in.readInt();

        /* Envelope generator state (full reSID single-cycle model). */
        for (int v = 0; v < 3; v++) envState[v] = in.readUnsignedByte();
        for (int v = 0; v < 3; v++) nextState[v] = in.readUnsignedByte();
        for (int v = 0; v < 3; v++) envCounter[v] = in.readUnsignedByte();
        for (int v = 0; v < 3; v++) rateCounter[v] = in.readUnsignedShort();
        for (int v = 0; v < 3; v++) envRatePeriod[v] = in.readInt();
        for (int v = 0; v < 3; v++) expCounter[v] = in.readInt();
        for (int v = 0; v < 3; v++) expPeriod[v] = in.readInt();
        for (int v = 0; v < 3; v++) newExpPeriod[v] = in.readInt();
        for (int v = 0; v < 3; v++) envPipeline[v] = in.readInt();
        for (int v = 0; v < 3; v++) expPipeline[v] = in.readInt();
        for (int v = 0; v < 3; v++) statePipeline[v] = in.readInt();
        for (int v = 0; v < 3; v++) resetRateCounter[v] = in.readBoolean();
        for (int v = 0; v < 3; v++) holdZero[v] = in.readBoolean();
        for (int v = 0; v < 3; v++) gate[v] = in.readBoolean();

        /* Filter state-variable integrator state. */
        ic1eq = in.readDouble();
        ic2eq = in.readDouble();

        /* Anti-alias biquad delay-line state (DF2T). */
        for (int s = 0; s < 3; s++) bqZ1[s] = in.readDouble();
        for (int s = 0; s < 3; s++) bqZ2[s] = in.readDouble();

        /* Output-rate DC-blocking high-pass state. */
        dcLastIn = in.readDouble();
        dcLastOut = in.readDouble();

        /* The ring buffer holds only pending output, not chip state: clear it. */
        ringHead = 0;
        ringTail = 0;
        ringCount = 0;
    }
}
