package it.denzosoft.jvice64.drive;

/**
 * The serial IEC bus: three open-collector lines (ATN, CLK, DATA). A line is
 * LOW (asserted) if any attached device pulls it low; otherwise it floats HIGH.
 * Here only the C64 and a single 1541 are attached. ATN is driven by the C64
 * only; CLK and DATA are driven by both. The drive also auto-acknowledges ATN
 * in hardware (ATNA), modelled in {@link DriveIecVia}.
 *
 * Booleans use the convention {@code true = line pulled LOW (asserted)}.
 */
public final class IecBus {

    private boolean c64Atn, c64Clk, c64Data;
    private boolean drvClk, drvData;
    public boolean atnEver, dataEver;   // debug sticky latches

    public void reset() { c64Atn = c64Clk = c64Data = drvClk = drvData = false; }

    // ---- C64 side (driven from CIA2) ---------------------------------------
    public void setC64(boolean atnLow, boolean clkLow, boolean dataLow) {
        c64Atn = atnLow; c64Clk = clkLow; c64Data = dataLow;
        if (atnLow) atnEver = true;
    }

    // ---- Drive side (driven from VIA1) -------------------------------------
    public void setDrive(boolean clkLow, boolean dataLow) {
        drvClk = clkLow; drvData = dataLow;
    }

    // ---- Resulting line levels (true = LOW) --------------------------------
    public boolean atn()  { return c64Atn; }
    public boolean clk()  { return c64Clk || drvClk; }
    public boolean data() { return c64Data || drvData; }

    // debug
    public boolean c64Clk()  { return c64Clk; }
    public boolean drvClk()  { return drvClk; }
    public boolean c64Data() { return c64Data; }
    public boolean drvData() { return drvData; }
}
