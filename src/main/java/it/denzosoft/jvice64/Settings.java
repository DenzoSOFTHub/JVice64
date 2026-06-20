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

    /** Remembered Blast collection folder ("" if unset). */
    public String blastDir()             { return props.getProperty("blast.dir", ""); }
    public void setBlastDir(String v)    { setStr("blast.dir", v == null ? "" : v); }

    /** SID playback volume, 0..100 (host output gain; default 100). */
    public int volume()                  { return clamp(getInt("audio.volume", 100)); }
    public void setVolume(int v)         { props.setProperty("audio.volume", Integer.toString(clamp(v))); save(); }
    private static int clamp(int v)      { return v < 0 ? 0 : (v > 100 ? 100 : v); }

    // ---- helpers --------------------------------------------------------------

    private boolean getBool(String k, boolean d) { return Boolean.parseBoolean(props.getProperty(k, Boolean.toString(d))); }
    private void setBool(String k, boolean v) { props.setProperty(k, Boolean.toString(v)); save(); }
    private void setStr(String k, String v) { props.setProperty(k, v); save(); }
    private int getInt(String k, int d) {
        try { return Integer.parseInt(props.getProperty(k, Integer.toString(d))); }
        catch (NumberFormatException e) { return d; }
    }
}
