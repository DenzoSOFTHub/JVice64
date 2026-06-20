package it.denzosoft.jvice64.io;

import java.awt.event.KeyEvent;

/**
 * Two digital joysticks. Each port reports 5 active-low bits on the CIA1 ports:
 * bit0 up, bit1 down, bit2 left, bit3 right, bit4 fire (0 = pressed).
 * Port 2 (the usual game port) is wired to CIA1 port A, port 1 to port B.
 *
 * <p>Two input sources feed the joystick INDEPENDENTLY and are OR-combined, so the
 * host keyboard and a USB gamepad never clobber each other's held directions:</p>
 * <ul>
 *   <li>Host keyboard — Arrow keys = directions, Space = fire 1, Ctrl = fire 2
 *       (2-button joystick, on the POTY line); numeric keypad (NumLock on): 8/2/4/6
 *       = directions, 7/9/1/3 = diagonals, 0/5 = fire 1, "." = fire 2.</li>
 *   <li>USB gamepad — driven by {@link HostGamepad} via the {@code gp*} methods.</li>
 * </ul>
 *
 * <p>Fields touched from more than one thread (EDT for keys, the gamepad reader
 * thread for {@code gp*}, the emu thread for the {@code read*} accessors) are
 * volatile; each source's bitset is mutated by a single thread, so the
 * read-modify-writes never race.</p>
 */
public final class Joystick {

    private static final int UP = 0x01, DOWN = 0x02, LEFT = 0x04, RIGHT = 0x08, FIRE = 0x10;

    private volatile int kbBits;             // keyboard directions + fire1 pressed (active-high)
    private volatile int gpBits;             // USB gamepad directions + fire1 pressed
    private volatile boolean kbFire2, gpFire2;   // second fire (POTY) from each source
    private volatile int activePort = 0;     // 0 = both ports, 1 = port 1 only, 2 = port 2 only

    public void reset() { kbBits = gpBits = 0; kbFire2 = gpFire2 = false; }

    /** Choose which control port the host inputs drive: 0 = both, 1 = port 1, 2 = port 2. */
    public void setActivePort(int p) { activePort = p; kbBits = gpBits = 0; kbFire2 = gpFire2 = false; }
    public int getActivePort() { return activePort; }

    private int pressed() { return (kbBits | gpBits) & 0x1F; }

    /** CIA1 port A (port 2), active-low (0 = pressed); 0xFF when this port isn't driven. */
    public int readPort2() { return (activePort != 1) ? (~pressed() & 0xFF) : 0xFF; }
    /** CIA1 port B (port 1), active-low. */
    public int readPort1() { return (activePort != 2) ? (~pressed() & 0xFF) : 0xFF; }
    /** Second fire button, wired to the POTY line (SID $D41A): 0 = pressed. */
    public int readPotY() { return (kbFire2 || gpFire2) ? 0x00 : 0xFF; }

    // ---- host keyboard --------------------------------------------------------

    public void keyPressed(KeyEvent e)  { applyKey(e.getKeyCode(), true); }
    public void keyReleased(KeyEvent e) { applyKey(e.getKeyCode(), false); }

    private void applyKey(int kc, boolean down) {
        if (kc == KeyEvent.VK_CONTROL || kc == KeyEvent.VK_DECIMAL) {   // second fire
            kbFire2 = down;
            return;
        }
        int mask = maskFor(kc);
        if (mask == 0) return;
        if (down) kbBits |= mask; else kbBits &= ~mask;
    }

    // ---- USB gamepad (driven by HostGamepad on its reader thread) --------------

    public void gpUp(boolean d)    { gp(UP, d); }
    public void gpDown(boolean d)  { gp(DOWN, d); }
    public void gpLeft(boolean d)  { gp(LEFT, d); }
    public void gpRight(boolean d) { gp(RIGHT, d); }
    public void gpFire(boolean d)  { gp(FIRE, d); }
    public void gpFire2(boolean d) { gpFire2 = d; }
    public void gpReset()          { gpBits = 0; gpFire2 = false; }
    private void gp(int mask, boolean d) { if (d) gpBits |= mask; else gpBits &= ~mask; }

    private static int maskFor(int kc) {
        switch (kc) {
            // Arrow keys + Space (no numeric keypad / NumLock needed).
            case KeyEvent.VK_UP:    return UP;
            case KeyEvent.VK_DOWN:  return DOWN;
            case KeyEvent.VK_LEFT:  return LEFT;
            case KeyEvent.VK_RIGHT: return RIGHT;
            case KeyEvent.VK_SPACE: return FIRE;
            // Numeric keypad (NumLock on).
            case KeyEvent.VK_NUMPAD8: return UP;
            case KeyEvent.VK_NUMPAD2: return DOWN;
            case KeyEvent.VK_NUMPAD4: return LEFT;
            case KeyEvent.VK_NUMPAD6: return RIGHT;
            case KeyEvent.VK_NUMPAD7: return UP | LEFT;
            case KeyEvent.VK_NUMPAD9: return UP | RIGHT;
            case KeyEvent.VK_NUMPAD1: return DOWN | LEFT;
            case KeyEvent.VK_NUMPAD3: return DOWN | RIGHT;
            case KeyEvent.VK_NUMPAD0:
            case KeyEvent.VK_NUMPAD5: return FIRE;
            default: return 0;
        }
    }
}
