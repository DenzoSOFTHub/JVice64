package it.denzosoft.jvice64.cia;

import it.denzosoft.jvice64.io.Joystick;
import it.denzosoft.jvice64.io.Keyboard;

/**
 * CIA #1 ($DC00). Drives the keyboard matrix (port A selects rows, port B reads
 * columns) and its interrupt output is wired to the CPU IRQ line. Joystick 2 is
 * wired to port A and joystick 1 to port B (both active-low, wired-AND with the
 * keyboard lines and with the CIA output drivers).
 */
public final class Cia1 extends Cia {

    private final Keyboard keyboard;
    private final Joystick joystick;

    public Cia1(int clockHz, IrqLine irq, Keyboard keyboard, Joystick joystick) {
        super(clockHz, irq);
        this.keyboard = keyboard;
        this.joystick = joystick;
    }

    private int portAOut() { return ((pra & ddra) | (~ddra & 0xFF)) & 0xFF; }
    private int portBOut() { return ((prb & ddrb) | (~ddrb & 0xFF)) & 0xFF; }

    /*
     * The port pins are wired-AND: a joystick switch or a pressed key shorts a
     * line to ground and wins over the CIA output driver. So the joystick must be
     * visible on bits that are OUTPUTS too (the KERNAL leaves DDRA = $FF and
     * $DC00 = $7F, and most games read joystick 2 with a plain LDA $DC00).
     * Mixing it in only on input bits made port 2 invisible unless the game
     * cleared DDRA itself. A line pulled low by a joystick also selects that
     * keyboard row/column, exactly as on the real machine.
     */

    @Override
    public int getPortA() {
        int paDrive = portAOut() & joystick.readPort2();
        int pbDrive = portBOut() & joystick.readPort1();
        return paDrive & keyboard.readPortA(pbDrive) & 0xFF;   // keys pull rows low via selected columns
    }

    @Override
    public int getPortB() {
        int paDrive = portAOut() & joystick.readPort2();
        int pbDrive = portBOut() & joystick.readPort1();
        return pbDrive & keyboard.readPortB(paDrive) & 0xFF;   // keys pull columns low via selected rows
    }

    // Kept for the Cia contract; getPortA/getPortB above do the full pin model.
    @Override
    protected int readPortAInput() { return getPortA(); }

    @Override
    protected int readPortBInput() { return getPortB(); }
}
