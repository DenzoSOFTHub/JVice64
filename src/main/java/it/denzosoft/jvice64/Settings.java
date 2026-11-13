package it.denzosoft.jvice64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Properties;

/**
 * Persistent user settings, stored as a {@code .properties} file next to the
 * running jar so the application comes back up configured the way the user left
 * it. Setters are write-through (they save immediately), so a menu toggle is
 * remembered even if the app is killed rather than closed cleanly.
 *
 * <p>Also the single source of truth for the application's on-disk layout:
 * {@link #jarDir()} (where the jar lives — the default folder for file choosers)
 * and {@link #snapshotsDir()} ({@code <jarDir>/snapshots} — where save-states go).</p>
 */
public final class Settings {

    private final File file;
    private final Properties props = new Properties();

    public Settings() { this(new File(baseDir(), "jvice64.properties")); }

    public Settings(File file) { this.file = file; load(); }

    // ---- on-disk layout (static; usable before a Settings instance exists) ----

    /** Directory containing the running jar (or the classes dir in a dev run); falls back to CWD.
     *  This is the default start directory for file choosers. */
    public static File jarDir() {
        try {
            URL loc = Settings.class.getProtectionDomain().getCodeSource().getLocation();
            File f = new File(loc.toURI());
            return f.isFile() ? f.getParentFile() : f;   // a jar -> its folder; a classes dir -> itself
        } catch (URISyntaxException | RuntimeException e) {
            return new File(System.getProperty("user.dir", "."));
        }
    }

    /** True when the code runs from a jar file (not from a classes directory). */
    private static boolean runningFromJar() {
        try {
            URL loc = Settings.class.getProtectionDomain().getCodeSource().getLocation();
            return new File(loc.toURI()).isFile();
        } catch (URISyntaxException | RuntimeException e) {
            return false;
        }
    }

    /** Where user-writable state (settings file + snapshots) lives: the jar folder when it is
     *  writable (portable install), else {@code ~/.jvice64} (a normally-installed jar can sit in a
     *  read-only location). A dev run from a classes directory ({@code mvn exec:java}, an IDE)
     *  also uses {@code ~/.jvice64}: writing into {@code target/classes} would let the next
     *  {@code mvn package} pack the user's settings (absolute paths) and snapshots into the jar. */
    public static File baseDir() {
        File cached = baseDirCache;
        if (cached != null) return cached;
        File jar = jarDir();
        File dir;
        if (runningFromJar() && jar != null && jar.isDirectory() && canCreateFileIn(jar)) {
            dir = jar;
        } else {
            dir = new File(System.getProperty("user.home", "."), ".jvice64");
            if (!dir.isDirectory()) dir.mkdirs();
        }
        baseDirCache = dir;
        return dir;
    }

    private static volatile File baseDirCache;

    /**
     * Whether a file can really be created in {@code dir}. {@code File.canWrite()} is not
     * enough: on Windows it reports {@code true} for folders such as {@code Program Files}
     * where the user cannot create files, and every settings save would then fail.
     */
    private static boolean canCreateFileIn(File dir) {
        try {
            File probe = File.createTempFile(".jvice64-", ".tmp", dir);
            if (!probe.delete()) probe.deleteOnExit();
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** {@code <baseDir>/snapshots}, created on demand. Save-states live here. */
    public static File snapshotsDir() {
        File d = new File(baseDir(), "snapshots");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    // ---- load / save ----------------------------------------------------------

    public synchronized void load() {
        if (file == null) return;
        File src = file;
        if (!src.isFile()) {
            // A save that was cut off between "old file gone" and "new file in place" (a
            // non-atomic fallback move, see writeAtomically) leaves the complete new copy
            // as <name>.tmp: use it rather than starting from the defaults.
            File tmp = new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".tmp");
            if (!tmp.isFile()) return;
            src = tmp;
        }
        try (InputStream in = new FileInputStream(src)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException = malformed unicode escape: a hand-edited Windows
            // path with single backslashes (roms.dir=C:/users/... typed with '\' instead
            // of '/', where "\" + "u" starts an escape). Start from the defaults, and move
            // the file aside so the next write-through save does not overwrite what the
            // user typed.
            props.clear();
            File bad = new File(src.getAbsoluteFile().getParentFile(), file.getName() + ".bad");
            String where;
            try {
                java.nio.file.Files.move(src.toPath(), bad.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                where = "moved to " + bad.getName();
            } catch (IOException | RuntimeException moveFailed) {
                where = "could not be moved aside: " + moveFailed.getMessage();
            }
            loadProblem = "Settings file " + file.getName() + " could not be read ("
                    + e.getMessage() + "); defaults are used and the file was " + where + ".\n"
                    + "In a hand-edited file write paths with '/' or doubled backslashes (C:\\\\roms).";
            System.err.println(loadProblem.replace('\n', ' '));
        }
    }

    /** Why the settings file could not be read at startup, or {@code null}. */
    public synchronized String loadProblem() { return loadProblem; }
    private String loadProblem;

    /**
     * Write the settings atomically (see {@link #writeAtomically}), so a crash or a full
     * disk mid-write can never leave an empty or half-written settings file behind.
     */
    public synchronized void save() {
        if (file == null) return;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            props.store(buf, "JVice64 user settings");
            writeAtomically(file, buf.toByteArray());
            saveFailed = false;
        } catch (IOException | RuntimeException e) {
            if (!saveFailed) {
                saveFailed = true;
                String msg = "Settings could not be saved to " + file + ":\n" + e.getMessage()
                        + "\n\nChanges apply to this session only.";
                saveFailMessage = msg;
                System.err.println(msg.replace('\n', ' '));
                java.util.function.Consumer<String> cb = onSaveFailed;
                if (cb != null) { saveFailReported = true; cb.accept(msg); }   // once per failing streak
                else saveFailReported = false;           // nobody to tell yet: tell the next listener
            }
        }
    }

    private boolean saveFailed;
    private boolean saveFailReported;   // the current failing streak has reached a listener
    private String saveFailMessage;     // what went wrong in the current failing streak

    /** True while the last save failed (the in-memory values are newer than the file). */
    public synchronized boolean isSaveFailing() { return saveFailed; }

    /**
     * Told (once per failing streak, on the saving thread) when a save fails. A failure
     * that happened BEFORE a listener was registered (the ROM folder is saved at startup,
     * before the window exists) is delivered to it at once, so it is never lost.
     */
    public synchronized void setOnSaveFailed(java.util.function.Consumer<String> cb) {
        this.onSaveFailed = cb;
        if (cb != null && saveFailed && !saveFailReported) {
            saveFailReported = true;
            cb.accept(saveFailMessage);
        }
    }
    private volatile java.util.function.Consumer<String> onSaveFailed;

    /**
     * Replace {@code file} with {@code data} without ever leaving a truncated file: the
     * bytes go to {@code <name>.tmp} in the same folder, are synced to the disk, and the
     * temporary file is then renamed over the real one.
     *
     * <ul>
     * <li>A symbolic link (settings kept with the user's dotfiles elsewhere) is followed:
     *     its target gets the new content and the link stays a link.</li>
     * <li>Where an atomic rename is not supported, the fallback rename deletes the old file
     *     first. If that rename then fails, the complete {@code .tmp} is the only good copy,
     *     so it is kept (and {@link #load} reads it when the real file is missing).</li>
     * </ul>
     * Also used for the game browser's favourites.
     */
    public static void writeAtomically(File file, byte[] data) throws IOException {
        java.nio.file.Path target = file.getAbsoluteFile().toPath();
        if (java.nio.file.Files.isSymbolicLink(target)) {
            try {
                target = target.toRealPath();
            } catch (IOException dangling) {         // link to a file that does not exist yet
                target = target.resolveSibling(java.nio.file.Files.readSymbolicLink(target));
            }
        }
        java.nio.file.Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        boolean tmpComplete = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(data);
                out.getFD().sync();                  // on disk before the rename makes it "the" file
            }
            tmpComplete = true;
            try {
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {   // e.g. some shared folders
                java.nio.file.Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            if (java.nio.file.Files.exists(target) || !tmpComplete) {
                tmp.toFile().delete();               // the real file is intact (or tmp is half-written)
            } else if (!tmp.toFile().renameTo(target.toFile())) {
                System.err.println("Kept " + tmp + ": the previous " + target.getFileName()
                        + " is gone and the new copy could not be renamed into place.");
            }
            throw e;
        }
    }

    // ---- typed, write-through accessors ---------------------------------------

    public boolean audioEnabled()        { return getBool("audio.enabled", false); }
    public void setAudioEnabled(boolean v) { setBool("audio.enabled", v); }

    /** "6581" or "8580". */
    public String sidModel()             { return props.getProperty("sid.model", "6581"); }
    public void setSidModel(String v)    { setStr("sid.model", v); }

    /** "PAL" or "NTSC". */
    public String region()               { return props.getProperty("region", "PAL"); }
    public void setRegion(String v)      { setStr("region", v); }

    public boolean trueDrive()           { return getBool("drive.true", false); }
    public void setTrueDrive(boolean v)  { setBool("drive.true", v); }

    /** 0 = both ports, 1 = port 1 only, 2 = port 2 only. */
    public int joystickPort()            { return getInt("joystick.port", 0); }
    public void setJoystickPort(int v)   { setStr("joystick.port", Integer.toString(v)); }

    /** Split the host inputs across both control ports (two players at once). */
    public boolean twoPlayer()            { return getBool("joystick.twoplayer", false); }
    public void setTwoPlayer(boolean v)   { setBool("joystick.twoplayer", v); }

    /**
     * Whether joystick direction keys ALSO type on the C64 keyboard (default off).
     * On means the arrows work as cursor keys again, at the cost of the port-line
     * clash that makes games read two directions at once.
     */
    public boolean joystickKeysType()          { return getBool("joystick.keystype", false); }
    public void setJoystickKeysType(boolean v) { setBool("joystick.keystype", v); }

    /** Also accept WASD + Left Shift / Q as player 2 (keyboards with no keypad). */
    public boolean wasdPlayer2()          { return getBool("joystick.wasd", false); }
    public void setWasdPlayer2(boolean v) { setBool("joystick.wasd", v); }

    /**
     * Joystick key bindings for a player, e.g. {@code joystick.p2.up=VK_NUMPAD8}.
     * Missing or unparseable entries fall back to that player's factory keys, so
     * a hand-edited properties file can never leave a player without controls.
     */
    public it.denzosoft.jvice64.io.JoyBinding joyBinding(int player) {
        it.denzosoft.jvice64.io.JoyBinding b = (player == 2)
                ? it.denzosoft.jvice64.io.JoyBinding.defaultsPlayer2()
                : it.denzosoft.jvice64.io.JoyBinding.defaultsPlayer1();
        String prefix = "joystick.p" + (player == 2 ? 2 : 1) + ".";
        for (it.denzosoft.jvice64.io.JoyBinding.Action a
                : it.denzosoft.jvice64.io.JoyBinding.Action.values()) {
            b.applyProperty(a.key(), props.getProperty(prefix + a.key()));
        }
        return b;
    }

    public void setJoyBinding(int player, it.denzosoft.jvice64.io.JoyBinding b) {
        if (b == null) return;
        String prefix = "joystick.p" + (player == 2 ? 2 : 1) + ".";
        for (java.util.Map.Entry<String, String> e : b.toProperties().entrySet()) {
            props.setProperty(prefix + e.getKey(), e.getValue());
        }
        save();
    }

    /** Forget any custom bindings so the factory keys apply again. */
    public void clearJoyBindings() {
        for (int player = 1; player <= 2; player++) {
            String prefix = "joystick.p" + player + ".";
            for (it.denzosoft.jvice64.io.JoyBinding.Action a
                    : it.denzosoft.jvice64.io.JoyBinding.Action.values()) {
                props.remove(prefix + a.key());
            }
        }
        save();
    }

    // ---- recently loaded programs --------------------------------------------

    /** How many entries the "Open recent" menu keeps. */
    public static final int MAX_RECENT = 8;

    /**
     * One entry of the recent list. {@code entry} is the name of a program inside
     * a {@code .d64} (as used by the game browser) and is empty for a plain file,
     * so a recent item can reload either kind exactly as it was loaded.
     */
    public static final class Recent {
        public final String title, path, entry;
        public Recent(String title, String path, String entry) {
            this.title = title == null ? "" : title;
            this.path = path == null ? "" : path;
            this.entry = entry == null ? "" : entry;
        }
        public java.io.File file() { return new java.io.File(path); }
        public boolean isDiskEntry() { return !entry.isEmpty(); }
        /** Same program? Used to move a re-loaded item back to the top. */
        public boolean sameAs(Recent o) {
            return o != null && path.equals(o.path) && entry.equals(o.entry);
        }
    }

    /** Most recent first; entries whose file no longer exists are skipped. */
    public synchronized java.util.List<Recent> recentFiles() {
        java.util.List<Recent> out = new java.util.ArrayList<Recent>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String path = props.getProperty("recent." + i + ".path", "");
            if (path.isEmpty()) continue;
            if (!new java.io.File(path).isFile()) continue;      // moved or deleted
            out.add(new Recent(props.getProperty("recent." + i + ".title", ""),
                               path,
                               props.getProperty("recent." + i + ".entry", "")));
        }
        return out;
    }

    /** Push a program to the top of the list, de-duplicating and trimming to 8. */
    public synchronized void addRecent(String title, java.io.File f, String entry) {
        if (f == null) return;
        Recent added = new Recent(title, f.getAbsolutePath(), entry);
        java.util.List<Recent> list = recentFiles();
        for (java.util.Iterator<Recent> it = list.iterator(); it.hasNext(); ) {
            if (it.next().sameAs(added)) it.remove();
        }
        list.add(0, added);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        writeRecent(list);
    }

    public synchronized void clearRecent() { writeRecent(new java.util.ArrayList<Recent>()); }

    private void writeRecent(java.util.List<Recent> list) {
        for (int i = 0; i < MAX_RECENT; i++) {
            props.remove("recent." + i + ".title");
            props.remove("recent." + i + ".path");
            props.remove("recent." + i + ".entry");
        }
        for (int i = 0; i < list.size(); i++) {
            Recent r = list.get(i);
            props.setProperty("recent." + i + ".title", r.title);
            props.setProperty("recent." + i + ".path", r.path);
            if (!r.entry.isEmpty()) props.setProperty("recent." + i + ".entry", r.entry);
        }
        save();
    }

    /** Remembered game collection folder ("" if unset). */
    public String gamesDir()             { return props.getProperty("games.dir", ""); }
    public void setGamesDir(String v)    { setStr("games.dir", v == null ? "" : v); }

    /** SID playback volume, 0..100 (host output gain; default 100). */
    public int volume()                  { return clamp(getInt("audio.volume", 100)); }
    public void setVolume(int v)         { setStr("audio.volume", Integer.toString(clamp(v))); }
    private static int clamp(int v)      { return v < 0 ? 0 : (v > 100 ? 100 : v); }

    /** Folder holding the C64 ROM dumps (kernal/basic/chargen[/dos1541].bin); "" if unset. */
    public String romsDir()              { return props.getProperty("roms.dir", ""); }
    public void setRomsDir(String v)     { setStr("roms.dir", v == null ? "" : v); }

    // ---- helpers --------------------------------------------------------------

    private boolean getBool(String k, boolean d) { return Boolean.parseBoolean(props.getProperty(k, Boolean.toString(d))); }
    private void setBool(String k, boolean v) { setStr(k, Boolean.toString(v)); }
    /** Write-through, but only when the value really changes: the ROM folder is set on every
     *  start, and rewriting an unchanged file each time is pointless (and on a read-only file
     *  it used to report a failure at startup). */
    private synchronized void setStr(String k, String v) {
        if (v.equals(props.getProperty(k))) return;
        props.setProperty(k, v);
        save();
    }
    private int getInt(String k, int d) {
        try { return Integer.parseInt(props.getProperty(k, Integer.toString(d))); }
        catch (NumberFormatException e) { return d; }
    }
}
