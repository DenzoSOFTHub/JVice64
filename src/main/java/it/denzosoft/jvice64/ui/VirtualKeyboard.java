package it.denzosoft.jvice64.ui;

import it.denzosoft.jvice64.io.Keyboard;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * On-screen Commodore 64 keyboard in its own window. Clicking a key drives the
 * emulated {@link Keyboard} matrix directly (normal keys are momentary — held
 * while the mouse button is down; the modifiers CTRL / C= / SHIFT / SHIFT-LOCK
 * are sticky toggles so shifted characters can be typed with a single mouse).
 * RESTORE drives the NMI line. The window is non-focusable so it never steals
 * keyboard focus from the main emulator window.
 */
public final class VirtualKeyboard extends JFrame {

    private final Keyboard kbd;
    private final java.util.Map<Integer, java.util.List<JToggleButton>> modCells = new java.util.HashMap<>();

    private static final int UNIT = 40;        // base key size (px)
    private static final int GAP = 3;

    // Key kinds.
    private static final int NORMAL = 0, MOD = 1, RESTORE = 2;

    /** A key spec: label, matrix row/col (ignored for RESTORE), width in units, kind. */
    private static final class K {
        final String label; final int row, col, kind; final double w;
        K(String label, int row, int col, double w, int kind) {
            this.label = label; this.row = row; this.col = col; this.w = w; this.kind = kind;
        }
        static K n(String l, int r, int c) { return new K(l, r, c, 1, NORMAL); }
        static K n(String l, int r, int c, double w) { return new K(l, r, c, w, NORMAL); }
        static K mod(String l, int r, int c, double w) { return new K(l, r, c, w, MOD); }
        static K restore() { return new K("RESTORE", 0, 0, 1.5, RESTORE); }
    }

    // The four main rows (+ the function-key column on the right) and the space bar.
    private static final K[][] ROWS = {
        { K.n("←",7,1), K.n("1",7,0), K.n("2",7,3), K.n("3",1,0), K.n("4",1,3), K.n("5",2,0),
          K.n("6",2,3), K.n("7",3,0), K.n("8",3,3), K.n("9",4,0), K.n("0",4,3), K.n("+",5,0),
          K.n("-",5,3), K.n("£",6,0), K.n("HOME",6,3,1.3), K.n("INST DEL",0,0,1.5), K.n("F1",0,4) },

        { K.mod("CTRL",7,2,1.5), K.n("Q",7,6), K.n("W",1,1), K.n("E",1,6), K.n("R",2,1), K.n("T",2,6),
          K.n("Y",3,1), K.n("U",3,6), K.n("I",4,1), K.n("O",4,6), K.n("P",5,1), K.n("@",5,6),
          K.n("*",6,1), K.n("↑",6,6), K.restore(), K.n("F3",0,5) },

        { K.n("RUN STOP",7,7,1.6), K.mod("SHIFT LOCK",1,7,1.5), K.n("A",1,2), K.n("S",1,5), K.n("D",2,2),
          K.n("F",2,5), K.n("G",3,2), K.n("H",3,5), K.n("J",4,2), K.n("K",4,5), K.n("L",5,2),
          K.n(":",5,5), K.n(";",6,2), K.n("=",6,5), K.n("RETURN",0,1,1.7), K.n("F5",0,6) },

        { K.mod("C=",7,5,1.2), K.mod("SHIFT",1,7,1.7), K.n("Z",1,4), K.n("X",2,7), K.n("C",2,4),
          K.n("V",3,7), K.n("B",3,4), K.n("N",4,7), K.n("M",4,4), K.n(",",5,7), K.n(".",5,4),
          K.n("/",6,7), K.mod("SHIFT",6,4,1.7), K.n("CRSR ↕",0,7,1.2), K.n("CRSR ↔",0,2,1.2), K.n("F7",0,3) },
    };

    public VirtualKeyboard(Keyboard kbd) {
        super("C64 keyboard");
        this.kbd = kbd;

        JPanel keys = new JPanel();
        keys.setLayout(new BoxLayout(keys, BoxLayout.Y_AXIS));
        keys.setBackground(new Color(0x4a4a4a));
        keys.setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));

        for (K[] row : ROWS) keys.add(buildRow(row));

        // Space bar row.
        JPanel spaceRow = new JPanel(new FlowLayout(FlowLayout.CENTER, GAP, GAP));
        spaceRow.setOpaque(false);
        spaceRow.add(keyButton(new K("SPACE", 7, 4, 9, NORMAL)));
        keys.add(spaceRow);

        setContentPane(keys);
        setDefaultCloseOperation(HIDE_ON_CLOSE);   // closing it just hides it; the emulator keeps running
        setFocusableWindowState(false);            // never steal keyboard focus from the main window
        setResizable(false);
        pack();
    }

    private JPanel buildRow(K[] row) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, GAP, GAP));
        p.setOpaque(false);
        for (K k : row) p.add(keyButton(k));
        return p;
    }

    private AbstractButton keyButton(K k) {
        AbstractButton b = (k.kind == MOD) ? new JToggleButton(k.label) : new JButton(k.label);
        b.setMargin(new Insets(2, 2, 2, 2));
        b.setFocusable(false);
        int w = (int) Math.round(UNIT * k.w);
        b.setPreferredSize(new Dimension(w, UNIT));
        b.setFont(b.getFont().deriveFont(k.label.length() > 2 ? 9f : 13f));

        if (k.kind == MOD) {
            // Sticky toggle. Several toggles can share one matrix cell (SHIFT and
            // SHIFT-LOCK both = 1,7); the cell is held while ANY of them is on, so
            // releasing one does not clear a hold set by another.
            int cell = k.row * 8 + k.col;
            modCells.computeIfAbsent(cell, x -> new java.util.ArrayList<>()).add((JToggleButton) b);
            b.addActionListener(e -> {
                boolean any = false;
                for (JToggleButton t : modCells.get(cell)) any |= t.isSelected();
                kbd.setMatrix(k.row, k.col, any);
            });
        } else if (k.kind == RESTORE) {
            b.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { kbd.setRestore(true); }
                @Override public void mouseReleased(MouseEvent e) { kbd.setRestore(false); }
            });
        } else {
            // Momentary: held while the mouse button is down.
            b.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { kbd.setMatrix(k.row, k.col, true); }
                @Override public void mouseReleased(MouseEvent e) { kbd.setMatrix(k.row, k.col, false); }
            });
        }
        return b;
    }
}
