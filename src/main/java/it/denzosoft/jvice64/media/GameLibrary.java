package it.denzosoft.jvice64.media;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalogue of a C64 disk collection: parses {@code 00index-by-volume.txt} (lines like
 * {@code "Games Volume 069"} then {@code "2 - Example"}) and maps each game to its disk
 * and the program entry inside it (the game number, e.g. {@code "2"}). The disk file
 * name comes from the volume line, so here it is {@code games069.d64}. Games are
 * returned sorted by name.
 */
public final class GameLibrary {

    /** A single game: its name, the disk that holds it, and the entry to load. */
    public static final class Game {
        public final String name;
        public final int volume;
        public final int number;
        public final File disk;
        public final String entry;   // program name inside the d64 (== the game number)

        Game(String name, int volume, int number, File disk) {
            this.name = name;
            this.volume = volume;
            this.number = number;
            this.disk = disk;
            this.entry = String.valueOf(number);
        }

        /** Stable identifier used for the favourites set. */
        public String key() { return volume + "/" + number; }

        @Override public String toString() { return name; }
    }

    private static final Pattern VOLUME = Pattern.compile("^(\\S.*?)\\s+Volume\\s+(\\d+)\\s*$");
    private static final Pattern GAME = Pattern.compile("^(\\d+)\\s*-\\s*(\\S.*?)\\s*$");

    /** Progress callback during {@link #scan}: {@code done} of {@code total} units. */
    public interface Progress { void update(int done, int total); }

    private GameLibrary() { }

    /** The conventional file name for the index inside a collection folder. */
    public static final String INDEX_NAME = "00index-by-volume.txt";

    /** True if {@code dir} looks like a game collection (has the index file). */
    public static boolean looksLikeCollection(File dir) {
        return dir != null && new File(dir, INDEX_NAME).isFile();
    }

    /**
     * Parse the collection in {@code dir} and return every game whose disk file
     * actually exists, sorted case-insensitively by name (ties broken by volume).
     */
    public static List<Game> scan(File dir) throws IOException { return scan(dir, null); }

    /** As {@link #scan(File)} but reporting progress for a UI progress bar. */
    public static List<Game> scan(File dir, Progress progress) throws IOException {
        File index = new File(dir, INDEX_NAME);
        if (!index.isFile()) {
            throw new FileNotFoundException(INDEX_NAME + " not found in " + dir);
        }

        List<String> lines = Files.readAllLines(index.toPath(), StandardCharsets.ISO_8859_1);
        int total = lines.size();
        List<Game> games = new ArrayList<>();
        int volume = -1;
        String diskPrefix = "";
        for (int li = 0; li < total; li++) {
            if (progress != null && (li & 0x3F) == 0) progress.update(li, total);
            String t = lines.get(li).trim();
            // Game lines first: a game may itself be called "Something Volume 2".
            Matcher mg = GAME.matcher(t);
            if (!mg.matches()) {
                Matcher mv = VOLUME.matcher(t);
                if (mv.matches()) {
                    diskPrefix = mv.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                    volume = smallNumber(mv.group(2));   // -1: its games are skipped
                }
                continue;
            }
            if (volume <= 0) continue;
            int number = smallNumber(mg.group(1));
            if (number < 0) continue;
            String name = mg.group(2).trim();
            if (name.isEmpty()) continue;
            File disk = new File(dir, diskPrefix + String.format(Locale.ROOT, "%03d.d64", volume));
            if (disk.isFile()) {
                games.add(new Game(name, volume, number, disk));
            }
        }
        if (progress != null) progress.update(total, total);

        games.sort((a, b) -> {
            int c = a.name.compareToIgnoreCase(b.name);
            if (c != 0) return c;
            c = Integer.compare(a.volume, b.volume);
            return c != 0 ? c : Integer.compare(a.number, b.number);
        });
        return games;
    }

    /**
     * A volume / game number, or -1 when it has more than 6 digits: one typo or
     * phone-number-like line must not abort the whole scan with a
     * NumberFormatException.
     */
    private static int smallNumber(String digits) {
        return digits.length() > 6 ? -1 : Integer.parseInt(digits);
    }
}
