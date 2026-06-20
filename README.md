# JVice64

A Commodore 64 emulator in **pure Java (1.8) / Maven**, ported from
[VICE](https://vice-emu.sourceforge.io/). It runs the **native C64 ROMs**
(KERNAL, BASIC, character generator) and loads programs in the usual
`.prg`, `.p00`, `.t64` and `.d64` formats, with **video and audio** output.

The boot screen (native ROMs, real chargen font):

```
    **** COMMODORE 64 BASIC V2 ****
 64K RAM SYSTEM  38911 BASIC BYTES FREE
READY.
```

## Download

The latest build is published as a GitHub **release**:

- **[JVice64 v1.0.0](https://github.com/DenzoSOFTHub/JVice64/releases/tag/v1.0.0)** —
  download `jvice64.jar` and run it with `java -jar jvice64.jar`.

The release jar bundles the C64 ROMs, so it runs out of the box (the repository
itself does **not** include the ROMs — see *Build* below). Java 8+ required.

## Build

```sh
mvn package
```

Produces a self-contained runnable jar at `target/jvice64.jar`.

### C64 ROMs (required, not included)

The Commodore C64 ROMs are copyrighted and are **not** part of this repository.
Before building, supply your own ROM dumps in `src/main/resources/roms/`:

| File          | Size  | What            |
|---------------|-------|-----------------|
| `basic.bin`   | 8 KB  | BASIC V2        |
| `kernal.bin`  | 8 KB  | KERNAL          |
| `chargen.bin` | 4 KB  | character ROM   |
| `dos1541.bin` | 16 KB | 1541 DOS (true-drive; optional) |

They are bundled into the jar as resources at build time.

## Run

GUI (Swing window, ~50 Hz PAL, audio via `javax.sound`):

```sh
java -jar target/jvice64.jar                 # just boot to READY.
java -jar target/jvice64.jar game.prg        # boot and autostart a program
java -jar target/jvice64.jar disk.d64        # autostart first PRG on a disk
```

Headless boot/verification mode (no window — prints the 40×25 screen RAM as
text; handy for CI and quick checks):

```sh
java -jar target/jvice64.jar --headless 200 [program]
```

### In the window

- **Games → Browse Blast collection…** — open a browser of the whole *Blast*
  C64 disk collection (1734 games), **sorted by name**, with a search filter, a
  per-game **favourites** toggle (★, persisted to `~/.jvice64-favorites`) and a
  *favourites only* view (the collection is scanned on a worker thread with a
  progress bar). Double-click a row (or the Launch button) to start the game — it resets the machine, mounts the disk on device 8 and autostarts the
  selected program. *Games → Set Blast folder…* picks the collection directory
  (auto-detected if present).
- **File → Open program…** — load a `.prg` / `.p00` / `.t64` / `.d64` and
  autostart it.
- **File → Save state… / Load state…** — snapshot and restore the **full** machine
  (CPU, RAM/colour RAM, VIC-II, both CIAs, **SID**, cartridge banking and the
  **true-1541 drive**). Snapshots live in a `snapshots/` folder next to the jar; quick
  save/load use a **per-game slot** named after the loaded program.
- **Controls → Enable audio** + **volume slider** — audio starts **off**; enable it
  and set the SID playback volume (0–100). Both are remembered between runs.
- **Machine** menu / hotkeys — **F9** pause/resume (PAUSED overlay), **F10**
  screenshot (PNG), **F11**/**F12** start/stop **video recording** — an **AVI with
  video *and* audio** (the run's SID sound is captured even if the speakers are off),
  **F2**/**F4** quick save / quick load (per-game). Screenshots and recordings go to
  the home folder as `jvice64-<timestamp>.png` / `.avi`.
- The window is **freely resizable**: the C64 screen scales to fill the space
  while keeping its aspect ratio (letterboxed, never stretched/distorted).
- **File → Use real 1541 drive** — toggle true-drive emulation.
- **File → Reset** — reset the machine.
- Keyboard is **positional** (host keys map to the C64 key in the same place).
  - `Page Up` = **RESTORE** (RUN/STOP + RESTORE warm-starts BASIC).
  - `Escape` = **RUN/STOP**, `Tab`/`Alt` = **C=** (Commodore), `Ctrl` = **CTRL**.
  - Arrow keys and `Home` map as expected.
  - **C64 function keys** — the C64 has four function keys that double as eight
    (the even ones are *shifted*), and they map to the host like this:

    | C64 key | Host key      | C64 key | Host key            |
    |---------|---------------|---------|---------------------|
    | **F1**  | `F1`          | **F2**  | `Shift`+`F1`        |
    | **F3**  | `F3`          | **F4**  | `Shift`+`F3`        |
    | **F5**  | `F5`          | **F6**  | `Shift`+`F5`        |
    | **F7**  | `F7`          | **F8**  | `Shift`+`F7`        |

    i.e. `F1`/`F3`/`F5`/`F7` go straight through, and you get the C64's
    `F2`/`F4`/`F6`/`F8` by holding `Shift` — exactly as on real hardware.
    > Note: the host `F2`, `F4` and `F9`–`F12` keys are **reserved for emulator
    > functions** (quick save/load, pause, screenshot, recording — see the
    > *Machine* menu) and do **not** reach the C64. Use `Shift`+`F1`/`F3`/`F5`/`F7`
    > for the C64's even function keys, or click them on the on-screen keyboard.
- **Controls → Show virtual keyboard** — an on-screen Commodore 64 keyboard in a
  separate window: click any key to drive the emulated keyboard matrix (CTRL /
  C= / SHIFT / SHIFT-LOCK are sticky toggles so shifted characters and the
  shifted `F2`/`F4`/`F6`/`F8` work with a single mouse). The window never steals
  keyboard focus, so the host keyboard keeps working alongside it.
### Joystick — using the keyboard (or a USB pad)

Most games are joystick-controlled. You don't need a physical joystick: the host
keyboard drives the emulated one. Two key sets work (use whichever you like):

| Action      | Arrow-key set         | Numeric keypad (NumLock on)      |
|-------------|-----------------------|----------------------------------|
| Up/Down/L/R | **Arrow keys**        | `8` / `2` / `4` / `6`            |
| Diagonals   | (two arrows together) | `7` / `9` / `1` / `3`            |
| **Fire 1**  | **Space**             | `0` or `5`                       |
| **Fire 2**  | **Ctrl**              | `.` (decimal)                    |

- **Which port?** Use **Controls → Joystick: both / port 2 / port 1**. Most games
  read **port 2** (the default covers both). The choice is remembered.
- **Fire 2** is the second button of a 2-button joystick, carried on the POTY line
  (SID `$D41A`). Only the few games that support a 2-button joystick respond to it;
  a standard 1-button game ignores it.
- **USB joystick / gamepad** (optional): plug one in and it's auto-detected at
  launch (or use **Controls → Detect USB joystick…** for hot-plug). Left stick or
  D-pad = directions, the lower face button = fire 1, the right face button = fire 2.
  This needs no native library — on **Linux** it reads `/dev/input/jsN`, on
  **Windows 10/11** it uses the built-in PowerShell + `Windows.Gaming.Input` (XInput /
  Xbox-style pads). On macOS / other setups it stays idle and you use the keyboard.

## Supported file formats

| Ext   | Description                          | Notes                                   |
|-------|-------------------------------------|-----------------------------------------|
| `.prg`| Raw program (2-byte load address)   | `$0801` BASIC programs auto-`RUN`        |
| `.p00`| PC64 single-file container          | 26-byte `C64File` header                 |
| `.t64`| Tape archive                        | first file autostarted                   |
| `.d64`| 1541 disk image (35/40 track)       | first usable PRG autostarted; mounted on device 8 |
| `.crt`| Cartridge image                     | 8K / 16K / Ultimax + bank-switching (Ocean, Magic Desk); CBM80 autostart |
| `.tap`| Datasette tape image                | pulse stream → cassette read line (CIA1 FLAG)            |

Programs are injected at their load address and started via the KERNAL keyboard
buffer (`RUN` for BASIC, `SYS` otherwise) once BASIC reaches its idle loop.

### Device 8 (KERNAL LOAD trap)

Opening / attaching an image also mounts it on **device 8**, and the KERNAL LOAD
vector ($F4A5) is trapped, so programs and direct mode can load from it:

```basic
LOAD"$",8        : LIST      directory listing
LOAD"*",8        : RUN       first PRG on the disk
LOAD"NAME",8               relocated load (BASIC)
LOAD"NAME",8,1            load at the file's own address
```

Wildcards (`NAME*`) and a `0:` drive prefix are accepted.

**SAVE** is also supported — `SAVE"NAME",8` writes a new PRG into the mounted
D64 (allocating blocks in the BAM, writing the sector chain and a directory
entry) and persists the modified image back to the host `.d64` file. Saving over
an existing name replaces it. If no disk is mounted, a standalone `.prg` is
written next to the last-attached file (or the working directory).

**Sequential files** work too: `OPEN 2,8,2,"NAME,S,W"` / `PRINT#` / `INPUT#` /
`GET#` / `CLOSE` read and write real SEQ files on the mounted D64 (written back
to the host image on `CLOSE`).

No IEC/1541 CPU is emulated (so custom fast-loaders / drive code don't run), but
`LOAD`, `SAVE`, the directory and sequential files are all serviced by traps.

## Architecture

```
it.denzosoft.jvice64
├── Main            entry point (GUI + --headless harness, ROM loading)
├── C64             machine wiring + PAL frame loop + NMI/IRQ + autostart
├── cpu/Cpu6510     MOS 6510 core (full documented + undocumented/illegal
│                   opcodes, decimal mode, IRQ level / NMI edge)
├── io/KernalTrap   device-8 traps: LOAD/SAVE/directory + sequential files
│                   (OPEN/CHKIN/CHKOUT/CHRIN/CHROUT/GETIN/CLRCHN/CLOSE)
├── mem/C64Memory   64K RAM + full 32-config PLA bank switching ($00/$01 +
│                   cartridge EXROM/GAME, ROML/ROMH), I/O routing, VIC view
├── io/Joystick     two digital joysticks (numpad -> port 2)
├── Region          PAL/NTSC timing (clock, raster geometry, refresh)
├── debug/Disassembler   6502 disassembler + hex dump (monitor)
├── media/Cartridge,Crt   .crt parser (generic 8K/16K/Ultimax)
├── media/Tap,Datasette   .tap parser + cassette pulse playback
├── vic/VicII       VIC-II: text (hires/MC/ECM), bitmap (hires/MC), 8 sprites
│                   (expand/MC/priority/collisions), border, raster IRQ
├── cia/Cia,Cia1,Cia2  6526 timers A/B + TOD + IRQ/NMI; keyboard; VIC bank
├── sid/Sid         6581/8580 sound: 3 voices, ADSR, waveforms, filter
├── io/Keyboard     8×8 keyboard matrix (positional host mapping)
├── media/…         Prg / P00 / T64 / D64 / Media loaders
├── sound/AudioOutput   16-bit mono PCM line
├── ui/Display      scaled framebuffer window + key forwarding + menu
└── drive/…         real 1541: 6502 + 2×6522 VIA + DOS ROM + GCR disk + IEC
                    bus, lockstep with the C64 (true-drive LOAD works)
```

The emulation is **rasterline-stepped**: each PAL line is 63 PHI2 cycles, 312
lines/frame (~50 Hz). The CPU runs instruction-by-instruction with the CIAs and
SID clocked per instruction; the VIC renders one line at a time. Register
changes between lines (raster-IRQ split effects) are therefore honoured.

## Known limitations (v1)

- **Cycle-driven VIC** (the CPU drives it one cycle per bus access, 8 px/cycle
  with live registers). Working: mid-line `$D020`/`$D021-$D024` (raster bars /
  rainbow), per-character display fetch with live `$D018`/`$D011`/`$D016` (mid-
  line charset & mode switches), **both border flip-flops** — vertical (open top/
  bottom border, FLD / "no border") and horizontal/main (live CSEL 38/40-col,
  side-border opening), badline CPU stun, all graphics modes, sprites (incl.
  multiplexing). The CPU does the read-modify-write **dummy write** so RMW is
  cycle-exact and the I/O quirks work (`INC $D019` / `INC $DC0D` acknowledge the
  interrupt). Bad-line **and sprite-DMA** cycle stealing are modelled. The VIC now
  runs the real **VC/VCBASE/RC/VMLI video-counter state machine** (mirror of
  VICE's viciisc) driving the character addressing, with per-cycle bad-line
  re-evaluation — verified byte-identical to the old addressing on every normal
  frame, and the mid-line bad-line-abort (VSP/FLD) mechanism is active. The last
  remaining items are the pixel-level glitch *renders* (grey-dot, exact VSP/FLD,
  sprite-crunch): these need the VIC idle-state pixel pipeline + the sprite-DMA
  MC/expansion-flip-flop machine + Phi1/Phi2-resolution CPU timing, and can't be
  verified here without a reference C64 — the documented final frontier.
- Two disk paths. **(1) KERNAL traps (default)** — fast `LOAD`/`SAVE`/directory
  and sequential files (`OPEN`/`PRINT#`/`INPUT#`/`GET#`/`CLOSE`). **(2) True 1541
  drive** (`drive/`, File menu *"Use real 1541 drive"* or `--truedrive`) — a real
  6502 running the native 1541 DOS, two 6522 VIAs, GCR-encoded disk and the serial
  IEC bus, clocked in lockstep with the C64. The full path works: `LOAD"$",8`,
  `LOAD"NAME",8` and the status channel all read through the real DOS/GCR/IEC
  (verified). True drive is slower (two CPUs) and instruction-stepped, so a few
  timing-exact fast-loaders may not work; the trap path is the default.
- SID is close to reSID: combined waveforms use reSID's chip-measured 6581 sample
  tables; the **filter** uses reSID's measured 6581/8580 cutoff curve (the real
  nonlinear FC→Hz mapping, ~300 Hz floor + steep knee, extracted from reSID's
  op-amp model) with a zero-delay-feedback (TPT) state-variable core and the
  per-chip resonance ladder; output is **anti-aliased** (6th-order Butterworth at
  16 kHz, run at the SID clock) before decimation, so high harmonics/noise no
  longer alias into the audband. Both **6581 (default) and 8580** models are
  selectable (`--8580` / `C64.setSidModel`). `$D418` volume "digis" work via the
  volume DAC + DC blocker. The **ADSR envelope** is a faithful port of reSID's
  single-cycle generator: pipelined 2-cycle gate→state delays, the 1-cycle
  exponential-decrement delay, the FREEZED hold-at-zero state, and the ADSR
  rate-counter **delay bug** (the counter wraps the full 15 bits when the rate is
  lowered below it). SID emulation is now reSID-grade end to end.
- The unstable illegal opcodes (ANE/LAX#/SHA/SHX/SHY/TAS) use the common
  "magic constant" behaviour and are not chip-exact.
- Cartridges: 8K/16K/Ultimax + Ocean (type 5) and Magic Desk (type 0x10) bank
  switching; other mapper chips may not bank correctly.
- Joystick input is the host keyboard (arrow keys / numeric keypad + Space/Ctrl) or
  an optional USB gamepad (Linux `/dev/input`, Windows XInput via PowerShell — no
  native library). The **second fire button** rides the POTY line, so it only works
  in the few games that read a 2-button joystick. USB pads on macOS, and old
  DirectInput-only sticks on Windows, are not supported (use the keyboard).
- Save states snapshot the **full** machine — CPU, RAM/colour RAM, VIC-II, both
  CIAs, **SID** and the **true-1541 drive** — but the cartridge/disk must still be
  attached when restoring (the GCR track is rebuilt from the mounted disk).
- **NTSC** (`--ntsc`): full NTSC timing (263 lines × 65 cyc, ~60 Hz, 1.0227 MHz)
  and the NTSC Pepto palette. The C64 KERNAL is region-agnostic (same ROM on PAL
  and NTSC), so this is a complete NTSC machine.
- **Datasette** (`.tap`): the TAP pulse stream feeds the cassette read line
  (CIA1 FLAG) with motor/sense wired to the processor port. The FLAG is now fed
  **per cycle** (the datasette is driven by the CPU cycle sink alongside the VIC),
  so a tape pulse edge lands on the exact cycle — what turbo / timing-exact tape
  loaders need (verified: edges land cycle-exact vs the old per-instruction
  granularity).

## Reference

VICE source is the implementation reference (memory banking from
`c64/c64meminit.c`, palette `data/C64/pepto-pal.vpl`, disk layout
`vdrive/vdrive-dir.c`, SID `sid/fastsid.c`, …).
