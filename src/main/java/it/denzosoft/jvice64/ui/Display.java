package it.denzosoft.jvice64.ui;

import it.denzosoft.jvice64.Settings;
import it.denzosoft.jvice64.io.Joystick;
import it.denzosoft.jvice64.io.Keyboard;
import it.denzosoft.jvice64.media.GameLibrary;
import it.denzosoft.jvice64.vic.VicII;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Swing window that shows the VIC-II framebuffer (scaled) and forwards host key
 * events to the emulated {@link Keyboard}. Provides a File &gt; Open menu and a
 * Reset menu wired to caller-supplied callbacks.
 */
public final class Display implements KeyListener {

    private final JFrame frame;
    private final ScreenPanel panel;
    private final BufferedImage image;
    private final int[] imageData;
    private final Keyboard keyboard;
    private final Joystick joystick;

    private Consumer<File> onOpenFile;
    private Consumer<File> onAttachFile;
    private Runnable onReset;
    private Runnable onDetachCartridge;
    private Runnable onRewindTape;
    private java.util.function.BooleanSupplier tapePresent;
    private java.util.function.BooleanSupplier cartridgePresent;
    private java.util.function.Function<Boolean, Boolean> onTrueDrive;   // request -> resulting state
    private JCheckBoxMenuItem trueDriveItem;
    private Consumer<File> onSaveState;
    private Consumer<File> onLoadState;
    private Consumer<GameLibrary.Game> onLaunchGame;
    private Supplier<Boolean> onPause;
    private Supplier<Boolean> onToggleAudio;
    private java.util.function.IntConsumer onVolume;   // SID playback volume 0..100
    private java.util.function.Supplier<String> onDetectGamepad;   // (re)scan for a USB joystick
    private File gamesDir;   // remembered game collection folder

    private final Settings settings;
    private String currentTitle;               // name of the loaded game/program -> per-game snapshot slot

    // Screenshot / recording.
    private final Object recLock = new Object();
    private volatile boolean recording;
    private AviWriter recorder;                 // MJPEG video + PCM audio, streamed to recFile
    private Consumer<Boolean> onRecordingChanged; // installs/removes the C64 audio tap
    private File recFile;
    private int recFrame;
    private int recFpsNum = 25, recFpsDen = 1;  // video frames/sec as an exact fraction
    private int recSampleRate = 44100;          // audio sample rate of the capture
    private VirtualKeyboard virtualKeyboard;

    public Display(Keyboard keyboard, Joystick joystick, int scale, Settings settings) {
        this.keyboard = keyboard;
        this.joystick = joystick;
        this.settings = settings;
        if (settings != null && !settings.gamesDir().isEmpty()) this.gamesDir = new File(settings.gamesDir());
        this.image = new BufferedImage(VicII.WIDTH, VicII.HEIGHT, BufferedImage.TYPE_INT_RGB);
        this.imageData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        frame = new JFrame(BASE_TITLE);
        // One exit path for the close button and File > Quit (see exit()). The shutdown
        // hook stays as a safety net for Ctrl+C / a kill from the console: a recording is
        // streamed to disk and must be finalised (index + header sizes) to stay playable.
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { exit(); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopRecording, "jvice-rec-close"));

        panel = new ScreenPanel();
        panel.setPreferredSize(new Dimension(VicII.WIDTH * scale, VicII.HEIGHT * scale));
        frame.setJMenuBar(buildMenu());
        frame.setContentPane(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);

        panel.setFocusable(true);
        panel.setFocusTraversalKeysEnabled(false);

        // Focus-independent key delivery: a global dispatcher sees every key event
        // for this app's windows, so the arrow keys reach the joystick even when
        // the panel is not the focus owner (after clicking the menu bar / a file
        // dialog / alt-tab). This is what the panel KeyListener could not do.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            int kc = e.getKeyCode();
            // A RELEASE is always honoured, even while the window is inactive:
            // dropping it strands the key DOWN forever (hold a direction, let the
            // pointer take activation away, release -> the C64 kept seeing it
            // pressed). Releasing a key that was never pressed is harmless.
            if (e.getID() == KeyEvent.KEY_RELEASED) {
                boolean hot = isHotkey(kc);
                if (hot) hotkeysDown.remove(kc);
                keyReleased(e);
                return hot && frame.isActive();            // swallow our own hotkey's release
            }
            if (e.getID() != KeyEvent.KEY_PRESSED || !frame.isActive()) return false;  // dialogs / other windows
            if (isHotkey(kc)) {
                // Act on the first press only: a held key auto-repeats KEY_PRESSED, which
                // toggled pause several times or wrote a burst of screenshots / saves.
                if (hotkeysDown.add(kc)) handleHotkey(kc);
                return true;                               // consumed: F10 must not also open the menu bar
            }
            // While a menu is open its keys (arrows, Enter, Esc) navigate the menu; they
            // must not also move the joystick or type on the C64.
            if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) return false;
            keyPressed(e);
            return false;                                  // keep propagating so menu mnemonics still work
        });

        // Losing focus altogether (the pointer moves to another window, alt-tab,
        // a focus-follows-mouse desktop) means the release event is delivered
        // somewhere else and we would never see it — so let go of everything.
        frame.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) { releaseAllKeys(); }
        });
    }

    public void setOnOpenFile(Consumer<File> cb) { this.onOpenFile = cb; }
    public void setOnAttachFile(Consumer<File> cb) { this.onAttachFile = cb; }
    public void setOnReset(Runnable cb) { this.onReset = cb; }
    /** File > Detach cartridge; {@code present} enables the item only while a cartridge is in. */
    /** File > Rewind tape; {@code present} enables the item only while a tape is mounted. */
    public void setOnRewindTape(Runnable cb, java.util.function.BooleanSupplier present) {
        this.onRewindTape = cb;
        this.tapePresent = present;
    }
    public void setOnDetachCartridge(Runnable cb, java.util.function.BooleanSupplier present) {
        this.onDetachCartridge = cb;
        this.cartridgePresent = present;
    }
    /**
     * File &gt; "Use real 1541 drive". The callback gets the requested state and returns the
     * state that actually took effect (false when no drive ROM is loaded); the item shows
     * that, and only that is saved.
     */
    public void setOnTrueDrive(java.util.function.Function<Boolean, Boolean> cb) { this.onTrueDrive = cb; }

    /**
     * Whether a 1541 drive ROM is loaded ({@code available}) and the true drive is on now
     * (saved setting or {@code --truedrive}). Without the ROM the item is disabled.
     */
    public void setTrueDriveState(boolean available, boolean on) {
        Runnable r = () -> {
            if (trueDriveItem == null) return;
            trueDriveItem.setEnabled(available);
            trueDriveItem.setSelected(available && on);
            trueDriveItem.setToolTipText(available ? null
                    : "Needs dos1541.bin (16 KB) in the C64 ROMs folder");
        };
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r);
    }
    /** Told {@code true} when a recording starts and {@code false} when it ends. */
    public void setOnRecordingChanged(Consumer<Boolean> cb) { this.onRecordingChanged = cb; }
    public void setOnSaveState(Consumer<File> cb) { this.onSaveState = cb; }
    public void setOnLoadState(Consumer<File> cb) { this.onLoadState = cb; }
    public void setOnLaunchGame(Consumer<GameLibrary.Game> cb) { this.onLaunchGame = cb; }
    public void setOnPause(Supplier<Boolean> cb) { this.onPause = cb; }
    public void setOnToggleAudio(Supplier<Boolean> cb) { this.onToggleAudio = cb; }
    public void setOnVolume(java.util.function.IntConsumer cb) { this.onVolume = cb; }
    public void setOnDetectGamepad(java.util.function.Supplier<String> cb) { this.onDetectGamepad = cb; }
    /** Run before the JVM exits from the window close button or File &gt; Quit (stop the machine). */
    public void setOnExit(Runnable cb) { this.onExit = cb; }
    private Runnable onExit;

    /** Hotkeys currently held (EDT only) — a held key's auto-repeat must not re-trigger it. */
    private final java.util.Set<Integer> hotkeysDown = new java.util.HashSet<Integer>();

    private static final String BASE_TITLE = "JVice64 — Commodore 64 emulator";
    private javax.swing.Timer statusTimer;

    /**
     * Leave the application: finalise a running recording, stop the emulation (so the
     * audio line is drained and closed), close the window, exit. Both the window close
     * button and File &gt; Quit come here; before, they killed the JVM straight away.
     */
    private void exit() {
        stopRecording();
        awaitScreenshots(2000);   // System.exit halts every thread, daemon or not
        Runnable cb = onExit;
        if (cb != null) {
            try { cb.run(); } catch (RuntimeException ex) { ex.printStackTrace(); }
        }
        frame.dispose();
        System.exit(0);
    }

    /**
     * Show an error in a dialog owned by the main window. Safe from any thread. A GUI
     * started without a console (double-click, javaw) would never see stderr.
     */
    public void showError(String title, String message) {
        showMessage(title, message, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Show a dialog owned by the main window ({@code JOptionPane} message type). Safe from
     * any thread. Does not print: callers log to the console themselves.
     */
    public void showMessage(String title, String message, int messageType) {
        // Always posted, also from the EDT: a modal dialog must not open in the middle
        // of a key dispatch or a menu action (it would run a nested event loop there).
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, message, title, messageType));
    }

    /**
     * Show a short confirmation ("Screenshot saved: ...") in the title bar for about
     * three seconds. Safe from any thread; also printed on stdout.
     */
    public void showStatus(String message) {
        System.out.println(message);
        Runnable r = () -> {
            frame.setTitle(BASE_TITLE + "  —  " + message);
            if (statusTimer == null) {
                statusTimer = new javax.swing.Timer(3000, ev -> frame.setTitle(BASE_TITLE));
                statusTimer.setRepeats(false);
            }
            statusTimer.restart();
        };
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeLater(r);
    }

    public void show() {
        frame.setVisible(true);
        panel.requestFocusInWindow();
        // Registered once the window is up: a save that already failed (the ROM folder is
        // saved at startup, before this window exists) is reported now, over the window,
        // instead of being used up with nobody listening.
        if (settings != null && !saveFailListening) {
            saveFailListening = true;
            settings.setOnSaveFailed(msg -> showMessage("JVice64 — settings", msg, JOptionPane.WARNING_MESSAGE));
        }
    }
    private boolean saveFailListening;

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem open = new JMenuItem("Open program / disk / cartridge / tape…");
        open.addActionListener(e -> {
            JFileChooser fc = mediaChooser();
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION && onOpenFile != null) {
                onOpenFile.accept(fc.getSelectedFile());
            }
            panel.requestFocusInWindow();
        });
        JMenuItem attach = new JMenuItem("Attach disk to drive 8 (no autostart)…");
        attach.addActionListener(e -> {
            JFileChooser fc = mediaChooser();
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION && onAttachFile != null) {
                onAttachFile.accept(fc.getSelectedFile());
            }
            panel.requestFocusInWindow();
        });
        JCheckBoxMenuItem trueDrive = new JCheckBoxMenuItem(
                "Use real 1541 drive (true drive)", settings != null && settings.trueDrive());
        trueDriveItem = trueDrive;
        trueDrive.addActionListener(e -> {
            boolean wanted = trueDrive.isSelected();
            Boolean got = onTrueDrive != null ? onTrueDrive.apply(wanted) : Boolean.valueOf(wanted);
            boolean on = got != null && got;
            trueDrive.setSelected(on);                     // what the machine really does
            if (settings != null && on == wanted) settings.setTrueDrive(on);   // persist only what took effect
            if (on != wanted) showError("1541 drive", "The real 1541 drive needs dos1541.bin (16 KB) in the C64 ROMs folder.");
            panel.requestFocusInWindow();
        });
        JMenuItem saveState = new JMenuItem("Save state…");
        saveState.addActionListener(e -> {
            JFileChooser fc = chooser(Settings.snapshotsDir());
            fc.setSelectedFile(gameSlot());     // pre-fill the per-game snapshot name
            if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION && onSaveState != null) {
                onSaveState.accept(fc.getSelectedFile());
            }
            panel.requestFocusInWindow();
        });
        JMenuItem loadState = new JMenuItem("Load state…");
        loadState.addActionListener(e -> {
            JFileChooser fc = chooser(Settings.snapshotsDir());
            if (gameSlot().isFile()) fc.setSelectedFile(gameSlot());   // pre-select this game's snapshot
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION && onLoadState != null) {
                onLoadState.accept(fc.getSelectedFile());
            }
            panel.requestFocusInWindow();
        });
        JMenuItem reset = new JMenuItem("Reset");
        reset.addActionListener(e -> { if (onReset != null) onReset.run(); panel.requestFocusInWindow(); });
        JMenuItem quit = new JMenuItem("Quit");
        quit.addActionListener(e -> exit());
        JMenuItem detachCart = new JMenuItem("Detach cartridge");
        detachCart.addActionListener(e -> {
            if (onDetachCartridge != null) { onDetachCartridge.run(); showStatus("Cartridge removed"); }
            panel.requestFocusInWindow();
        });
        JMenuItem rewindTape = new JMenuItem("Rewind tape");
        rewindTape.addActionListener(e -> {
            if (onRewindTape != null) { onRewindTape.run(); showStatus("Tape rewound"); }
            panel.requestFocusInWindow();
        });
        file.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                detachCart.setEnabled(onDetachCartridge != null
                        && (cartridgePresent == null || cartridgePresent.getAsBoolean()));
                rewindTape.setEnabled(onRewindTape != null
                        && (tapePresent == null || tapePresent.getAsBoolean()));
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) { }
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) { }
        });
        file.add(open); file.add(attach); file.add(detachCart); file.add(rewindTape);
        recentMenu = new JMenu("Open recent");
        file.add(recentMenu);
        rebuildRecentMenu();
        file.addSeparator();
        file.add(saveState); file.add(loadState); file.addSeparator();
        file.add(menuItem("Set C64 ROMs folder…", this::setRomsFolder));
        file.add(trueDrive); file.add(reset); file.addSeparator(); file.add(quit);
        bar.add(file);

        // Controls: which control port the host keys (arrows + Space/Ctrl) drive.
        JMenu controls = new JMenu("Controls");
        ButtonGroup grp = new ButtonGroup();
        int jp = settings != null ? settings.joystickPort() : 0;
        JRadioButtonMenuItem both = new JRadioButtonMenuItem("Joystick: both ports (1 & 2)", jp == 0);
        JRadioButtonMenuItem p2 = new JRadioButtonMenuItem("Joystick: port 2 only", jp == 2);
        JRadioButtonMenuItem p1 = new JRadioButtonMenuItem("Joystick: port 1 only", jp == 1);
        both.addActionListener(e -> { joystick.setActivePort(0); if (settings != null) settings.setJoystickPort(0); panel.requestFocusInWindow(); });
        p2.addActionListener(e ->   { joystick.setActivePort(2); if (settings != null) settings.setJoystickPort(2); panel.requestFocusInWindow(); });
        p1.addActionListener(e ->   { joystick.setActivePort(1); if (settings != null) settings.setJoystickPort(1); panel.requestFocusInWindow(); });
        grp.add(both); grp.add(p2); grp.add(p1);
        controls.add(both); controls.add(p2); controls.add(p1);
        // With two players the port radios mean something else — they pick which port
        // player 1 holds — so relabel them (and drop "both", which then has no meaning:
        // both ports are in use by definition).
        this.portItems = new JRadioButtonMenuItem[] { both, p2, p1 };
        controls.add(menuItem("Detect USB joystick…", this::detectGamepad));
        controls.addSeparator();

        // Two players: the host inputs split across both control ports.
        JMenuItem help = new JMenuItem();
        help.setEnabled(false);
        JCheckBoxMenuItem wasd = new JCheckBoxMenuItem(
                "Player 2 also on WASD", settings != null && settings.wasdPlayer2());
        JCheckBoxMenuItem twoPlayer = new JCheckBoxMenuItem(
                "Two players (split control ports)", settings != null && settings.twoPlayer());

        twoPlayer.addActionListener(e -> {
            boolean on = twoPlayer.isSelected();
            joystick.setTwoPlayer(on);
            if (settings != null) settings.setTwoPlayer(on);
            wasd.setEnabled(on);                       // player-2 only setting
            relabelPortItems();
            help.setText(controlsHelpText());
            panel.requestFocusInWindow();
        });
        wasd.addActionListener(e -> {
            boolean on = wasd.isSelected();
            joystick.setWasdPlayer2(on);
            if (settings != null) settings.setWasdPlayer2(on);
            help.setText(controlsHelpText());
            panel.requestFocusInWindow();
        });
        wasd.setEnabled(joystick.isTwoPlayer());
        relabelPortItems();
        help.setText(controlsHelpText());
        joystickKeysType = settings != null && settings.joystickKeysType();
        JCheckBoxMenuItem keysType = new JCheckBoxMenuItem(
                "Joystick keys also type (arrows = cursor)", joystickKeysType);
        keysType.setToolTipText("Off (recommended): direction keys drive only the joystick. "
                + "On: they also reach the C64 keyboard, which can make games read two directions at once.");
        keysType.addActionListener(e -> {
            joystickKeysType = keysType.isSelected();
            if (settings != null) settings.setJoystickKeysType(joystickKeysType);
            keyboard.reset();                      // drop anything already held on the matrix
            if (virtualKeyboard != null) virtualKeyboard.reapplyModifiers();   // sticky on-screen holds stay
            panel.requestFocusInWindow();
        });

        controls.add(twoPlayer);
        controls.add(wasd);
        controls.add(keysType);
        controls.add(menuItem("Configure joystick keys…", () -> {
            new JoystickConfigDialog(frame, joystick, settings).setVisible(true);
            help.setText(controlsHelpText());
            panel.requestFocusInWindow();
        }));
        controls.addSeparator();
        controls.add(help);
        controls.addSeparator();
        controls.add(menuItem("Show virtual keyboard", this::showVirtualKeyboard));
        controls.addSeparator();
        JCheckBoxMenuItem audioOn = new JCheckBoxMenuItem("Enable audio", settings != null && settings.audioEnabled());
        audioOn.addActionListener(e -> {
            boolean on = onToggleAudio != null ? onToggleAudio.get() : audioOn.isSelected();
            audioOn.setSelected(on);          // authoritative state from the emulator
            if (settings != null) settings.setAudioEnabled(on);
            panel.requestFocusInWindow();
        });
        controls.add(audioOn);

        // SID playback volume (0..100), applied live and remembered.
        int vol0 = settings != null ? settings.volume() : 100;
        JPanel volPanel = new JPanel(new BorderLayout());
        volPanel.setOpaque(false);
        volPanel.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        JLabel volLabel = new JLabel("Volume: " + vol0 + "%");
        JSlider volume = new JSlider(0, 100, vol0);
        volume.setOpaque(false);
        volume.addChangeListener(ev -> {
            int v = volume.getValue();
            volLabel.setText("Volume: " + v + "%");
            if (onVolume != null) onVolume.accept(v);                                  // apply live
            if (settings != null && !volume.getValueIsAdjusting()) settings.setVolume(v);  // persist on release
        });
        volPanel.add(volLabel, BorderLayout.NORTH);
        volPanel.add(volume, BorderLayout.CENTER);
        controls.add(volPanel);
        bar.add(controls);

        // Games: browse + launch from a disk collection the user owns.
        JMenu gamesMenu = new JMenu("Games");
        JMenuItem browse = new JMenuItem("Browse game collection…");
        browse.addActionListener(e -> openGameBrowser(false));
        JMenuItem chooseDir = new JMenuItem("Set game collection folder…");
        chooseDir.addActionListener(e -> openGameBrowser(true));
        gamesMenu.add(browse);
        gamesMenu.add(chooseDir);
        bar.add(gamesMenu);

        // Machine: pause, screenshot, video recording, quick save/load state.
        JMenu machine = new JMenu("Machine");
        machine.add(menuItem("Pause / resume   (F9)", this::doPause));
        machine.addSeparator();
        machine.add(menuItem("Save screenshot   (F10)", this::saveScreenshot));
        machine.add(menuItem("Start video recording   (F11)", this::startRecording));
        machine.add(menuItem("Stop video recording   (F12)", this::stopRecording));
        machine.addSeparator();
        machine.add(menuItem("Quick save state   (F2)", this::quickSave));
        machine.add(menuItem("Quick load state   (F4)", this::quickLoad));
        bar.add(machine);
        return bar;
    }

    private JMenuItem menuItem(String label, Runnable action) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(e -> { action.run(); panel.requestFocusInWindow(); });
        return mi;
    }

    /** A file chooser pre-pointed at {@code startDir} (the jar folder, or the snapshots dir). */
    private JFileChooser chooser(File startDir) {
        JFileChooser fc = new JFileChooser();
        if (startDir != null && startDir.isDirectory()) fc.setCurrentDirectory(startDir);
        return fc;
    }

    /** File chooser for C64 media, filtered to the supported formats ("All files" stays available). */
    private JFileChooser mediaChooser() {
        JFileChooser fc = chooser(Settings.jarDir());
        javax.swing.filechooser.FileNameExtensionFilter c64 = new javax.swing.filechooser.FileNameExtensionFilter(
                "C64 files (prg, p00, t64, d64, crt, tap)", "prg", "p00", "t64", "d64", "crt", "tap");
        fc.addChoosableFileFilter(c64);
        fc.setFileFilter(c64);
        return fc;
    }

    private void rememberGamesDir(File d) {
        if (settings != null && d != null) settings.setGamesDir(d.getAbsolutePath());
    }

    private void detectGamepad() {
        String msg = onDetectGamepad != null ? onDetectGamepad.get() : "USB joystick support unavailable.";
        JOptionPane.showMessageDialog(frame, msg, "USB joystick", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Pick the folder holding the C64 ROM dumps and remember it (applied on next launch). */
    private void setRomsFolder() {
        JFileChooser fc = chooser(Settings.jarDir());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Select the folder with kernal.bin / basic.bin / chargen.bin");
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File d = fc.getSelectedFile();
            String problem = it.denzosoft.jvice64.Main.romProblem(d);   // names AND sizes
            if (problem != null) {
                JOptionPane.showMessageDialog(frame, "That folder cannot be used:\n" + problem
                        + "\n\nNeeded: kernal.bin (8 KB), basic.bin (8 KB), chargen.bin (4 KB).",
                        "C64 ROMs", JOptionPane.WARNING_MESSAGE);
            } else if (settings != null) {
                settings.setRomsDir(d.getAbsolutePath());
                JOptionPane.showMessageDialog(frame, "ROMs folder saved:\n" + d + "\n\nRestart JVice64 to use it.",
                        "C64 ROMs", JOptionPane.INFORMATION_MESSAGE);
            }
        }
        panel.requestFocusInWindow();
    }

    /** Show the on-screen C64 keyboard in its own (non-focusable) window. */
    /** When true the joystick keys ALSO type on the C64 (old behaviour, conflicts). */
    private boolean joystickKeysType;

    /** File > "Open recent": the last few programs, reloaded in one click. */
    private JMenu recentMenu;
    private java.util.function.Consumer<Settings.Recent> onOpenRecent;

    /** Called with the entry to (re)load when the user picks a recent item. */
    public void setOnOpenRecent(java.util.function.Consumer<Settings.Recent> cb) {
        this.onOpenRecent = cb;
        rebuildRecentMenu();
    }

    /**
     * Rebuild the recent list from the settings. Safe to call from any thread and
     * at any time — the emulator calls it after every load.
     */
    public void rebuildRecentMenu() {
        if (recentMenu == null) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::rebuildRecentMenu);
            return;
        }
        recentMenu.removeAll();
        java.util.List<Settings.Recent> list =
                (settings == null) ? new java.util.ArrayList<Settings.Recent>() : settings.recentFiles();
        if (list.isEmpty()) {
            JMenuItem none = new JMenuItem("(nothing loaded yet)");
            none.setEnabled(false);
            recentMenu.add(none);
            recentMenu.setEnabled(true);
            return;
        }
        int n = 1;
        for (final Settings.Recent r : list) {
            String label = n + ". " + (r.title.isEmpty() ? r.file().getName() : r.title);
            JMenuItem mi = new JMenuItem(label);
            // Full path (and disk entry) in the tooltip: two games can share a title.
            mi.setToolTipText(r.path + (r.isDiskEntry() ? "  [" + r.entry + "]" : ""));
            mi.addActionListener(e -> {
                if (onOpenRecent != null) onOpenRecent.accept(r);
                panel.requestFocusInWindow();
            });
            recentMenu.add(mi);
            n++;
        }
        recentMenu.addSeparator();
        JMenuItem clear = new JMenuItem("Clear list");
        clear.addActionListener(e -> {
            if (settings != null) settings.clearRecent();
            rebuildRecentMenu();
            panel.requestFocusInWindow();
        });
        recentMenu.add(clear);
    }

    /** The three control-port radio items, relabelled when the player count changes. */
    private JRadioButtonMenuItem[] portItems;

    /**
     * In one-player mode the radios choose which port the inputs reach; in
     * two-player mode both ports are always in use, so they instead choose which
     * port player 1 holds — and "both" is meaningless and gets hidden.
     */
    private void relabelPortItems() {
        if (portItems == null) return;
        boolean two = joystick.isTwoPlayer();
        portItems[0].setVisible(!two);                              // "both ports"
        portItems[0].setText("Joystick: both ports (1 & 2)");
        portItems[1].setText(two ? "Player 1 on port 2 (player 2 on port 1)" : "Joystick: port 2 only");
        portItems[2].setText(two ? "Player 1 on port 1 (player 2 on port 2)" : "Joystick: port 1 only");
        // Tick what the joystick really does. "both" and "port 2" behave identically for
        // player 1, so with "both" hidden "port 2" shows it; leaving two-player mode ticks
        // "both" again if that is the real setting (setSelected fires no action, so the
        // radios must follow the model, never the other way round).
        int ap = joystick.getActivePort();
        JRadioButtonMenuItem sel = (ap == 1) ? portItems[2] : (ap == 2 || two) ? portItems[1] : portItems[0];
        sel.setSelected(true);
    }

    /** One-line reminder of the current control mapping, shown in the Controls menu. */
    private String controlsHelpText() {
        String p1 = describe(1);
        if (!joystick.isTwoPlayer()) return "Keys — " + p1;
        return "P1: " + p1 + "   |   P2: " + describe(2)
                + (joystick.isWasdPlayer2() ? " (or WASD)" : "");
    }

    /** "Up/Down/Left/Right, fire X" for a player's current bindings. */
    private String describe(int player) {
        it.denzosoft.jvice64.io.JoyBinding b = joystick.binding(player);
        return it.denzosoft.jvice64.io.JoyBinding.displayName(b.get(it.denzosoft.jvice64.io.JoyBinding.Action.UP))
             + "/" + it.denzosoft.jvice64.io.JoyBinding.displayName(b.get(it.denzosoft.jvice64.io.JoyBinding.Action.DOWN))
             + "/" + it.denzosoft.jvice64.io.JoyBinding.displayName(b.get(it.denzosoft.jvice64.io.JoyBinding.Action.LEFT))
             + "/" + it.denzosoft.jvice64.io.JoyBinding.displayName(b.get(it.denzosoft.jvice64.io.JoyBinding.Action.RIGHT))
             + ", fire " + it.denzosoft.jvice64.io.JoyBinding.displayName(b.get(it.denzosoft.jvice64.io.JoyBinding.Action.FIRE));
    }

    private void showVirtualKeyboard() {
        if (virtualKeyboard == null) {
            virtualKeyboard = new VirtualKeyboard(keyboard);
            virtualKeyboard.setLocationRelativeTo(frame);
        }
        virtualKeyboard.setVisible(true);
        virtualKeyboard.toFront();
    }

    /** The open game browser (one at a time), and the scan it shows (reused while the index is unchanged). */
    private GameBrowser browser;
    private File browserDir;
    private String browserStamp;
    private java.util.List<GameLibrary.Game> browserGames;

    /** Open the game collection browser (scanning on a worker with a progress bar). */
    private void openGameBrowser(boolean forceChoose) {
        File dir = resolveGamesDir(forceChoose);
        if (dir == null) { panel.requestFocusInWindow(); return; }

        // The scan lists only games whose disk image exists, so adding or removing a .d64 must
        // invalidate it too: the folder's own modification time changes then, and the
        // count of disk images catches file systems where it does not. "Set game
        // collection folder…" (forceChoose) always rescans.
        String stamp = collectionStamp(dir);
        boolean sameScan = !forceChoose && browserGames != null && dir.equals(browserDir)
                && stamp.equals(browserStamp);
        if (browser != null && browser.isDisplayable() && sameScan) {
            browser.setVisible(true);              // already open on this collection: just raise it
            browser.toFront();
            return;
        }
        if (browser != null) { browser.dispose(); browser = null; }
        if (sameScan) { showGameBrowser(browserGames); return; }   // unchanged: no rescan

        JDialog prog = new JDialog(frame, "Loading game collection…", true);
        // Closing it would only hide it and then block the EDT in worker.get() until the scan ends.
        prog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        JPanel pp = new JPanel(new BorderLayout(8, 10));
        pp.setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        pp.add(new JLabel("Scanning " + dir.getName() + " …"), BorderLayout.NORTH);
        pp.add(bar, BorderLayout.CENTER);
        prog.setContentPane(pp);
        prog.setSize(360, 110);
        prog.setLocationRelativeTo(frame);

        SwingWorker<java.util.List<GameLibrary.Game>, Void> worker =
                new SwingWorker<java.util.List<GameLibrary.Game>, Void>() {
            @Override protected java.util.List<GameLibrary.Game> doInBackground() throws Exception {
                return GameLibrary.scan(dir, (done, total) ->
                        setProgress(total > 0 ? (int) Math.min(100, done * 100L / total) : 0));
            }
            @Override protected void done() { prog.dispose(); }
        };
        worker.addPropertyChangeListener(ev -> {
            if ("progress".equals(ev.getPropertyName())) bar.setValue((Integer) ev.getNewValue());
        });
        worker.execute();
        prog.setVisible(true);   // modal: blocks (pumping the EDT) until done() disposes it

        try {
            java.util.List<GameLibrary.Game> games = worker.get();
            if (games.isEmpty()) {
                browserGames = null;
                JOptionPane.showMessageDialog(frame,
                        "No games found in " + dir + "\n(need " + GameLibrary.INDEX_NAME + " and its numbered .d64 files).",
                        "Game collection", JOptionPane.WARNING_MESSAGE);
            } else {
                browserDir = dir;
                browserStamp = stamp;
                browserGames = games;
                showGameBrowser(games);
                return;
            }
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            JOptionPane.showMessageDialog(frame, "Could not read the collection:\n" + cause.getMessage(),
                    "Game collection", JOptionPane.ERROR_MESSAGE);
        }
        panel.requestFocusInWindow();
    }

    /** What the cached scan of {@code dir} depends on: index file, folder contents. */
    static String collectionStamp(File dir) {
        String[] disks = dir.list((d, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".d64"));
        return new File(dir, GameLibrary.INDEX_NAME).lastModified() + "/" + dir.lastModified()
                + "/" + (disks == null ? -1 : disks.length);
    }

    private void showGameBrowser(java.util.List<GameLibrary.Game> games) {
        browser = new GameBrowser(frame, games, g -> { if (onLaunchGame != null) onLaunchGame.accept(g); });
        browser.setVisible(true);
    }

    /** Resolve the collection folder: remembered → folder chooser. */
    private File resolveGamesDir(boolean forceChoose) {
        if (!forceChoose && GameLibrary.looksLikeCollection(gamesDir)) return gamesDir;
        JFileChooser fc = chooser(gamesDir != null ? gamesDir : Settings.jarDir());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Select the game collection folder");
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File d = fc.getSelectedFile();
            if (!GameLibrary.looksLikeCollection(d)) {
                JOptionPane.showMessageDialog(frame,
                        "That folder has no " + GameLibrary.INDEX_NAME + ".", "Game collection",
                        JOptionPane.WARNING_MESSAGE);
                return null;
            }
            gamesDir = d;
            rememberGamesDir(d);
            return d;
        }
        return null;
    }

    /** Copy a VIC framebuffer into the on-screen image and repaint. Thread-safe to call off-EDT. */
    public void updateFrame(int[] fb) {
        boolean p = paused;
        // While paused the picture does not change: copy and repaint it once, not 50 times a second.
        if (!p || !lastWasPaused) {
            synchronized (frameLock) {
                System.arraycopy(fb, 0, imageData, 0, Math.min(fb.length, imageData.length));
            }
            panel.repaint();
        }
        lastWasPaused = p;
        // Record video every 2nd frame (~25 fps). Skip while paused: the emulator isn't
        // advancing and the audio tap is silent, so capturing frozen frames would push
        // video ahead of audio by the whole pause. (Video + audio stay frame-locked.)
        if (!p && recording && ((recFrame++ & 1) == 0)) {
            String stopReason = null;
            synchronized (recLock) {
                if (recording && recorder != null) {
                    try {
                        recorder.addFrame(image);   // emulation thread: the only writer of imageData
                        if (recorder.isFull()) stopReason = "the file reached the AVI size limit (about 1.9 GB)";
                    } catch (IOException ex) {
                        stopReason = "write error: " + ex.getMessage();
                    }
                }
            }
            if (stopReason != null) abortRecording(stopReason);
        }
    }

    /**
     * Drop every held key. Called when the window loses focus, because the
     * matching key-release will be delivered to whatever took focus instead of
     * to us, leaving directions stuck on.
     */
    private void releaseAllKeys() {
        keyboard.reset();
        joystick.reset();
        hotkeysDown.clear();
        // The on-screen CTRL / C= / SHIFT / SHIFT LOCK toggles are deliberate holds, still
        // shown as pressed: put them back on the matrix the reset just cleared.
        if (virtualKeyboard != null) virtualKeyboard.reapplyModifiers();
    }

    // ---- KeyListener --------------------------------------------------------

    @Override public void keyPressed(KeyEvent e)  {
        if (isHotkey(e.getKeyCode())) return;         // emulator function keys: handled by the dispatcher, never sent to the C64
        joystick.keyPressed(e);
        if (!withholdFromKeyboard(e)) keyboard.keyPressed(e);
    }
    @Override public void keyReleased(KeyEvent e) {
        if (isHotkey(e.getKeyCode())) return;
        joystick.keyReleased(e);
        // Always release on the matrix, even if the press was withheld: the option
        // could have been toggled in between, and a spurious release is harmless.
        keyboard.keyReleased(e);
    }

    /**
     * A joystick direction key is kept out of the C64 keyboard matrix, because the
     * matrix and the joysticks share the CIA1 port lines: the right arrow also
     * closes cursor-right (row 0 bit 2), which is joystick LEFT on port B, so a
     * game read left and right at once and would not steer. Turn
     * "Joystick keys also type" back on if you need the arrows as cursor keys.
     */
    private boolean withholdFromKeyboard(KeyEvent e) {
        return !joystickKeysType && joystick.consumesKey(e.getKeyCode());
    }
    @Override public void keyTyped(KeyEvent e) { }

    /** Keys the emulator keeps for itself (never reach the C64 or the joystick). */
    static boolean isHotkey(int kc) {
        return it.denzosoft.jvice64.io.JoyBinding.isReservedHotkey(kc);   // one list, shared
    }

    private boolean handleHotkey(int kc) {
        switch (kc) {
            case KeyEvent.VK_F2:  quickSave();      return true;
            case KeyEvent.VK_F4:  quickLoad();      return true;
            case KeyEvent.VK_F9:  doPause();        return true;
            case KeyEvent.VK_F10: saveScreenshot(); return true;
            case KeyEvent.VK_F11: startRecording(); return true;
            case KeyEvent.VK_F12: stopRecording();  return true;
            default: return false;
        }
    }

    private void doPause() {
        if (onPause != null) { paused = onPause.get(); panel.repaint(); }
    }

    /** Set the loaded game/program name; the snapshot slot is derived from it. */
    public void setCurrentTitle(String title) { this.currentTitle = title; }

    /** The snapshot file bound to the currently loaded game (one per game). */
    private File gameSlot() {
        return new File(Settings.snapshotsDir(), snapshotBaseName() + ".jv64");
    }

    private String snapshotBaseName() {
        if (currentTitle == null) return "quicksave";
        String t = currentTitle.trim().replaceAll("[^A-Za-z0-9 ._-]", "_").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.isEmpty() ? "quicksave" : t;
    }

    private void quickSave() {
        // The save itself runs on the emulation thread before the next frame; say it was
        // requested, not that it succeeded (a failure is reported separately).
        if (onSaveState != null) { File f = gameSlot(); onSaveState.accept(f); showStatus("Quick save -> " + f.getName()); }
    }

    private void quickLoad() {
        File f = gameSlot();
        if (onLoadState != null && f.isFile()) { onLoadState.accept(f); showStatus("Quick load <- " + f.getName()); }
        else showStatus("No quick-save for " + (currentTitle == null ? "(no game loaded)" : currentTitle));
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.ROOT).format(new Date());
    }

    /** Save the current screen to a timestamped PNG in the user's home folder. */
    public void saveScreenshot() {
        File f = new File(System.getProperty("user.home", "."), "jvice64-" + stamp() + ".png");
        // Copy the pixels under the frame lock (one consistent frame), encode off the EDT.
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        int[] dst = ((DataBufferInt) copy.getRaster().getDataBuffer()).getData();
        synchronized (frameLock) {
            System.arraycopy(imageData, 0, dst, 0, Math.min(dst.length, imageData.length));
        }
        // Encoded to "<name>.part" and renamed when complete: a write cut off by a kill or
        // Ctrl+C leaves no truncated .png behind. File > Quit / the close button wait for
        // the writer (awaitScreenshots); a non-daemon thread alone would not survive their
        // System.exit.
        File part = new File(f.getPath() + ".part");
        Thread t = new Thread(() -> {
            try {
                if (!ImageIO.write(copy, "png", part)) throw new IOException("no PNG writer available");
                java.nio.file.Files.move(part.toPath(), f.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                showStatus("Screenshot saved: " + f);
            } catch (IOException | RuntimeException ex) {
                part.delete();
                System.err.println("Screenshot failed: " + f + " " + ex.getMessage());
                showError("Screenshot", "Screenshot failed: " + f + "\n" + ex.getMessage());
            } finally {
                synchronized (screenshotThreads) { screenshotThreads.remove(Thread.currentThread()); }
            }
        }, "jvice-screenshot");
        synchronized (screenshotThreads) { screenshotThreads.add(t); }
        t.start();
    }

    /** Screenshot writers still running (removed by themselves when done). */
    private final java.util.List<Thread> screenshotThreads = new java.util.ArrayList<Thread>();

    /** Wait up to {@code millis} in total for the running screenshot writers to finish. */
    void awaitScreenshots(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        java.util.List<Thread> pending;
        synchronized (screenshotThreads) { pending = new java.util.ArrayList<Thread>(screenshotThreads); }
        for (Thread t : pending) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try { t.join(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }

    /**
     * Video frame rate (an exact fraction {@code fpsNum / fpsDen}) and audio sample rate
     * of the next recording; set by the host before show(). The fraction keeps picture
     * and sound in sync: the emulated frame rate is not a whole number.
     */
    public void setRecordingParams(int fpsNum, int fpsDen, int sampleRate) {
        if (fpsNum > 0 && fpsDen > 0) {
            int g = java.math.BigInteger.valueOf(fpsNum).gcd(java.math.BigInteger.valueOf(fpsDen)).intValue();
            this.recFpsNum = fpsNum / g;
            this.recFpsDen = fpsDen / g;
        }
        if (sampleRate > 0) this.recSampleRate = sampleRate;
    }

    /** Feed emulated audio into the active recording. Called from the C64 audio tap (emu thread). */
    public void recordAudio(short[] buf, int count) {
        if (!recording) return;                 // cheap pre-check off the lock
        String stopReason = null;
        synchronized (recLock) {
            if (recording && recorder != null) {
                try { recorder.addAudio(buf, count); }
                catch (IOException ex) { stopReason = "write error: " + ex.getMessage(); }
            }
        }
        if (stopReason != null) abortRecording(stopReason);   // once: recording is false afterwards
    }

    /** Stop a recording that cannot continue, keep what was written, and tell the user. */
    private void abortRecording(String why) {
        System.err.println("Recording stopped: " + why);
        finishRecording(why);     // finalise what was recorded so far; ONE dialog for both problems
    }

    /** Start recording the screen + audio to an AVI (MJPEG video + 16-bit PCM audio). */
    public void startRecording() {
        File f;
        synchronized (recLock) {
            if (recording) return;
            f = new File(System.getProperty("user.home", "."), "jvice64-" + stamp() + ".avi");
            try {
                recorder = new AviWriter(f, image.getWidth(), image.getHeight(),
                                         recFpsNum, recFpsDen, recSampleRate);
            } catch (IOException ex) {
                System.err.println("Recording failed to start: " + f + " " + ex.getMessage());
                showError("Video recording", "Cannot create " + f + ":\n" + ex.getMessage());
                return;
            }
            recFile = f;
            recFrame = 0;
            recording = true;
            // Inside the lock, like the "false" in finishRecording: otherwise an abort on the
            // emulation thread racing this start could deliver its "false" AFTER this "true"
            // and leave the new recording without its audio tap. The callback only sets a
            // field and queues a machine task, so it cannot block here.
            Consumer<Boolean> cb = onRecordingChanged;
            if (cb != null) cb.accept(true);   // start feeding SID audio into the recording
        }
        showStatus("Recording -> " + f.getName() + "  (F12 stops)");
    }

    /** Stop recording and finalise the AVI file (index + header sizes). */
    public void stopRecording() { finishRecording(null); }

    /**
     * Stop recording and finalise the file. {@code abortReason} is set when the recording
     * could not continue (write error, size limit): the user then gets a single dialog
     * saying why it stopped and, if finalising failed too, that as well.
     */
    private void finishRecording(String abortReason) {
        AviWriter w; File f;
        String saved = null, failed = null;
        synchronized (recLock) {
            if (!recording) return;
            recording = false;
            w = recorder; f = recFile;
            recorder = null;
            try {
                if (w != null) w.close();      // under the lock: no addAudio/addFrame can race it
                if (w != null) saved = "Recording saved: " + f + "  (" + w.frameCount() + " frames)";
            } catch (Throwable ex) {
                failed = "Recording save failed: " + f + "\n" + ex;
            }
            Consumer<Boolean> cb = onRecordingChanged;
            if (cb != null) cb.accept(false);  // stop running the SID output chain for it
        }
        if (abortReason != null) {
            String msg = "Recording stopped: " + abortReason + (f != null ? "\n" + f : "");
            if (failed != null) {
                System.err.println(failed.replace('\n', ' '));
                msg += "\n\nThe file could not be finalised completely:\n" + failed;
            } else if (w != null) {
                msg += "\n(" + w.frameCount() + " frames kept)";
            }
            showMessage("Video recording", msg, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (saved != null) showStatus(saved);
        if (failed != null) {
            System.err.println(failed.replace('\n', ' '));
            showError("Video recording", failed);
        }
    }

    public boolean isRecording() { return recording; }

    private final class ScreenPanel extends JPanel {
        /** EDT-owned copy of {@link #image} that is scaled onto the panel. */
        private final BufferedImage paintImage =
                new BufferedImage(VicII.WIDTH, VicII.HEIGHT, BufferedImage.TYPE_INT_RGB);
        private final int[] paintData = ((DataBufferInt) paintImage.getRaster().getDataBuffer()).getData();

        ScreenPanel() { setBackground(Color.BLACK); }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);   // black background fills the letterbox bars
            int pw = getWidth(), ph = getHeight();
            int iw = image.getWidth(), ih = image.getHeight();
            // Scale to fill the panel while keeping the framebuffer aspect ratio
            // (uniform scale, centred) — no horizontal/vertical distortion.
            double scale = Math.min(pw / (double) iw, ph / (double) ih);
            int dw = (int) Math.round(iw * scale);
            int dh = (int) Math.round(ih * scale);
            int dx = (pw - dw) / 2;
            int dy = (ph - dh) / 2;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // Copy under the lock (one consistent frame, ~0.1 ms), scale OUTSIDE it: the
            // scaled blit grows with the window, and the emulation thread needs the same
            // lock every frame in updateFrame.
            synchronized (frameLock) {           // never half of one frame and half of the next
                System.arraycopy(imageData, 0, paintData, 0, Math.min(paintData.length, imageData.length));
            }
            g2.drawImage(paintImage, dx, dy, dw, dh, null);
            if (paused) drawPausedOverlay(g2, pw, ph);
        }
    }

    /** Written on the EDT (F9), read on the emulation thread in {@link #updateFrame}. */
    private volatile boolean paused;
    /** Emulation thread only: the last {@link #updateFrame} already showed the paused picture. */
    private boolean lastWasPaused;
    /** Guards {@link #imageData}: the emulation thread writes it, the EDT paints / screenshots it. */
    private final Object frameLock = new Object();

    private void drawPausedOverlay(Graphics2D g2, int pw, int ph) {
        g2.setColor(new Color(0, 0, 0, 110));
        g2.fillRect(0, 0, pw, ph);
        g2.setColor(Color.WHITE);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, Math.max(18f, ph / 14f)));
        String s = "PAUSED";
        java.awt.FontMetrics fm = g2.getFontMetrics();
        g2.drawString(s, (pw - fm.stringWidth(s)) / 2, ph / 2);
    }
}
