package it.denzosoft.jvice64.cia;

import it.denzosoft.jvice64.io.Joystick;
import it.denzosoft.jvice64.io.Keyboard;

/**
 * CIA #1 ($DC00). Drives the keyboard matrix (port A selects rows, port B reads
 * columns) and its interrupt output is wired to the CPU IRQ line. Joystick 2 is
 * wired to port A and joystick 1 to port B (both active-low, ANDed with the
 * keyboard lines).
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

    @Override
    protected int readPortAInput() {
        // Keyboard reverse scan AND joystick port 2.
        return keyboard.readPortA(portBOut()) & joystick.readPort2() & 0xFF;
    }

    @Override
    protected int readPortBInput() {
        // Keyboard columns for the selected rows AND joystick port 1.
        return keyboard.readPortB(portAOut()) & joystick.readPort1() & 0xFF;
    }
}
