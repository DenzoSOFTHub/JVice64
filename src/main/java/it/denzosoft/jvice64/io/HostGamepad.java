package it.denzosoft.jvice64.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Optional USB gamepad / joystick support with NO native library bundled — it
 * only ever uses components already present in the OS:
 *
 * <ul>
 *   <li><b>Linux</b>: the joystick API ({@code /dev/input/jsN}) — a daemon thread
 *       reads the fixed 8-byte {@code js_event} records directly.</li>
 *   <li><b>Windows</b>: the OS's own {@code powershell.exe} (5.1) projecting the
 *       {@code Windows.Gaming.Input.Gamepad} WinRT API. A helper script polls the
 *       pad ~60×/s and prints {@code "<lx> <ly> <buttons>"} lines that this class
 *       parses. No JNI/JNA, no third-party jar — works for XInput (Xbox-style)
 *       controllers on Windows 10/11.</li>
 *   <li>Anything else / no pad: stays idle.</li>
 * </ul>
 *
 * Both backends drive the shared {@link Joystick} via its {@code gp*} methods:
 * the main stick (or D-pad) becomes the four directions, the lower face button
 * is fire 1 and the right face button is fire 2.
 */
public final class HostGamepad {

    // ---- Linux js_event ----
    private static final int JS_EVENT_BUTTON = 0x01;
    private static final int JS_EVENT_AXIS   = 0x02;
    private static final int JS_EVENT_INIT   = 0x80;
    private static final int AXIS_THRESHOLD  = 16000;     // of the ±32767 range

    // ---- Windows WinRT GamepadButtons flags + thumbstick threshold (of ±1000) ----
    private static final int BTN_A = 0x4, BTN_B = 0x8;
    private static final int DPAD_UP = 0x40, DPAD_DOWN = 0x80, DPAD_LEFT = 0x100, DPAD_RIGHT = 0x200;
    private static final int THUMB_THRESHOLD = 500;

    /** PowerShell helper: project the WinRT Gamepad API and stream pad state lines. */
    private static String psScript(int padIndex) {
        return
        "$ErrorActionPreference='SilentlyContinue';" +
        "[void][Windows.Gaming.Input.Gamepad,Windows.Gaming.Input,ContentType=WindowsRuntime];" +
        "while($true){" +
        " $g=[Windows.Gaming.Input.Gamepad]::Gamepads;" +
        " if($g -and $g.Count -gt " + padIndex + "){" +
        "  $r=$g[" + padIndex + "].GetCurrentReading();" +
        "  ('{0} {1} {2}' -f [int]($r.LeftThumbstickX*1000),[int]($r.LeftThumbstickY*1000),[int64]$r.Buttons)" +
        " } else { '-' }" +
        " Start-Sleep -Milliseconds 16" +
        "}";
    }

    private final Joystick joystick;
    /** Which control port this pad feeds: player 1 or player 2 (see {@link Joystick}). */
    private final int player;
    /** Which physical pad to open: 0 = first present, 1 = second, ... */
    private final int padIndex;
    private volatile boolean running;
    private Thread thread;
    private Process process;
    private volatile String source;

    /** First pad, driving player 1. */
    public HostGamepad(Joystick joystick) { this(joystick, 1, 0); }

    /**
     * @param player   1 or 2 — which control port this pad drives
     * @param padIndex 0-based index among the pads present, so a second player
     *                 opens the second device instead of fighting over the first
     */
    public HostGamepad(Joystick joystick, int player, int padIndex) {
        this.joystick = joystick;
        this.player = player;
        this.padIndex = padIndex;
    }

    public int player() { return player; }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** First usable {@code /dev/input/jsN} (n = 0..7), or null. */
    public static File findLinuxDevice() { return findLinuxDevice(0); }

    /** The {@code ordinal}-th usable {@code /dev/input/jsN}, or null if there are fewer. */
    public static File findLinuxDevice(int ordinal) {
        int seen = 0;
        for (int i = 0; i < 8; i++) {
            File f = new File("/dev/input/js" + i);
            if (f.exists() && isRealJoystick(i)) {
                if (seen == ordinal) return f;
                seen++;
            }
        }
        return null;
    }

    /** How many usable pads the host exposes (Linux; 0 elsewhere/unknown). */
    public static int countLinuxDevices() {
        int n = 0;
        for (int i = 0; i < 8; i++) if (new File("/dev/input/js" + i).exists() && isRealJoystick(i)) n++;
        return n;
    }

    /**
     * Whether {@code /dev/input/jsN} is an actual game controller.
     *
     * <p>The kernel hands a {@code jsN} node to anything with absolute axes, not
     * just to gamepads. A VirtualBox guest, for instance, exposes
     * {@code "VirtualBox mouse integration"} as {@code mouse2 event6 js1} — take
     * that for a joystick and every pointer movement is fed to the emulated
     * joystick as a direction, fighting the arrow keys. Worse, that device reports
     * ABSOLUTE coordinates, so a pointer near the top of the screen pins the Y
     * axis to about -32000 and reads as UP held down forever.</p>
     *
     * <p>The test: find the {@code /proc/bus/input/devices} block whose Handlers
     * list contains this {@code jsN}, and reject it if the same block is also a
     * mouse/touchpad handler. A real controller never claims one. If the file
     * cannot be read (not Linux, restricted), assume the device is fine — the old
     * behaviour.</p>
     */
    private static final java.util.Set<String> reported =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    static boolean isRealJoystick(int index) {
        String token = "js" + index;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream("/proc/bus/input/devices"), StandardCharsets.US_ASCII));
            String line;
            String handlers = null;
            String name = "";
            while ((line = r.readLine()) != null) {
                if (line.startsWith("N: Name=")) { name = line; handlers = null; }
                else if (line.startsWith("H: Handlers=")) handlers = line;
                if (handlers != null && hasToken(handlers, token)) {
                    boolean isPointer = hasPrefixedToken(handlers, "mouse")
                                     || name.toLowerCase().contains("touchpad")
                                     || name.toLowerCase().contains("mouse integration");
                    if (isPointer && reported.add(token)) {      // say it once, not per scan
                        System.out.println("Ignoring " + token + ": "
                                + name.replace("N: Name=", "").trim() + " is a pointing device, not a joystick.");
                    }
                    return !isPointer;
                }
            }
        } catch (IOException e) {
            return true;                      // cannot tell -> keep the old behaviour
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) { }
        }
        return true;                          // not listed -> assume usable
    }

    /** Exact whitespace-delimited token match ("js1" must not match "js11"). */
    private static boolean hasToken(String haystack, String token) {
        for (String part : haystack.split("[\\s=]+")) if (part.equals(token)) return true;
        return false;
    }

    /** A token starting with the prefix and continuing with digits, e.g. "mouse2". */
    private static boolean hasPrefixedToken(String haystack, String prefix) {
        for (String part : haystack.split("[\\s=]+")) {
            if (part.startsWith(prefix) && part.length() > prefix.length()
                    && Character.isDigit(part.charAt(prefix.length()))) return true;
        }
        return false;
    }

    /**
     * Detect a pad and start reading it on a daemon thread.
     * @return a short label of the source, or null if no backend is available.
     */
    public synchronized String start() {
        if (running) return source;
        if (isWindows()) {
            Process p = startWindowsHelper();
            if (p == null) return null;
            process = p;
            source = "Windows gamepad #" + (padIndex + 1) + " (XInput, via PowerShell)";
            running = true;
            thread = new Thread(this::runWindows, "jvice-gamepad-p" + player);
        } else {
            File dev = findLinuxDevice(padIndex);
            if (dev == null) return null;
            source = dev.getPath();
            running = true;
            thread = new Thread(() -> runLinux(dev), "jvice-gamepad-p" + player);
        }
        thread.setDaemon(true);
        thread.start();
        return source;
    }

    public synchronized void stop() {
        running = false;
        if (process != null) { process.destroy(); process = null; }
        if (thread != null) { thread.interrupt(); thread = null; }
        joystick.gpReset(player);
    }

    public boolean isRunning() { return running; }
    public String source() { return source; }

    // ---- Linux backend --------------------------------------------------------

    private void runLinux(File dev) {
        byte[] ev = new byte[8];
        try (InputStream in = new FileInputStream(dev)) {
            while (running) {
                if (!readFully(in, ev)) break;        // EOF / unplugged
                processEvent(ev);
            }
        } catch (IOException e) {
            System.err.println("USB gamepad read ended: " + e.getMessage());
        } finally {
            running = false;
            joystick.gpReset(player);
        }
    }

    /**
     * Resting position of each axis, taken from the synthetic JS_EVENT_INIT burst
     * the kernel sends on open. Most pads rest at 0, but a throttle, a pedal or a
     * trigger rests at full scale — measuring against 0 would read as a direction
     * held down forever, which the arrow keys then cannot overcome (the sources
     * are OR-combined).
     */
    private final int[] axisRest = new int[16];

    /** Decode one 8-byte js_event and drive the joystick. Package-private for testing. */
    void processEvent(byte[] ev) {
        int value = (short) ((ev[4] & 0xFF) | (ev[5] << 8));   // s16, little-endian
        int type  = ev[6] & 0xFF;
        int number = ev[7] & 0xFF;
        if ((type & JS_EVENT_INIT) != 0) {                     // synthetic initial state
            if ((type & ~JS_EVENT_INIT) == JS_EVENT_AXIS && number < axisRest.length) {
                axisRest[number] = value;                      // remember where this axis rests
            }
            return;
        }
        type &= ~JS_EVENT_INIT;
        if (type == JS_EVENT_AXIS && number < axisRest.length) {
            value -= axisRest[number];                         // measure from rest, not from 0
            if (value < -32767) value = -32767;
            if (value > 32767) value = 32767;
        }
        if (type == JS_EVENT_BUTTON) {
            boolean down = value != 0;
            if (number == 1) joystick.gpFire2(player, down);           // second button -> fire 2 (POTY)
            else joystick.gpFire(player, down);                        // button 0 (and others) -> fire 1
        } else if (type == JS_EVENT_AXIS) {
            switch (number) {
                case 0: case 6:                                // X axis or D-pad hat X
                    joystick.gpLeft(player, value < -AXIS_THRESHOLD);
                    joystick.gpRight(player, value > AXIS_THRESHOLD);
                    break;
                case 1: case 7:                                // Y axis or D-pad hat Y
                    joystick.gpUp(player, value < -AXIS_THRESHOLD);
                    joystick.gpDown(player, value > AXIS_THRESHOLD);
                    break;
                default: break;
            }
        }
    }

    // ---- Windows backend ------------------------------------------------------

    private Process startWindowsHelper() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", psScript(padIndex));
            pb.redirectErrorStream(false);
            return pb.start();
        } catch (IOException e) {
            System.err.println("Windows gamepad helper not started: " + e.getMessage());
            return null;
        }
    }

    private void runWindows() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while (running && (line = r.readLine()) != null) processLine(line);
        } catch (IOException e) {
            System.err.println("USB gamepad read ended: " + e.getMessage());
        } finally {
            running = false;
            joystick.gpReset(player);
        }
    }

    /** Parse one "<lx> <ly> <buttons>" line from the WinRT helper. Package-private for testing. */
    void processLine(String line) {
        line = line.trim();
        if (line.isEmpty() || line.equals("-")) { joystick.gpReset(player); return; }   // no pad
        String[] p = line.split("\\s+");
        if (p.length < 3) return;
        int lx, ly; long btn;
        try { lx = Integer.parseInt(p[0]); ly = Integer.parseInt(p[1]); btn = Long.parseLong(p[2]); }
        catch (NumberFormatException e) { return; }
        // WinRT: +Y is up. Directions from the left stick OR the D-pad.
        joystick.gpLeft (player, lx < -THUMB_THRESHOLD || (btn & DPAD_LEFT)  != 0);
        joystick.gpRight(player, lx >  THUMB_THRESHOLD || (btn & DPAD_RIGHT) != 0);
        joystick.gpUp   (player, ly >  THUMB_THRESHOLD || (btn & DPAD_UP)    != 0);
        joystick.gpDown (player, ly < -THUMB_THRESHOLD || (btn & DPAD_DOWN)  != 0);
        joystick.gpFire (player, (btn & BTN_A) != 0);          // A -> fire 1
        joystick.gpFire2(player, (btn & BTN_B) != 0);          // B -> fire 2
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }
}
