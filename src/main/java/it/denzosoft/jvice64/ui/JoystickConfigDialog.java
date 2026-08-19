package it.denzosoft.jvice64.ui;

import it.denzosoft.jvice64.Settings;
import it.denzosoft.jvice64.io.JoyBinding;
import it.denzosoft.jvice64.io.Joystick;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;

/**
 * Editor for the two players' joystick keys. Each action gets a button; clicking
 * it arms capture, and the next key press becomes the binding. Conflicts are
 * resolved by clearing the key wherever else it was bound, so one key can never
 * drive two actions at once.
 */
final class JoystickConfigDialog extends JDialog {

    private final Joystick joystick;
    private final Settings settings;
    private final JoyBinding[] edit = new JoyBinding[2];      // working copies, [0] = P1
    private final JButton[][] buttons =
            new JButton[2][JoyBinding.Action.values().length];
    private JButton capturing;                                 // armed button, or null

    JoystickConfigDialog(Frame owner, Joystick joystick, Settings settings) {
        super(owner, "Configure joystick keys", true);
        this.joystick = joystick;
        this.settings = settings;
        edit[0] = joystick.binding(1).copy();
        edit[1] = joystick.binding(2).copy();

        JPanel players = new JPanel(new GridLayout(1, 2, 16, 0));
        players.add(playerPanel(1));
        players.add(playerPanel(2));

        JLabel hint = new JLabel("Click a key, then press the key you want. Esc cancels the capture.");
        hint.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton defaults = new JButton("Restore defaults");
        defaults.addActionListener(e -> {
            edit[0] = JoyBinding.defaultsPlayer1();
            edit[1] = JoyBinding.defaultsPlayer2();
            refresh();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> { apply(); dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(defaults);
        south.add(cancel);
        south.add(ok);

        setLayout(new BorderLayout());
        add(hint, BorderLayout.NORTH);
        add(players, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);

        // Capture happens here rather than on the buttons, so any key (arrows,
        // Space, Tab...) is seen instead of being eaten by focus traversal.
        setFocusTraversalKeysEnabled(false);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { captured(e.getKeyCode()); }
        });
        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel playerPanel(int player) {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.setBorder(BorderFactory.createTitledBorder(
                "Player " + player + (player == 1 ? " (control port 2)" : " (control port 1)")));
        for (JoyBinding.Action a : JoyBinding.Action.values()) {
            p.add(new JLabel(a.label() + ":"));
            JButton b = new JButton();
            b.setFocusable(false);                       // keep key events on the dialog
            b.addActionListener(e -> arm(b));
            buttons[player - 1][a.ordinal()] = b;
            p.add(b);
        }
        return p;
    }

    private void arm(JButton b) {
        if (capturing != null) refreshButton(capturing);
        capturing = b;
        b.setText("press a key…");
        requestFocusInWindow();
    }

    private void captured(int keyCode) {
        if (capturing == null) return;
        if (keyCode == KeyEvent.VK_ESCAPE) { capturing = null; refresh(); return; }
        outer:
        for (int pl = 0; pl < 2; pl++) {
            for (JoyBinding.Action a : JoyBinding.Action.values()) {
                if (buttons[pl][a.ordinal()] != capturing) continue;
                JoyBinding.assign(edit, pl, a, keyCode);   // also clears any clash
                break outer;
            }
        }
        capturing = null;
        refresh();
    }

    private void refresh() {
        for (int pl = 0; pl < 2; pl++) {
            for (JoyBinding.Action a : JoyBinding.Action.values()) refreshButton(buttons[pl][a.ordinal()]);
        }
        requestFocusInWindow();
    }

    private void refreshButton(JButton b) {
        for (int pl = 0; pl < 2; pl++) {
            for (JoyBinding.Action a : JoyBinding.Action.values()) {
                if (buttons[pl][a.ordinal()] == b) {
                    b.setText(JoyBinding.displayName(edit[pl].get(a)));
                    return;
                }
            }
        }
    }

    private void apply() {
        joystick.setBinding(1, edit[0]);
        joystick.setBinding(2, edit[1]);
        if (settings != null) {
            settings.setJoyBinding(1, edit[0]);
            settings.setJoyBinding(2, edit[1]);
        }
    }
}
