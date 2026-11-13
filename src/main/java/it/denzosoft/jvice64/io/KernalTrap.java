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
    /** Lowest / highest trap address; keep in sync when a trap is added. */
    private static final int MIN_TRAP_PC = GETIN_TRAP, MAX_TRAP_PC = SAVE_TRAP;

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
        boolean command;              // secondary address 15: DOS command / error channel
        int fileType = D64.FT_SEQ;    // type written on CLOSE
        byte[] readBuf;
        int pos;
        ByteArrayOutputStream writeBuf;
    }

    /** DOS status reported on the command channel (sa 15), e.g. "00, OK,00,00". */
    private String dosStatus = "00, OK,00,00";

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

    /** A parsed device-8 mount, built off the emulation thread by {@link #prepare}. */
    public static final class Mount {
        final File source;
        final D64 disk;
        final ProgramFile file;
        Mount(File source, D64 disk, ProgramFile file) { this.source = source; this.disk = disk; this.file = file; }
    }

    /** Read and parse {@code f} (may throw on bad input); {@code null} means "detach". */
    public static Mount prepare(File f) throws IOException {
        if (f == null) return new Mount(null, null, null);
        if (f.getName().toLowerCase().endsWith(".d64")) {
            return new Mount(f, D64.open(Files.readAllBytes(f.toPath())), null);
        }
        return new Mount(f, null, Media.loadAuto(f));
    }

    /** Install a prepared mount. Call on the emulation thread. */
    public void install(Mount m) {
        mediaFile = m.source;
        disk = m.disk;
        file = m.file;
        if (disk != null) {
            diskName = disk.diskName();
            diskId = disk.diskId();
        } else if (file != null) {
            diskName = file.name;
        }
    }

    /** Mount an image/program on device 8. {@code null} detaches. */
    public void attach(File f) throws IOException {
        install(prepare(f));
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
        // Cheap range pre-filter: this runs once per emulated instruction.
        if (pc < MIN_TRAP_PC || pc > MAX_TRAP_PC) return false;
        // Only the real KERNAL is trapped, never RAM code that happens to sit at
        // the same address while the KERNAL ROM is banked out ($01 = $35/$34/...).
        if (!mem.kernalVisible()) return false;
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
        int rawSa = mem.peekRam(SA) & 0xFF;
        int sa = (rawSa == 0xFF) ? 0 : (rawSa & 0x0F);   // $FF = no secondary address given
        int fnlen = mem.peekRam(FNLEN);
        int fnadr = mem.peekRam(FNADR) | (mem.peekRam(FNADR + 1) << 8);
        String spec = readName(fnadr, fnlen);

        // Secondary address 15 = command / error channel: never fails. The name,
        // if any, is a DOS command; reading returns the status ("00, OK,00,00").
        if (sa == 15) {
            SeqFile f = new SeqFile();
            f.la = la; f.fa = 8; f.sa = sa; f.name = ""; f.command = true; f.write = true;
            f.writeBuf = new ByteArrayOutputStream();
            if (!spec.isEmpty()) executeCommand(spec);
            else if (disk == null && file == null) dosStatus = "74,DRIVE NOT READY,00,00";
            loadStatus(f);
            return openOk(la, f);
        }

        // "$" = the directory, read as the BASIC listing program (load address $0401).
        if (spec.startsWith("$")) {
            SeqFile f = new SeqFile();
            f.la = la; f.fa = 8; f.sa = sa; f.name = "$";
            ProgramFile dir = buildDirectory(0x0401);
            f.readBuf = new byte[dir.data.length + 2];
            f.readBuf[0] = 0x01; f.readBuf[1] = 0x04;
            System.arraycopy(dir.data, 0, f.readBuf, 2, dir.data.length);
            return openOk(la, f);
        }

        // "@0:NAME,S,W" -> "NAME" + options; the drive prefix is not part of the name.
        String[] parts = stripDrivePrefix(spec).split(",");
        String name = parts.length > 0 ? parts[0].trim() : "";
        boolean write = (sa == 1);                 // sa 1 = write (SAVE-style), sa 0 = read
        boolean append = false;
        int type = (sa <= 1) ? D64.FT_PRG : D64.FT_SEQ;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim().toUpperCase(java.util.Locale.ROOT);
            if (p.isEmpty()) continue;
            switch (p.charAt(0)) {
                case 'W': write = true; append = false; break;
                case 'A': write = true; append = true; break;
                case 'R': write = false; append = false; break;
                case 'S': type = D64.FT_SEQ; break;
                case 'P': type = D64.FT_PRG; break;
                case 'U': type = D64.FT_USR; break;
                default: break;
            }
        }

        SeqFile f = new SeqFile();
        f.la = la; f.fa = 8; f.sa = sa; f.name = name; f.write = write; f.fileType = type;
        if (write) {
            f.writeBuf = new ByteArrayOutputStream();
            if (append) {
                // Append keeps what the file already holds (and its type); the old
                // code wrote only the new bytes over it on CLOSE.
                D64.Entry old = (disk != null) ? disk.find(name) : null;
                if (old == null) {
                    dosStatus = "62,FILE NOT FOUND,00,00";
                    return openFailed(ERR_FILE_NOT_FOUND);
                }
                byte[] data = disk.readRaw(name);
                f.writeBuf.write(data, 0, data.length);
                f.name = old.name.trim();
                f.fileType = old.fileType;
            }
        } else {
            byte[] data = (disk != null) ? disk.readRaw(name) : null;
            if (data == null) {
                dosStatus = "62,FILE NOT FOUND,00,00";
                return openFailed(ERR_FILE_NOT_FOUND);
            }
            f.readBuf = data; f.pos = 0;
        }
        dosStatus = "00, OK,00,00";
        return openOk(la, f);
    }

    private boolean openOk(int la, SeqFile f) {
        openFiles.put(la, f);
        mem.pokeRam(STATUS, 0);
        cpu.fc = false;
        rts();
        return true;
    }

    private boolean openFailed(int code) {
        mem.pokeRam(STATUS, 0);
        cpu.a = code;
        cpu.fc = true;
        rts();
        return true;
    }

    /** Drop a leading {@code @} and a {@code 0:} drive prefix. */
    private static String stripDrivePrefix(String spec) {
        String n = spec;
        if (n.startsWith("@")) n = n.substring(1);
        int colon = n.indexOf(':');
        if (colon >= 0) n = n.substring(colon + 1);
        return n;
    }

    /** Refill the command channel's read buffer with the current DOS status. */
    private void loadStatus(SeqFile f) {
        String st = dosStatus + "\r";
        f.readBuf = new byte[st.length()];
        for (int i = 0; i < st.length(); i++) f.readBuf[i] = (byte) st.charAt(i);
        f.pos = 0;
        dosStatus = "00, OK,00,00";       // reading the status clears it, as on the drive
    }

    /** Execute a DOS command sent on the command channel (the few that matter here). */
    private void executeCommand(String cmd) {
        String c = cmd.trim();
        while (c.endsWith("\r")) c = c.substring(0, c.length() - 1);
        if (c.isEmpty()) return;
        char op = Character.toUpperCase(c.charAt(0));
        if (op == 'S' && c.indexOf(':') >= 0 && disk != null) {
            int n = 0;
            for (String nm : c.substring(c.indexOf(':') + 1).split(",")) n += disk.scratch(nm.trim());
            persistDisk();
            dosStatus = String.format("01, FILES SCRATCHED,%02d,00", n);
        } else {
            // I(nitialize), V(alidate), U(ser), M(emory) commands, ...: nothing to do
            // without a real drive; answer OK so the program carries on.
            dosStatus = "00, OK,00,00";
        }
    }

    /** Write the mounted D64 back to its host file. */
    private void persistDisk() {
        if (disk == null || mediaFile == null) return;
        try {
            Files.write(mediaFile.toPath(), disk.image());
        } catch (IOException e) {
            System.err.println("JVice64: disk write failed: " + e.getMessage());
        }
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
        if (f == null || !(f.write || f.command)) return false;
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
        if (f.command && f.pos >= f.readBuf.length) loadStatus(f);   // status read again
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
        flushCommand(currentOutLa);              // PRINT#15,"S:NAME" runs on UNLISTEN
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
        if (f.command) {
            flushCommand(la);
        } else if (f.write && f.writeBuf != null && disk != null) {
            try {
                disk.writeFile(f.name, f.fileType, f.writeBuf.toByteArray());
                if (mediaFile != null) Files.write(mediaFile.toPath(), disk.image());
                dosStatus = "00, OK,00,00";
            } catch (Exception e) {
                dosStatus = "72,DISK FULL,00,00";
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

    /** Run the command buffered on a command channel, if any. */
    private void flushCommand(int la) {
        SeqFile f = la < 0 ? null : openFiles.get(la);
        if (f == null || !f.command || f.writeBuf == null || f.writeBuf.size() == 0) return;
        byte[] b = f.writeBuf.toByteArray();
        f.writeBuf.reset();
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int v = x & 0xFF;
            sb.append(v == 0x0D ? '\r' : petscii(v));
        }
        executeCommand(sb.toString());
        loadStatus(f);
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

    private ProgramFile buildDirectory() { return buildDirectory(0x0801); }

    private ProgramFile buildDirectory(int base) {
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
        int addr = base;
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
        return new ProgramFile("$", base, out.toByteArray());
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
