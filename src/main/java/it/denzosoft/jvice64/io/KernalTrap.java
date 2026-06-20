package it.denzosoft.jvice64.io;

import it.denzosoft.jvice64.cpu.Cpu6510;
import it.denzosoft.jvice64.media.D64;
import it.denzosoft.jvice64.media.Media;
import it.denzosoft.jvice64.media.ProgramFile;
import it.denzosoft.jvice64.mem.C64Memory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KERNAL LOAD trap for device 8 (and 9-11). Intercepts the KERNAL's indirect
 * LOAD vector target ($F4A5) so that {@code LOAD"name",8}, {@code LOAD"name",8,1}
 * and {@code LOAD"$",8} read from a mounted disk image / program file instead of
 * the (unemulated) serial IEC bus. SAVE ($F5ED) is guarded so it reports an
 * error rather than hanging on the serial routines.
 *
 * <p>At $F4A5 the KERNAL state is: A = load/verify flag, $C3/$C4 = requested
 * load address (MEMUSS, set by $F49E from X/Y), $BA = device, $B9 = secondary
 * address, $B7 = filename length, $BB/$BC = filename pointer. The handler
 * services the request in Java, sets the documented return values
 * (carry clear, X/Y and $AE/$AF = end+1 on success; carry set, A = error code on
 * failure) and performs an RTS back to the BASIC caller.</p>
 */
public final class KernalTrap {

    public static final int LOAD_TRAP = 0xF4A5;
    public static final int SAVE_TRAP = 0xF5ED;

    // KERNAL routine entry points for the open/channel/byte-stream calls.
    private static final int OPEN_TRAP = 0xF34A, CLOSE_TRAP = 0xF291;
    private static final int CHKIN_TRAP = 0xF20E, CHKOUT_TRAP = 0xF250;
    private static final int CLRCHN_TRAP = 0xF333, CHRIN_TRAP = 0xF157;
    private static final int CHROUT_TRAP = 0xF1CA, GETIN_TRAP = 0xF13E;

    // Zero-page locations used by the KERNAL LOAD/SAVE/file path.
    private static final int FNLEN = 0xB7, LA = 0xB8, SA = 0xB9, FA = 0xBA;
    private static final int FNADR = 0xBB, VERCK_ADDR = 0x93;
    private static final int MEMUSS = 0xC3, EAL = 0xAE, STATUS = 0x90;
    private static final int DFLTN = 0x99, DFLTO = 0x9A;     // current input/output device

    /** An open device-8 sequential file. */
    private static final class SeqFile {
        int la, fa, sa;
        String name;
        boolean write;
        byte[] readBuf;
        int pos;
        ByteArrayOutputStream writeBuf;
    }

    private final Map<Integer, SeqFile> openFiles = new HashMap<Integer, SeqFile>();
    private int currentInLa = -1, currentOutLa = -1;

    // BASIC error codes returned in A with carry set.
    private static final int ERR_FILE_NOT_FOUND = 4;
    private static final int ERR_DEVICE_NOT_PRESENT = 5;

    private final Cpu6510 cpu;
    private final C64Memory mem;

    private D64 disk;            // mounted D64 (nullable)
    private ProgramFile file;    // mounted single-file container (nullable)
    private File mediaFile;      // host file backing the mount (for persisting writes)
    private String diskName = "JVICE";
    private String diskId = "64";

    public KernalTrap(Cpu6510 cpu, C64Memory mem) {
        this.cpu = cpu;
        this.mem = mem;
    }

    /** Mount an image/program on device 8. {@code null} detaches. */
    public void attach(File f) throws IOException {
        mediaFile = f;
        if (f == null) { disk = null; file = null; return; }
        String name = f.getName().toLowerCase();
        if (name.endsWith(".d64")) {
            disk = D64.open(Files.readAllBytes(f.toPath()));
            file = null;
            diskName = disk.diskName();
            diskId = disk.diskId();
        } else {
            file = Media.loadAuto(f);
            disk = null;
            diskName = file.name;
        }
    }

    public boolean hasMedia() { return disk != null || file != null; }

    /** Clear any open sequential files (on machine reset). */
    public void reset() {
        openFiles.clear();
        currentInLa = -1;
        currentOutLa = -1;
    }

    /**
     * If the CPU is sitting on a LOAD/SAVE trap for a serial device, service it
     * and return true (the caller should not execute the ROM instruction).
     */
    public boolean serviceIfTrapped() {
        int pc = cpu.pc;
        if (pc == LOAD_TRAP && (mem.peekRam(FA) & 0xFF) >= 8) { serviceLoad(); return true; }
        if (pc == SAVE_TRAP && (mem.peekRam(FA) & 0xFF) >= 8) { serviceSave(); return true; }
        if (pc == OPEN_TRAP) return serviceOpen();
        if (openFiles.isEmpty()) return false;        // seq traps inert with no open files
        switch (pc) {
            case CLOSE_TRAP:  return serviceClose();
            case CHKIN_TRAP:  return serviceChkin();
            case CHKOUT_TRAP: return serviceChkout();
            case CLRCHN_TRAP: return serviceClrchn();
            case CHRIN_TRAP:  return serviceReadByte(true);
            case GETIN_TRAP:  return serviceReadByte(false);
            case CHROUT_TRAP: return serviceChrout();
            default: return false;
        }
    }

    // ---- Sequential files (device 8) ---------------------------------------

    private boolean serviceOpen() {
        if ((mem.peekRam(FA) & 0xFF) != 8) return false;     // not device 8 -> ROM
        int la = mem.peekRam(LA);
        int sa = mem.peekRam(SA);
        int fnlen = mem.peekRam(FNLEN);
        int fnadr = mem.peekRam(FNADR) | (mem.peekRam(FNADR + 1) << 8);
        String spec = readName(fnadr, fnlen);

        String[] parts = spec.split(",");
        String name = parts.length > 0 ? parts[0].trim() : "";
        boolean write = false;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim().toUpperCase();
            if (p.equals("W") || p.equals("A")) write = true;
            if (p.equals("R")) write = false;
        }

        SeqFile f = new SeqFile();
        f.la = la; f.fa = 8; f.sa = sa; f.name = name; f.write = write;
        if (write) {
            f.writeBuf = new ByteArrayOutputStream();
        } else {
            byte[] data = (disk != null) ? disk.readRaw(name) : null;
            if (data == null) { mem.pokeRam(STATUS, 0); cpu.a = ERR_FILE_NOT_FOUND; cpu.fc = true; rts(); return true; }
            f.readBuf = data; f.pos = 0;
        }
        openFiles.put(la, f);
        mem.pokeRam(STATUS, 0);
        cpu.fc = false;
        rts();
        return true;
    }

    private boolean serviceChkin() {
        SeqFile f = openFiles.get(cpu.x & 0xFF);
        if (f == null) return false;
        currentInLa = f.la;
        mem.pokeRam(DFLTN, 8);
        cpu.fc = false;
        rts();
        return true;
    }

    private boolean serviceChkout() {
        SeqFile f = openFiles.get(cpu.x & 0xFF);
        if (f == null || !f.write) return false;
        currentOutLa = f.la;
        mem.pokeRam(DFLTO, 8);
        cpu.fc = false;
        rts();
        return true;
    }

    private boolean serviceReadByte(boolean chrin) {
        if ((mem.peekRam(DFLTN) & 0xFF) != 8 || currentInLa < 0) return false;  // keyboard -> ROM
        SeqFile f = openFiles.get(currentInLa);
        if (f == null || f.readBuf == null) return false;
        int b;
        if (f.pos < f.readBuf.length) {
            b = f.readBuf[f.pos++] & 0xFF;
            mem.pokeRam(STATUS, (f.pos >= f.readBuf.length) ? 0x40 : 0);   // EOF on last byte
        } else {
            b = 0;
            mem.pokeRam(STATUS, 0x42);                                      // EOF + read-past-end
        }
        cpu.a = b;
        cpu.fc = false;
        rts();
        return true;
    }

    private boolean serviceChrout() {
        if ((mem.peekRam(DFLTO) & 0xFF) != 8 || currentOutLa < 0) return false; // screen -> ROM
        SeqFile f = openFiles.get(currentOutLa);
        if (f == null || f.writeBuf == null) return false;
        f.writeBuf.write(cpu.a & 0xFF);
        mem.pokeRam(STATUS, 0);
        cpu.fc = false;
        rts();
        return true;
    }

    private boolean serviceClrchn() {
        currentInLa = -1;
        currentOutLa = -1;
        mem.pokeRam(DFLTN, 0);
        mem.pokeRam(DFLTO, 3);
        cpu.fc = false;
        rts();
        return true;
    }

    private boolean serviceClose() {
        int la = cpu.a & 0xFF;
        SeqFile f = openFiles.get(la);
        if (f == null) return false;
        if (f.write && f.writeBuf != null && disk != null) {
            try {
                disk.writeFile(f.name, it.denzosoft.jvice64.media.D64.FT_SEQ, f.writeBuf.toByteArray());
                if (mediaFile != null) Files.write(mediaFile.toPath(), disk.image());
            } catch (Exception e) {
                System.err.println("JVice64: SEQ write failed: " + e.getMessage());
            }
        }
        openFiles.remove(la);
        if (currentInLa == la) currentInLa = -1;
        if (currentOutLa == la) currentOutLa = -1;
        mem.pokeRam(STATUS, 0);
        cpu.fc = false;
        rts();
        return true;
    }

    // ---- LOAD ---------------------------------------------------------------

    private void serviceLoad() {
        int verify = cpu.a & 0xFF;
        int sa = mem.peekRam(SA);
        int fnlen = mem.peekRam(FNLEN);
        int fnadr = mem.peekRam(FNADR) | (mem.peekRam(FNADR + 1) << 8);
        String name = readName(fnadr, fnlen);

        if (!hasMedia()) { loadReturnError(ERR_DEVICE_NOT_PRESENT); return; }

        ProgramFile pf;
        try {
            pf = resolve(name);
        } catch (RuntimeException e) {
            pf = null;
        }
        if (pf == null) { loadReturnError(ERR_FILE_NOT_FOUND); return; }

        // Secondary address 0 -> relocate to the caller's address ($C3/$C4);
        // otherwise honour the file's own load address.
        int target = (sa == 0)
                ? (mem.peekRam(MEMUSS) | (mem.peekRam(MEMUSS + 1) << 8))
                : pf.loadAddress;

        if (verify == 0) {
            mem.loadIntoRam(target, pf.data);
        }
        int end = (target + pf.data.length) & 0xFFFF;

        mem.pokeRam(EAL, end & 0xFF);
        mem.pokeRam(EAL + 1, (end >> 8) & 0xFF);
        mem.pokeRam(STATUS, 0);
        cpu.x = end & 0xFF;
        cpu.y = (end >> 8) & 0xFF;
        cpu.a = 0;
        cpu.fc = false;     // carry clear = success
        rts();
    }

    private void loadReturnError(int code) {
        cpu.a = code;
        cpu.fc = true;      // carry set = error
        mem.pokeRam(STATUS, 0);
        rts();
    }

    // ---- SAVE ---------------------------------------------------------------

    private void serviceSave() {
        // KERNAL SAVE state at $F5ED: start = $C1/$C2, end (exclusive) = $AE/$AF.
        int start = mem.peekRam(0xC1) | (mem.peekRam(0xC2) << 8);
        int end = mem.peekRam(EAL) | (mem.peekRam(EAL + 1) << 8);
        int fnlen = mem.peekRam(FNLEN);
        int fnadr = mem.peekRam(FNADR) | (mem.peekRam(FNADR + 1) << 8);
        String name = readName(fnadr, fnlen);

        if (name.isEmpty()) { saveReturnError(ERR_FILE_NOT_FOUND); return; }
        if (end < start) end = start;

        // Build the PRG image: 2-byte load address + the saved memory range.
        int len = end - start;
        byte[] prg = new byte[len + 2];
        prg[0] = (byte) (start & 0xFF);
        prg[1] = (byte) ((start >> 8) & 0xFF);
        for (int i = 0; i < len; i++) prg[2 + i] = (byte) mem.peekRam((start + i) & 0xFFFF);

        try {
            if (disk != null) {
                disk.writeFile(name, D64.FT_PRG, prg);
                if (mediaFile != null) Files.write(mediaFile.toPath(), disk.image());
            } else {
                writeHostPrg(name, prg);
            }
            mem.pokeRam(STATUS, 0);
            cpu.a = 0;
            cpu.fc = false;     // success
            rts();
        } catch (Exception ex) {
            System.err.println("JVice64: SAVE failed: " + ex.getMessage());
            saveReturnError(ERR_DEVICE_NOT_PRESENT);
        }
    }

    private void saveReturnError(int code) {
        cpu.a = code;
        cpu.fc = true;
        rts();
    }

    /** Fallback when no D64 is mounted: write a standalone .prg next to the mount (or cwd). */
    private void writeHostPrg(String name, byte[] prg) throws IOException {
        String n = name;
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        if (n.startsWith("@")) n = n.substring(1);
        String safe = n.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "OUTPUT";
        if (!safe.toLowerCase().endsWith(".prg")) safe += ".prg";
        File dir = (mediaFile != null && mediaFile.getParentFile() != null)
                ? mediaFile.getParentFile() : new File(".");
        File out = new File(dir, safe);
        Files.write(out.toPath(), prg);
        System.err.println("JVice64: saved " + out.getAbsolutePath() + " (" + prg.length + " bytes)");
    }

    // ---- File resolution ----------------------------------------------------

    private ProgramFile resolve(String name) {
        if (name.startsWith("$")) {
            return buildDirectory();
        }
        String n = name;
        int colon = n.indexOf(':');     // drop "0:" / "@0:" drive prefix
        if (colon >= 0) n = n.substring(colon + 1);
        if (disk != null) {
            return disk.read(n.isEmpty() ? "*" : n);
        }
        return file;                    // single-file container ignores the name
    }

    private String readName(int addr, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            int b = mem.peekRam((addr + i) & 0xFFFF) & 0xFF;
            sb.append(petscii(b));
        }
        return sb.toString();
    }

    private static char petscii(int b) {
        if (b >= 0x20 && b <= 0x5F) return (char) b;        // ASCII-compatible range
        if (b >= 0xC1 && b <= 0xDA) return (char) (b - 0x80); // shifted letters
        return '?';
    }

    // ---- Directory ("$") as a listable BASIC program ------------------------

    private ProgramFile buildDirectory() {
        List<Integer> nums = new ArrayList<Integer>();
        List<byte[]> texts = new ArrayList<byte[]>();

        nums.add(0);
        texts.add(titleLine());

        int used = 0;
        if (disk != null) {
            for (D64.Entry e : disk.directory()) {
                nums.add(e.sizeBlocks);
                texts.add(fileLine(e.name, e.fileType));
                used += e.sizeBlocks;
            }
        } else if (file != null) {
            int blocks = file.data.length / 254 + 1;
            nums.add(blocks);
            texts.add(fileLine(file.name, D64.FT_PRG));
            used = blocks;
        }
        nums.add(Math.max(0, 664 - used));
        texts.add(ascii("BLOCKS FREE."));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int addr = 0x0801;
        for (int i = 0; i < nums.size(); i++) {
            byte[] t = texts.get(i);
            int lineLen = 2 + 2 + t.length + 1;
            int next = (addr + lineLen) & 0xFFFF;
            out.write(next & 0xFF);
            out.write((next >> 8) & 0xFF);
            int ln = nums.get(i) & 0xFFFF;
            out.write(ln & 0xFF);
            out.write((ln >> 8) & 0xFF);
            out.write(t, 0, t.length);
            out.write(0);
            addr = next;
        }
        out.write(0);
        out.write(0);   // end of program
        return new ProgramFile("$", 0x0801, out.toByteArray());
    }

    private byte[] titleLine() {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x12);          // RVS ON
        b.write('"');
        byte[] nm = padded(diskName, 16);
        b.write(nm, 0, nm.length);
        b.write('"');
        b.write(' ');
        byte[] id = padded(diskId, 2);
        b.write(id, 0, id.length);
        b.write(' ');
        b.write('2'); b.write('A');
        return b.toByteArray();
    }

    private byte[] fileLine(String name, int fileType) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(' ');
        b.write('"');
        String nm = name.length() > 16 ? name.substring(0, 16) : name;
        byte[] nb = ascii(nm.toUpperCase());
        b.write(nb, 0, nb.length);
        b.write('"');
        for (int i = nm.length(); i < 16; i++) b.write(' ');
        b.write(' ');
        byte[] ty = ascii(typeName(fileType));
        b.write(ty, 0, ty.length);
        return b.toByteArray();
    }

    private static String typeName(int t) {
        switch (t) {
            case D64.FT_SEQ: return "SEQ";
            case D64.FT_PRG: return "PRG";
            case D64.FT_USR: return "USR";
            case D64.FT_REL: return "REL";
            default: return "DEL";
        }
    }

    private static byte[] padded(String s, int n) {
        byte[] out = new byte[n];
        byte[] src = ascii(s.toUpperCase());
        for (int i = 0; i < n; i++) out[i] = (i < src.length) ? src[i] : (byte) ' ';
        return out;
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            out[i] = (byte) (c < 0x80 ? c : '?');
        }
        return out;
    }

    // ---- Return to caller ---------------------------------------------------

    private void rts() {
        int s = cpu.sp;
        int lo = mem.peekRam(0x0100 | ((s + 1) & 0xFF));
        int hi = mem.peekRam(0x0100 | ((s + 2) & 0xFF));
        cpu.sp = (s + 2) & 0xFF;
        cpu.pc = ((lo | (hi << 8)) + 1) & 0xFFFF;
    }
}
