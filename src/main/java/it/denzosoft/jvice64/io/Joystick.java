package it.denzosoft.jvice64.io;

import java.awt.event.KeyEvent;

/**
 * The two C64 control ports, as seen by CIA1: each port reports 5 active-low
 * bits (bit0 up, bit1 down, bit2 left, bit3 right, bit4 fire; 0 = pressed).
 * Port 2 (the usual single-player game port) is wired to CIA1 port A, port 1 to
 * port B.
 *
 * <p>Each port has TWO independent input sources that are OR-combined, so the
 * host keyboard and a USB gamepad never clobber each other's held directions.</p>
 *
 * <h3>One-player mode (default)</h3>
 * Every host input drives a single shared state, routed to the port chosen with
 * {@link #setActivePort(int)} (0 = both ports see it). Arrow keys and the numeric
 * keypad are interchangeable, exactly as before.
 *
 * <h3>Two-player mode ({@link #setTwoPlayer(boolean)})</h3>
 * The inputs split across the two ports, so two people can play at once:
 * <table><caption>Two-player mapping</caption>
 *   <tr><th></th><th>Player 1</th><th>Player 2</th></tr>
 *   <tr><td>Keyboard</td>
 *       <td>arrows, Space = fire 1, Ctrl = fire 2</td>
 *       <td>keypad 8/2/4/6 (+ 7/9/1/3 diagonals), 0 = fire 1, "." = fire 2;
 *           optionally WASD + Left Shift / Q via {@link #setWasdPlayer2(boolean)}
 *           for keyboards with no keypad</td></tr>
 *   <tr><td>USB pad</td><td>first pad</td><td>second pad</td></tr>
 * </table>
 * Player 1 gets control port 2 (what single-player games read) unless
 * {@link #setActivePort(int)} is 1, in which case the two are swapped.
 *
 * <h3>Key bindings</h3>
 * Those keys are only the factory defaults: each player's six actions are
 * rebindable through {@link JoyBinding} ({@link #setBinding(int, JoyBinding)}),
 * edited from Controls &gt; "Configure joystick keys…" and persisted in
 * {@code jvice64.properties} as {@code joystick.pN.<action>=VK_*}. Two extras
 * ride along with the defaults rather than being bound one by one: the keypad's
 * single-key diagonals (while a player's directions still are the keypad) and,
 * in one-player mode only, the keypad acting as a second way to drive the lone
 * stick.
 *
 * <p>Fields touched from more than one thread (EDT for keys, a gamepad reader
 * thread per pad, the emu thread for the {@code read*} accessors) are volatile;
 * each source's bitset is mutated by a single thread, so the read-modify-writes
 * never race.</p>
 */
public final class Joystick {

    private static final int UP = 0x01, DOWN = 0x02, LEFT = 0x04, RIGHT = 0x08, FIRE = 0x10;

    /** One control port: the keyboard source and the gamepad source, OR-combined. */
    private static final class Port {
        volatile int kbBits;                     // directions + fire1 from the keyboard
        volatile int gpBits;                     // directions + fire1 from a USB pad
        volatile boolean kbFire2, gpFire2;       // second fire (POTY line)

        int pressed()   { return (kbBits | gpBits) & 0x1F; }
        boolean fire2() { return kbFire2 || gpFire2; }
        void clear()    { kbBits = gpBits = 0; kbFire2 = gpFire2 = false; }
        void kb(int mask, boolean d) { if (d) kbBits |= mask; else kbBits &= ~mask; }
        void gp(int mask, boolean d) { if (d) gpBits |= mask; else gpBits &= ~mask; }
    }

    /** Port 1 = CIA1 port B. Only driven in two-player mode. */
    private final Port port1 = new Port();
    /** Port 2 = CIA1 port A, and the shared state in one-player mode. */
    private final Port port2 = new Port();

    private volatile int activePort = 0;         // 0 = both ports, 1 = port 1 only, 2 = port 2 only
    private volatile boolean twoPlayer;
    private volatile boolean wasdPlayer2;

    /** Editable key bindings, one set per player (see {@link JoyBinding}). */
    private volatile JoyBinding bind1 = JoyBinding.defaultsPlayer1();
    private volatile JoyBinding bind2 = JoyBinding.defaultsPlayer2();

    /** The binding set of player 1 or 2 (live object — call {@link #setBinding} to replace). */
    public JoyBinding binding(int player) { return player == 2 ? bind2 : bind1; }

    /** Replace a player's bindings (a copy is stored, so later edits are explicit). */
    public void setBinding(int player, JoyBinding b) {
        if (b == null) return;
        if (player == 2) bind2 = b.copy(); else bind1 = b.copy();
        reset();
    }

    /** Restore the factory keys for both players. */
    public void resetBindings() {
        bind1 = JoyBinding.defaultsPlayer1();
        bind2 = JoyBinding.defaultsPlayer2();
        reset();
    }

    public void reset() { port1.clear(); port2.clear(); }

    /**
     * Which control port the host inputs drive in one-player mode: 0 = both,
     * 1 = port 1, 2 = port 2. In two-player mode this instead selects which port
     * player 1 holds (1 = port 1, anything else = port 2).
     */
    public void setActivePort(int p) { activePort = p; reset(); }
    public int getActivePort() { return activePort; }

    /** Split the host inputs across both control ports (see the class docs). */
    public void setTwoPlayer(boolean on) { twoPlayer = on; reset(); }
    public boolean isTwoPlayer() { return twoPlayer; }

    /** Also accept WASD + Left Shift / Q as player 2 (for keyboards with no keypad). */
    public void setWasdPlayer2(boolean on) { wasdPlayer2 = on; reset(); }
    public boolean isWasdPlayer2() { return wasdPlayer2; }

    /** The port a given player (1 or 2) drives; in one-player mode both share port2. */
    private Port sink(int player) {
        if (!twoPlayer) return port2;
        boolean p1OnPort1 = (activePort == 1);
        if (player == 1) return p1OnPort1 ? port1 : port2;
        return p1OnPort1 ? port2 : port1;
    }

    // ---- CIA1 / SID reads -----------------------------------------------------

    /** CIA1 port A (control port 2), active-low (0 = pressed). */
    public int readPort2() {
        if (twoPlayer) return ~port2.pressed() & 0xFF;
        return (activePort != 1) ? (~port2.pressed() & 0xFF) : 0xFF;
    }

    /** CIA1 port B (control port 1), active-low. */
    public int readPort1() {
        if (twoPlayer) return ~port1.pressed() & 0xFF;
        return (activePort != 2) ? (~port2.pressed() & 0xFF) : 0xFF;
    }

    /**
     * Second fire button, wired to the POTY line (SID $D41A): 0 = pressed.
     * The SID pot lines are shared between the ports in this model, so either
     * player's second button reads through.
     */
    public int readPotY() { return (port1.fire2() || port2.fire2()) ? 0x00 : 0xFF; }

    // ---- host keyboard --------------------------------------------------------

    public void keyPressed(KeyEvent e)  { applyKey(e.getKeyCode(), true); }
    public void keyReleased(KeyEvent e) { applyKey(e.getKeyCode(), false); }

    private void applyKey(int kc, boolean down) {
        // Player 1 first, so a key bound to both players goes to player 1.
        if (applyFor(1, bind1, kc, down)) return;
        applyFor(2, bind2, kc, down);
    }

    /**
     * Whether this key steers a joystick DIRECTION, and so must not also be typed
     * into the C64 keyboard matrix.
     *
     * <p>The matrix shares the CIA1 port lines with the joysticks: cursor-right
     * sits at row 0 bit 2, and bit 2 of port B is joystick 1 LEFT — so pressing
     * the right arrow while the KERNAL scanned the keyboard made a game read
     * "left AND right" at once and the sprite would not steer. Feeding one key to
     * both destinations has no equivalent on real hardware, where the joystick is
     * a separate device.</p>
     *
     * <p>FIRE is deliberately NOT included: Space is fire 1 by default and also
     * sits on the very bit the joystick uses (row 7 bit 4 / port bit 4), so the
     * two agree instead of fighting — and countless games want a real "PRESS
     * SPACE" keypress. Only the directions are withheld.</p>
     */
    public boolean consumesKey(int keyCode) {
        return isDirection(1, bind1, keyCode) || isDirection(2, bind2, keyCode);
    }

    private boolean isDirection(int player, JoyBinding b, int keyCode) {
        JoyBinding.Action a = b.actionFor(keyCode);
        if (a != null) {
            return a == JoyBinding.Action.UP || a == JoyBinding.Action.DOWN
                || a == JoyBinding.Action.LEFT || a == JoyBinding.Action.RIGHT;
        }
        // The keypad diagonals / one-player keypad / WASD extras are directions too;
        // extraMask returns only direction bits, never FIRE, except the WASD Shift.
        int m = extraMask(player, b, keyCode);
        return m != 0 && (m & FIRE) == 0;
    }

    /** Route one key through a player's bindings; true if it was consumed. */
    private boolean applyFor(int player, JoyBinding b, int kc, boolean down) {
        JoyBinding.Action a = b.actionFor(kc);
        if (a != null) {
            if (a == JoyBinding.Action.FIRE2) sink(player).kbFire2 = down;
            else sink(player).kb(maskOf(a), down);
            return true;
        }
        int extra = extraMask(player, b, kc);
        if (extra != 0) { sink(player).kb(extra, down); return true; }
        if (player == 2 && wasdPlayer2 && kc == KeyEvent.VK_Q) { sink(2).kbFire2 = down; return true; }
        return false;
    }

    private static int maskOf(JoyBinding.Action a) {
        switch (a) {
            case UP:    return UP;
            case DOWN:  return DOWN;
            case LEFT:  return LEFT;
            case RIGHT: return RIGHT;
            default:    return FIRE;
        }
    }

    /**
     * Keys that come along with a binding set rather than being bound one by one:
     * the keypad's single-key diagonals (only while the directions still are the
     * keypad), the keypad as a second stick for the lone player in one-player
     * mode, and the optional WASD block for player 2.
     */
    private int extraMask(int player, JoyBinding b, int kc) {
        if (b.usesKeypadDirections()) {
            int d = keypadDiagonal(kc);
            if (d != 0) return d;
        }
        if (!twoPlayer && player == 1 && b.usesArrowDirections()) {
            int m = keypadMask(kc);            // one player: keypad drives the same stick
            if (m != 0) return m;
        }
        if (player == 2 && wasdPlayer2) {
            switch (kc) {
                case KeyEvent.VK_W:     return UP;
                case KeyEvent.VK_S:     return DOWN;
                case KeyEvent.VK_A:     return LEFT;
                case KeyEvent.VK_D:     return RIGHT;
                case KeyEvent.VK_SHIFT: return FIRE;
                default: break;
            }
        }
        return 0;
    }

    // ---- USB gamepads (driven by HostGamepad, one reader thread per pad) -------

    public void gpUp(int player, boolean d)    { sink(player).gp(UP, d); }
    public void gpDown(int player, boolean d)  { sink(player).gp(DOWN, d); }
    public void gpLeft(int player, boolean d)  { sink(player).gp(LEFT, d); }
    public void gpRight(int player, boolean d) { sink(player).gp(RIGHT, d); }
    public void gpFire(int player, boolean d)  { sink(player).gp(FIRE, d); }
    public void gpFire2(int player, boolean d) { sink(player).gpFire2 = d; }
    public void gpReset(int player)            { Port p = sink(player); p.gpBits = 0; p.gpFire2 = false; }

    // Single-pad conveniences (player 1), kept so existing callers still compile.
    public void gpUp(boolean d)    { gpUp(1, d); }
    public void gpDown(boolean d)  { gpDown(1, d); }
    public void gpLeft(boolean d)  { gpLeft(1, d); }
    public void gpRight(boolean d) { gpRight(1, d); }
    public void gpFire(boolean d)  { gpFire(1, d); }
    public void gpFire2(boolean d) { gpFire2(1, d); }
    public void gpReset()          { gpReset(1); }

    // ---- key maps -------------------------------------------------------------

    /** Numeric keypad (NumLock on), diagonals included. */
    private static int keypadMask(int kc) {
        int d = keypadDiagonal(kc);
        if (d != 0) return d;
        switch (kc) {
            case KeyEvent.VK_NUMPAD8: return UP;
            case KeyEvent.VK_NUMPAD2: return DOWN;
            case KeyEvent.VK_NUMPAD4: return LEFT;
            case KeyEvent.VK_NUMPAD6: return RIGHT;
            case KeyEvent.VK_NUMPAD0:
            case KeyEvent.VK_NUMPAD5: return FIRE;
            default: return 0;
        }
    }

    /** The keypad's single-key diagonals. */
    private static int keypadDiagonal(int kc) {
        switch (kc) {
            case KeyEvent.VK_NUMPAD7: return UP | LEFT;
            case KeyEvent.VK_NUMPAD9: return UP | RIGHT;
            case KeyEvent.VK_NUMPAD1: return DOWN | LEFT;
            case KeyEvent.VK_NUMPAD3: return DOWN | RIGHT;
            default: return 0;
        }
    }
}
