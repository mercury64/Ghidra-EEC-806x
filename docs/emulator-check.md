# Emulator check of the Ford function lookup

After one fix to the 8065 language, Ghidra's p-code emulator gives the same result as an
independent calculation for all 7,708 lookups of the check. The fix sets the N flag to the sign of
the true result of an addition, a subtraction or a compare. Before the fix, 512 lookups of two
signed-x functions gave a wrong result.

The six shift curves of the test image (Ford strategy TDE1, a 1996 F-350 7.3L) are read only by the shift routine at
0x89e10. Each curve address is the base register Rfc plus an immediate offset in an `AD3W`
instruction. No descriptor or pointer record points at the curves.

This file holds addresses, counts and instruction names only. The image, the listings and the
result files stay on the local machine. Addresses are bank × 0x10000 + 16-bit address.

## The lookup routines

All entries are in bank 8. The names are the names that SAD gives them.

| Entry | Function | How the function is given | SAD name |
|---|---|---|---|
| 0x87dd2 | byte | registers (see below) | `UUYFuncLU_87dd2` |
| 0x87da7 | byte | 2 inline argument bytes after the call: the address of a descriptor record | `UUYFuncLU_87da7` |
| 0x87d9c | byte | as 0x87da7, signed x | `SUYFuncLU_87d9c` |
| 0x87e52 | word | registers | `UUWFuncLU_87e52` |
| 0x87e27 | word | inline argument bytes, as 0x87da7 | `UUWFuncLU_87e27` |
| 0x87e1c, 0x87e21, 0x87e24 | word | as 0x87e27: signed x; signed x and y; signed y | `SUWFuncLU`, `SSWFuncLU`, `USWFuncLU` |

Inputs and output of the register entries:

* R36 (word): the 16-bit address of the function. The routine reads the function from the data
  bank, bank 1.
* R38: x (a byte, or a word for 0x87e52).
* R60 bit 5: x is signed. R60 bit 4: y is signed. The routine clears both bits.
* Output: y in R3c. The byte routine clears R3d, so the word R3c holds y.

The descriptor entries read the two inline argument bytes with `ROMBK 8`. They push the address
after the two bytes as the return address, and continue into 0x87dd2 or 0x87e52. A descriptor
record has 4 bytes:

* bytes 0-1: bits 0-11 are the offset of the function from a base register. Bits 12-15 are n:
  the base register is the word at 0xf0 + n.
* bytes 2-3: the address of x.

SAD finds no routine for 2-D tables in bank 8, and the check found no 2-D read of the curves. The
code at 0x84279 is arithmetic on RAM values and reads no table.

### How the byte routine reads a function

A function is a list of (x, y) byte pairs. The x values go down to 0 (to 0x80 for signed x).

1. The routine compares x with the x of the next pair. While x is lower, it goes to the next pair.
2. It interpolates between that pair and the next pair: y1 + (x − x1) × (y0 − y1) / (x0 − x1).
   It multiplies the magnitudes, truncates the quotient, and applies the sign after the division.
3. Two pairs with the same x make a step: at that x the routine gives the y of the first of the
   two pairs.

No x is lower than 0, so the routine never reads past the first pair whose x is 0. Each of the
six shift curves stores 10 breakpoints, and its last breakpoints have x 0. The breakpoints after
the first one with x 0 have no effect. The check confirmed this with `poke=` (see "Method"):

* A change of all 40 bytes of these unused pairs changed none of 3,072 results.
* A change of the speed byte of the last pair that the routine reads, in each curve, changed 796
  of the 3,072 results.

## How the code reaches the six curves

The reset code calls 0x833ed at 0x8210d. 0x833ed copies 8 words (the count is the byte at
0x82060) from the list at 0x82062-0x82071 in bank 8 to the base registers Rf0-Rfe. Rfc is the
seventh word, at 0x8206e, and holds 0x4020.

The shift routine at 0x89e10 (called at 0x89d9d) checks each shift with this sequence:

```
AD3W  R36, Rfc, #offset     ; R36 = the shift curve
AD3W  R40, Rfc, #offset2    ; R40 = the adjustment function for the same shift
SCALL 0x8a04a
```

| Shift | Curve | Offset | AD3W R36 at | Adjustment function | SCALL at |
|---|---|---|---|---|---|
| 1-2 up | 0x14520 | 0x500 | 0x89f32 | 0x1450c | 0x89f3c |
| 2-3 up | 0x14578 | 0x558 | 0x89f02 | 0x14534 | 0x89f0c |
| 3-4 up | 0x145d0 | 0x5b0 | 0x89ecc | 0x1458c | 0x89ed6 |
| 2-1 down | 0x145f8 | 0x5d8 | 0x89f4c | 0x145e4 | 0x89f56 |
| 3-2 down | 0x14620 | 0x600 | 0x89f6c | 0x1460c | 0x89f76 |
| 4-3 down | 0x14648 | 0x628 | 0x89f8f | 0x14634 | 0x89f99 |

0x8a04a calls 0x89d14. 0x89d14 does these steps:

1. It reads x from the byte at RAM 0x7b1 (`[Re8+31]`).
2. It reads the shift curve (R36) through 0x87dd2, unsigned.
3. It reads the adjustment function (R40) through 0x87dd2 with signed y.
4. It scales the adjustment by the byte at RAM 0x842 (0x80 is 1) and adds it to the curve value.

0x8a04a then scales the sum by the byte at RAM 0x8ad (0x80 is 1) and compares it with the byte at
RAM 0x78c. The carry of that compare decides the shift. For 2-3 up and 3-4 up the routine also
reads a word function through 0x87e52: 0x14548 and 0x145a0. Their x is the word at RAM 0x7b0.

The earlier description said: no immediate load of the table address, a pointer built from RAM
slots, and a shared 2-D routine at 0x84279. Part of it is correct: no instruction loads a full
curve address, and the address comes from the RAM register Rfc. The rest is not correct:

* Rfc is a base register that the reset code loads once.
* The offset is an immediate operand of `AD3W`.
* The routine is the byte function lookup at 0x87dd2, not a 2-D routine.

### No other code refers to the curves

The reference search of `EecEmulatorCheck.java` covered all 20 stored bytes of each curve and
the 2 bytes before it (`span=14`). It found:

* Instructions that form a curve address: the six `AD3W` above, and `LDW R34,[Rfc+0x2b2]` at
  0x84470. That `LDW` loads a multiplier for `ML2W` at 0x84475; its value is in a curve's range by
  chance.
* Call sites that reach a lookup of a curve: the six `SCALL 0x8a04a`, and the call of the shift
  routine at 0x89d9d.
* Ghidra references to the curves: none.
* Data words that hold a curve address, its offset from a base register, or a descriptor word for
  it: 1,486. Code uses 47 of them: as values, as byte addresses, or in the records of other
  routines (for example a word lookup at 0x83e65 whose descriptor uses Rf0).

Ghidra's listing has these callers of the byte routine, and only the six blocks above pass a
shift curve:

* Calls of 0x87dd2:
  * 0x89d1b and 0x89d2d, in 0x89d14 (see the next item).
  * 0x8aaa9: R36 = Rfc + 0x854 + 6 × R24, so at least 0x4874.
  * 0x8af10: R36 = Rfc + an entry of the 6-entry list at 0x16294. No entry gives a curve.
* Calls of 0x89d14:
  * 0x8a04a, from the six blocks above.
  * 0x8b5bc and 0x8b5d2: R36 = Rfc + 0x66c + R24 or Rfc + 0x6cc + R24, with R24 ≥ 0, so at
    least 0x468c.
* Calls of 0x87da7 and 0x87d9c: 8 calls with fixed descriptor records at 0x15996, 0x1599a,
  0x159a6, 0x159aa, 0x16237, 0x16267, 0x1627c and 0x16280. None of the records gives a curve.

## The language fix: N after an overflow

The EEC-IV Software Manual defines N as the sign of the true result, also when the result
overflows (pages `ADCB`, `AD2B`, `CMPB`, `SB2B`, `DECB`, `NEGB`). The Intel 8096 user manual
says the same. The p-code took the sign bit of the truncated result. `JGE`, `JGT`, `JLE` and
`JLT` test N, so they went the wrong way after an overflow.

The two signed-x functions read through 0x87d9c (0x14f54 and 0x13bc8) have two pairs each: x
goes from the highest to the lowest signed value. For x 0x10, `CMPB` compares 0x10 with the
lowest value 0x80, and 0x10 − 0x80 overflows. With the old p-code the routine did not stop at the
last pair, and read the bytes after the function. This gave a wrong result for every x from 0x00
to 0x7f.

The fix in `data/languages/806x.sinc`: N is the sign bit exclusive-or V in `additionFlags` and
`subtractFlags`. `INC` and `INCB` keep the sign bit of the result, as the manual gives for them.
The decompiler now shows the signed compare in 0x87dd2 as `(char)a <= (char)b`. Before the fix it
showed `-1 < (char)(b - a)`.

`test/run-compare.sh --mode multi --find-code` gives the same listing with the fix as before:

| Figure | Result |
|---|---|
| SAD code rows that match | 12,144 of 12,144 |
| jump and call targets the same | 3,815 of 3,815 |
| operands the same | 12,144 of 12,144 |
| Ghidra instructions not at a SAD code address | 636 |
| Ghidra decode errors | 12 |

## Results

Each row is one lookup: a direct call of the register entry, a call through the descriptor entry,
or a lookup that a trace recorded. `inputs=all` gives every x from 0x00 to 0xff (every 0x101 for
word functions) and the breakpoints and midpoints.

| Functions | Rows | Match before the fix | Match after the fix |
|---|---:|---:|---:|
| 6 shift curves, breakpoints, midpoints, 0 and 0xff | 140 | 140 | 140 |
| 6 shift curves, all inputs and 6 traces | 3,078 | 3,078 | 3,078 |
| 6 adjustment functions (signed y), all inputs and 6 traces | 3,078 | 3,078 | 3,078 |
| 2 word functions of the shift routine | 528 | 528 | 528 |
| 2 signed-x functions read through 0x87d9c | 1,024 | 512 | 1,024 |
| Total of the full run | 7,708 | 7,196 | 7,708 |

The first row is a separate run with a smaller set of inputs. The set is the distinct x values of
the pairs that the routine reads, the midpoints, 0 and 0xff. That is 11 to 13 inputs for each
shift curve. Each input is called directly and through the descriptor entry.

The descriptor calls decoded the same function address and x as the record that the script gave
them, in every call.

## Method

* `test/emulator/ford_function.py` computes the expected output from the image. It follows the
  routine's integer steps and uses the true sign for signed compares, as the manuals define N.
  `compare` checks each emulator row and prints counts only.
* `test/ghidra/EecEmulatorCheck.java` copies the program memory into Ghidra's `PcodeEmulator`.
  It runs the init routines, then calls each lookup entry for each input. `traces=` runs real
  code from a start to an end address and records each lookup. `refs=` searches for references.
  `poke=` changes bytes in the emulator's copy only.
* The reference search emulates every basic block twice: from the RAM after init with the other
  bytes 0, then with the other bytes 2. A value that is the same in both runs comes from constants
  and init values only. It also runs each block that ends with a call through the call.
* `test/run-emulator-check.sh` makes the analyzed project in a temporary folder
  (`EecLoadBanks.java`, auto-analysis, `EecFindCode.java`), runs the check, and runs the
  comparison.

The arguments for TDE1 are in `EEC_CHECK_ARGS` in `run-emulator-check.env` in the output folder.
Give them on one line, separated by spaces:

| Argument | Value |
|---|---|
| `init` | `833ed,8224c` (0x833ed loads Rf0-Rfe; 0x8224c loads the RAM base registers Rdc-Ree) |
| `ram` | `7b1:80` (x for the traces) |
| `byte`, `word`, `descriptor` | `87dd2`, `87e52`, `87da7` |
| `inputs` | `all` |
| `tables` | `14520,14578,145d0,145f8,14620,14648,1450c:sy,14534:sy,1458c:sy,145e4:sy,1460c:sy,14634:sy,14548:w,145a0:w,14f54:sx,13bc8:sx` |
| `traces` | `89ecc-89ed8,89f02-89f0e,89f32-89f3e,89f4c-89f58,89f6c-89f78,89f8f-89f9b` |

Ghidra uses the language of the module in the Extensions folder of its settings folder. To check
a changed language without a change to the installed module:

1. Copy the module (`data`, `ghidra_scripts`, `extension.properties`, `Module.manifest`) to a new
   folder.
2. Make a settings folder with `ghidra/ghidra_12.2_PUBLIC/Extensions/Ghidra-EEC-806x` linked to
   the copy.
3. Set `GHIDRA_HEADLESS_JAVA_OPTIONS=-Dapplication.settingsdir=<settings folder>`.

## Limits of the emulation

* The p-code stack pointer SP is not the RAM word StackPtr (0x20). Code that reads or writes 0x20
  does not see the stack of `PUSH`, `POP`, `CALL` and `RET`.
* R0 is ordinary RAM in the p-code. The script counts writes to R0; there were none.
* Hardware registers (0x02-0x1f) read 0.
* The emulator has no `normalize` operation (`NORML`), and a divide by 0 stops it. In the reference
  search, 59 of 15,032 basic block runs stopped early for these reasons.
* The reference search does not follow values from one basic block to the next, except through
  calls. The list of callers above covers the shift curves.
