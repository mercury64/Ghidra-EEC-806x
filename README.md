# Ghidra-EEC-806x

<!-- TABLE OF CONTENTS -->
<details open="open">
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
    </li>
    <li>
      <a href="#getting-started">Getting Started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#acknowledgements">Acknowledgements</a></li>
  </ol>
</details>

### Prerequisites

The EEC 806x processor module requires the following software to be installed:
* Java Development Kit (JDK)
* Ghidra 

### Installation

The module is a Ghidra user extension. These steps were tested with Ghidra 12.2.

1. Clone this repository.
2. Put the clone, or a symbolic link to it, in the Extensions folder of your Ghidra user
   settings folder. On macOS with Ghidra 12.2 that folder is
   `~/Library/ghidra/ghidra_12.2_PUBLIC/Extensions`:
   ```sh
   mkdir -p ~/Library/ghidra/ghidra_12.2_PUBLIC/Extensions
   ln -s "$PWD" ~/Library/ghidra/ghidra_12.2_PUBLIC/Extensions/Ghidra-EEC-806x
   ```
3. If you use a different Ghidra version, set `version` in `extension.properties` to that
   version.
4. Run Ghidra. Ghidra compiles the `.sla` files from the `.slaspec` files the first time it
   loads a language, and again when a `.slaspec` or `.sinc` file changes. To compile them
   yourself and see the compiler warnings:
   ```sh
   <ghidra>/support/sleigh -a data/languages
   ```


## Usage

### Languages

| Language ID | CPU | Address space |
|---|---|---|
| `806x:LE:16:default` | 8061 | 16-bit. ROM from 0x2000. |
| `8065:LE:24:default` | 8065 | bank × 0x10000 + 16-bit address. Bank 8 address 0x2075 is 0x82075. 0x00000-0x01fff (registers and RAM) is used by all banks. |

The 8065 language decodes the 8065 instructions: `ROMBK` (opcode 10, the bank for the next
instruction), `REGBK 0`-`REGBK 3` (register bank), and with the prefix fe the
alternate-stack instructions `PUSHA`, `POPA`, `CALLA`, `SCLLA`, `RETA`, `RETIA`, `PSHPA`,
`POPPA`. Both languages decode the signed multiply and divide instructions with the prefix
fe (`SML2W`, `SML3W`, `SML2B`, `SML3B`, `SDIVW`, `SDIVB`).

Banks in the 8065 language:

* Code runs in the bank of the instruction. Jumps and calls stay in that bank unless a
  `ROMBK` prefix gives another bank.
* Data at 0x2000 and above comes from the data bank, unless a `ROMBK` prefix gives another
  bank. The data bank is bits 0-3 of `BANK_Select` (register 0x11); Ghidra follows writes
  of a constant to it (`LDB BANK_Select,#0x01` selects data bank 1). Before such a write the
  data bank is 1.

### Load an 8065 image with all its banks

For a 256 KiB image with the banks in the order 0, 1, 8, 9:

1. File > Import File, choose the image. Format: Raw Binary. Language:
   `8065:LE:24:default`. Options: Base Address `0x82000`, File Offset `0x22000`, Length
   `0xe000`. This loads bank 8, the code bank. Do not run the auto-analysis yet.
2. Run the script `EecLoadBanks.java` (Script Manager, category EEC). It asks for the image
   again, loads the other banks (bank n at n × 0x10000 + 0x2000) and disassembles from the
   reset entry and the interrupt vectors.
3. Optional: run `EecFindCode.java`. It finds code that the flow analysis does not reach:
   tables of code addresses used through `PUSHW`, subroutines that read inline argument
   bytes after the call, code gaps that end with `RET`, and the byte after `SKIP`. These
   rules come from SAD; check the result.
4. Run the auto-analysis if you want it.

### Compare with SAD

`test/run-compare.sh` makes a Ghidra listing of an image and compares it with a listing from
SAD, the Semi Automatic Disassembler. See [test/RESULTS.md](test/RESULTS.md) for the method
and the results.

### Check the p-code with the emulator

`test/run-emulator-check.sh` runs the Ford function lookup routines of an image in Ghidra's
p-code emulator and compares the results with an independent calculation. See
[docs/emulator-check.md](docs/emulator-check.md) for the method and the results.

## Contributing

Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch
3. Commit your Changes
4. Push to the Branch
5. Open a Pull Request

## License

Released under the Apache 2.0 license. See `LICENSE` for more information.

## Acknowledgements
* [EFI Dyno Tuning](http://www.efidynotuning.com)
* [Semi Automatic Disassembler (SAD)](https://github.com/tvrfan/EEC-IV-disassembler)
* [Ghidra](https://ghidra-sre.org)
