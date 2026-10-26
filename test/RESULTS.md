# Ghidra compared with SAD

This file records how the Ghidra listing of an EEC-IV image compares with the listing that
SAD (the Semi Automatic Disassembler) makes of the same image. It holds counts, addresses and
instruction names only. The image, the listings and the detail files stay on the local machine
and are not in git.

Result: the 8065 language `8065:LE:24:default` decodes every one of SAD's 12,144 code rows in
bank 8 with the same address, length and mnemonic, the same jump and call targets, and the same
operands. With `EecFindCode.java` the Ghidra flow analysis also reaches all of them. The
remaining differences are listed and explained in "Remaining differences".

## How to run

```sh
test/run-compare.sh --mode multi                 # 8065:LE:24:default, banks 0, 1, 8, 9
test/run-compare.sh --mode multi --find-code     # also run ghidra_scripts/EecFindCode.java
test/run-compare.sh --mode multi --seed-only     # decode one instruction at each SAD code address
test/run-compare.sh --mode multi --seed          # start flow analysis at each SAD code address
test/run-compare.sh --mode single                # 806x:LE:16:default, bank 8 only
```

`--image` (or `EEC_IMAGE`) is the 256 KiB image and `--ref` (or `EEC_REF`) is the SAD listing in
the comparison format. The comparison script has unit tests:
`python3 -m unittest discover -s test/compare`. The script writes the Ghidra listing, a log, a report and a file with every
non-matching row to `.claude/local-data/ghidra/` in the main clone.

## Inputs

* Image: a 1996 F-350 7.3L controller image (Ford strategy TDE1). 262,144 bytes: four 64 KiB blocks in
  bank order 0, 1, 8, 9. The bytes of each bank start at offset 0x2000 in its block. Bank 8 holds
  the code; bank 0 is a copy of bank 8; banks 1 and 9 hold calibration data.
* References, made by the reference worker with a patched SAD 4.13 (`test/sad/` on the branch
  `feature/8065-sad-reference`):
  * `tde1_bank8_ref.tsv`: four-bank run, bank 8 rows only. SAD decides code and data itself.
    12,144 code rows. This is the main reference.
  * `tde1_bank8_ref_codeforced.tsv`: the same run with a directive that makes SAD decode all of
    0x82072-0x8bd1f as code, data included. 12,981 code rows.
  * `tde1_singlebank_ref.tsv`: single-bank run of bank 8. 1,887 code rows. SAD reads its code
    tables from bank 8 instead of bank 1 in this mode, so it finds little code.

## Modes

* Flow: Ghidra disassembles from the reset entry (bank 8, 0x2000) and the 40 interrupt vectors
  at 0x2010-0x205f, and follows every jump and call (`EecLoadBanks.java`).
* Find code: flow, then `EecFindCode.java` (code tables, inline arguments, code gaps, skipped
  bytes; see the script header).
* Seed only: Ghidra decodes one instruction at every SAD code address. This tests the decoding
  without the flow analysis.
* Seeded: Ghidra starts flow analysis at every SAD code address.

## Comparison rules

* Instructions are compared by address, length and mnemonic, in lowercase. `GHIDRA_TO_SAD` in
  `test/compare/compare_listings.py` maps the Ghidra mnemonics that differ to the SAD names
  (for example `lcall` to `call`, `jbc` to `jnb`, `ldbze` to `ldzbw`). The new 8065
  instructions use the SAD names.
* A single-bank listing (4-digit addresses) is read as bank 8.
* The ROM bank prefix (opcode 10 and a bank byte) is a separate 2-byte row in both listings: SAD
  prints `rombk n`, Ghidra decodes the instruction `ROMBK n`, which sets the bank of the next
  instruction only.
* `SKIP` (opcode 00) is a 1-byte jump over the next byte in both listings.
* Jump and call targets are compared as numbers. Operands are compared after a normalization:
  register names to addresses, `R30` to `30`, Ghidra's `0x12[0x34]` to SAD's `[R34+12]`, signed
  bytes to unsigned bytes, and SAD's `[R0+x]` to Ghidra's absolute `[x]` without the bank.

## Results

### Baseline: 806x:LE:16:default before this work

The upstream 8061 language (commit c860ca1, compiled under Ghidra 12.2), single-bank mode. A first
baseline was taken against an earlier SAD listing (flow mode 10.67%, seed only 97.39% matching);
those figures are replaced by the figures below against the corrected references.

| Figure | Flow, vs single-bank ref | Seed only, vs single-bank ref | Flow, vs bank 8 ref | Seed only, vs bank 8 ref |
|---|---:|---:|---:|---:|
| SAD code rows | 1,887 | 1,887 | 12,144 | 12,144 |
| match | 1,399 (74.14%) | 1,862 (98.68%) | 1,399 (11.52%) | 12,043 (99.17%) |
| length or mnemonic differs | 3 | 3 | 3 | 64 |
| Ghidra decode error | 3 | 22 | 3 | 37 |
| not disassembled | 482 | 0 | 10,739 | 0 |

Causes:

* `fe` was a separate 1-byte instruction `signds`, so every signed multiply and divide and every
  alternate-stack instruction was wrong: `sml2w` 34, `sml3w` 13, `sdivw` 9, `pusha` 4, `popa` 4.
* `regbk` (20) and `rombk` (17) were decode errors. In flow mode Ghidra stopped at the first two
  `regbk` and the first `rombk` on its paths.
* The opcodes 04, 0b and 1b were decoded as `skr4`, `skrc` and `xchb`, which SAD and the Ford
  tables list as unused: `@if defined(8065)` is true when 8065 is defined as "0". They are not in
  SAD's code in this image, so they do not change the counts above.

### 806x:LE:16:default after this work

| Figure | Flow, vs single-bank ref | Seed only, vs single-bank ref | Flow, vs bank 8 ref | Seed only, vs bank 8 ref |
|---|---:|---:|---:|---:|
| match | 1,402 (74.30%) | 1,865 (98.83%) | 1,402 (11.54%) | 12,099 (99.63%) |
| length or mnemonic differs | 0 | 0 | 0 | 0 |
| Ghidra decode error | 3 | 22 | 3 | 45 |
| jump and call targets the same | 387 of 387 | 490 of 490 | 387 of 387 | 3,815 of 3,815 |

All decode errors are 8065-only instructions (`regbk` 20, `rombk` 17, `pusha` 4, `popa` 4),
which the 8061 language does not decode, as on an 8061.

### 8065:LE:24:default (new), bank 8

| Figure | Flow | Find code | Seed only | Seeded |
|---|---:|---:|---:|---:|
| SAD code rows | 12,144 | 12,144 | 12,144 | 12,144 |
| match | 1,457 (12.00%) | 12,144 (100.00%) | 12,144 (100.00%) | 11,839 (97.49%) |
| length or mnemonic differs | 0 | 0 | 0 | 0 |
| Ghidra decode error at a SAD address | 0 | 0 | 0 | 44 |
| inside another Ghidra instruction | 0 | 0 | 0 | 261 |
| not disassembled | 10,687 | 0 | 0 | 0 |
| jump and call targets the same | 396 of 396 | 3,815 of 3,815 | 3,815 of 3,815 | 3,747 of 3,747 |
| operands the same | 1,457 of 1,457 | 12,144 of 12,144 | 12,144 of 12,144 | 11,467 of 11,839 |
| Ghidra instructions not at a SAD code address | 0 | 636 | 0 | 433 |
| Ghidra decode errors in total | 0 | 12 | 0 | 172 |

Against the code-forced reference, seed only: 12,981 of 12,981 code rows match (100.00%), jump
and call targets 4,154 of 4,154; operands 10,510 of 12,981 (see difference 5).

`EecFindCode.java` found 10 inline-argument subroutines (all with 2 argument bytes), fixed 136
calls, read 16 code tables, and restarted the disassembly 5 times.

## Remaining differences

1. **Flow mode reaches 12% of SAD's code.** The rest is reached only through computed calls and
   code without references, which flow analysis does not follow:
   * Code tables: for example the background task loop at 0x8221e pushes a return address
     (`PUSHW` with a constant) and then an entry of the table at 0x1567c in bank 1 (`PUSHW`
     with an indirect operand, after an `AD2W` that adds the table address to the index). The
     table lists the tasks in bank 8.
   * Subroutines with inline arguments: for example the table lookup at 0x87e27 pops its return
     address, reads the 2 argument bytes after the call (two `LDB` with an auto-increment
     operand, each after `ROMBK 8`), and pushes the new return address. Without this knowledge Ghidra decodes the argument bytes as
     code after each call.
   * Subroutines that nothing refers to: SAD decodes a gap between code that ends with a RET
     (SAD 4.13 `scan_cgap`).
   * The byte after `SKIP` at 0x871ea, which SAD lists as an instruction.

   `EecFindCode.java` applies these four rules and then reaches all 12,144 rows.

2. **636 Ghidra instructions on bytes that SAD shows as unknown (`???`), with find code.** They
   come from code tables that SAD did not follow: 0x157b0 and 0x157ba (used at 0x83c2c and
   0x83cc4), 0x1596e, 0x159e2, 0x15a3a, 0x15f10, and 0x1619c and 0x161ae (used at 0x88d59 and
   0x88d4c, in code that SAD found only by its gap scan), and from two gaps (0x85d0f, 0x89486).
   For example 0x83c71 holds `SCALL`, `LDW`, `STW` and `SJMP` with the same call and jump
   targets as SAD's code just before it, and the table at 0x15a3a points to a row of four `RET`
   instructions at 0x85d0e. These instructions are probably real code that SAD misses; this was not checked
   further.

3. **12 decode errors in that extra code, with find code.** They are at calls to subroutines that
   take a variable number of inline bytes through a helper: 0x89124 pops two return addresses
   and copies a count of bytes from its caller's caller (for example 0x893c9 then takes 2 bytes
   from its own caller). `EecFindCode.java` recognizes only subroutines that read a fixed number
   of bytes after their own call, so Ghidra decodes some argument bytes there as code. SAD does
   not reach this code at all.

4. **Seeded mode: 305 rows differ.** The seeds include the code after calls to inline-argument
   subroutines; flow analysis from there decodes the argument bytes as code and then conflicts
   with the real code. Find code avoids this.

5. **Operands against the code-forced reference: 2,471 differ.** 2,470 follow `regbk 3` at 0x89418
   in that reference. SAD keeps the last register bank in listing order (its print pass does not
   reset it), so it adds 0x300 to the registers of the next 3,400 code rows; Ghidra follows
   control flow, and in seed-only mode it does not pass the bank of `REGBK` to the next
   instruction. The remaining one is an `ml2b` with an odd destination byte at 0x839d7: SAD
   applies the 8065 word register rule (an odd byte selects page 0x100) only when the read size in
   its opcode table is a word, and its table gives the destination of `ml2b` a byte read size.
   Ghidra applies the rule to every word operand, so the two show different register numbers. None of this occurs in the main reference.

6. **Word registers after `REGBK 1` or `REGBK 3`.** Ghidra follows the Ford EEC-IV Software
   Manual (6-4.4): bit 1 of the register bank selects the bank pair and bit 0 of the operand
   byte selects the even or odd bank. SAD 4.13 puts an even word register in the selected bank.
   The two differ only for even word registers after `REGBK 1` or `REGBK 3`, which do not occur in
   the code of this image (it uses only `regbk 0`).

7. **Data addresses 0x0400-0x1fff.** Ghidra treats 0x0000-0x1fff as unbanked, shared by all
   banks. SAD's pseudo source adds the data bank from 0x0400 (for example `[10400]` for
   `[R0+400]`). The operand columns agree. The Ford manual says the CPU drives the data bank on
   these addresses; which banks the RAM answers is a property of the board (see
   docs/8065-notes.md on the notes branch).

## Decisions

* Address format: bank × 0x10000 + 16-bit address in a 24-bit space; 0x00000-0x01fff is
  unbanked.
* The code writes `BANK_Select` (0x11) once, at 0x82075, with data bank 1 (bits 3-0) and stack
  bank 8 (bits 7-4) (Ford EEC-IV Software Manual 6-14.1.2; SAD 6.01 command.cpp). The code bank is
  not in `BANK_Select`: it is the bank of the instruction (PSW bits 13-10), changed only by a
  `ROMBK` prefix on a branch. Ghidra follows writes of a constant with `LDB`; the data bank is 1
  before the first write (SAD's default; after reset the register is 0 and memory expansion is
  off).
* `ROMBK` is a separate instruction that sets the bank of the next instruction's indirect or
  indexed operand, or of its jump, call or return target.
* Unused opcodes (04, 0b, 14, 1b-1f, c1, c5, cd, e1-e6, e8-ee, f7, and `fe` before other
  opcodes) are decode errors, as in SAD. Ford's table says the CPU runs most of them as
  no-operations or as aliases.
