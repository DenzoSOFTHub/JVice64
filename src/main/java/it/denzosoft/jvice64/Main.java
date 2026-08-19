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
        boolean headless = args.length > 0 && args[0].equals("--headless");

        // C64 ROMs are NOT bundled (Commodore copyright). They are loaded from a folder
        // remembered in the settings, or auto-found next to the jar / cwd / ~/.jvice64,
        // or (GUI) picked once via a chooser. See README.
        Settings settings = headless ? null : new Settings();
        File romsDir = resolveRomsDir(settings, !headless);
        if (romsDir == null) {
            String msg = "C64 ROMs not found. Obtain kernal.bin, basic.bin and chargen.bin "
                       + "(and optionally dos1541.bin) and put them in a 'roms' folder next to "
                       + "the jar, set -Djvice64.roms=DIR, or pick the folder when asked.";
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
        C64 c64 = new C64(basic, kernal, chargen, keyboard, joystick, display, audio, region);
        c64.setSidModel(s8580 ? it.denzosoft.jvice64.sid.Sid.MODEL_8580
                              : it.denzosoft.jvice64.sid.Sid.MODEL_6581);
        c64.setAudioEnabled(settings.audioEnabled());
        audio.setVolume(settings.volume());      // SID playback volume (0..100), remembered

        // Attach a real 1541 (experimental true-drive; remembered or --truedrive).
        File dos = new File(romsDir, "dos1541.bin");
        if (dos.isFile()) {
            try {
                c64.attachDrive(java.nio.file.Files.readAllBytes(dos.toPath()));
                if (tdrive) {
                    c64.setTrueDrive(true);
                    System.out.println("True 1541 drive enabled (experimental).");
                }
            } catch (IOException ignored) { /* drive ROM optional */ }
        }
        // NB: CLI flags are run-only overrides — they are deliberately NOT written back to
        // Settings (a one-off --truedrive/--ntsc/--8580 must not become a sticky default).
        // Only menu actions persist; region/SID have no menu, so they stay flag-only.

        display.setOnReset(c64::requestReset);
        display.setOnTrueDrive(c64::setTrueDrive);
        display.setOnSaveState(c64::requestSaveSnapshot);
        display.setOnLoadState(c64::requestLoadSnapshot);
        display.setOnPause(c64::togglePause);
        display.setOnToggleAudio(c64::toggleAudio);
        display.setOnVolume(audio::setVolume);                                  // SID playback volume slider
        display.setRecordingParams(region.refreshHz / 2.0, C64.SAMPLE_RATE);   // video fps = refresh/2
        c64.setAudioTap(display::recordAudio);                                  // capture audio into recordings

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
            if (!pad1.isRunning()) {
                return "No USB joystick found (Linux /dev/input/jsN, or Windows 10+ XInput pad).";
            }
            String msg = "Player 1 pad: " + pad1.source();
            msg += pad2.isRunning()
                    ? "\nPlayer 2 pad: " + pad2.source()
                      + "\n\nEnable Controls -> \"Two players\" to give them separate ports."
                    : "\nPlayer 2 pad: none (plug in a second controller).";
            return msg;
        });
        display.setOnLaunchGame(game -> {
            try {
                c64.requestReset();                  // back to a clean machine (pending survives reset)
                c64.attach(game.disk);               // mount the disk on device 8 for in-game loads
                ProgramFile p = it.denzosoft.jvice64.media.D64
                        .open(java.nio.file.Files.readAllBytes(game.disk.toPath())).read(game.entry);
                c64.load(p, true);                   // inject + autostart once BASIC boots
                display.setCurrentTitle(game.name);  // per-game snapshot slot
                settings.addRecent(game.name, game.disk, game.entry);
                display.rebuildRecentMenu();
                System.out.println("Launching " + game.name + " (vol " + game.volume + ", #" + game.number + ")");
            } catch (Exception ex) {
                System.err.println("Launch failed: " + ex.getMessage());
            }
        });
        display.setOnOpenFile(f -> {
            try {
                c64.attach(f);                            // device 8 mount, or insert .crt (+reset)
                if (!c64.isCartridge(f)) {
                    c64.load(Media.loadAuto(f), true);    // and autostart programs
                }
                display.setCurrentTitle(baseName(f));     // per-game snapshot slot
                settings.addRecent(baseName(f), f, null);
                display.rebuildRecentMenu();
            } catch (IOException ex) {
                System.err.println("Load failed: " + ex.getMessage());
            }
        });

        // File > Open recent: replay exactly how the program was loaded the first
        // time — a disk entry goes back through the D64, anything else through the
        // normal auto-loader.
        display.setOnOpenRecent(r -> {
            File f = r.file();
            if (!f.isFile()) {
                javax.swing.JOptionPane.showMessageDialog(null,
                        "File no longer there:\n" + r.path, "Open recent",
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                display.rebuildRecentMenu();          // drops entries that vanished
                return;
            }
            try {
                if (r.isDiskEntry()) {
                    c64.requestReset();
                    c64.attach(f);
                    ProgramFile p = it.denzosoft.jvice64.media.D64
                            .open(java.nio.file.Files.readAllBytes(f.toPath())).read(r.entry);
                    c64.load(p, true);
                } else {
                    c64.attach(f);
                    if (!c64.isCartridge(f)) c64.load(Media.loadAuto(f), true);
                }
                display.setCurrentTitle(r.title.isEmpty() ? baseName(f) : r.title);
                settings.addRecent(r.title, f, r.entry);   // move back to the top
                display.rebuildRecentMenu();
            } catch (Exception ex) {
                System.err.println("Open recent failed: " + ex.getMessage());
            }
        });
        display.setOnAttachFile(f -> {
            try {
                c64.attach(f);
                c64.requestReset();                   // clean machine to LOAD from
                display.setCurrentTitle(baseName(f));
            } catch (IOException ex) {
                System.err.println("Attach failed: " + ex.getMessage());
            }
        });

        // Optional program on the command line.
        for (String a : args) {
            if (!a.startsWith("--")) {
                try {
                    File f = new File(a);
                    c64.attach(f);
                    if (!c64.isCartridge(f)) {
                        ProgramFile p = Media.loadAuto(f);
                        c64.load(p, true);
                        System.out.println("Queued: " + p);
                    } else {
                        System.out.println("Inserted cartridge: " + f.getName());
                    }
                    display.setCurrentTitle(baseName(f));   // per-game snapshot slot
                    settings.addRecent(baseName(f), f, null);
                    display.rebuildRecentMenu();
                } catch (IOException ex) {
                    System.err.println("Could not load " + a + ": " + ex.getMessage());
                }
                break;
            }
        }

        // If the emulation thread dies on an unexpected error, say so instead of
        // leaving a frozen window behind.
        c64.setOnCrash(t -> SwingUtilities.invokeLater(() ->
                javax.swing.JOptionPane.showMessageDialog(null,
                        "Emulation stopped on an unexpected error:\n\n"
                                + t + "\n\nSee the console for the full stack trace.",
                        "JVice64 — emulation stopped",
                        javax.swing.JOptionPane.ERROR_MESSAGE)));

        SwingUtilities.invokeLater(display::show);
        Thread emu = new Thread(c64::run, "jvice-emu");
        emu.setDaemon(false);
        emu.start();
    }

    // ---- Headless boot/verify harness --------------------------------------

    private static void runHeadless(byte[] basic, byte[] kernal, byte[] chargen, String[] args) {
        int frames = 200;
        String program = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].matches("\\d+")) frames = Integer.parseInt(args[i]);
            else program = args[i];
        }

        Region region = hasFlag(args, "--ntsc") ? Region.NTSC : Region.PAL;
        C64 c64 = new C64(basic, kernal, chargen, new Keyboard(), new Joystick(), null, null, region);
        if (hasFlag(args, "--8580")) c64.setSidModel(it.denzosoft.jvice64.sid.Sid.MODEL_8580);
        if (program != null) {
            try {
                File f = new File(program);
                c64.attach(f);
                if (!c64.isCartridge(f)) c64.load(Media.loadAuto(f), true);
            } catch (IOException e) { System.err.println("load: " + e.getMessage()); }
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

    /** A folder is a valid ROM set if it has the three mandatory dumps. */
    private static boolean hasRoms(File d) {
        return d != null && d.isDirectory()
            && new File(d, "kernal.bin").isFile()
            && new File(d, "basic.bin").isFile()
            && new File(d, "chargen.bin").isFile();
    }

    /**
     * Locate the C64 ROM folder: the saved setting, then {@code -Djvice64.roms},
     * then {@code <jarDir>/roms}, {@code ./roms}, {@code ~/.jvice64/roms}; in the GUI,
     * fall back to a directory chooser. The resolved folder is saved to the settings.
     */
    private static File resolveRomsDir(Settings settings, boolean allowPrompt) {
        java.util.List<File> cands = new java.util.ArrayList<>();
        if (settings != null && !settings.romsDir().isEmpty()) cands.add(new File(settings.romsDir()));
        String sys = System.getProperty("jvice64.roms");
        if (sys != null && !sys.isEmpty()) cands.add(new File(sys));
        cands.add(new File(Settings.jarDir(), "roms"));
        cands.add(new File(System.getProperty("user.dir", "."), "roms"));
        cands.add(new File(System.getProperty("user.home", "."), ".jvice64/roms"));
        for (File d : cands) {
            if (hasRoms(d)) {
                if (settings != null) settings.setRomsDir(d.getAbsolutePath());
                return d;
            }
        }
        if (!allowPrompt) return null;
        while (true) {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser(Settings.jarDir());
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Select the C64 ROMs folder (kernal.bin, basic.bin, chargen.bin)");
            if (fc.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null;
            File d = fc.getSelectedFile();
            if (hasRoms(d)) {
                if (settings != null) settings.setRomsDir(d.getAbsolutePath());
                return d;
            }
            javax.swing.JOptionPane.showMessageDialog(null,
                "That folder has no kernal.bin / basic.bin / chargen.bin.",
                "C64 ROMs", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }
}
