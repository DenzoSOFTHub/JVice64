package it.denzosoft.jvice64.media;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Front door for loading a C64 program from any supported container, picking
 * the parser by file extension.
 */
public final class Media {

    private Media() {
    }

    /**
     * Load a program from {@code f}, dispatching on its lower-cased extension:
     * {@code .prg}, {@code .p00}, {@code .t64}, {@code .d64}.
     *
     * @param f file to load
     * @return the first/only program contained
     * @throws IOException if the file cannot be read
     */
    public static ProgramFile loadAuto(File f) throws IOException {
        if (f == null) {
            throw new IllegalArgumentException("file is null");
        }
        byte[] raw = Files.readAllBytes(f.toPath());
        String fileName = f.getName();
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".prg")) {
            return Prg.load(raw, stripExtension(fileName));
        }
        if (lower.endsWith(".p00")) {
            return P00.load(raw);
        }
        if (lower.endsWith(".t64")) {
            return T64.loadFirst(raw);
        }
        if (lower.endsWith(".d64")) {
            return D64.open(raw).readFirst();
        }
        throw new IOException("Unsupported media extension: " + fileName);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
