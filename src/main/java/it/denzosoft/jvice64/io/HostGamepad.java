package it.denzosoft.jvice64.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * PowerShell helper: project the WinRT Gamepad API and stream pad state lines.
     * When {@code parentPid} is known the script checks about once a second that
     * this JVM is still alive and exits otherwise, so a JVM killed without running
     * its shutdown hooks leaves no orphan helper behind.
     */
    static String psScript(int padIndex, long parentPid) {
        return
        "$ErrorActionPreference='SilentlyContinue';" +
        "[void][Windows.Gaming.Input.Gamepad,Windows.Gaming.Input,ContentType=WindowsRuntime];" +
        "$jp=" + parentPid + ";$n=0;" +
        "while($true){" +
        " $n=($n+1)%60;" +
        " if($jp -gt 0 -and $n -eq 1 -and -not (Get-Process -Id $jp -ErrorAction SilentlyContinue)){exit};" +
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
    /**
     * Run flag of the CURRENT reader. Each start() gets its own flag, so a reader
     * from an earlier run that is still winding down can never be revived by a
     * later start() and feed the joystick alongside the new one.
     */
    private volatile AtomicBoolean run;
    private Thread thread;
    private Process process;
    private FileChannel device;               // Linux: open js device (closed by stop())
    private volatile String source;
    /** Windows: the helper has reported a connected pad (cleared again on "-"). */
    private volatile boolean padSeen;
    private boolean hookAdded;

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
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
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
                                     || name.toLowerCase(java.util.Locale.ROOT).contains("touchpad")
                                     || name.toLowerCase(java.util.Locale.ROOT).contains("mouse integration");
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
     *
     * <p>Linux: the device is opened here, so a missing permission (user not in
     * group {@code input}) is a {@code null} return, not a later stderr line.
     * Windows: the result only says that the helper is running; whether a pad is
     * actually connected is {@link #isConnected()}, known once the helper has
     * reported (PowerShell takes about a second to start).</p>
     *
     * @return a short label of the source, or null if no backend is available.
     */
    public synchronized String start() {
        if (isRunning()) return source;
        if (isWindows()) {
            Process p = startWindowsHelper();
            if (p == null) return null;
            final AtomicBoolean alive = new AtomicBoolean(true);
            process = p;
            padSeen = false;
            source = "Windows gamepad #" + (padIndex + 1) + " (XInput, via PowerShell)";
            run = alive;
            thread = new Thread(() -> runWindows(p, alive), "jvice-gamepad-p" + player);
        } else {
            File dev = findLinuxDevice(padIndex);
            if (dev == null) return null;
            if (!openLinux(dev)) return null;
        }
        addShutdownHook();
        thread.setDaemon(true);
        thread.start();
        return source;
    }

    /** Open {@code dev} and prepare its reader thread (not started). Package-private for testing. */
    boolean openLinux(File dev) {
        final FileChannel ch;
        try {
            ch = FileChannel.open(dev.toPath(), StandardOpenOption.READ);
        } catch (IOException | SecurityException e) {
            System.err.println("USB gamepad " + dev + " not opened: " + e);
            return false;
        }
        final AtomicBoolean alive = new AtomicBoolean(true);
        device = ch;
        source = dev.getPath();
        run = alive;
        thread = new Thread(() -> runLinux(ch, alive), "jvice-gamepad-p" + player);
        return true;
    }

    /** Package-private for testing: the reader of {@link #openLinux}. */
    synchronized void startThread() {
        if (thread != null && !thread.isAlive()) { thread.setDaemon(true); thread.start(); }
    }

    /** Stop the reader at JVM exit too (the Windows helper is a separate process). */
    private void addShutdownHook() {
        if (hookAdded) return;
        hookAdded = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "jvice-gamepad-exit-p" + player));
        } catch (IllegalStateException | SecurityException ignored) { }
    }

    public synchronized void stop() {
        AtomicBoolean r = run;
        if (r != null) r.set(false);
        run = null;
        padSeen = false;
        if (process != null) { process.destroy(); process = null; }
        // Closing the channel wakes a reader blocked in read() (FileChannel is
        // interruptible); FileInputStream.read on a device would stay blocked.
        if (device != null) { try { device.close(); } catch (IOException ignored) { } device = null; }
        if (thread != null) { thread.interrupt(); thread = null; }
        joystick.gpReset(player);
    }

    public boolean isRunning() { AtomicBoolean r = run; return r != null && r.get(); }

    /**
     * Whether a pad is really there: Linux = the device is open and being read;
     * Windows = the helper runs AND has reported a connected controller.
     */
    public boolean isConnected() { return isRunning() && (process == null || padSeen); }

    public String source() { return source; }

    // ---- Linux backend --------------------------------------------------------

    private void runLinux(FileChannel ch, AtomicBoolean alive) {
        byte[] ev = new byte[8];
        ByteBuffer buf = ByteBuffer.wrap(ev);
        try {
            while (alive.get()) {
                buf.clear();
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) return;    // EOF / unplugged
                }
                if (!alive.get()) break;             // stopped while blocked: drop it
                processEvent(ev);
            }
        } catch (IOException e) {
            // AsynchronousCloseException / ClosedByInterruptException = stop(): quiet.
            if (alive.get()) System.err.println("USB gamepad read ended: " + e);
        } finally {
            try { ch.close(); } catch (IOException ignored) { }
            endRun(alive);
        }
    }

    /** A reader ends: clear its flag; release the pad only if no newer run took over. */
    private void endRun(AtomicBoolean alive) {
        alive.set(false);
        synchronized (this) {
            if (run == alive || run == null) joystick.gpReset(player);
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

    /** Buttons held, one bit per button number (0..63). Button 1 = fire 2, any other = fire 1. */
    private long buttonsDown;
    /** Last value of the main stick (axes 0/1) and of the D-pad hat (axes 6/7), from rest. */
    private int stickX, stickY, hatX, hatY;

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
            if (number < 64) {
                if (value != 0) buttonsDown |= 1L << number; else buttonsDown &= ~(1L << number);
            }
            // Each button is tracked on its own, so releasing one (a shoulder, Start)
            // does not release fire while another is still held.
            joystick.gpFire(player, (buttonsDown & ~2L) != 0);      // button 0 (and others) -> fire 1
            joystick.gpFire2(player, (buttonsDown & 2L) != 0);      // second button -> fire 2 (POTY)
        } else if (type == JS_EVENT_AXIS) {
            switch (number) {
                case 0: stickX = value; break;                 // main stick
                case 1: stickY = value; break;
                case 6: hatX = value; break;                   // D-pad hat
                case 7: hatY = value; break;
                default: return;
            }
            // Stick and hat are separate sources: centring one keeps the other's direction.
            joystick.gpLeft(player,  stickX < -AXIS_THRESHOLD || hatX < -AXIS_THRESHOLD);
            joystick.gpRight(player, stickX >  AXIS_THRESHOLD || hatX >  AXIS_THRESHOLD);
            joystick.gpUp(player,    stickY < -AXIS_THRESHOLD || hatY < -AXIS_THRESHOLD);
            joystick.gpDown(player,  stickY >  AXIS_THRESHOLD || hatY >  AXIS_THRESHOLD);
        }
    }

    // ---- Windows backend ------------------------------------------------------

    private Process startWindowsHelper() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                psScript(padIndex, jvmPid()));
            // Merge stderr into the stream we read: an unread stderr pipe can fill up
            // and block the helper. processLine ignores lines it cannot parse.
            pb.redirectErrorStream(true);
            return pb.start();
        } catch (IOException e) {
            System.err.println("Windows gamepad helper not started: " + e.getMessage());
            return null;
        }
    }

    /** This JVM's process id (Java 8: from the runtime MXBean name "pid@host"), or -1. */
    private static long jvmPid() {
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = n.indexOf('@');
            return at > 0 ? Long.parseLong(n.substring(0, at)) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void runWindows(Process p, AtomicBoolean alive) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while (alive.get() && (line = r.readLine()) != null) {
                if (alive.get()) processLine(line);
            }
        } catch (IOException e) {
            if (alive.get()) System.err.println("USB gamepad read ended: " + e.getMessage());
        } finally {
            p.destroy();
            endRun(alive);
        }
    }

    /** Parse one "<lx> <ly> <buttons>" line from the WinRT helper. Package-private for testing. */
    void processLine(String line) {
        line = line.trim();
        if (line.equals("-")) { padSeen = false; joystick.gpReset(player); return; }   // no pad
        String[] p = line.split("\\s+");
        if (p.length < 3) return;                      // blank line / stderr noise
        int lx, ly; long btn;
        try { lx = Integer.parseInt(p[0]); ly = Integer.parseInt(p[1]); btn = Long.parseLong(p[2]); }
        catch (NumberFormatException e) { return; }
        padSeen = true;
        // WinRT: +Y is up. Directions from the left stick OR the D-pad.
        joystick.gpLeft (player, lx < -THUMB_THRESHOLD || (btn & DPAD_LEFT)  != 0);
        joystick.gpRight(player, lx >  THUMB_THRESHOLD || (btn & DPAD_RIGHT) != 0);
        joystick.gpUp   (player, ly >  THUMB_THRESHOLD || (btn & DPAD_UP)    != 0);
        joystick.gpDown (player, ly < -THUMB_THRESHOLD || (btn & DPAD_DOWN)  != 0);
        joystick.gpFire (player, (btn & BTN_A) != 0);          // A -> fire 1
        joystick.gpFire2(player, (btn & BTN_B) != 0);          // B -> fire 2
    }
}
