package it.denzosoft.jvice64.io;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The editable keyboard bindings of one joystick: five directions/fire plus the
 * second fire button. Stored as AWT key codes, but converted to and from the
 * {@code VK_*} names so {@code jvice64.properties} stays human-editable
 * (e.g. {@code joystick.p2.up=VK_NUMPAD8}).
 */
public final class JoyBinding {

    /** The six assignable actions of a C64 joystick. */
    public enum Action {
        UP("up"), DOWN("down"), LEFT("left"), RIGHT("right"), FIRE("fire"), FIRE2("fire2");

        private final String key;
        Action(String key) { this.key = key; }
        /** Property-name fragment, e.g. "up" in {@code joystick.p1.up}. */
        public String key() { return key; }
        /** Label for the UI. */
        public String label() {
            switch (this) {
                case UP: return "Up";
                case DOWN: return "Down";
                case LEFT: return "Left";
                case RIGHT: return "Right";
                case FIRE: return "Fire 1";
                default: return "Fire 2";
            }
        }
    }

    private final int[] codes = new int[Action.values().length];

    private JoyBinding(int up, int down, int left, int right, int fire, int fire2) {
        codes[Action.UP.ordinal()]    = up;
        codes[Action.DOWN.ordinal()]  = down;
        codes[Action.LEFT.ordinal()]  = left;
        codes[Action.RIGHT.ordinal()] = right;
        codes[Action.FIRE.ordinal()]  = fire;
        codes[Action.FIRE2.ordinal()] = fire2;
    }

    /** Player 1 factory default: arrow keys, Space, Ctrl. */
    public static JoyBinding defaultsPlayer1() {
        return new JoyBinding(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT,
                              KeyEvent.VK_RIGHT, KeyEvent.VK_SPACE, KeyEvent.VK_CONTROL);
    }

    /** Player 2 factory default: numeric keypad, keypad 0, keypad "." */
    public static JoyBinding defaultsPlayer2() {
        return new JoyBinding(KeyEvent.VK_NUMPAD8, KeyEvent.VK_NUMPAD2, KeyEvent.VK_NUMPAD4,
                              KeyEvent.VK_NUMPAD6, KeyEvent.VK_NUMPAD0, KeyEvent.VK_DECIMAL);
    }

    public JoyBinding copy() {
        JoyBinding b = defaultsPlayer1();
        System.arraycopy(codes, 0, b.codes, 0, codes.length);
        return b;
    }

    public int get(Action a) { return codes[a.ordinal()]; }
    public void set(Action a, int keyCode) { codes[a.ordinal()] = keyCode; }

    /** The action bound to {@code keyCode}, or null. */
    public Action actionFor(int keyCode) {
        if (keyCode == KeyEvent.VK_UNDEFINED) return null;
        for (Action a : Action.values()) if (codes[a.ordinal()] == keyCode) return a;
        return null;
    }

    /**
     * True when the four directions are still the numeric keypad, in which case
     * the single-key diagonals (keypad 7/9/1/3) are worth honouring as extras.
     */
    public boolean usesKeypadDirections() {
        return codes[Action.UP.ordinal()]    == KeyEvent.VK_NUMPAD8
            && codes[Action.DOWN.ordinal()]  == KeyEvent.VK_NUMPAD2
            && codes[Action.LEFT.ordinal()]  == KeyEvent.VK_NUMPAD4
            && codes[Action.RIGHT.ordinal()] == KeyEvent.VK_NUMPAD6;
    }

    /** True when the four directions are still the arrow keys. */
    public boolean usesArrowDirections() {
        return codes[Action.UP.ordinal()]    == KeyEvent.VK_UP
            && codes[Action.DOWN.ordinal()]  == KeyEvent.VK_DOWN
            && codes[Action.LEFT.ordinal()]  == KeyEvent.VK_LEFT
            && codes[Action.RIGHT.ordinal()] == KeyEvent.VK_RIGHT;
    }

    /**
     * Assign {@code keyCode} to one action, clearing it from every other action of
     * every player first: two actions sharing a key would fire both at once, which
     * is never wanted. Passing {@link KeyEvent#VK_UNDEFINED} just unbinds.
     *
     * @param all     the binding sets in play (typically player 1 and player 2)
     * @param target  which set in {@code all} receives the key
     */
    public static void assign(JoyBinding[] all, int target, Action action, int keyCode) {
        if (all == null || target < 0 || target >= all.length || action == null) return;
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            for (JoyBinding b : all) {
                if (b == null) continue;
                Action clash = b.actionFor(keyCode);
                if (clash != null) b.set(clash, KeyEvent.VK_UNDEFINED);
            }
        }
        all[target].set(action, keyCode);
    }

    // ---- persistence ----------------------------------------------------------

    /** {@code {"up": "VK_UP", ...}} for saving into the settings file. */
    public Map<String, String> toProperties() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Action a : Action.values()) m.put(a.key(), nameOf(codes[a.ordinal()]));
        return m;
    }

    /** Apply a saved value; unparseable or missing entries keep the current one. */
    public void applyProperty(String actionKey, String value) {
        if (value == null || value.isEmpty()) return;
        for (Action a : Action.values()) {
            if (a.key().equals(actionKey)) {
                int c = codeOf(value);
                if (c != KeyEvent.VK_UNDEFINED) codes[a.ordinal()] = c;
                return;
            }
        }
    }

    /** {@code VK_*} name of a key code, falling back to the raw number. */
    public static String nameOf(int keyCode) {
        for (Field f : KeyEvent.class.getFields()) {
            if (!f.getName().startsWith("VK_")) continue;
            try {
                if (f.getType() == int.class && f.getInt(null) == keyCode) return f.getName();
            } catch (IllegalAccessException ignored) { /* skip */ }
        }
        return Integer.toString(keyCode);
    }

    /** Parse a {@code VK_*} name (or a raw number); VK_UNDEFINED if unknown. */
    public static int codeOf(String name) {
        if (name == null) return KeyEvent.VK_UNDEFINED;
        String n = name.trim();
        if (n.isEmpty()) return KeyEvent.VK_UNDEFINED;
        if (n.matches("-?\\d+")) {
            try { return Integer.parseInt(n); } catch (NumberFormatException e) { return KeyEvent.VK_UNDEFINED; }
        }
        if (!n.startsWith("VK_")) n = "VK_" + n.toUpperCase();
        try {
            Field f = KeyEvent.class.getField(n);
            if (f.getType() == int.class) return f.getInt(null);
        } catch (Exception ignored) { /* unknown name */ }
        return KeyEvent.VK_UNDEFINED;
    }

    /** Human-readable key name for the UI ("Left Shift" style where possible). */
    public static String displayName(int keyCode) {
        if (keyCode == KeyEvent.VK_UNDEFINED) return "(none)";
        String t = KeyEvent.getKeyText(keyCode);
        return (t == null || t.isEmpty()) ? nameOf(keyCode) : t;
    }
}
