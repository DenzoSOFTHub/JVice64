package it.denzosoft.jvice64;

import it.denzosoft.jvice64.io.HostGamepad;
import it.denzosoft.jvice64.io.Joystick;
import it.denzosoft.jvice64.io.Keyboard;
import it.denzosoft.jvice64.media.Media;
import it.denzosoft.jvice64.media.ProgramFile;
import it.denzosoft.jvice64.sound.AudioOutput;
import it.denzosoft.jvice64.ui.Display;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;

/**
 * Entry point. Boots the native C64 ROMs and opens the emulator window.
 *
 * Usage:
 *   java -jar jvice64.jar [program.prg|.p00|.t64|.d64]
 *   java -jar jvice64.jar --headless [frames] [program]   (no GUI; prints screen)
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        // Flags are position-independent: "--ntsc --headless 200" is headless too.
        boolean headless = hasFlag(args, "--headless");
        for (String a : args) {
            if (a.startsWith("--") && !KNOWN_FLAGS.contains(a)) {
                System.err.println("Unknown option " + a + " (ignored). " + USAGE);
            }
        }

        // C64 ROMs are NOT bundled (Commodore copyright). They are loaded from a folder
        // remembered in the settings, or auto-found next to the jar / cwd / ~/.jvice64,
        // or (GUI) picked once via a chooser. See README.
        Settings settings = headless ? null : new Settings();
        java.util.List<String> romProblems = new java.util.ArrayList<String>();
        File romsDir = resolveRomsDir(settings, !headless, romProblems);
        if (romsDir == null) {
            String msg = "C64 ROMs not found. Obtain kernal.bin, basic.bin and chargen.bin "
                       + "(and optionally dos1541.bin) and put them in a 'roms' folder next to "
                       + "the jar, set -Djvice64.roms=DIR, or pick the folder when asked.";
            for (String p : romProblems) msg += "\n" + p;
            if (headless) System.err.println(msg);
            else javax.swing.JOptionPane.showMessageDialog(null, msg,
                    "JVice64 — C64 ROMs missing", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        byte[] basic   = loadRomFile(romsDir, "basic.bin");
        byte[] kernal  = loadRomFile(romsDir, "kernal.bin");
        byte[] chargen = loadRomFile(romsDir, "chargen.bin");

        if (headless) {
            runHeadless(basic, kernal, chargen, args);
            return;
        }

        guiErrors = true;
        // CLI flags override the saved value for this run.
        boolean ntsc   = hasFlag(args, "--ntsc")      || "NTSC".equalsIgnoreCase(settings.region());
        boolean s8580  = hasFlag(args, "--8580")      || "8580".equals(settings.sidModel());
        boolean tdrive = hasFlag(args, "--truedrive") || settings.trueDrive();
        Region region = ntsc ? Region.NTSC : Region.PAL;
        Keyboard keyboard = new Keyboard();
        Joystick joystick = new Joystick();
        joystick.setActivePort(settings.joystickPort());
        joystick.setTwoPlayer(settings.twoPlayer());       // split the ports across two players
        joystick.setWasdPlayer2(settings.wasdPlayer2());
        joystick.setBinding(1, settings.joyBinding(1));    // editable keys (Controls menu)
        joystick.setBinding(2, settings.joyBinding(2));
        AudioOutput audio = new AudioOutput(C64.SAMPLE_RATE);
        Display display = new Display(keyboard, joystick, 2, settings);
        errorDisplay = display;
        C64 c64 = new C64(basic, kernal, chargen, keyboard, joystick, display, audio, region);
        c64.setSidModel(s8580 ? it.denzosoft.jvice64.sid.Sid.MODEL_8580
                              : it.denzosoft.jvice64.sid.Sid.MODEL_6581);
        c64.setAudioEnabled(settings.audioEnabled());
        audio.setVolume(settings.volume());      // SID playback volume (0..100), remembered

        // Attach a real 1541 (experimental true-drive; remembered or --truedrive).
        // A wrong-size DOS ROM is treated as absent instead of booting garbage.
        File dos = new File(romsDir, "dos1541.bin");
        if (dos.isFile() && dos.length() != 16384) {
            System.err.println("Ignoring " + dos + ": " + dos.length()
                    + " bytes, a 1541 DOS ROM is 16384 (true drive unavailable).");
        } else if (dos.isFile()) {
            try {
                c64.attachDrive(java.nio.file.Files.readAllBytes(dos.toPath()));
                if (tdrive) {
                    c64.setTrueDrive(true);
                    System.out.println("True 1541 drive enabled (experimental).");
                }
            } catch (IOException | IllegalArgumentException e) {   // drive ROM optional
                System.err.println("True 1541 drive unavailable: " + e.getMessage());
            }
        }
        // NB: CLI flags are run-only overrides — they are deliberately NOT written back to
        // Settings (a one-off --truedrive/--ntsc/--8580 must not become a sticky default).
        // Only menu actions persist; region/SID have no menu, so they stay flag-only.

        display.setOnReset(c64::requestReset);
        // The menu item shows (and the settings keep) what really happened: without a
        // drive ROM the request is refused. setTrueDrive is queued for the emulation
        // thread, so the result is computed here rather than read back.
        display.setOnTrueDrive(on -> {
            c64.setTrueDrive(on);
            return on && c64.hasDrive();
        });
        display.setTrueDriveState(c64.hasDrive(), tdrive && c64.hasDrive());
        display.setOnSaveState(c64::requestSaveSnapshot);
        display.setOnLoadState(c64::requestLoadSnapshot);
        // Snapshot results come from the emulation thread; Display's helpers are thread-safe.
        c64.setOnSnapshot((f, save, err) -> {
            if (err == null) {
                display.showStatus((save ? "State saved: " : "State loaded: ") + f.getName());
            } else {
                String msg = (save ? "Save state failed: " : "Load state failed: ") + err.getMessage();
                System.err.println(msg);
                display.showError(save ? "Save state" : "Load state", f.getName() + "\n" + msg);
            }
        });
        display.setOnDetachCartridge(c64::detachCartridge, c64::hasCartridge);
        display.setOnRewindTape(c64::rewindTape, c64::hasTape);
        display.setOnPause(c64::togglePause);
        display.setOnToggleAudio(c64::toggleAudio);
        display.setOnVolume(audio::setVolume);                                  // SID playback volume slider
        // Video = every 2nd emulated frame, at the EXACT emulated rate
        // clock / (lines * cycles per line * 2) (PAL 25.062 fps, not 25.000): audio is
        // produced at 44100 samples per emulated second, so a rounded rate would let
        // picture and sound drift apart (150 ms per minute on PAL).
        display.setRecordingParams(region.clockHz, region.lines * region.cyclesPerLine * 2,
                                   C64.SAMPLE_RATE);
        // The recording tap is installed only while a recording runs: a permanent tap
        // kept the SID output chain (most of the CPU time) running with audio off.
        display.setOnRecordingChanged(on -> c64.setAudioTap(on ? display::recordAudio : null));

        // Optional USB gamepads (Linux /dev/input/jsN; no-op elsewhere). The first pad
        // drives player 1, a second pad drives player 2 — so two people can play with
        // two controllers, independently of the keyboard.
        HostGamepad pad1 = new HostGamepad(joystick, 1, 0);
        HostGamepad pad2 = new HostGamepad(joystick, 2, 1);
        String p1name = pad1.start();
        if (p1name != null) System.out.println("USB joystick (player 1) detected: " + p1name);
        String p2name = pad2.start();
        if (p2name != null) System.out.println("USB joystick (player 2) detected: " + p2name);
        display.setOnDetectGamepad(() -> {
            if (!pad1.isRunning()) pad1.start();          // hot-plug rescan
            if (!pad2.isRunning()) pad2.start();
            // isConnected(): on Windows the helper starts at once but only knows about
            // a pad once it has reported one, so "running" alone is not "found".
            if (!pad1.isConnected()) {
                return "No USB joystick found (Linux /dev/input/jsN, or Windows 10+ XInput pad)."
                        + (pad1.isRunning() ? "\nOn Windows the pad may need a second: try again." : "");
            }
            String msg = "Player 1 pad: " + pad1.source();
            msg += pad2.isConnected()
                    ? "\nPlayer 2 pad: " + pad2.source()
                      + "\n\nEnable Controls -> \"Two players\" to give them separate ports."
                    : "\nPlayer 2 pad: none (plug in a second controller).";
            return msg;
        });
        display.setOnLaunchGame(game -> {
            try {
                // Parse first, so a bad disk changes nothing. Reset, attach and load
                // then run on the emulation thread in this order.
                ProgramFile p = it.denzosoft.jvice64.media.D64
                        .open(java.nio.file.Files.readAllBytes(game.disk.toPath())).read(game.entry);
                c64.requestReset();                  // back to a clean machine
                c64.attach(game.disk);               // mount the disk on device 8 for in-game loads
                c64.load(p, true);                   // inject + autostart once BASIC boots
                display.setCurrentTitle(game.name);  // per-game snapshot slot
                settings.addRecent(game.name, game.disk, game.entry);
                display.rebuildRecentMenu();
                System.out.println("Launching " + game.name + " (vol " + game.volume + ", #" + game.number + ")");
            } catch (Exception ex) {
                reportError("Launch failed", game.disk, ex);
            }
        });
        display.setOnOpenFile(f -> {
            try {
                openMedia(c64, f);                        // device 8 mount + autostart, .crt, or .tap
                display.setCurrentTitle(baseName(f));     // per-game snapshot slot
                settings.addRecent(baseName(f), f, null);
                display.rebuildRecentMenu();
                if (isTape(f)) display.showStatus(TAPE_HINT + f.getName());
            } catch (Exception ex) {
                reportError("Load failed", f, ex);
            }
        });

        // File > Open recent: replay exactly how the program was loaded the first
        // time — a disk entry goes back through the D64, anything else through the
        // normal auto-loader.
        display.setOnOpenRecent(r -> {
            File f = r.file();
            if (!f.isFile()) {
                display.showMessage("Open recent", "File no longer there:\n" + r.path,
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                display.rebuildRecentMenu();          // drops entries that vanished
                return;
            }
            try {
                if (r.isDiskEntry()) {
                    ProgramFile p = it.denzosoft.jvice64.media.D64
                            .open(java.nio.file.Files.readAllBytes(f.toPath())).read(r.entry);
                    c64.requestReset();
                    c64.attach(f);
                    c64.load(p, true);
                } else {
                    openMedia(c64, f);
                    if (isTape(f)) display.showStatus(TAPE_HINT + f.getName());
                }
                display.setCurrentTitle(r.title.isEmpty() ? baseName(f) : r.title);
                settings.addRecent(r.title, f, r.entry);   // move back to the top
                display.rebuildRecentMenu();
            } catch (Exception ex) {
                reportError("Open recent failed", f, ex);
            }
        });
        display.setOnAttachFile(f -> {
            try {
                c64.attach(f);
                c64.requestReset();                   // clean machine to LOAD from
                display.setCurrentTitle(baseName(f));
            } catch (Exception ex) {
                reportError("Attach failed", f, ex);
            }
        });

        // Optional program on the command line. A bad file is reported and the
        // window still opens (an exception here used to leave a JVM with no window).
        for (String a : args) {
            if (!a.startsWith("--")) {
                File f = new File(a);
                try {
                    openMedia(c64, f);
                    System.out.println(c64.isCartridge(f) ? "Inserted cartridge: " + f.getName()
                                     : isTape(f) ? TAPE_HINT + f.getName()
                                                 : "Queued: " + f.getName());
                    display.setCurrentTitle(baseName(f));   // per-game snapshot slot
                    settings.addRecent(baseName(f), f, null);
                    display.rebuildRecentMenu();
                } catch (Exception ex) {
                    reportError("Could not load", f, ex);
                }
                break;
            }
        }

        // If the emulation thread dies on an unexpected error, say so instead of
        // leaving a frozen window behind.
        c64.setOnCrash(t -> display.showError("JVice64 — emulation stopped",
                "Emulation stopped on an unexpected error:\n\n"
                        + t + "\n\nSee the console for the full stack trace."));

        SwingUtilities.invokeLater(display::show);
        if (settings.loadProblem() != null) display.showError("JVice64 — settings", settings.loadProblem());
        Thread emu = new Thread(c64::run, "jvice-emu");
        emu.setDaemon(false);
        // Window close / File > Quit: stop the emulation loop so it closes the audio
        // line, then let the JVM exit (Display finalises a running recording first).
        display.setOnExit(() -> {
            c64.stop();
            try { emu.join(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        emu.start();
    }

    /**
     * Open a media file the way File > Open does: a cartridge is inserted (the
     * machine resets into it), a tape is mounted (LOAD from the datasette), anything
     * else is mounted on device 8 and its first program autostarts. The program is
     * parsed BEFORE anything is mounted, so a bad file leaves the machine untouched.
     *
     * <p>An autostart resets the machine first: the program is injected only once
     * BASIC is idle, which never happens while a game is running, so without the
     * reset File &gt; Open did nothing and the program started much later, on the
     * next unrelated reset. The tasks run on the emulation thread in this order:
     * mount, reset, load.</p>
     */
    static void openMedia(C64 c64, File f) throws IOException {
        if (c64.isCartridge(f) || isTape(f)) {
            c64.attach(f);
            return;
        }
        ProgramFile p = Media.loadAuto(f);
        c64.attach(f);           // parses the image first; throws before anything is queued
        c64.requestReset();      // back to a clean machine that reaches the BASIC idle loop
        c64.load(p, true);
    }

    /** A datasette image: mounted only; the user types LOAD (the KERNAL then starts the tape). */
    private static boolean isTape(File f) {
        return f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".tap");
    }

    private static final String TAPE_HINT = "Tape inserted (type LOAD and press RETURN): ";

    private static volatile boolean guiErrors;   // show load errors as dialogs (GUI only)
    private static volatile Display errorDisplay; // parent window for those dialogs

    /** Report a failed user action on stderr and, in the GUI, in a dialog over the window. */
    private static void reportError(String what, File f, Exception ex) {
        String detail = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        String msg = what + (f != null ? ": " + f.getName() : "") + "\n" + detail;
        System.err.println(msg.replace('\n', ' '));
        if (!guiErrors) return;
        Display d = errorDisplay;
        if (d != null) { d.showError("JVice64", msg); return; }
        SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(null, msg,
                "JVice64", javax.swing.JOptionPane.ERROR_MESSAGE));
    }

    /** Options the launcher understands; anything else starting with "--" is reported. */
    private static final java.util.Set<String> KNOWN_FLAGS = new java.util.HashSet<String>(
            java.util.Arrays.asList("--headless", "--ntsc", "--8580", "--truedrive"));
    private static final String USAGE = "Usage: java -jar jvice64.jar [--ntsc] [--8580] [--truedrive] "
            + "[program]  |  --headless [frames] [program]";

    // ---- Headless boot/verify harness --------------------------------------

    private static void runHeadless(byte[] basic, byte[] kernal, byte[] chargen, String[] args) {
        // Flags (anything starting with "--") are read by hasFlag wherever they are; the
        // FIRST all-digit argument is the frame count, the first other one the program.
        int frames = 200;
        boolean framesSeen = false;
        String program = null;
        for (String a : args) {
            if (a.startsWith("--")) continue;
            if (!framesSeen && a.matches("\\d+")) {
                try {
                    frames = Integer.parseInt(a);
                } catch (NumberFormatException tooBig) {
                    System.err.println("Frame count too large: " + a + ". " + USAGE);
                    return;
                }
                framesSeen = true;
            } else if (program == null) {
                program = a;
            }
        }

        Region region = hasFlag(args, "--ntsc") ? Region.NTSC : Region.PAL;
        C64 c64 = new C64(basic, kernal, chargen, new Keyboard(), new Joystick(), null, null, region);
        if (hasFlag(args, "--8580")) c64.setSidModel(it.denzosoft.jvice64.sid.Sid.MODEL_8580);
        if (program != null) {
            try {
                openMedia(c64, new File(program));
            } catch (Exception e) { System.err.println("load: " + e.getMessage()); }
        }
        for (int f = 0; f < frames; f++) c64.stepFrame();

        System.out.println("After " + frames + " frames, screen RAM ($0400):");
        System.out.println(dumpScreen(c64));
    }

    /** Render the 40×25 screen-RAM character matrix to ASCII (for boot verification). */
    private static String dumpScreen(C64 c64) {
        StringBuilder sb = new StringBuilder();
        for (int rowIdx = 0; rowIdx < 25; rowIdx++) {
            for (int col = 0; col < 40; col++) {
                int code = c64.memory().peekRam(0x0400 + rowIdx * 40 + col) & 0x7F;
                sb.append(screenCodeToAscii(code));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static char screenCodeToAscii(int code) {
        if (code == 0x20) return ' ';
        if (code >= 0x01 && code <= 0x1A) return (char) ('A' + code - 1);
        if (code >= 0x30 && code <= 0x39) return (char) ('0' + code - 0x30);
        if (code == 0x00) return '@';
        switch (code) {
            case 0x2E: return '.'; case 0x2C: return ','; case 0x3A: return ':';
            case 0x3B: return ';'; case 0x3F: return '?'; case 0x21: return '!';
            case 0x28: return '('; case 0x29: return ')'; case 0x2F: return '/';
            case 0x2D: return '-'; case 0x2B: return '+'; case 0x3D: return '=';
            case 0x2A: return '*'; case 0x22: return '"';
            default: return (code == 0x20) ? ' ' : '.';
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equals(flag)) return true;
        return false;
    }

    /** File name without its extension — used to name the per-game snapshot slot. */
    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static byte[] loadRomFile(File dir, String name) throws IOException {
        return java.nio.file.Files.readAllBytes(new File(dir, name).toPath());
    }

    /** The three mandatory ROM dumps and their exact sizes. */
    private static final String[] ROM_NAMES = { "kernal.bin", "basic.bin", "chargen.bin" };
    private static final long[]   ROM_SIZES = { 8192, 8192, 4096 };

    /**
     * Why {@code dir} is not a usable C64 ROM folder, or {@code null} when it is:
     * {@code kernal.bin} and {@code basic.bin} must be 8192 bytes and
     * {@code chargen.bin} 4096. Checking the size (not just the name) keeps a
     * truncated download or a combined dump from being saved as the ROM folder and
     * then crashing the machine at power-on.
     */
    public static String romProblem(File dir) {
        if (dir == null || !dir.isDirectory()) return "No such folder: " + dir;
        for (int i = 0; i < ROM_NAMES.length; i++) {
            File f = new File(dir, ROM_NAMES[i]);
            if (!f.isFile()) return "Missing " + ROM_NAMES[i] + " in " + dir;
            if (f.length() != ROM_SIZES[i]) {
                return f + " is " + f.length() + " bytes; it must be exactly " + ROM_SIZES[i]
                        + " (" + (ROM_SIZES[i] / 1024) + " KB)";
            }
        }
        return null;
    }

    /** True if {@code d} holds at least one of the ROM files (so a problem with it is worth reporting). */
    private static boolean looksLikeRomFolder(File d) {
        if (d == null || !d.isDirectory()) return false;
        for (String n : ROM_NAMES) if (new File(d, n).isFile()) return true;
        return false;
    }

    /**
     * Locate the C64 ROM folder: {@code -Djvice64.roms}, then the saved setting,
     * then {@code <jarDir>/roms}, {@code ./roms}, {@code ~/.jvice64/roms}; in the GUI,
     * fall back to a directory chooser. The resolved folder is saved to the settings,
     * and only after it passed {@link #romProblem}; a candidate that fails (even the
     * saved one) is skipped, and what was wrong with it is added to {@code problems}.
     * The system property is an explicit choice for this run, so it beats the saved
     * folder and is NOT written back (like {@code --ntsc}, it is run-only).
     */
    static File resolveRomsDir(Settings settings, boolean allowPrompt,
                               java.util.List<String> problems) {
        java.util.List<File> cands = new java.util.ArrayList<>();
        String sys = System.getProperty("jvice64.roms");
        File sysDir = (sys != null && !sys.isEmpty()) ? new File(sys) : null;
        if (sysDir != null) cands.add(sysDir);
        if (settings != null && !settings.romsDir().isEmpty()) cands.add(new File(settings.romsDir()));
        cands.add(new File(Settings.jarDir(), "roms"));
        cands.add(new File(System.getProperty("user.dir", "."), "roms"));
        cands.add(new File(System.getProperty("user.home", "."), ".jvice64/roms"));
        for (File d : cands) {
            String problem = romProblem(d);
            if (problem == null) {
                if (settings != null && d != sysDir) settings.setRomsDir(d.getAbsolutePath());
                return d;
            }
            if (looksLikeRomFolder(d) && !problems.contains(problem)) {
                problems.add(problem);
                System.err.println("Skipping ROM folder: " + problem);
            }
        }
        if (!allowPrompt) return null;
        while (true) {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser(Settings.jarDir());
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Select the C64 ROMs folder (kernal.bin, basic.bin, chargen.bin)");
            if (fc.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null;
            File d = fc.getSelectedFile();
            String problem = romProblem(d);
            if (problem == null) {
                if (settings != null) settings.setRomsDir(d.getAbsolutePath());
                return d;
            }
            javax.swing.JOptionPane.showMessageDialog(null,
                "That folder cannot be used:\n" + problem
                    + "\n\nNeeded: kernal.bin (8 KB), basic.bin (8 KB), chargen.bin (4 KB).",
                "C64 ROMs", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }
}
