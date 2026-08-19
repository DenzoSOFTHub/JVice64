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

    /** Where user-writable state (settings file + snapshots) lives: the jar folder when it is
     *  writable (portable install), else {@code ~/.jvice64} (a normally-installed jar can sit in a
     *  read-only location). */
    public static File baseDir() {
        File jar = jarDir();
        if (jar != null && jar.isDirectory() && jar.canWrite()) return jar;
        File home = new File(System.getProperty("user.home", "."), ".jvice64");
        if (!home.isDirectory()) home.mkdirs();
        return home;
    }

    /** {@code <baseDir>/snapshots}, created on demand. Save-states live here. */
    public static File snapshotsDir() {
        File d = new File(baseDir(), "snapshots");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    // ---- load / save ----------------------------------------------------------

    public synchronized void load() {
        if (file == null || !file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("Settings load failed: " + e.getMessage());
        }
    }

    public synchronized void save() {
        if (file == null) return;
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, "JVice64 user settings");
        } catch (IOException e) {
            System.err.println("Settings save failed: " + e.getMessage());
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
    public void setJoystickPort(int v)   { props.setProperty("joystick.port", Integer.toString(v)); save(); }

    /** Split the host inputs across both control ports (two players at once). */
    public boolean twoPlayer()            { return getBool("joystick.twoplayer", false); }
    public void setTwoPlayer(boolean v)   { setBool("joystick.twoplayer", v); }

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

    /** Remembered Blast collection folder ("" if unset). */
    public String blastDir()             { return props.getProperty("blast.dir", ""); }
    public void setBlastDir(String v)    { setStr("blast.dir", v == null ? "" : v); }

    /** SID playback volume, 0..100 (host output gain; default 100). */
    public int volume()                  { return clamp(getInt("audio.volume", 100)); }
    public void setVolume(int v)         { props.setProperty("audio.volume", Integer.toString(clamp(v))); save(); }
    private static int clamp(int v)      { return v < 0 ? 0 : (v > 100 ? 100 : v); }

    /** Folder holding the C64 ROM dumps (kernal/basic/chargen[/dos1541].bin); "" if unset. */
    public String romsDir()              { return props.getProperty("roms.dir", ""); }
    public void setRomsDir(String v)     { setStr("roms.dir", v == null ? "" : v); }

    // ---- helpers --------------------------------------------------------------

    private boolean getBool(String k, boolean d) { return Boolean.parseBoolean(props.getProperty(k, Boolean.toString(d))); }
    private void setBool(String k, boolean v) { props.setProperty(k, Boolean.toString(v)); save(); }
    private void setStr(String k, String v) { props.setProperty(k, v); save(); }
    private int getInt(String k, int d) {
        try { return Integer.parseInt(props.getProperty(k, Integer.toString(d))); }
        catch (NumberFormatException e) { return d; }
    }
}
