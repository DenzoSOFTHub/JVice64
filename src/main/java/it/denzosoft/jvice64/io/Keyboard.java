package it.denzosoft.jvice64.io;

import java.awt.event.KeyEvent;

/**
 * Commodore 64 keyboard matrix emulation.
 *
 * <p>The C64 keyboard is an 8x8 matrix scanned through CIA1. Port A (PRA) drives
 * the rows as outputs (active LOW: a 0 bit selects that row), and Port B (PRB)
 * reads the columns (pulled high; a pressed key in a selected row pulls its
 * column bit LOW). The matrix is indexed as {@code pressed[row][column]} where
 * {@code row} is the PRA bit and {@code column} is the PRB bit.
 *
 * <pre>
 *        +-----+-----+-----+-----+-----+-----+-----+-----+
 *        |Col 0|Col 1|Col 2|Col 3|Col 4|Col 5|Col 6|Col 7|
 *  +-----+-----+-----+-----+-----+-----+-----+-----+-----+
 *  |Row 0| DEL |Retrn|C_L/R|  F7 |  F1 |  F3 |  F5 |C_U/D|
 *  |Row 1| 3 # |  W  |  A  | 4 $ |  Z  |  S  |  E  | S_L |
 *  |Row 2| 5 % |  R  |  D  | 6 & |  C  |  F  |  T  |  X  |
 *  |Row 3| 7 ' |  Y  |  G  | 8 ( |  B  |  H  |  U  |  V  |
 *  |Row 4| 9 ) |  I  |  J  |  0  |  M  |  K  |  O  |  N  |
 *  |Row 5|  +  |  P  |  L  |  -  | . > | : [ |  @  | , < |
 *  |Row 6|POUND|  *  | ; ] | HOME| S_R |  =  | A_UP| / ? |
 *  |Row 7| 1 ! |A_LFT| CTRL| 2 " |SPACE|  C= |  Q  | R/S |
 *  +-----+-----+-----+-----+-----+-----+-----+-----+-----+
 * </pre>
 *
 * <p>Uses a positional host mapping (PC key in the same physical spot as the C64
 * key) derived from VICE's {@code x11_pos.vkm}.
 *
 * <p>Not thread-safe. Key events and CPU port reads are expected to be
 * serialized by the host (e.g. on a single emulation thread).
 */
public final class Keyboard {

    /** Matrix state: pressed[row][column], true == key held down. */
    private final boolean[][] pressed = new boolean[8][8];

    /** RESTORE key (host PAGE_UP), wired to the CIA2 NMI line. */
    private boolean restore;

    /**
     * Synthetic LEFT-SHIFT (row1 col7) requested by the currently-held arrow
     * keys that need a shift (LEFT and UP). Counts how many such keys are down
     * so overlapping presses are handled correctly.
     */
    private int syntheticLeftShift;

    /**
     * Whether the host's real LEFT-SHIFT key is physically down. Tracked
     * separately from {@link #syntheticLeftShift} because both drive the same
     * matrix cell (row1 col7); the cell is only released when neither source
     * holds it.
     */
    private boolean realLeftShift;

    public Keyboard() {
        reset();
    }

    /** Clears the entire matrix and all auxiliary state. */
    public void reset() {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                pressed[r][c] = false;
            }
        }
        restore = false;
        syntheticLeftShift = 0;
        realLeftShift = false;
    }

    // ---------------------------------------------------------------------
    // Host key events
    // ---------------------------------------------------------------------

    /** Handles a host key-press, setting the matched matrix cell(s). */
    public void keyPressed(KeyEvent e) {
        setKey(e, true);
    }

    /** Handles a host key-release, clearing the matched matrix cell(s). */
    public void keyReleased(KeyEvent e) {
        setKey(e, false);
    }

    private void setKey(KeyEvent e, boolean down) {
        int code = e.getKeyCode();

        switch (code) {
            // ---- Letters ----
            case KeyEvent.VK_A: set(1, 2, down); return;
            case KeyEvent.VK_B: set(3, 4, down); return;
            case KeyEvent.VK_C: set(2, 4, down); return;
            case KeyEvent.VK_D: set(2, 2, down); return;
            case KeyEvent.VK_E: set(1, 6, down); return;
            case KeyEvent.VK_F: set(2, 5, down); return;
            case KeyEvent.VK_G: set(3, 2, down); return;
            case KeyEvent.VK_H: set(3, 5, down); return;
            case KeyEvent.VK_I: set(4, 1, down); return;
            case KeyEvent.VK_J: set(4, 2, down); return;
            case KeyEvent.VK_K: set(4, 5, down); return;
            case KeyEvent.VK_L: set(5, 2, down); return;
            case KeyEvent.VK_M: set(4, 4, down); return;
            case KeyEvent.VK_N: set(4, 7, down); return;
            case KeyEvent.VK_O: set(4, 6, down); return;
            case KeyEvent.VK_P: set(5, 1, down); return;
            case KeyEvent.VK_Q: set(7, 6, down); return;
            case KeyEvent.VK_R: set(2, 1, down); return;
            case KeyEvent.VK_S: set(1, 5, down); return;
            case KeyEvent.VK_T: set(2, 6, down); return;
            case KeyEvent.VK_U: set(3, 6, down); return;
            case KeyEvent.VK_V: set(3, 7, down); return;
            case KeyEvent.VK_W: set(1, 1, down); return;
            case KeyEvent.VK_X: set(2, 7, down); return;
            case KeyEvent.VK_Y: set(3, 1, down); return;
            case KeyEvent.VK_Z: set(1, 4, down); return;

            // ---- Digits (top row) ----
            case KeyEvent.VK_0: set(4, 3, down); return;
            case KeyEvent.VK_1: set(7, 0, down); return;
            case KeyEvent.VK_2: set(7, 3, down); return;
            case KeyEvent.VK_3: set(1, 0, down); return;
            case KeyEvent.VK_4: set(1, 3, down); return;
            case KeyEvent.VK_5: set(2, 0, down); return;
            case KeyEvent.VK_6: set(2, 3, down); return;
            case KeyEvent.VK_7: set(3, 0, down); return;
            case KeyEvent.VK_8: set(3, 3, down); return;
            case KeyEvent.VK_9: set(4, 0, down); return;

            // ---- Control / editing keys ----
            case KeyEvent.VK_ENTER:     set(0, 1, down); return; // RETURN
            case KeyEvent.VK_SPACE:     set(7, 4, down); return; // SPACE
            case KeyEvent.VK_BACK_SPACE:
            case KeyEvent.VK_DELETE:    set(0, 0, down); return; // DEL
            case KeyEvent.VK_HOME:      set(6, 3, down); return; // HOME
            case KeyEvent.VK_ESCAPE:    set(7, 7, down); return; // RUN/STOP
            case KeyEvent.VK_CONTROL:   set(7, 2, down); return; // CTRL
            case KeyEvent.VK_ALT:                                 // Commodore key
            case KeyEvent.VK_WINDOWS:   set(7, 5, down); return;

            // ---- Function keys ----
            case KeyEvent.VK_F1: set(0, 4, down); return;
            case KeyEvent.VK_F3: set(0, 5, down); return;
            case KeyEvent.VK_F5: set(0, 6, down); return;
            case KeyEvent.VK_F7: set(0, 3, down); return;

            // ---- Arrows (some require a synthetic LEFT-SHIFT) ----
            case KeyEvent.VK_RIGHT: set(0, 2, down); return;          // CRSR-L/R
            case KeyEvent.VK_DOWN:  set(0, 7, down); return;          // CRSR-U/D
            case KeyEvent.VK_LEFT:  setShiftedArrow(0, 2, down); return;
            case KeyEvent.VK_UP:    setShiftedArrow(0, 7, down); return;

            // ---- Positional punctuation ----
            case KeyEvent.VK_PLUS:        set(5, 0, down); return; // '+'
            case KeyEvent.VK_MINUS:       set(5, 3, down); return; // '-'
            case KeyEvent.VK_PERIOD:      set(5, 4, down); return; // '.'
            case KeyEvent.VK_COMMA:       set(5, 7, down); return; // ','
            case KeyEvent.VK_COLON:       set(5, 5, down); return; // ':'
            case KeyEvent.VK_AT:          set(5, 6, down); return; // '@'
            case KeyEvent.VK_ASTERISK:    set(6, 1, down); return; // '*'
            case KeyEvent.VK_SEMICOLON:   set(6, 2, down); return; // ';'
            case KeyEvent.VK_EQUALS:      set(6, 5, down); return; // '='
            case KeyEvent.VK_SLASH:       set(6, 7, down); return; // '/'

            // ---- Shift keys (distinguished by keyboard location) ----
            case KeyEvent.VK_SHIFT:
                if (e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT) {
                    set(6, 4, down); // RIGHT-SHIFT
                } else {
                    realLeftShift = down; // LEFT-SHIFT (left/standard/unknown)
                    updateLeftShift();
                }
                return;

            // ---- RESTORE ----
            case KeyEvent.VK_PAGE_UP:
                restore = down;
                return;

            default:
                // Unmapped key: ignore.
                return;
        }
    }

    /**
     * Sets an arrow key that needs a synthetic LEFT-SHIFT held alongside it.
     * The base matrix cell is the unshifted twin (RIGHT for LEFT, DOWN for UP),
     * and LEFT-SHIFT (row1 col7) is held while any such arrow is down.
     */
    private void setShiftedArrow(int row, int col, boolean down) {
        set(row, col, down);
        if (down) {
            syntheticLeftShift++;
        } else if (syntheticLeftShift > 0) {
            syntheticLeftShift--;
        }
        updateLeftShift();
    }

    /**
     * Recomputes the LEFT-SHIFT matrix cell (row1 col7) from its two
     * independent sources: the real host LEFT-SHIFT key and any synthetic
     * shift requested by shifted arrow keys. The cell stays pressed while
     * either source is active.
     */
    private void updateLeftShift() {
        pressed[1][7] = realLeftShift || syntheticLeftShift > 0;
    }

    private void set(int row, int col, boolean down) {
        pressed[row][col] = down;
    }

    /** Directly drive a matrix cell (used by the on-screen virtual keyboard). */
    public void setMatrix(int row, int col, boolean down) {
        if (row >= 0 && row < 8 && col >= 0 && col < 8) pressed[row][col] = down;
    }

    /** Drive the RESTORE line (NMI) from the virtual keyboard. */
    public void setRestore(boolean down) { restore = down; }

    // ---------------------------------------------------------------------
    // CIA port scanning
    // ---------------------------------------------------------------------

    /**
     * Reads CIA1 Port B (columns). Rows are selected by Port A: a row whose
     * Port A bit is 0 is active. For each pressed key in an active row, the
     * corresponding column bit is pulled LOW (cleared).
     *
     * @param portAOutput value driven on CIA1 Port A (rows, active LOW)
     * @return Port B column reading, 0..255 (a 0 bit means a pressed key)
     */
    public int readPortB(int portAOutput) {
        int result = 0xFF;
        for (int row = 0; row < 8; row++) {
            if ((portAOutput & (1 << row)) == 0) {     // row selected (active LOW)
                boolean[] cols = pressed[row];
                for (int col = 0; col < 8; col++) {
                    if (cols[col]) {
                        result &= ~(1 << col);          // pull column bit LOW
                    }
                }
            }
        }
        return result & 0xFF;
    }

    /**
     * Reverse scan: reads CIA1 Port A (rows) when Port B drives the columns.
     * This is the transpose of {@link #readPortB(int)}: a column whose Port B
     * bit is 0 is active, and pressed keys in that column pull their row bit LOW.
     *
     * @param portBOutput value driven on CIA1 Port B (columns, active LOW)
     * @return Port A row reading, 0..255 (a 0 bit means a pressed key)
     */
    public int readPortA(int portBOutput) {
        int result = 0xFF;
        for (int col = 0; col < 8; col++) {
            if ((portBOutput & (1 << col)) == 0) {     // column selected (active LOW)
                for (int row = 0; row < 8; row++) {
                    if (pressed[row][col]) {
                        result &= ~(1 << row);          // pull row bit LOW
                    }
                }
            }
        }
        return result & 0xFF;
    }

    /**
     * @return whether the RESTORE key (host PAGE_UP) is currently held. The
     *         caller wires this to the CIA2 NMI line.
     */
    public boolean restorePressed() {
        return restore;
    }
}
