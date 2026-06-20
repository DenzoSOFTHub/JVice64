package it.denzosoft.jvice64.cia;

/** Sink for a chip's interrupt output line (wired to CPU IRQ or NMI). */
public interface IrqLine {
    void set(boolean asserted);
}
