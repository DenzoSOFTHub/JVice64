package it.denzosoft.jvice64.debug;

/**
 * 6502 / 6510 disassembler and simple machine-state monitor helper.
 *
 * Decodes the full documented opcode set plus the common "illegal" opcodes used
 * by C64 software (LAX, SAX, DCP, ISC, SLO, RLA, SRE, RRA, ANC, ALR, ARR, SBX,
 * the NOP variants, SHA/SHX/SHY/TAS/LAS and ANE). The mnemonic / addressing-mode
 * / instruction-length tables are kept in lock-step with the executing core in
 * {@code it.denzosoft.jvice64.cpu.Cpu6510}.
 *
 * Pure Java 1.8, no external dependencies, deterministic.
 */
public final class Disassembler {

    /** Reads one byte of target memory. Implementations must return 0..255. */
    public interface MemReader {
        int read(int addr);
    }

    private Disassembler() {
    }

    // ---- Addressing modes --------------------------------------------------

    private static final int IMP = 0;   // implied
    private static final int ACC = 1;   // accumulator (operand "A")
    private static final int IMM = 2;   // #$nn
    private static final int ZP  = 3;   // $nn
    private static final int ZPX = 4;   // $nn,X
    private static final int ZPY = 5;   // $nn,Y
    private static final int ABS = 6;   // $nnnn
    private static final int ABX = 7;   // $nnnn,X
    private static final int ABY = 8;   // $nnnn,Y
    private static final int IND = 9;   // ($nnnn)
    private static final int IZX = 10;  // ($nn,X)
    private static final int IZY = 11;  // ($nn),Y
    private static final int REL = 12;  // relative branch

    /** Instruction byte length per addressing mode, indexed by the constants above. */
    private static final int[] MODE_LEN = {
            1, // IMP
            1, // ACC
            2, // IMM
            2, // ZP
            2, // ZPX
            2, // ZPY
            3, // ABS
            3, // ABX
            3, // ABY
            3, // IND
            2, // IZX
            2, // IZY
            2  // REL
    };

    private static final String[] MNE = new String[256];
    private static final int[] MODE = new int[256];

    private static void op(int code, String mne, int mode) {
        MNE[code] = mne;
        MODE[code] = mode;
    }

    static {
        // Default: every undefined slot is an illegal/JAM that the core treats
        // as a 2-cycle, single-byte NOP. Show it explicitly.
        for (int i = 0; i < 256; i++) {
            MNE[i] = "???";
            MODE[i] = IMP;
        }

        // ---- LDA ----
        op(0xA9, "LDA", IMM); op(0xA5, "LDA", ZP);  op(0xB5, "LDA", ZPX);
        op(0xAD, "LDA", ABS); op(0xBD, "LDA", ABX); op(0xB9, "LDA", ABY);
        op(0xA1, "LDA", IZX); op(0xB1, "LDA", IZY);
        // ---- LDX ----
        op(0xA2, "LDX", IMM); op(0xA6, "LDX", ZP);  op(0xB6, "LDX", ZPY);
        op(0xAE, "LDX", ABS); op(0xBE, "LDX", ABY);
        // ---- LDY ----
        op(0xA0, "LDY", IMM); op(0xA4, "LDY", ZP);  op(0xB4, "LDY", ZPX);
        op(0xAC, "LDY", ABS); op(0xBC, "LDY", ABX);
        // ---- STA ----
        op(0x85, "STA", ZP);  op(0x95, "STA", ZPX); op(0x8D, "STA", ABS);
        op(0x9D, "STA", ABX); op(0x99, "STA", ABY); op(0x81, "STA", IZX);
        op(0x91, "STA", IZY);
        // ---- STX ----
        op(0x86, "STX", ZP);  op(0x96, "STX", ZPY); op(0x8E, "STX", ABS);
        // ---- STY ----
        op(0x84, "STY", ZP);  op(0x94, "STY", ZPX); op(0x8C, "STY", ABS);
        // ---- Transfers ----
        op(0xAA, "TAX", IMP); op(0xA8, "TAY", IMP); op(0x8A, "TXA", IMP);
        op(0x98, "TYA", IMP); op(0xBA, "TSX", IMP); op(0x9A, "TXS", IMP);
        // ---- Stack ----
        op(0x48, "PHA", IMP); op(0x68, "PLA", IMP);
        op(0x08, "PHP", IMP); op(0x28, "PLP", IMP);
        // ---- AND ----
        op(0x29, "AND", IMM); op(0x25, "AND", ZP);  op(0x35, "AND", ZPX);
        op(0x2D, "AND", ABS); op(0x3D, "AND", ABX); op(0x39, "AND", ABY);
        op(0x21, "AND", IZX); op(0x31, "AND", IZY);
        // ---- ORA ----
        op(0x09, "ORA", IMM); op(0x05, "ORA", ZP);  op(0x15, "ORA", ZPX);
        op(0x0D, "ORA", ABS); op(0x1D, "ORA", ABX); op(0x19, "ORA", ABY);
        op(0x01, "ORA", IZX); op(0x11, "ORA", IZY);
        // ---- EOR ----
        op(0x49, "EOR", IMM); op(0x45, "EOR", ZP);  op(0x55, "EOR", ZPX);
        op(0x4D, "EOR", ABS); op(0x5D, "EOR", ABX); op(0x59, "EOR", ABY);
        op(0x41, "EOR", IZX); op(0x51, "EOR", IZY);
        // ---- BIT ----
        op(0x24, "BIT", ZP);  op(0x2C, "BIT", ABS);
        // ---- ADC ----
        op(0x69, "ADC", IMM); op(0x65, "ADC", ZP);  op(0x75, "ADC", ZPX);
        op(0x6D, "ADC", ABS); op(0x7D, "ADC", ABX); op(0x79, "ADC", ABY);
        op(0x61, "ADC", IZX); op(0x71, "ADC", IZY);
        // ---- SBC ----
        op(0xE9, "SBC", IMM); op(0xE5, "SBC", ZP);  op(0xF5, "SBC", ZPX);
        op(0xED, "SBC", ABS); op(0xFD, "SBC", ABX); op(0xF9, "SBC", ABY);
        op(0xE1, "SBC", IZX); op(0xF1, "SBC", IZY);
        op(0xEB, "SBC", IMM); // illegal alias of SBC #imm
        // ---- CMP ----
        op(0xC9, "CMP", IMM); op(0xC5, "CMP", ZP);  op(0xD5, "CMP", ZPX);
        op(0xCD, "CMP", ABS); op(0xDD, "CMP", ABX); op(0xD9, "CMP", ABY);
        op(0xC1, "CMP", IZX); op(0xD1, "CMP", IZY);
        // ---- CPX / CPY ----
        op(0xE0, "CPX", IMM); op(0xE4, "CPX", ZP);  op(0xEC, "CPX", ABS);
        op(0xC0, "CPY", IMM); op(0xC4, "CPY", ZP);  op(0xCC, "CPY", ABS);
        // ---- INC / DEC ----
        op(0xE6, "INC", ZP);  op(0xF6, "INC", ZPX); op(0xEE, "INC", ABS); op(0xFE, "INC", ABX);
        op(0xC6, "DEC", ZP);  op(0xD6, "DEC", ZPX); op(0xCE, "DEC", ABS); op(0xDE, "DEC", ABX);
        op(0xE8, "INX", IMP); op(0xC8, "INY", IMP);
        op(0xCA, "DEX", IMP); op(0x88, "DEY", IMP);
        // ---- ASL ----
        op(0x0A, "ASL", ACC); op(0x06, "ASL", ZP);  op(0x16, "ASL", ZPX);
        op(0x0E, "ASL", ABS); op(0x1E, "ASL", ABX);
        // ---- LSR ----
        op(0x4A, "LSR", ACC); op(0x46, "LSR", ZP);  op(0x56, "LSR", ZPX);
        op(0x4E, "LSR", ABS); op(0x5E, "LSR", ABX);
        // ---- ROL ----
        op(0x2A, "ROL", ACC); op(0x26, "ROL", ZP);  op(0x36, "ROL", ZPX);
        op(0x2E, "ROL", ABS); op(0x3E, "ROL", ABX);
        // ---- ROR ----
        op(0x6A, "ROR", ACC); op(0x66, "ROR", ZP);  op(0x76, "ROR", ZPX);
        op(0x6E, "ROR", ABS); op(0x7E, "ROR", ABX);
        // ---- Jumps / calls ----
        op(0x4C, "JMP", ABS); op(0x6C, "JMP", IND);
        op(0x20, "JSR", ABS);
        op(0x60, "RTS", IMP); op(0x40, "RTI", IMP); op(0x00, "BRK", IMP);
        // ---- Branches ----
        op(0x10, "BPL", REL); op(0x30, "BMI", REL); op(0x50, "BVC", REL);
        op(0x70, "BVS", REL); op(0x90, "BCC", REL); op(0xB0, "BCS", REL);
        op(0xD0, "BNE", REL); op(0xF0, "BEQ", REL);
        // ---- Flags ----
        op(0x18, "CLC", IMP); op(0x38, "SEC", IMP); op(0x58, "CLI", IMP);
        op(0x78, "SEI", IMP); op(0xB8, "CLV", IMP); op(0xD8, "CLD", IMP);
        op(0xF8, "SED", IMP);
        // ---- NOP (documented) ----
        op(0xEA, "NOP", IMP);

        // ---- Illegal: NOP variants -------------------------------------------------
        op(0x1A, "NOP", IMP); op(0x3A, "NOP", IMP); op(0x5A, "NOP", IMP);
        op(0x7A, "NOP", IMP); op(0xDA, "NOP", IMP); op(0xFA, "NOP", IMP);
        op(0x80, "NOP", IMM); op(0x82, "NOP", IMM); op(0x89, "NOP", IMM);
        op(0xC2, "NOP", IMM); op(0xE2, "NOP", IMM);
        op(0x04, "NOP", ZP);  op(0x44, "NOP", ZP);  op(0x64, "NOP", ZP);
        op(0x14, "NOP", ZPX); op(0x34, "NOP", ZPX); op(0x54, "NOP", ZPX);
        op(0x74, "NOP", ZPX); op(0xD4, "NOP", ZPX); op(0xF4, "NOP", ZPX);
        op(0x0C, "NOP", ABS);
        op(0x1C, "NOP", ABX); op(0x3C, "NOP", ABX); op(0x5C, "NOP", ABX);
        op(0x7C, "NOP", ABX); op(0xDC, "NOP", ABX); op(0xFC, "NOP", ABX);

        // ---- Illegal: LAX (LDA + LDX) ----
        op(0xA7, "LAX", ZP);  op(0xB7, "LAX", ZPY); op(0xAF, "LAX", ABS);
        op(0xBF, "LAX", ABY); op(0xA3, "LAX", IZX); op(0xB3, "LAX", IZY);
        op(0xAB, "LAX", IMM); // unstable LAX #imm
        // ---- Illegal: SAX (store A & X) ----
        op(0x87, "SAX", ZP);  op(0x97, "SAX", ZPY); op(0x8F, "SAX", ABS);
        op(0x83, "SAX", IZX);
        // ---- Illegal: DCP (DEC + CMP) ----
        op(0xC7, "DCP", ZP);  op(0xD7, "DCP", ZPX); op(0xCF, "DCP", ABS);
        op(0xDF, "DCP", ABX); op(0xDB, "DCP", ABY); op(0xC3, "DCP", IZX);
        op(0xD3, "DCP", IZY);
        // ---- Illegal: ISC (INC + SBC) ----
        op(0xE7, "ISC", ZP);  op(0xF7, "ISC", ZPX); op(0xEF, "ISC", ABS);
        op(0xFF, "ISC", ABX); op(0xFB, "ISC", ABY); op(0xE3, "ISC", IZX);
        op(0xF3, "ISC", IZY);
        // ---- Illegal: SLO (ASL + ORA) ----
        op(0x07, "SLO", ZP);  op(0x17, "SLO", ZPX); op(0x0F, "SLO", ABS);
        op(0x1F, "SLO", ABX); op(0x1B, "SLO", ABY); op(0x03, "SLO", IZX);
        op(0x13, "SLO", IZY);
        // ---- Illegal: RLA (ROL + AND) ----
        op(0x27, "RLA", ZP);  op(0x37, "RLA", ZPX); op(0x2F, "RLA", ABS);
        op(0x3F, "RLA", ABX); op(0x3B, "RLA", ABY); op(0x23, "RLA", IZX);
        op(0x33, "RLA", IZY);
        // ---- Illegal: SRE (LSR + EOR) ----
        op(0x47, "SRE", ZP);  op(0x57, "SRE", ZPX); op(0x4F, "SRE", ABS);
        op(0x5F, "SRE", ABX); op(0x5B, "SRE", ABY); op(0x43, "SRE", IZX);
        op(0x53, "SRE", IZY);
        // ---- Illegal: RRA (ROR + ADC) ----
        op(0x67, "RRA", ZP);  op(0x77, "RRA", ZPX); op(0x6F, "RRA", ABS);
        op(0x7F, "RRA", ABX); op(0x7B, "RRA", ABY); op(0x63, "RRA", IZX);
        op(0x73, "RRA", IZY);
        // ---- Illegal: immediate-AND family ----
        op(0x0B, "ANC", IMM); op(0x2B, "ANC", IMM); // ANC #imm (two encodings)
        op(0x4B, "ALR", IMM); // ALR/ASR #imm
        op(0x6B, "ARR", IMM); // ARR #imm
        op(0xCB, "SBX", IMM); // SBX/AXS #imm
        op(0x8B, "ANE", IMM); // ANE/XAA #imm (unstable)
        // ---- Illegal: store reg AND (high+1) (unstable) ----
        op(0x9F, "SHA", ABY); // SHA/AHX abs,y
        op(0x93, "SHA", IZY); // SHA/AHX (zp),y
        op(0x9E, "SHX", ABY); // SHX abs,y
        op(0x9C, "SHY", ABX); // SHY abs,x
        op(0x9B, "TAS", ABY); // TAS/SHS abs,y
        op(0xBB, "LAS", ABY); // LAS abs,y
    }

    // ---- Public API --------------------------------------------------------

    /**
     * Disassemble one instruction at {@code addr}. Returns the mnemonic + operand
     * text (e.g. {@code "LDA #$01"}) and, via {@code outLen[0]}, the byte length
     * (1..3). {@code outLen} may be null.
     */
    public static String disasm(MemReader mem, int addr, int[] outLen) {
        int op = mem.read(addr) & 0xFF;
        int mode = MODE[op];
        String mne = MNE[op];
        int len = MODE_LEN[mode];
        if (outLen != null) {
            outLen[0] = len;
        }

        int lo = (len > 1) ? (mem.read((addr + 1) & 0xFFFF) & 0xFF) : 0;
        int hi = (len > 2) ? (mem.read((addr + 2) & 0xFFFF) & 0xFF) : 0;
        int word = lo | (hi << 8);

        String operand;
        switch (mode) {
            case IMP: operand = "";                                  break;
            case ACC: operand = "A";                                 break;
            case IMM: operand = "#$" + hex2(lo);                     break;
            case ZP:  operand = "$" + hex2(lo);                      break;
            case ZPX: operand = "$" + hex2(lo) + ",X";               break;
            case ZPY: operand = "$" + hex2(lo) + ",Y";               break;
            case ABS: operand = "$" + hex4(word);                    break;
            case ABX: operand = "$" + hex4(word) + ",X";             break;
            case ABY: operand = "$" + hex4(word) + ",Y";             break;
            case IND: operand = "($" + hex4(word) + ")";             break;
            case IZX: operand = "($" + hex2(lo) + ",X)";             break;
            case IZY: operand = "($" + hex2(lo) + "),Y";             break;
            case REL: {
                int off = (lo < 0x80) ? lo : lo - 0x100;
                int target = (addr + 2 + off) & 0xFFFF;
                operand = "$" + hex4(target);
                break;
            }
            default:  operand = "";                                  break;
        }

        return operand.isEmpty() ? mne : (mne + " " + operand);
    }

    /**
     * Disassemble {@code count} instructions starting at {@code addr}. Each line is
     * formatted {@code "C000  A9 01     LDA #$01"}: the address, the raw bytes, and
     * the decoded instruction.
     */
    public static String[] disasmRange(MemReader mem, int addr, int count) {
        String[] out = new String[count];
        int pc = addr & 0xFFFF;
        int[] len = new int[1];
        for (int i = 0; i < count; i++) {
            String text = disasm(mem, pc, len);
            int n = len[0];

            StringBuilder bytes = new StringBuilder();
            for (int b = 0; b < n; b++) {
                if (b > 0) {
                    bytes.append(' ');
                }
                bytes.append(hex2(mem.read((pc + b) & 0xFFFF) & 0xFF));
            }

            // Up to three "NN " groups -> pad to a fixed 9-char column.
            out[i] = hex4(pc) + "  " + padRight(bytes.toString(), 9) + " " + text;
            pc = (pc + n) & 0xFFFF;
        }
        return out;
    }

    /** Convenience overload for a {@code byte[]} loaded at {@code baseAddr}. */
    public static String[] disasm(byte[] code, int baseAddr, int count) {
        final byte[] data = code;
        final int base = baseAddr & 0xFFFF;
        MemReader mem = new MemReader() {
            @Override
            public int read(int a) {
                int idx = (a & 0xFFFF) - base;
                if (idx < 0 || idx >= data.length) {
                    return 0;
                }
                return data[idx] & 0xFF;
            }
        };
        return disasmRange(mem, base, count);
    }

    /**
     * Format {@code rows} lines of a 16-byte hex dump, each like
     * {@code "C000: 01 08 0B 08 ...  ........"} with an ASCII gutter (printable
     * bytes shown, others as '.').
     */
    public static String hexDump(MemReader mem, int addr, int rows) {
        StringBuilder out = new StringBuilder();
        int base = addr & 0xFFFF;
        for (int r = 0; r < rows; r++) {
            int row = (base + r * 16) & 0xFFFF;
            StringBuilder bytes = new StringBuilder();
            StringBuilder ascii = new StringBuilder();
            for (int c = 0; c < 16; c++) {
                int b = mem.read((row + c) & 0xFFFF) & 0xFF;
                if (c > 0) {
                    bytes.append(' ');
                }
                bytes.append(hex2(b));
                ascii.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
            }
            if (r > 0) {
                out.append('\n');
            }
            out.append(hex4(row)).append(": ").append(bytes).append("  ").append(ascii);
        }
        return out.toString();
    }

    // ---- Formatting helpers ------------------------------------------------

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String hex2(int v) {
        v &= 0xFF;
        return new String(new char[] { HEX[(v >> 4) & 0xF], HEX[v & 0xF] });
    }

    private static String hex4(int v) {
        v &= 0xFFFF;
        return new String(new char[] {
                HEX[(v >> 12) & 0xF], HEX[(v >> 8) & 0xF],
                HEX[(v >> 4) & 0xF], HEX[v & 0xF]
        });
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) {
            b.append(' ');
        }
        return b.toString();
    }
}
