package it.denzosoft.jvice64.ui;

import it.denzosoft.jvice64.Settings;
import it.denzosoft.jvice64.media.GameLibrary.Game;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Non-modal dialog that lists a game collection sorted by name, with a
 * search filter, a per-game favourites toggle (persisted to
 * {@code favorites.txt} in {@link Settings#baseDir()}, next to the settings) and a
 * "favourites only" view. Double-clicking a
 * row (or the Launch button) starts the game through the supplied callback.
 */
public final class GameBrowser extends JDialog {

    private final List<Game> all;
    private final Consumer<Game> onLaunch;
    private final Set<String> favorites = new HashSet<>();
    private final File favFile = new File(Settings.baseDir(), "favorites.txt");
    /** Where versions up to 1.1.3 kept the favourites; imported once if the new file is missing. */
    private static final File OLD_FAV_FILE = new File(System.getProperty("user.home", "."), ".jvice64-favorites");

    private final GameTableModel model = new GameTableModel();
    private final JTable table = new JTable(model);
    private final JTextField search = new JTextField(18);
    private final JCheckBox favOnly = new JCheckBox("Favourites only");
    private final JLabel count = new JLabel();

    private List<Game> view = new ArrayList<>();

    public GameBrowser(Frame owner, List<Game> games, Consumer<Game> onLaunch) {
        super(owner, "Game collection", false);
        this.all = games;
        this.onLaunch = onLaunch;
        loadFavorites();
        buildUi();
        refilter();
        setSize(560, 540);
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        left.add(new JLabel("Search:"));
        left.add(search);
        left.add(favOnly);
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(left, BorderLayout.WEST);
        top.add(count, BorderLayout.EAST);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(20);
        table.getColumnModel().getColumn(0).setMaxWidth(30);   // star
        table.getColumnModel().getColumn(0).setHeaderValue("★");
        table.getColumnModel().getColumn(2).setMaxWidth(64);   // volume
        table.setAutoCreateRowSorter(false);                   // already sorted by name

        JButton launch = new JButton("Launch");
        JButton close = new JButton("Close");
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(new JLabel("double-click or Launch to start   "));
        bottom.add(launch);
        bottom.add(close);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        search.getDocument().addDocumentListener(new SimpleDoc(this::refilter));
        favOnly.addActionListener(e -> refilter());
        launch.addActionListener(e -> launchSelected());
        close.addActionListener(e -> dispose());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) launchSelected();
            }
        });
        getRootPane().setDefaultButton(launch);
    }

    private void refilter() {
        String q = search.getText().trim().toLowerCase(java.util.Locale.ROOT);
        boolean fo = favOnly.isSelected();
        view = new ArrayList<>();
        for (Game g : all) {
            if (fo && !favorites.contains(g.key())) continue;
            if (!q.isEmpty() && !g.name.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            view.add(g);
        }
        model.fireTableDataChanged();
        count.setText(view.size() + " games   ");
    }

    private void launchSelected() {
        int r = table.getSelectedRow();
        if (r < 0 || r >= view.size() || onLaunch == null) return;
        onLaunch.accept(view.get(r));
        dispose();   // close the browser once a game is launched
    }

    private void toggleFavorite(int row) {
        Game g = view.get(row);
        if (!favorites.remove(g.key())) favorites.add(g.key());
        saveFavorites();
        if (favOnly.isSelected()) refilter();
        else model.fireTableRowsUpdated(row, row);
    }

    private void loadFavorites() {
        File tmp = new File(favFile.getPath() + ".tmp");   // left by a save cut off mid-rename
        File src = favFile.isFile() ? favFile : tmp.isFile() ? tmp
                 : OLD_FAV_FILE.isFile() ? OLD_FAV_FILE : null;
        if (src == null) return;
        try {
            for (String l : Files.readAllLines(src.toPath(), StandardCharsets.UTF_8))
                if (!l.trim().isEmpty()) favorites.add(l.trim());
            if (src == OLD_FAV_FILE) saveFavorites();      // one-time move to the new place
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not read favourites " + src + ": " + e.getMessage());
        }
    }

    /** Write through a temporary file, so a failed write never leaves a truncated list
     *  (same safe write as the settings: synced, symlink-aware, keeps the only good copy). */
    private void saveFavorites() {
        try {
            List<String> sorted = new ArrayList<>(favorites);
            java.util.Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            for (String l : sorted) sb.append(l).append(System.lineSeparator());
            Settings.writeAtomically(favFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not save favourites to " + favFile + ": " + e.getMessage());
        }
    }

    private final class GameTableModel extends AbstractTableModel {
        private final String[] cols = {"★", "Name", "Vol"};
        public int getRowCount() { return view.size(); }
        public int getColumnCount() { return 3; }
        public String getColumnName(int c) { return cols[c]; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        public boolean isCellEditable(int r, int c) { return c == 0; }
        public Object getValueAt(int r, int c) {
            Game g = view.get(r);
            switch (c) {
                case 0:  return favorites.contains(g.key());
                case 1:  return g.name;
                default: return String.format(java.util.Locale.ROOT, "%03d", g.volume);
            }
        }
        public void setValueAt(Object v, int r, int c) { if (c == 0) toggleFavorite(r); }
    }

    private static final class SimpleDoc implements DocumentListener {
        private final Runnable r;
        SimpleDoc(Runnable r) { this.r = r; }
        public void insertUpdate(DocumentEvent e) { r.run(); }
        public void removeUpdate(DocumentEvent e) { r.run(); }
        public void changedUpdate(DocumentEvent e) { r.run(); }
    }
}
