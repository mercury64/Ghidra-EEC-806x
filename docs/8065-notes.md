# 8065 notes for the 806x SLEIGH module

This file lists what the 8065 adds or changes compared with the 8061. It is for the person who extends the 806x SLEIGH module to decode 8065 code. Each fact has a source. A sentence that starts with "Inference:" is not read from a source.

Scope: the CPU core (instructions, registers, memory banks, interrupts). Peripheral registers appear only where they change an address, a size or a name. This file contains no data from any ECU image.

Terms:
- PSW: processor status word, 16 bits. SFR: special function register. SP and ASP: stack pointer and alternate stack pointer.
- HSI and HSO: high-speed input and output ports. LSO: low-speed output port. LSSI and LSSO: the four input and four output bytes of the I/O expander.
- "A" operand: the operand that can use all six address modes and can be outside the chip. The B and D operands are always on-chip registers.
- Memory bank: one of up to 16 external 64 KB banks, selected by a 4-bit number. Program bank: the memory bank used for instruction fetch. RAM bank: one of four 256-byte on-chip register banks, selected by PSW bits 9-8.
- Ford: Ford Motor Company, the author of the primary documents SW and PG (section 2). SAD: the EEC-IV disassembler by tvrfan (section 2).
- Nibble: 4 bits. The low nibble is bits 3-0. The high nibble is bits 7-4.
- Prefix: an instruction byte or bytes placed before another instruction to change how that instruction runs. ROMBANK latch: the 4-bit temporary register that holds the bank byte of a ROMBANK prefix.
- Constructor: a SLEIGH rule that decodes one form of one instruction.
- `0x` marks a hex number. Ford writes hex with `^`.

Sections: 1 Summary. 2 Sources. 3 Memory and banks. 4 Special function registers. 5 Instructions. 6 Interrupts. 7 Reset state. 8 Other differences. 9 The module's partial 8065 section. 10 Where the sources disagree. 11 Open questions.

## 1. Summary

- **New instructions.** Ford counts six: ALT, BANK0, BANK1, BANK2, BANK3 and ROMBANK. Execution times and the logical operations of the other instructions are the same on both CPUs, except that RETI becomes RETEI and the N flag of divide is now always correct. [SW 2-10 (PDF 26)][sw-26]
- **Address.** An external address has 20 bits: a 4-bit bank number (A19-A16) and a 16-bit address. Addresses 0x0000-0x03FF are on-chip registers and never use a bank. Addresses 0x0400-0xFFFF are external and use a bank. [SW 2-12 (PDF 28)][sw-28] [SW 2-13 (PDF 29)][sw-29]
- **Bank sources.** Instruction fetch uses PSW bits 13-10. An external data operand (the "A" operand) uses bits 3-0 of the byte at address 0x0011. An external stack access uses bits 7-4 of that byte. [PG B9 (image 09)][pg-09] [SW 6-57 (PDF 331)][sw-331]
- **Bank numbers.** A bank number has 4 bits. Real images use banks 0, 1, 8 and 9. The bank after reset is 8. The reset address is 0x82000. [SAD6 manual PDF 3][sadm-3] [SW 2-13 (PDF 29)][sw-29]
- **ROMBANK.** The bytes `10 bb` form a prefix. Bits 3-0 of `bb` are the bank. The prefix affects the next instruction only. For a data instruction with an indirect or indexed "A" operand, it replaces the data bank. For a branch, call or return, it switches the program bank. It does both jobs. [SW 3-124 (PDF 166)][sw-166] [SW 3-125 (PDF 167)][sw-167]
- **BANKn.** The one-byte opcodes `F4`, `F5`, `F6` and `FD` write 0, 1, 2 and 3 to PSW bits 9-8. They select one of four 256-byte on-chip register banks. [SW 3-46 (PDF 88)][sw-88]
- **FE prefix.** `FE` before a multiply or divide opcode makes it signed. `FE` before a stack opcode makes it use the alternate stack pointer at 0x0022. ROMBANK must come before `FE`. [SW 3-10 (PDF 52)][sw-52] [SW 3-30 (PDF 72)][sw-72]
- **SFR count.** The 8065 has 42 SFR byte locations: 0x0000-0x0023 and the zero registers at 0x0100, 0x0200 and 0x0300 (2 bytes each). The 8061 has 18: 0x0000-0x0011. The stack pointer moves from 0x0010 to 0x0020. [SW 6-4 (PDF 278)][sw-278] [SW 5-3 (PDF 235)][sw-235] [SW 6-6 (PDF 280)][sw-280]
- **Interrupts.** The 8065 has 40 two-byte vectors at 0x2010-0x205F. The vector for level n is at 0x2010 + 2n. Level 0 is the highest priority. The 8061 has 8 vectors at 0x2010-0x201F, and its highest priority is the vector at 0x201E. [SW 6-72 (PDF 346)][sw-346] [PG A6 (image 05)][pg-05]
- **Changed opcode.** `F1` is RETEI on the 8065. It also sets PSW bit 15. [SW 3-123 (PDF 165)][sw-165]
- **Module.** The instructions SKR4, SKRC, XCHB and SKRB at `806x.sinc:971-1022` do not match any 8065 opcode. Ford lists their opcodes (0x04, 0x0B, 0x1B) as unusable two-byte no-operation instructions on the 8065, and as unassigned opcodes with other behavior on the 8061. See section 9. [SW A-2 (PDF 462)][sw-462] [SW A-1 (PDF 461)][sw-461] [module 806x.sinc:971-1022][msinc-971-1022]

## 2. Sources

Key: P = primary (Ford documents). S = secondary (other people's reading of the primary documents or of binaries).

| Short name | Document | Status | How it was read |
|---|---|---|---|
| SW | Ford, EEC-IV Software Manual, copyright May 1990, 490-page scan with OCR text. [SW PDF 3][sw-3] | P | `EEC-IV Software Manual.pdf` in [Useful-Docs 643ddfe][Useful-Docs 643ddfe]. The file is stored in Git LFS. I downloaded it from the `download_url` that the contents API returns. I read the OCR text. OCR text has errors, so for tables, figures and code examples I read the page image. A page label gives the printed manual page, then the PDF page. |
| PG | Ford, EEC-IV Custom Integrated Circuits Pocket Reference Guide, first edition, January 1991. [PG cover (image 01)][pg-01] | P | `EEC_handbook.zip` in Useful-Docs. It holds 54 page images. "Image NN" in a citation is the file `EEC-IV_hbook/EEC-IV-hr_NN-m.jpg` in the archive. Each image shows two printed pages. I read the pages for the 8061 (labels A), the 8065 (B), the instruction set (C) and the memory maps (H) as images. I did not read every page of the other sections (the CART, UART, DUCE and EBC chips, the pin lists and the console commands). The page label is the label printed on the page. |
| TC | Tom Cloud, Technical Notes on the EEC-IV MCU, last edited 29 September 1998. [TC PDF 1][tc-1] | S | `eectch98.pdf` in Useful-Docs (Git LFS), read as text. |
| OD | `8061_opcodes.doc`. Its heading is "8065 Opcodes - adapted from 8096 Table" (a list of opcodes). [8061_opcodes.doc][od] | S | RTF file in Useful-Docs, converted to text. |
| I196 | Intel 8XC196Kx, 8XC196Jx, 87C196CA User's Manual, stored in Useful-Docs as `8096_user_manual.pdf`. [I196 A-40 (PDF 479)][i196-479] | P for the 196 only | Used only to identify the XCH and XCHB opcodes (section 9). |
| SAD 4.13, SAD 6.01 | tvrfan EEC-IV-disassembler source: `source/4.13` and `Development/sources/6.01`, at [SAD 19373a0][SAD 19373a0]. The local checkout (HEAD [800b8c2][800b8c2]) has no 6.01 folder. In the checkout, `source/4.13/Core.cpp` and `core.h` have the same blob ids as the files at [SAD 19373a0][SAD 19373a0]. | S | Source code read directly. |
| SAD6 manual | `Docs/SAD6_User_Manual.pdf` in the same repository. | S | Text extracted from the PDF. Page numbers are PDF pages. |
| SAD806x | OpenEEC-Project/SAD806x (C#), at [SAD806x 09e70da][SAD806x 09e70da]: `SADDef.cs`, `SADOPCode.cs`, `SADBin.cs`, and `Manual/SAD806x.pdf`. | S | Source and manual read directly. |
| dis8061 | OpenEEC-Project/dis8061 at [dis8061 25794ac][dis8061 25794ac]. `Dis8061.doc` says "This is an 8061 disassembler". A search for "8065", "bank" and "rombank" in `Dis_806x.c`, `Dis_806x.h`, `main.c` and `sym.c` found no 8065 content. | none | Read. There is nothing to cite. |
| module | OpenEEC-Project/Ghidra-EEC-806x, `main` at [module 893cb6d][module 893cb6d]. The branch that holds this file starts at commit [c860ca1][c860ca1] on the fork RavenHursT/Ghidra-EEC-806x. That commit changes PUSHP and POPP in `806x.sinc` ([806x.sinc:642-655 at c860ca1][806x.sinc:642-655 at c860ca1]; 7 lines added, 6 removed). Lines after that are one line higher in the branch: the block cited here as 971-1022 is at [806x.sinc:972-1023 at c860ca1][806x.sinc:972-1023 at c860ca1] there. Links in this file point to [module 893cb6d][module 893cb6d]. | - | Source read directly. |
| EECTuning | Forum threads [EECTuning thread t=23036][forum-t-23036], [EECTuning thread t=23036&start=30][forum-t-23036-start-30] and [EECTuning thread t=23036&start=60][forum-t-23036-start-60]. | S, unverified | Used for the open questions in section 11 and for one statement about RAM banks in section 3.2. |

Checks on the files: the SHA-256 of each downloaded Git LFS file (SW, TC, I196 and `MCS96.pdf`) equals the oid in its LFS pointer at the pinned commit. The copies of the SAD, SAD806x and module files that I read for line citations are byte-identical to the blobs at the pinned commits, so every line link points at the text that I read.

Not read: `EEC-IV Custom Integrated Circuits Architecture and Hardware Reference.pdf` (228 MB, Git LFS, not downloaded), `EEC-IV_CalibrationConsoleAndDACModule_CourseText_1988.pdf` (28 MB, not downloaded), and `MCS96.pdf` (Intel MCS-96 data sheet for the 8096, October 1987; a 20-page scan with no text layer; I viewed pages 1-2 and did not use it).

## 3. Memory and banks

### 3.1 Address map

| Range | 8061 | 8065 |
|---|---|---|
| 0x0000-0x0001 | zero register | zero register |
| 0x0002-0x000F | SFRs | SFRs |
| 0x0010-0x0011 | stack pointer | SFRs: HSO interrupt pending #1 (word at 0x0010) and the memory bank select register (byte at 0x0011) |
| 0x0012-0x001F | general registers | SFRs |
| 0x0020-0x0023 | general registers | stack pointer (0x0020), alternate stack pointer (0x0022) |
| 0x0024-0x00FF | general registers (0x0012-0x00FF: 238 bytes) | general registers of RAM bank 0 (220 bytes) |
| 0x0100-0x03FF | external working memory (RAM and I/O) | on-chip RAM banks 1, 2, 3: 256 bytes each. The first 2 bytes of each are a zero register. |
| 0x0400-0x1FFF | external working memory (0x0100-0x1FFF: 7,936 bytes) | external working memory, 7,168 bytes |
| 0x2000 | reset address | reset address (bank 8) |
| 0x2001-0x200F | program memory | program memory |
| 0x2010-0x201F | 8 vectors, 16 bytes | start of the vectors |
| 0x2020-0x205F | program memory | rest of the vectors (0x2010-0x205F: 80 bytes) |
| 0x2060-0xFFFF | program memory | program memory |

Sources: 8061 [SW 2-11 (PDF 27)][sw-27]; 8065 [SW 2-12 (PDF 28)][sw-28] and [PG H3 (image 39)][pg-39].

- Table 2-4 prints the last range as `n2020`-`nFFFF`. The vector range `n2010`-`n205F` and the byte count 57,248 both fit a range that starts at `n2060`. The pocket guide memory map shows program memory from `n2060`. Inference: the printed start address is a typo. [SW 2-12 (PDF 28)][sw-28] [PG H3 (image 39)][pg-39]

### 3.2 Which addresses use a bank

- A 20-bit address is the 4-bit bank number in A19-A16 and a 16-bit address in A15-A0: address = bank x 0x10000 + 16-bit address. Example: bank 8 and 0x2000 give 0x82000. [SW 2-12 (PDF 28)][sw-28] [SW 2-13 (PDF 29)][sw-29]
- Addresses `n0000`-`n03FF` always mean on-chip RAM, for every bank number `n`. The chip cannot reach external memory at these addresses. [SW 2-13 (PDF 29)][sw-29]
- The chip tests the six most significant bits of a 16-bit effective address. If all six are zero, the location is on-chip. [SW 6-11 (PDF 285)][sw-285]
- Four banks give 256 KB. Sixteen banks, with an external 4-to-16 decoder chip, give 1 MB. [SW 2-11 (PDF 27)][sw-27] [SW 6-56 (PDF 330)][sw-330]
- Program instructions cannot run from addresses 0x0000-0x03FF. [SW 6-14 (PDF 288)][sw-288]
- The vectors have the same addresses in all banks. Each bank must hold a full vector list. [SW 2-13 (PDF 29)][sw-29] [SW 6-75 (PDF 349)][sw-349]
- The PC has 16 bits. It wraps from 0xFFFF to 0x0000 inside the same bank. The 8065 never carries into the next bank. [SW 6-14 (PDF 288)][sw-288] [SW 6-56 (PDF 330)][sw-330]
- Ford shows RAM and I/O devices at `n0400`-`n1FFF`, where n is any bank number from 0 to F and the default bank is 8. The sources I read do not say which bank numbers a RAM or I/O device answers. [PG H3 (image 39)][pg-39]
- SAD uses bank 1 as the default data bank for multi-bank images. Its manual says that multi-bank images typically have all their data in bank 1, with a few exceptions. [SAD 4.13 Core.cpp:13454][s413-13454] [SAD6 manual PDF 3][sadm-3]
- Forum posts by the SAD author and another member say that on multi-bank images all external RAM is in bank 1 (from 0x10400), and that peripheral chips such as the DUCE are mapped into bank 1 like RAM. They say some later images also have RAM at 0x1E000 and up. These posts are secondary and unverified. [EECTuning thread t=23036&start=60][forum-t-23036-start-60]
- SAD stores the bank number plus one inside, so `0x20000` is bank 1 and `0x90000` is bank 8. [SAD 4.13 core.h:25-27][s413h-25-27]

### 3.3 Where the 4-bit bank number comes from

| Bus access | Bank number comes from | Source |
|---|---|---|
| Instruction fetch (opcode and operand bytes) | PSW bits 13-10 (MB3-MB0) | [SW 6-60 (PDF 334)][sw-334], [PG B9 (image 09)][pg-09] |
| External "A" operand of a data instruction | Memory bank select register (0x0011) bits 3-0 | [SW 6-57 (PDF 331)][sw-331] |
| External stack access of a stack instruction | Memory bank select register (0x0011) bits 7-4 | [SW 6-57 (PDF 331)][sw-331] |
| External "A" operand of the one data instruction that follows ROMBANK | ROMBANK latch (bits 3-0 of the byte after opcode `10`) | [SW 6-62 (PDF 336)][sw-336] |
| Fetches after a ROMBANK-prefixed branch | ROMBANK latch, then PSW bits 13-10 (section 3.5) | [SW 6-62 (PDF 336)][sw-336] |
| On-chip registers and SFRs (0x0000-0x03FF) | no bank | [SW 2-13 (PDF 29)][sw-29] |

- The bank number goes to the pins BS3-BS0, which are the LSO7-LSO4 pins. [SW 6-56 (PDF 330)][sw-330]
- Only the "A" operand can be external. [SW 6-56 (PDF 330)][sw-330]
- Table 6-12 of SW lists, for each 8065 instruction, which bank sources it uses. A stack instruction uses the stack nibble for its stack access. CALL, SCALL, RET and RETEI use the ROMBANK latch only for the branch bank. PUSHW and POPW use the ROMBANK latch instead of the data nibble for the "A" operand. [SW 6-61 (PDF 335)][sw-335]
- Interrupt entry pushes the PC on the stack at the SP in 0x0020. Inference: it uses the stack bank like any stack access. The sources do not say so. [SW 6-75 (PDF 349)][sw-349]

### 3.4 Memory bank select register and enable bit

| Item | Value | Source |
|---|---|---|
| Name | Ford: memory bank select register, label `ROM_BANK_CTL`. SAD: `BANK_Select`. | [SW 6-5 (PDF 279)][sw-279], [SAD 6.01 command.cpp:509][s601c-509] |
| Address | 0x0011. Byte access only. A word access at 0x0010 reaches HSO interrupt pending register #1. | [SW 6-57 (PDF 331)][sw-331], [SW 6-5 (PDF 279)][sw-279] |
| Bits 3-0 | data bank | [SW 6-57 (PDF 331)][sw-331], [SAD 6.01 command.cpp:511][s601c-511] |
| Bits 7-4 | stack bank | [SW 6-57 (PDF 331)][sw-331], [SAD 6.01 command.cpp:512][s601c-512] |
| Value after reset | 0x00 (bank 0) | [SW 6-63 (PDF 337)][sw-337] |
| Software must write it | before memory expansion is enabled | [SW 6-57 (PDF 331)][sw-331] |
| Enable bit | bit 4 of the I/O status/memory expansion register at 0x000A. 1 = enabled. 0 = disabled. The bit is 0 after reset. | [SW 6-18 (PDF 292)][sw-292], [SW 6-63 (PDF 337)][sw-337] |

- Ford's example enables banking with `B1 56 11` (LDB #0x56 to 0x0011: data bank 6, stack bank 5) and then `B1 10 0A` (LDB #0x10 to 0x000A: expansion on). [SW 6-63 (PDF 337)][sw-337]
- When memory expansion is disabled, the bank circuits have no effect. The 8065 can address 64 KB. ROMBANK does nothing. [SW 6-57 (PDF 331)][sw-331] [SW 6-2 (PDF 276)][sw-276] [SW 3-124 (PDF 166)][sw-166]
- When it is enabled, the pins LSO7-LSO4 drive BS3-BS0. Software can still write and read LSO bits 7-4, but they do not change the bank outputs. [SW 6-57 (PDF 331)][sw-331]
- A system with a direct connection sets one bit of the nibble per bank. A system with a 4-to-16 decoder uses the nibble as a binary number. [SW 6-60 (PDF 334)][sw-334]
- The SAD6 manual shows `SYM 11 "Data_Bank" [B 4 7]` and `SYM 11 "Stack_Bank" [B 0 3]`. This swaps the two nibbles. SAD's own code uses bits 3-0 for data and bits 7-4 for stack. Ford agrees with the code. [SAD6 manual PDF 12][sadm-12] [SAD 4.13 Core.cpp:8897-8905][s413-8897-8905]

### 3.5 Program bank (PSW bits 13-10): how it changes

- The PSW has 16 bits and no memory address. PUSHP and POPP move the whole register to and from the stack. Bit 15 is the interrupt enable. Bit 14 is fixed at 1. Bits 13-10 are the program bank. Bits 9-8 are the RAM bank. Bits 7-0 are the flags: Z (7), N (6), V (5), VT (4), C (3), N+Z (2), C and not Z (1), ST (0). [SW 6-15 (PDF 289)][sw-289] [SW 6-16 (PDF 290)][sw-290] [PG B9 (image 09)][pg-09]
- Flag bits 2 and 1 appear only when software reads the PSW. They are not stored. [SW 3-14 (PDF 56)][sw-56]
- On the 8061, bit 15 is the interrupt enable and bits 14-8 are all fixed at 1. [PG A6 (image 05)][pg-05]
- After reset the PSW is 0x6000: bank 8, RAM bank 0, interrupts off. The 8061 value is 0x7F00. [SW 6-17 (PDF 291)][sw-291] [PG A3 (image 03)][pg-03]
- ROMBANK followed by a taken conditional branch: bits 3-0 of the ROMBANK byte go into PSW bits 13-10 at the end of the branch. If the branch is not taken, the bank does not change. [SW 3-125 (PDF 167)][sw-167]
- ROMBANK followed by an unconditional branch (CALL, JUMP, RET, RETEI, SCALL, SJMP and their ALT forms): the ROMBANK latch drives the bank pins for the next fetches. PSW bits 13-10 change at the end of the first instruction after the branch. That is two instructions after ROMBANK. [SW 3-125 (PDF 167)][sw-167] [SW 6-62 (PDF 336)][sw-336] [SW 6-78 (PDF 352)][sw-352]
- Reason for the delay: a PUSHP as the first instruction at the target saves the old bank. A later POPP and RET can then return to the old bank. [SW 3-125 (PDF 167)][sw-167] [SW 6-63 (PDF 337)][sw-337]
- POPP loads all PSW bits at once, including bits 13-10. The bank pins change only after the first branch instruction that follows POPP. [SW 3-114 (PDF 156)][sw-156]
- CALL, SCALL and interrupt entry store the 16-bit PC only. They do not save the PSW, so they do not save the program bank. [SW 3-4 (PDF 46)][sw-46] [SW 6-77 (PDF 351)][sw-351]
- Software can read PSW bits 13-10 indirectly: PUSHP, then a word read from the stack. [SW 6-59 (PDF 333)][sw-333]
- Ford's cross-bank call example: the caller runs `10 03` (ROMBANK 3) and `EF 56 19` (CALL). The subroutine in bank 3 starts with `F2` (PUSHP) and ends with `F3` (POPP) and `F0` (RET). The RET returns to bank 8. [SW 6-63 (PDF 337)][sw-337]

### 3.6 On-chip register banks (RAM banks 0-3, selected by PSW bits 9-8)

The register file has four banks of 256 bytes, at 0x0000, 0x0100, 0x0200 and 0x0300. The SFRs are in bank 0, except the zero registers at the start of banks 1-3. The rest of banks 1-3 is general registers. To reach an SFR in bank 0, the instruction must select bank 0. [SW 6-4 (PDF 278)][sw-278] [SW 6-6 (PDF 280)][sw-280]

| Reference | Bank used | Source |
|---|---|---|
| Byte operand, direct register address r (0x00-0xFF) | PSW bits 9-8 | [SW 6-8 (PDF 282)][sw-282], [SW 3-8 (PDF 50)][sw-50] |
| Pointer register of an indirect or indexed mode, and shift-count register | PSW bits 9-8 | [SW 6-8 (PDF 282)][sw-282], [SW 6-11 (PDF 285)][sw-285] |
| Word or double-word operand, direct register address r | bank number = 2 x PSW bit 9 + bit 0 of r. Banks 0 and 1 are one pair. Banks 2 and 3 are the other pair. PSW bit 8 is not used. Bits 7-1 of r give the word. | [SW 6-8 (PDF 282)][sw-282], [SW 6-9 (PDF 283)][sw-283], [SW 6-10 (PDF 284)][sw-284], [SW 3-8 (PDF 50)][sw-50] |
| 16-bit effective address below 0x0400 (indirect, indexed, stack) | address bits 9-8 | [SW 6-11 (PDF 285)][sw-285] |
| Fetch of SP or ASP by a stack instruction | always bank 0 (0x0020, 0x0022) | [SW 6-11 (PDF 285)][sw-285] |

- Inference: as formulas. Byte operand: `addr = (PSW & 0x300) + r`. Word or double-word operand: `addr = (PSW & 0x200) + ((r & 1) << 8) + (r & 0xFE)`. Pointer register: `addr = (PSW & 0x300) + (r & 0xFE)`. Bit 0 of the pointer byte selects the mode (auto-increment or long index), so it is not part of the address. [SW 3-6 (PDF 48)][sw-48]
- Ford's example, with BANK1 selected. In assembler source, Ford writes the bank digit before the register number: `^156` is register 0x56 of bank 1. In machine code, the register byte of a word operand is odd for an odd bank. The bytes `A0 57 59` (`LDW ^156,^158`) move register 0x56 of bank 1 to register 0x58 of bank 1. The bytes `A0 57 58` (`LDW ^156,^58`) move register 0x56 of bank 1 to register 0x58 of bank 0. The bytes `A0 56 58` (`LDW ^56,^58`) use bank 0 for both operands. So an even word register stays in bank 0 although BANK1 is active. [SW 6-10 (PDF 284)][sw-284]
- Ford's pocket guide limits the odd/even rule for word direct operands to "D-step and beyond" of the 8065. The Software Manual text I read has no such limit. [PG C14 (image 17)][pg-17] [SW 3-6 (PDF 48)][sw-48]
- The first 2 bytes of each bank are read-only zero registers. Writes are not stored. They can be a base for indexed addressing of any absolute address. [SW 6-4 (PDF 278)][sw-278]
- PUSHP does not change the RAM bank bits. POPP sets them at once from the popped value. [SW 6-14 (PDF 288)][sw-288]
- Inference: PSW bits 9-8 and 13-10 are run-time values. A static decoder cannot know them. SAD follows BANKn instructions in order and sets the RAM bank to 0 at every return it scans. [SAD 4.13 Core.cpp:9949-9968][s413-9949-9968] [SAD 4.13 Core.cpp:9767][s413-9767]

### 3.7 Bank numbers in images

- Ford's Table 2-5 lists all 16 bank numbers, 0 to 15. It shades banks 1, 2, 4 and 8 as "typical banks of a 256K-byte memory system". The pocket guide shows the four bank outputs BS3, BS2, BS1 and BS0 as banks 8, 4, 2 and 1 in a four-bank system. [SW 2-13 (PDF 29)][sw-29] [PG B9 (image 09)][pg-09]
- SAD says that Ford and the hardware number the banks 0, 1, 8 and 9. The order of the banks inside an image file differs between images. The code starts in bank 8. [SAD6 manual PDF 3][sadm-3]
- SAD treats a single-bank image as bank 8. This holds for 8061 images and for single-bank 8065 images. [SAD6 manual PDF 3][sadm-3]
- File order for four banks, by the slot that holds bank 8: slot 1 gives 8, 9, 0, 1; slot 2 gives 9, 8, 0, 1; slot 3 gives 0, 1, 8, 9; slot 4 gives 0, 1, 9, 8. SAD says this default may be wrong for some images. [SAD6 manual PDF 3][sadm-3]
- SAD maps each bank to start at 0x2000. [SAD6 manual PDF 3][sadm-3]
- SAD's own comment says that the biggest images seem to be 256 KB (four banks, 2 bits) and that only banks 0, 1, 8 and 9 are used. [SAD 4.13 core.h:15-27][s413h-15-27]
- Inference: the numbers 0, 1, 8 and 9 use only bit 3 and bit 0 of the bank number, which are the outputs BS3 and BS0. A board that decodes only those two outputs gives this set. No source I read says this (section 11).

## 4. Special function registers

The columns show the 8065 meaning. "Acc" is Ford's access type: B = byte only, W = word only (second byte of a word), E = either. When an address has two rows, the access size selects the register. A byte access to 0x0007 reaches the A/D-timed register. A word access at 0x0006 reaches the master I/O timer. [SW 6-4 (PDF 278)][sw-278]

| Address | Acc | Read | Write | Ford label | SAD 6.01 preset | 8061 at this address |
|---|---|---|---|---|---|---|
| 0x0000-0x0001 | E | zero register | not stored | `ZERO` | none | same |
| 0x0002 | E | LSO port | LSO port | `LSOUT` | `LSO_Port` (bit 6: `CPU_OK`) | same |
| 0x0003 | B | bidirectional I/O port, with IDDQ test status | bidirectional I/O port | `BIDIR` | `LIO_Port` | I/O port |
| 0x0004 | E | A/D immediate result, low byte | A/D immediate command | none | `AD_Imm_Result_Lo`, `AD_Imm_Result`, `AD_Cmd` | A/D result low or command |
| 0x0005 | B | A/D immediate result, high byte | watchdog timer | `WATCH_DOG` (write) | `AD_Imm_Result_High`, `WDG_Timer` | same |
| 0x0006 | E | master I/O timer, low byte | not stored | `Clock` | `IO_Timer_Lo`, `IO_Timer` | timer low |
| 0x0007 | W | master I/O timer, middle byte | not stored | `Clock` | part of `IO_Timer` | timer high |
| 0x0007 | B | A/D timed result, low byte | A/D timed command | none | `AD_Timed_Result_Lo`, `AD_Timed_Cmd` | (see above) |
| 0x0008 | E | I/O interrupt mask | same | `IO_INT_MASK` | `INT_Mask` | same |
| 0x0009 | B | I/O interrupt pending | same | `IO_INT_PEND` | `INT_Pend` | same |
| 0x000A | E | I/O status and memory expansion | same | `IO_STATUS` | `IO_Status` (bit 4 `MEM_Expand`) | I/O status (bits 4-5 are interrupt status and priority) |
| 0x000B | B | HSI sample | IDDQ test mode | `HSI_PINS` (read) | `HSI_Sample`, `IDDQ_Test Mode` | HSI sample (write not stored) |
| 0x000C | E | HSI transition mask | same | `HSI_MASK` | `HSI_Trans_Mask` | HSI data mask |
| 0x000D | B | HSI data hold | HSO command hold | `HSI_BUFF`, `HSO_CMD` | `HSI_Data`, `HSO_Cmd` | same |
| 0x000E | E | HSI time hold, low byte | HSO time hold, low byte | `HSI_TIME`, `HSO_TIME` | `HSI_Time_Hold` | same |
| 0x000F | W | HSI time hold, high byte | HSO time hold, high byte | `HSI_TIME`, `HSO_TIME` | part of `HSI_Time_Hold` | same |
| 0x000F | B | A/D timed result, high byte | not stored | none | `AD_Timed_Result_Hi` | (see above) |
| 0x0010 | E | HSO interrupt pending #1, low byte | same | `HSO_INT_PEND_1` | `HSO_Intpend1` | stack pointer, low byte |
| 0x0011 | W | HSO interrupt pending #1, high byte | same | `HSO_INT_PEND_1` | part of `HSO_Intpend1` | stack pointer, high byte |
| 0x0011 | B | memory bank select register | same | `ROM_BANK_CTL` | `BANK_Select`, `Data_Bank`, `Stack_Bank` | (see above) |
| 0x0012 | E | HSO interrupt mask #1, low byte | same | `HSO_INT_MASK_1` | `HSO_IntMask1` | general register |
| 0x0013 | W | HSO interrupt mask #1, high byte | same | `HSO_INT_MASK_1` | part of `HSO_IntMask1` | general register |
| 0x0013 | B | master I/O timer, high byte | same | `Clock` | `IO_Timer_Hi` | (see above) |
| 0x0014 | E | HSO interrupt pending #2, low byte | same | `HSO_INT_PEND_2` | `HSO_IntPend2` | general register |
| 0x0015 | W | HSO interrupt pending #2, high byte | same | `HSO_INT_PEND_2` | part of `HSO_IntPend2` | general register |
| 0x0015 | B | LSSI byte A | LSSO byte A | `LSI_EXP_A`, `LSO_EXP_A` | `LSSI_A`, `LSSO_A` | (see above) |
| 0x0016 | E | HSO interrupt mask #2, low byte | same | `HSO_INT_MASK_2` | `HSO_IntMask2` | general register |
| 0x0017 | W | HSO interrupt mask #2, high byte | same | `HSO_INT_MASK_2` | part of `HSO_IntMask2` | general register |
| 0x0017 | B | LSSI byte B | LSSO byte B | `LSI_EXP_B`, `LSO_EXP_B` | `LSSI_B`, `LSSO_B` | (see above) |
| 0x0018 | E | HSO state, low byte | not stored | `HSO_PIN_STATE` | `HSO_State` | general register |
| 0x0019 | W | HSO state, high byte | not stored | `HSO_PIN_STATE` | part of `HSO_State` | general register |
| 0x0019 | B | LSSI byte C | LSSO byte C | `LSI_EXP_C`, `LSO_EXP_C` | `LSSI_C`, `LSSO_C` | (see above) |
| 0x001A | E | HSI edge/mode | same | `HSI_EDGE_MODE` | `HSI_Mode` | general register |
| 0x001B | B | HSO used slot count and reset status | same | `CAM_SLOTS_AVBL` | `HSO_UsedCnt` | general register |
| 0x001C | E | HSO time/count read, low byte | not stored | `CAM_SLOT_READ` | `HSO_TimeCnt` | general register |
| 0x001D | W | HSO time/count read, high byte | not stored | `CAM_SLOT_READ` | part of `HSO_TimeCnt` | general register |
| 0x001D | B | LSSI byte D | LSSO byte D | `LSI_EXP_D`, `LSO_EXP_D` | `LSSI_D`, `LSSO_D` | (see above) |
| 0x001E | E | HSO last slot used | same | `CAM_SLOT_LAST_USED` | `HSO_LastSlot` | general register |
| 0x001F | B | HSO slot select | same | `CAM_SLOT_SELECT` | `HSO_SlotSel` | general register |
| 0x0020-0x0021 | E | stack pointer (SP) | same | `STACK_POINTER` | `StackPtr` | general register |
| 0x0022-0x0023 | E | alternate stack pointer (ASP) | same | `ALT_STK_POINTER` | `AltStackPtr` | general register |
| 0x0100-0x0101, 0x0200-0x0201, 0x0300-0x0301 | E | zero registers of RAM banks 1-3 | not stored | `ZERO` | none | not present |

Sources: Ford labels and access types [SW 6-5 (PDF 279)][sw-279]; the same list with names [PG B2 (image 06)][pg-06]; SAD 6.01 presets [SAD 6.01 command.cpp:469-536][s601c-469-536]; 8061 [SW 5-3 (PDF 235)][sw-235] and [PG A3 (image 03)][pg-03].

- The SAD6 manual says SAD has presets for registers 0-0x10 on the 8061 and 0-0x22 on the 8065. Inference: these numbers are the start addresses of the last preset on each CPU (the word at 0x10 or at 0x22). [SAD6 manual PDF 6][sadm-6]
- SAD 6.01 names for the 8061: `LSOut_Port` 0x02 (bit 6 `CPU_OK`); `BIDI_Port` (read) and `BIDO_Port` (write) 0x03; `AD_Result_Lo` 0x04 (read); `AD_Cmd` 0x04 (write); `AD_result_Hi` 0x05 (read); `WDG_Timer` 0x05 (write); `IO_Timer` 0x06 (word); `INT_Mask` 0x08; `INT_Pend` 0x09; `IO_Status` 0x0A; `HSI_Sample` 0x0B; `HSI_Mask` 0x0C; `HSI_Data` 0x0D (read); `HSO_Cmd` 0x0D (write); `HSI_Time` 0x0E (word); `StackPtr` 0x10 (word). [SAD 6.01 command.cpp:371-439][s601c-371-439]
- SAD 4.13 calls address 0x0022 `StackPtr2`. SAD 6.01 calls it `AltStackPtr`. [SAD 4.13 Core.cpp:802][s413-802] [SAD 6.01 command.cpp:534][s601c-534]
- The SFR list in SAD806x has the same addresses and the same byte and word variants. It names 0x0011 `BANK_SEL`, 0x0020-0x0021 `STACK` and 0x0022-0x0023 `ALTSTACK`. [SAD806x SADDef.cs:365-427][oesd-365-427]
- A load byte (LDB) into a word-length SFR writes a sign-extended word. Example: LDB to 0x0012 also writes 0x0013. [SW 6-4 (PDF 278)][sw-278] [SW 6-78 (PDF 352)][sw-352]
- The high byte of HSO interrupt pending #2 cannot be accessed alone. A word access can use 0x0014 or 0x0015 and gets both bytes. [SW 6-4 (PDF 278)][sw-278]
- The master I/O timer has 24 bits on the 8065: 0x0006 (low), 0x0007 (middle, word access) and 0x0013 (high, byte access). The 8061 timer has 16 bits. [SW 6-4 (PDF 278)][sw-278] [SW 2-8 (PDF 24)][sw-24]
- SAD 6.01 has a comment about which of these registers are byte-only or word-only. The comment is uncertain ("appear to be", "text implies"). Use Ford's access column instead. [SAD 6.01 command.cpp:445-463][s601c-445-463]

## 5. Instructions

### 5.1 Opcode differences

All other opcodes have the same meaning on both CPUs. [SW 2-10 (PDF 26)][sw-26]

| Opcode | 8061 | 8065 | Source |
|---|---|---|---|
| 0x10 | unassigned. Runs as SKIP. | ROMBANK prefix (2 bytes) | [SW 11-1 (PDF 455)][sw-455], [SW A-1 (PDF 461)][sw-461], [SW 3-124 (PDF 166)][sw-166] |
| 0xF1 | RETI | RETEI | [SW 11-1 (PDF 455)][sw-455], [SW 3-123 (PDF 165)][sw-165] |
| 0xF4, 0xF5, 0xF6 | unassigned. Run as NOP. | BANK0, BANK1, BANK2 | [SW 11-1 (PDF 455)][sw-455], [SW A-1 (PDF 461)][sw-461], [SW 3-46 (PDF 88)][sw-88] |
| 0xFD | unassigned. Runs as NOP. | BANK3 | same |
| 0xFE | SIGND prefix | SIGND prefix, or ALT prefix | [SW 11-1 (PDF 455)][sw-455], [SW 3-156 (PDF 198)][sw-198] |
| 0x04, 0x0B, 0x14, 0x1B, 0x1C-0x1F, 0xC1, 0xC5, 0xCD, 0xE1-0xE6, 0xE8-0xEE | unassigned | unusable | section 5.8 |

- Opcode 0xF7 (INT) is the same on both CPUs. The interrupt controller injects it. A program that contains it runs it as a NOP. [SW A-2 (PDF 462)][sw-462] [SW 6-75 (PDF 349)][sw-349]
- Ford says the CPU executes every opcode that it reads, whether assigned or not. [SW A-1 (PDF 461)][sw-461]
- The Tom Cloud notes and `8061_opcodes.doc` list the same 8065 additions: ROMBANK at 0x10, RETEI at 0xF1, BANK0-BANK3, and ALT at 0xFE. [TC PDF 14][tc-14] [8061_opcodes.doc][od]

### 5.2 ROMBANK (`10 bb`)

Encoding and size
- Byte 1 is 0x10. Byte 2 holds the bank number in bits 3-0. Bits 7-4 are not used. [SW 3-124 (PDF 166)][sw-166]
- It is a separate 2-byte instruction. It takes 4 machine states. [SW 3-126 (PDF 168)][sw-168]
- The cross-assembler writes `ROMBANK n` before the instruction. Ford's example byte sequences: `10 04 B7 01 00 30 50` (ROMBANK 4, then ADCB with a long indexed operand); `10 05 FE 9E 02 40` (ROMBANK 5, then a signed DIVB with an indirect operand); `10 0E FE CB 01 00 07` (ROMBANK 14, then ALT PUSHW with a long indexed operand); `10 03 DB 4F` (ROMBANK 3, then JC); `10 0A EF 03 4F` (ROMBANK 10, then CALL); `10 0E FE EF 03 4F` (ROMBANK 14, then CALLALT). [SW 3-126 (PDF 168)][sw-168] [SW 3-127 (PDF 169)][sw-169]

Effect, by the instruction that follows
- A data instruction whose "A" operand uses indirect, indirect with auto-increment, short indexed or long indexed mode: the ROMBANK bank replaces the data bank for that "A" operand. It applies to that one instruction. [SW 3-124 (PDF 166)][sw-166]
- A branch, call or return: the ROMBANK bank becomes the program bank (rules in section 3.5). [SW 3-125 (PDF 167)][sw-167]
- Any other instruction: no effect. This covers shifts, CLRB, CLRW, CPLB, CPLW, NEGB, NEGW, INCB, INCW, DECB, DECW, SEXB, SEXW, NORM, NOP, SKP, CLC, STC, CLRVT, DI, EI, PUSHP, POPP, BANK0-BANK3 and ROMBANK. It also covers a data instruction with a direct or immediate "A" operand. [SW 3-125 (PDF 167)][sw-167]
- If memory expansion is disabled: no effect. [SW 3-124 (PDF 166)][sw-166]

Legal followers
- Data instructions (36): ADCB, ADCW, AD2B, AD2W, AD3B, AD3W, AN2B, AN2W, AN3B, AN3W, CMPB, CMPW, DIVB, DIVW, LDB, LDSBW, LDW, LDZBW, ML2B, ML2W, ML3B, ML3W, ORRB, ORRW, POPW, PUSHW, SBBB, SBBW, SB2B, SB2W, SB3B, SB3W, STB, STW, XRB, XRW. [SW 3-124 (PDF 166)][sw-166]
- Branches (25): CALL, DJNZ, JB, JC, JE, JGE, JGT, JGTU, JLE, JLEU, JLT, JNB, JNC, JNE, JNST, JNV, JNVT, JST, JUMP, JV, JVT, RET, RETEI, SCALL, SJMP. [SW 3-125 (PDF 167)][sw-167]
- Inference: opcode ranges, from the Ford mnemonic lists and the opcode map. Data: opcodes 0x40-0xCF where bits 1-0 are 2 or 3. Branches: 0x20-0x3F, 0xD0-0xDF, 0xE0, 0xE7, 0xEF, 0xF0, 0xF1. [SW 11-1 (PDF 455)][sw-455] [PG C13 (image 16)][pg-16]
- SAD 6.01 uses the same set of 61 mnemonics for its "rombank prefix allowed" flag. I compared the two lists. [SAD 6.01 Core.cpp:171-274][s601-171-274] [SAD 6.01 shared.h:1158][s601s-1158]

Branch timing, interrupts and limits
- A taken conditional branch writes the bank into PSW bits 13-10 at the end of the branch. An unconditional branch writes it one instruction after the branch (section 3.5). [SW 3-125 (PDF 167)][sw-167]
- The first instruction after a ROMBANK-prefixed unconditional branch cannot be ROMBANK or ALT. An ALT-PUSHP cannot be that first instruction. [SW 6-78 (PDF 352)][sw-352] [SW 6-62 (PDF 336)][sw-336]
- No interrupt is serviced during ROMBANK or during the next instruction. After an unconditional branch it is also blocked for the instruction after the branch. [SW 6-63 (PDF 337)][sw-337] [SW 3-125 (PDF 167)][sw-167]
- ROMBANK can precede an ALT-modified CALL, POPW, PUSHW, RET, RETEI or SCALL (Figure 3-4). Before an ALT-modified branch it selects the branch bank. Before ALT PUSHW or ALT POPW with an indirect or indexed operand it selects the bank of the "A" operand. ROMBANK has no effect before PUSHP and POPP, with or without ALT, because they are on its invalid list. [SW 3-11 (PDF 53)][sw-53] [SW 3-126 (PDF 168)][sw-168] [SW 3-125 (PDF 167)][sw-167]
- ROMBANK before SIGND (`10 bb FE op`) is legal for the signed multiply and divide instructions. [SW 3-10 (PDF 52)][sw-52]

SAD and the other tools
- SAD 4.13 accepts `10` only for the 8065. It reads the bank byte, keeps bits 3-0, and applies the bank to jump targets and to data addresses of that instruction. [SAD 4.13 Core.cpp:10033-10052][s413-10033-10052] [SAD 4.13 Core.cpp:9441][s413-9441] [SAD 4.13 Core.cpp:5623][s413-5623]
- SAD 6.01 also rejects ROMBANK before an opcode that lacks the "rombank prefix allowed" flag. It accepts the prefix only when the bank byte is 0x00, 0x01, 0x08 or 0x09 (mask 0xF6). Its comment says that the banks are not always decided yet. [SAD 6.01 Core.cpp:1402-1437][s601-1402-1437]
- SAD806x applies the bank of a `10` instruction to both jump targets and data reads of the next operation. [SAD806x SADOPCode.cs:329-333][oeso-329-333]
- SAD 4.13 uses ROMBANK to find the order of the banks in an image file. For each bank it reads the interrupt vector targets. If a target starts with `10 bb` followed by `E7` (JUMP) or `20`-`27` (SJMP), SAD takes `bb` as the bank of the jump and rules `bb` out as the number of the bank being scanned. The code comment says some images have no such cross-bank jumps. [SAD 4.13 Core.cpp:13466][s413-13466] [SAD 4.13 Core.cpp:13484-13497][s413-13484-13497]

### 5.3 BANK0-BANK3

| Opcode | Mnemonic | Effect | Size |
|---|---|---|---|
| 0xF4 | BANK0 | PSW bits 9-8 = 00 | 1 byte, 4 states |
| 0xF5 | BANK1 | PSW bits 9-8 = 01 | 1 byte, 4 states |
| 0xF6 | BANK2 | PSW bits 9-8 = 10 | 1 byte, 4 states |
| 0xFD | BANK3 | PSW bits 9-8 = 11 | 1 byte, 4 states |

- No flags change. No prefix is allowed. [SW 3-46 (PDF 88)][sw-88] [PG C4 (image 12)][pg-12]
- The selected bank stays until the next BANKn or POPP. Byte direct operands, pointer registers and shift counts use it. Word operands use only PSW bit 9 and bit 0 of the register byte (section 3.6). [SW 6-9 (PDF 283)][sw-283]
- SAD calls these four opcodes `regbk`. It keeps a running RAM bank value (bank times 0x100) and adds it to register numbers. [SAD 4.13 Core.cpp:612-615][s413-612-615] [SAD 4.13 Core.cpp:9949-9968][s413-9949-9968]

### 5.4 Alternate stack forms (`FE` before a stack opcode)

The 8065 has two stack pointers: SP at 0x0020 and ASP at 0x0022. An instruction without a prefix uses SP. An instruction after `FE` uses ASP. [SW 6-77 (PDF 351)][sw-351]

| Bytes | Mnemonic (SAD name) | Operation | Source |
|---|---|---|---|
| `FE C8`-`FE CB` | PUSHALT (`pusha`) | ASP = ASP - 2; [ASP] = word from the "A" operand | [PG C11 (image 15)][pg-15], [SW 3-126 (PDF 168)][sw-168] |
| `FE CC`, `FE CE`, `FE CF` | POPALT (`popa`) | "A" operand = [ASP]; ASP = ASP + 2 | [PG C11 (image 15)][pg-15] |
| `FE EF` + 2 bytes | CALLALT (`calla`) | ASP = ASP - 2; [ASP] = PC; PC = PC + displacement (but see section 10) | [PG C11 (image 15)][pg-15], [SW 3-47 (PDF 89)][sw-89] |
| `FE F0` | RETALT (`reta`) | PC = [ASP]; ASP = ASP + 2 | [PG C11 (image 15)][pg-15] |
| `FE F1` | RETEIALT (`retia`) | PC = [ASP]; ASP = ASP + 2; PSW bit 15 = 1 | [PG C11 (image 15)][pg-15] |
| `FE F2` | PUSHPALT (`pshpa`) | ASP = ASP - 2; [ASP] = PSW | [PG C11 (image 15)][pg-15] |
| `FE F3` | POPPALT (`poppa`) | PSW = [ASP]; ASP = ASP + 2 | [PG C11 (image 15)][pg-15] |
| `FE 28`-`FE 2F` + 1 byte | SCALLALT (SAD: `sclla`) | Ford: "Same as CALLALT" (SCALL with ASP) | [PG C11 (image 15)][pg-15] |

- The prefix adds 1 byte and 4 machine states. [SW 3-121 (PDF 163)][sw-163]
- Interrupt entry pushes the PC to the stack at SP, not ASP. [SW 6-75 (PDF 349)][sw-349]
- Ford says the CPU tells ALT and SIGND apart by the next opcode. It lists the ALT-capable instructions as CALL, POPP, POPW, PUSHP, PUSHW, RET, RETEI and SCALL. [SW 3-30 (PDF 72)][sw-72] [SW 3-11 (PDF 53)][sw-53]
- SAD marks the ALT row of each instruction as 8065 only. The exception is the `sclla` row: its 8065-only flag is clear in both versions. [SAD 4.13 Core.cpp:534-584][s413-534-584] [SAD 4.13 Core.cpp:579][s413-579] [SAD 6.01 Core.cpp:264][s601-264]
- Inference: for a SLEIGH decoder, `FE` plus one of the opcodes above is the ALT form. The ALT form has the same operands as the plain form.

### 5.5 `FE` before multiply and divide (signed forms)

- `FE` makes ML2B, ML2W, ML3B, ML3W, DIVB and DIVW signed. The bytes are `FE` plus the opcode. [SW 3-11 (PDF 53)][sw-53] [SW 3-156 (PDF 198)][sw-198]
- Inference: the affected opcodes are 0x4C-0x4F, 0x5C-0x5F, 0x6C-0x6F, 0x7C-0x7F, 0x8C-0x8F and 0x9C-0x9F. [PG C13 (image 16)][pg-16]
- `FE` before any other instruction, except a stack instruction, runs as a NOP. [SW 3-156 (PDF 198)][sw-198]
- `FE` and ROMBANK together: ROMBANK is first. The bytes are `10 bb FE op ...`. `FE 10 bb ...` is not allowed. [SW 3-10 (PDF 52)][sw-52] [SW 3-11 (PDF 53)][sw-53]
- The prefix adds 1 byte and 4 machine states. [PG C12 (image 16)][pg-16]

### 5.6 Prefix patterns for a decoder

| Bytes | Meaning | Added length | Source |
|---|---|---|---|
| `10 bb` + data opcode (indirect or indexed A operand) | bank `bb & 15` for the A operand | 2 bytes | [SW 3-124 (PDF 166)][sw-166] |
| `10 bb` + branch opcode | switch the program bank | 2 bytes | [SW 3-125 (PDF 167)][sw-167] |
| `10 bb FE` + ML or DIV opcode | bank for the A operand, signed | 3 bytes | [SW 3-11 (PDF 53)][sw-53] |
| `10 bb FE` + opcode of CALL, RET, RETEI, SCALL, PUSHW or POPW | bank for the branch or the A operand, alternate stack | 3 bytes | [SW 3-11 (PDF 53)][sw-53] |
| `FE` + ML or DIV opcode | signed | 1 byte | [SW 3-156 (PDF 198)][sw-198] |
| `FE` + stack opcode | alternate stack | 1 byte | [SW 3-30 (PDF 72)][sw-72] |
| `10 bb` + any other opcode | no effect | 2 bytes | [SW 3-125 (PDF 167)][sw-167] |

### 5.7 Changed behavior of existing opcodes

| Item | 8061 | 8065 | Source |
|---|---|---|---|
| RETI/RETEI (0xF1) | clears I/O status bit 5 and restores bit 4 | sets PSW bit 15 | [SW 3-123 (PDF 165)][sw-165] |
| POPP | software must run DI before it | no DI needed. Interrupts are blocked during POPP and the next instruction. With memory expansion on, they stay blocked until the first instruction after the first branch that follows POPP. | [SW 3-114 (PDF 156)][sw-156] [SW 6-17 (PDF 291)][sw-291] |
| Interrupt entry | I/O status bit 4 shows service and bit 5 the priority level | clears PSW bit 15 only. The PSW is not saved. | [PG A6 (image 05)][pg-05] [SW 6-75 (PDF 349)][sw-349] [SW 6-17 (PDF 291)][sw-291] |
| Divide | N flag undefined. Signed divide sets V by the "Sign Divide Algorithm" of the SW appendices, and when the result is 0x80 (DIVB) or 0x8000 (DIVW). | N flag defined. Signed divide sets V when the true quotient would exceed 127 (byte) or 32,767 (word) in magnitude. | [SW 3-14 (PDF 56)][sw-56] |
| Multiply | V flag not affected | V flag cleared. ST flag unpredictable. | [SW 3-14 (PDF 56)][sw-56] [SW 6-78 (PDF 352)][sw-352] |
| Direct register operands | 8-bit register number, no bank | bank rules of section 3.6 | [SW 2-10 (PDF 26)][sw-26] |

### 5.8 Unassigned opcodes on the 8061 and unusable opcodes on the 8065 (Ford Tables A-1 and A-2)

Ford says that the CPU runs every opcode that it reads, whether assigned or not. Table A-1 lists what the 8061 does with its unassigned opcodes. Ford adds that this may not always happen, under conditions that it does not specify. Table A-2 lists 8065 opcodes that a program must not use, because later parts may change them. [SW A-1 (PDF 461)][sw-461] [SW A-2 (PDF 462)][sw-462]

| Opcodes | 8061 (Table A-1) | 8065 (Table A-2) |
|---|---|---|
| 0x04, 0x14 | run as SKIP | `SKR4`: 2 bytes, 4 states. A no-operation. It reads the register named by byte 2 and discards the data. |
| 0xC1, 0xC5 | run as STW (0xC1) and STB (0xC5) | `SKR4` |
| 0x0B, 0x1B | 3 bytes: opcode, Ra, Rb. Ford gives two operations: `Rb = AD2B Rb, Ra` or `Rb = SHLB =1, Rb`. | `SKRC`: 2 bytes, 12 states. Same as `SKR4`. |
| 0x1F | runs as NORM | `SKRC` |
| 0x1C, 0x1D, 0x1E | run as SHRDW, SHLDW and ASRDW | `SKR9`: 2 bytes, 9 states. Same as `SKR4`. |
| 0xCD | runs as POPW | POPW that stores the data nowhere. Ford writes "The stack pointer is decremented by two???". |
| 0xE1-0xE3 | run as DJNZ | same length, time and operation as DJNZ (0xE0) |
| 0xE4-0xE6 | run as JUMP | same as JUMP (0xE7) |
| 0xE8-0xEE | run as CALL | same as CALL (0xEF) |
| 0x10 | runs as SKIP | ROMBANK (section 5.2) |
| 0xF4, 0xF5, 0xF6, 0xFD | run as NOP | BANK0, BANK1, BANK2, BANK3 (section 5.3) |
| 0xF7 (INT) | generated inside the CPU. Runs as NOP if it is in a program. | same. 1 byte, 23 states when generated. |

- A read of 0x000D by an `SKRn` opcode can lose HSI data: it advances the HSI FIFO and clears the data-available bit. A read of 0x001B by an `SKRn` opcode clears the watchdog reset status bit. [SW A-2 (PDF 462)][sw-462]
- SAD 4.13 and 6.01 mark every opcode in the list above as invalid, except 0x10, 0xF4-0xF6 and 0xFD, which they decode as 8065 opcodes. I checked all 256 entries of both opcode index maps: the invalid set is exactly 0x04, 0x0B, 0x14, 0x1B, 0x1C-0x1F, 0xC1, 0xC5, 0xCD, 0xE1-0xE6, 0xE8-0xEE and 0xF7. [SAD 4.13 Core.cpp:376-411][s413-376-411] [SAD 6.01 Core.cpp:52-87][s601-52-87]

## 6. Interrupts

- The 8065 has 40 interrupt sources and 40 vectors. The vector of level n is a 16-bit word at 0x2010 + 2n (low byte first). Level 0 has the highest priority. The vector is an address in the program bank that is active when the interrupt occurs. [SW 6-72 (PDF 346)][sw-346] [SW 6-75 (PDF 349)][sw-349]
- The 8061 has 8 vectors at 0x2010-0x201F. The highest priority is the vector at 0x201E (HSI pin 0). The lowest is the vector at 0x2010 (HSO port output #2). [PG A6 (image 05)][pg-05] [TC PDF 17][tc-17]

| Level | Vector address | Source of the interrupt |
|---|---|---|
| 0-9 | 0x2010-0x2022 | HSO codes 0x00-0x09 (HSO pins 0-9) |
| 10-15 | 0x2024-0x202E | HSO codes 0x0A-0x0F (HSO expansion pins A-F) |
| 16 | 0x2030 | HSI FIFO 50% full |
| 17 | 0x2032 | external interrupt pin |
| 18 | 0x2034 | HSI pin 0 |
| 19 | 0x2036 | HSI data available |
| 20 | 0x2038 | HSI pin 1 |
| 21 | 0x203A | immediate A/D conversion done |
| 22 | 0x203C | timed A/D conversion done |
| 23 | 0x203E | alternate I/O timer overflow |
| 24 | 0x2040 | HSO code 0x10: timed A/D conversion started |
| 25 | 0x2042 | HSO code 0x11: alternate I/O timer reset |
| 26-29 | 0x2044-0x204A | HSO codes 0x12-0x15: start/stop counters 1-4 |
| 30-39 | 0x204C-0x205E | HSO codes 0x16-0x1F (free for software) |

Source: [SW 6-72 (PDF 346)][sw-346], [PG B8 (image 09)][pg-09]. SAD 4.13 has the same names and ranges. [SAD 4.13 Core.cpp:677-700][s413-677-700]

- The pending and mask registers are: I/O pending 0x0009 and I/O mask 0x0008 (levels 16-23, byte access); HSO pending #1 0x0010 and mask #1 0x0012 (levels 0-15, word access); HSO pending #2 0x0014 and mask #2 0x0016 (levels 24-39, word access). [SW 6-72 (PDF 346)][sw-346]
- Interrupt entry: the CPU replaces the opcode with the internal opcode 0xF7. It pushes the PC on the stack at SP, clears PSW bit 15, and loads the PC from the vector. It does not save the PSW. [SW 6-75 (PDF 349)][sw-349]
- Each bank needs a vector list and the first part of the handler. SW 6-63 says this part is PUSHP, a JUMP with a ROMBANK prefix, then POPP and RETEI. SW 6-76 says it is a ROMBANK-CALL to the service routine and a RETEI at the end. [SW 6-63 (PDF 337)][sw-337] [SW 6-76 (PDF 350)][sw-350]
- RETEI does not test for pending interrupts. The next instruction runs before another interrupt is serviced. [SW 6-76 (PDF 350)][sw-350]
- The 8065 does not service an interrupt in these cases. [SW 6-75 (PDF 349)][sw-349]
  - During DI and EI.
  - During POPP, PUSHP, RET, RETEI and ROMBANK, and during the next instruction. With memory expansion on, after POPP they stay blocked until the first instruction after the first branch that follows POPP ([SW 3-114 (PDF 156)][sw-156]).
  - During SIGND or ALT, and during the instruction that it modifies.
  - During CALL or SCALL after ROMBANK, and during the next instruction.
  - When an instruction reads the low word of the master I/O timer (with some exceptions).

## 7. Reset state

| Item | 8061 | 8065 | Source |
|---|---|---|---|
| PC | 0x2000 | 0x2000 in bank 8 (0x82000) | [PG A3 (image 03)][pg-03], [SW 6-14 (PDF 288)][sw-288] |
| PSW | 0x7F00 | 0x6000 (bank 8, RAM bank 0, interrupts off) | [PG A3 (image 03)][pg-03], [SW 6-17 (PDF 291)][sw-291] |
| Memory expansion | none | disabled (bit 4 of 0x000A is 0) | [SW 6-63 (PDF 337)][sw-337] |
| Memory bank select register | none | 0x00 | [SW 6-63 (PDF 337)][sw-337] |
| LSO port | 0x00 | 0x80 (LSO7 high selects bank 8) | [PG A3 (image 03)][pg-03], [PG B3 (image 06)][pg-06] |
| SP and ASP | indeterminate | indeterminate | [SW 6-78 (PDF 352)][sw-352] |

## 8. Other differences that change names or addresses

- The HSO port has 30 carousel locations (the 8061 has 12) and up to 32 interrupts. [SW 2-7 (PDF 23)][sw-23] [SW 2-8 (PDF 24)][sw-24]
- The HSI port has a 15-entry FIFO, an edge/mode register and a "FIFO 50% full" interrupt. [SW 2-8 (PDF 24)][sw-24]
- The A/D converter has 16 channels, two start modes, and separate command and result registers for each mode. [SW 2-9 (PDF 25)][sw-25]
- The watchdog timer is inhibited after reset until it is written. A status bit shows a watchdog reset. [SW 2-10 (PDF 26)][sw-26]
- The 8065 has an I/O expander mode. It re-purposes the bidirectional port and three LSO pins. [SW 2-9 (PDF 25)][sw-25]
- The opcode 0xE3 (BR on the 8096) does not exist on the 8061 or the 8065. [TC PDF 14][tc-14]
- Two rules are stated for the 8065 in SW. I did not check the 8061 chapter for the same rules. First: a read of the low word of the master I/O timer blocks interrupts for one instruction (with exceptions). Second: LDB into a word SFR sign-extends. [SW 6-78 (PDF 352)][sw-352]

## 9. The module's partial 8065 section

**Verdict.** None of the instructions SKR4, SKRC, XCHB and SKRB matches an 8065 opcode. They do not match Ford's description of the same opcodes on the 8061 either.

What the module has:
- The language list defines one language, `processor="8061"`, with id `806x:LE:16:default`. [module 806x.ldefs:4-16][mldef-4-16]
- `806x.slaspec` defines the names 8061, 8063 and 8065. The value of 8065 is "0". [module 806x.slaspec:1-3][mslas-1-3]
- The block at `806x.sinc:971-1022` has two `@if defined(8065)` parts. They hold five constructors. [module 806x.sinc:971-1022][msinc-971-1022]
- The committed compiled file `806x.sla` contains all five constructors. Their `line` attributes are 972, 978, 988, 1002 and 1012. Their lengths are 3, 4, 5, 4 and 5 bytes. [module 806x.sla:4246][msla-4246] [module 806x.sla:4273][msla-4273] [module 806x.sla:4323][msla-4323] [module 806x.sla:4370][msla-4370] [module 806x.sla:4420][msla-4420]
- So the constructors are active in the 8061 language today. Inference: the cause is that `@define 8065 "0"` still defines the name, and `@if defined(8065)` tests only whether the name exists.
- `FE` is a one-byte no-operation, `SIGNDS`. The module has no signed multiply or divide. The instruction after `FE` decodes as an ordinary instruction. So `FE` plus a multiply or divide opcode decodes as two instructions with unsigned operations, and `FE` plus a stack opcode decodes as two instructions that use SP. [module 806x.sinc:894-896][msinc-894-896]
- No active constructor exists for 0x10, 0xF4, 0xF5, 0xF6 or 0xFD. (0xF6 has `IDLPD` inside a `C196KB` block, which is not active.) [module 806x.sinc:487-498][msinc-487-498]
- `806x.ldefs` refers to `806x.cspec`. The files `8061.cspec`, `8063.cspec` and `8065.cspec` have identical content, and only a commented-out line refers to them. They hold no 8065 settings. [module 806x.ldefs:14-15][mldef-14-15]
- The module has one address space, `RAM`, 2 bytes wide. It holds registers, data and code. The 8065 has a 20-bit address (section 3). [module 806x.sinc:6-7][msinc-6-7]
- The module puts the stack pointer `SP` at 0x0010, and the compiler spec names `SP` as the stack pointer. On the 8065, 0x0010-0x0011 hold HSO interrupt pending #1 and the memory bank select register. The 8065 stack pointers are at 0x0020 and 0x0022 (section 4). [module 806x.sinc:16][msinc-16] [module 806x.sinc:44-45][msinc-44-45] [module 806x.cspec:7][mcspec-7]
- The module's `PSW` is a 4-byte register. The flags `I Z N V VT C NZ CZ ST` are separate 1-byte registers. No register holds the bank bits (PSW bits 13-8). [module 806x.sinc:12][msinc-12] [module 806x.sinc:47][msinc-47]
- SFR names in `806x.sinc` stop at 0x0011. [module 806x.sinc:19-45][msinc-19-45]

The five constructors:

| Source line | Opcode | Module name | Bytes | Operation in the module |
|---|---|---|---|---|
| [module 806x.sinc:972][msinc-972] | 0x04 | `SKR4 wreg, waop16` | 3 | swaps a word register with the word operand `waop16` |
| [module 806x.sinc:978][msinc-978] | 0x0B | `SKRC wreg, immed8[iwreg]` | 4 | swaps a word register with a word at base plus short index |
| [module 806x.sinc:988][msinc-988] | 0x0B | `SKRC wreg, immed16[iwreg]` | 5 | same, long index |
| [module 806x.sinc:1002][msinc-1002] | 0x1B | `XCHB breg, immed8[iwreg]` | 4 | swaps a byte register with a byte at base plus short index |
| [module 806x.sinc:1012][msinc-1012] | 0x1B | `SKRB breg, immed16[iwreg]` | 5 | same, long index |

Evidence that they do not match:

| Opcode | Ford 8061 (Table A-1) | Ford 8065 (Table A-2) | SAD 4.13 and 6.01 | Tom Cloud notes | Intel 8XC196 |
|---|---|---|---|---|---|
| 0x04 | runs as SKIP | unusable `SKR4`: 2-byte no-operation read | invalid | `nu`, `(ua)` | XCH direct |
| 0x0B | 3 bytes: add or shift | unusable `SKRC`: 2-byte no-operation read | invalid | `nu`, `(ua)` | XCH indexed |
| 0x1B | 3 bytes: add or shift | unusable `SKRC`: 2-byte no-operation read | invalid | `nu`, `(ua)` | XCHB indexed |
| 0x14 (no module constructor) | runs as SKIP | unusable `SKR4` | invalid | `nu`, `(ua)` | XCHB direct |

Sources: Ford [SW A-1 (PDF 461)][sw-461], [SW A-2 (PDF 462)][sw-462] and [SW 11-1 (PDF 455)][sw-455]; SAD 4.13 [SAD 4.13 Core.cpp:379-381][s413-379-381]; SAD 6.01 [SAD 6.01 Core.cpp:55-57][s601-55-57]; Tom Cloud, who marks them `nu` (not used) in Table 10 and `(ua)` (unassigned) in the opcode maps [TC PDF 14][tc-14] [TC PDF 15][tc-15] [TC PDF 17][tc-17]; Intel [I196 A-40 (PDF 479)][i196-479] and [I196 A-42 (PDF 481)][i196-481].

- The names `SKR4` and `SKRC` are Ford's names for opcodes that read a register and discard the data. The module uses them for exchange instructions.
- `SKRB` is in no source I read. `XCHB` is Intel's name for the byte exchange.
- Ford's instructions have 2 bytes: opcode and register byte. The module's have 3, 4 or 5 bytes.
- The module source has other blocks that are for the 8XC196 only. [module 806x.sinc:451-457][msinc-451-457] [module 806x.sinc:487-498][msinc-487-498]
- Inference: the module encodings match Intel's XCH (`04 waop wreg`, `0B` indexed) and XCHB (`1B` indexed). The module took them from a 196 definition.
- The module has commented text that names 0xC1 `SKR4` as a block move. Ford lists 0xC1 as a 2-byte no-operation `SKR4`. [module 806x.sinc:351-353][msinc-351-353]
- No 8065-only opcode is defined in the module: ROMBANK (0x10), BANK0-BANK3 and the ALT forms are all missing.

Inference: for an 8065 language the opcodes of section 5.8 can stay undefined, as in SAD, or can decode as the Ford no-operations. The five constructors fit neither CPU in Ford's documents.

## 10. Where the sources disagree

- **Data and stack nibbles.** The SAD6 manual swaps them (section 3.4). Ford and SAD code agree with each other. [SAD6 manual PDF 12][sadm-12] [SAD 6.01 command.cpp:511-512][s601c-511-512]
- **Word register bank rule.** Ford: bank = PSW bit 9 and operand bit 0. SAD 4.13 and 6.01 add the RAM bank to the register number. For an odd register they set bit 8. For an even register they keep bit 8. With BANK1 or BANK3 selected, an even word register goes to bank 1 or 3 in SAD. Ford sends it to bank 0 or 2. [SAD 4.13 Core.cpp:5606-5619][s413-5606-5619] [SAD 6.01 Core.cpp:5243-5260][s601-5243-5260] [SW 6-10 (PDF 284)][sw-284]
- **Odd word register in SAD806x.** It always shows `0x100 + r - 1`, whatever the RAM bank. It has no RAM bank tracking. Its default is on for 8065 images. [SAD806x SADOPCode.cs:427-440][oeso-427-440] [SAD806x SADBin.cs:1714-1732][oesb-1714-1732] [SAD806x manual PDF 24][s806m-24]
- **ROMBANK bank byte.** Ford: bits 3-0, other bits ignored. SAD 4.13: bits 3-0. SAD 6.01: only 0, 1, 8, 9 pass. SAD806x: the whole byte. [SW 3-124 (PDF 166)][sw-166] [SAD 4.13 Core.cpp:10040-10042][s413-10040-10042] [SAD 6.01 Core.cpp:1419-1429][s601-1419-1429] [SAD806x SADOPCode.cs:394-397][oeso-394-397]
- **FE before other opcodes.** Ford: a NOP. SAD: invalid opcode. [SW 3-156 (PDF 198)][sw-198] [SAD 4.13 Core.cpp:10054-10062][s413-10054-10062]
- **SAD 4.13 helper.** In `find_opcode`, the `FE` branch reads the byte after the opcode `10`, which is the bank byte. It should read the byte after `FE`. The decode function `do_code` does it correctly. [SAD 4.13 Core.cpp:1700-1719][s413-1700-1719] [SAD 4.13 Core.cpp:10054-10062][s413-10054-10062]
- **Manual wording on SFRs.** Section 3-2.2 of SW (word operands: bit 0 selects the bank) and section 6-3 (word access to an SFR ignores bit 0) disagree for odd SFR addresses. [SW 3-6 (PDF 48)][sw-48] [SW 6-4 (PDF 278)][sw-278]
- **ROMBANK before ALT PUSHW and ALT POPW.** SW 3-125 says that ROMBANK before an ALT-modified instruction selects the branch bank only. Figure 3-4 lists PUSHW and POPW as legal after ROMBANK and ALT, and the example on SW 3-126 puts ROMBANK before ALT PUSHW. I follow the figure and the example. [SW 3-125 (PDF 167)][sw-167] [SW 3-11 (PDF 53)][sw-53] [SW 3-126 (PDF 168)][sw-168]
- **ROMBANK enable example.** The example at SW 6-63 pushes 0x0200 (`C9 00 02`) and says "Set bank #8". Bank 8 in PSW bits 13-10 is 0x2000. Inference: the example has a typo. [SW 6-63 (PDF 337)][sw-337]
- **Stack used by CALLALT.** SW 3-47 and the pocket guide say that CALLALT stores the PC on the alternate stack. SW 6-77 says that the PC is always pushed onto the stack that SP references. Inference: the SW 6-77 note describes interrupt entry. [SW 3-47 (PDF 89)][sw-89] [PG C11 (image 15)][pg-15] [SW 6-77 (PDF 351)][sw-351]
- **Reset value of 0x000A.** The pocket guide shows 0x68. The Software Manual shows 0x48. I read both as page images. [PG B3 (image 06)][pg-06] [SW 6-80 (PDF 354)][sw-354]

## 11. Open questions

- **Word direct odd/even bank in real silicon.** Ford's pocket guide limits it to "D-step and beyond". Forum posts report 8065 chips that do not do it and chips that do more than the manual says. This file cannot tell which rule a given chip follows. [PG C14 (image 17)][pg-17] [EECTuning thread t=23036&start=60][forum-t-23036-start-60] [EECTuning thread t=23036][forum-t-23036]
- **Odd SFR addresses in word operands.** See section 10. The manual text does not settle it. [EECTuning thread t=23036][forum-t-23036]
- **Why images use banks 0, 1, 8, 9.** Ford shows banks 1, 2, 4, 8 as typical. No source I read explains the set 0, 1, 8, 9. Section 3.7 gives an inference. A board schematic or the 228 MB hardware reference might settle it.
- **Which banks answer RAM and I/O addresses.** Forum posts say bank 1 on multi-bank images (section 3.2). No Ford document that I read states it.
- **Stack bank for interrupt entry.** Ford does not say which nibble the PC push uses.
- **ALT before opcodes that are neither stack nor multiply and divide.** Ford says SIGND before them is a NOP. It does not say what an ALT use means there.

<!-- Links in this file point to pinned commits. -->

<!-- Link targets -->
[sw-26]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-28]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-29]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[pg-09]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[sw-331]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sadm-3]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Docs/SAD6_User_Manual.pdf
[sw-166]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-167]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-88]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-52]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-72]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-278]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-235]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-280]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-346]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[pg-05]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[sw-165]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-462]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-461]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[msinc-971-1022]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L971-L1022
[sw-3]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[Useful-Docs 643ddfe]: https://github.com/OpenEEC-Project/Useful-Docs/commit/643ddfe97ece93c828aa386d2ab35805aa4582ec
[pg-01]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[tc-1]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/eectch98.pdf
[od]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/8061_opcodes.doc
[i196-479]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/8096_user_manual.pdf
[SAD 19373a0]: https://github.com/tvrfan/EEC-IV-disassembler/commit/19373a0f922bd226ffad7e7d32d193ea11998501
[800b8c2]: https://github.com/tvrfan/EEC-IV-disassembler/commit/800b8c2d49c86f5a7ac98096872dda1fcd2588e9
[SAD806x 09e70da]: https://github.com/OpenEEC-Project/SAD806x/commit/09e70da63ac7e238e44a97932b50dc0bb5ae2a48
[dis8061 25794ac]: https://github.com/OpenEEC-Project/dis8061/commit/25794aca3e2306e71b43d96312be3260c49a87af
[module 893cb6d]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/commit/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884
[c860ca1]: https://github.com/RavenHursT/Ghidra-EEC-806x/commit/c860ca184b2908054437c931c95be9788a34c6d9
[806x.sinc:642-655 at c860ca1]: https://github.com/RavenHursT/Ghidra-EEC-806x/blob/c860ca184b2908054437c931c95be9788a34c6d9/data/languages/806x.sinc#L642-L655
[806x.sinc:972-1023 at c860ca1]: https://github.com/RavenHursT/Ghidra-EEC-806x/blob/c860ca184b2908054437c931c95be9788a34c6d9/data/languages/806x.sinc#L972-L1023
[forum-t-23036]: https://eectuning.org/forums/viewtopic.php?t=23036
[forum-t-23036-start-30]: https://eectuning.org/forums/viewtopic.php?t=23036&start=30
[forum-t-23036-start-60]: https://eectuning.org/forums/viewtopic.php?t=23036&start=60
[sw-27]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[pg-39]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[sw-285]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-330]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-288]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-349]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[s413-13454]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L13454
[s413h-25-27]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/core.h#L25-L27
[sw-334]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-336]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-335]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-279]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[s601c-509]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L509
[s601c-511]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L511
[s601c-512]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L512
[sw-337]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-292]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-276]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sadm-12]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Docs/SAD6_User_Manual.pdf
[s413-8897-8905]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L8897-L8905
[sw-289]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-290]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-56]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-291]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[pg-03]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[sw-352]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-156]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-46]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-351]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-333]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-282]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-50]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-283]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-284]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-48]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[pg-17]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[s413-9949-9968]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L9949-L9968
[s413-9767]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L9767
[s413h-15-27]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/core.h#L15-L27
[pg-06]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[s601c-469-536]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L469-L536
[sadm-6]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Docs/SAD6_User_Manual.pdf
[s601c-371-439]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L371-L439
[s413-802]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L802
[s601c-534]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L534
[oesd-365-427]: https://github.com/OpenEEC-Project/SAD806x/blob/09e70da63ac7e238e44a97932b50dc0bb5ae2a48/SADDef.cs#L365-L427
[sw-24]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[s601c-445-463]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L445-L463
[sw-455]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-198]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[tc-14]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/eectch98.pdf
[sw-168]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-169]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[pg-16]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[s601-171-274]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/Core.cpp#L171-L274
[s601s-1158]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/shared.h#L1158
[sw-53]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[s413-10033-10052]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L10033-L10052
[s413-9441]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L9441
[s413-5623]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L5623
[s601-1402-1437]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/Core.cpp#L1402-L1437
[oeso-329-333]: https://github.com/OpenEEC-Project/SAD806x/blob/09e70da63ac7e238e44a97932b50dc0bb5ae2a48/SADOPCode.cs#L329-L333
[s413-13466]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L13466
[s413-13484-13497]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L13484-L13497
[pg-12]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[s413-612-615]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L612-L615
[pg-15]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC_handbook.zip
[sw-89]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-163]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[s413-534-584]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L534-L584
[s413-579]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L579
[s601-264]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/Core.cpp#L264
[s413-376-411]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L376-L411
[s601-52-87]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/Core.cpp#L52-L87
[tc-17]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/eectch98.pdf
[s413-677-700]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L677-L700
[sw-350]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-23]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[sw-25]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
[mldef-4-16]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.ldefs#L4-L16
[mslas-1-3]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.slaspec#L1-L3
[msla-4246]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sla#L4246
[msla-4273]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sla#L4273
[msla-4323]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sla#L4323
[msla-4370]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sla#L4370
[msla-4420]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sla#L4420
[msinc-894-896]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L894-L896
[msinc-487-498]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L487-L498
[mldef-14-15]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.ldefs#L14-L15
[msinc-6-7]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L6-L7
[msinc-16]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L16
[msinc-44-45]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L44-L45
[mcspec-7]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.cspec#L7
[msinc-12]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L12
[msinc-47]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L47
[msinc-19-45]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L19-L45
[msinc-972]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L972
[msinc-978]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L978
[msinc-988]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L988
[msinc-1002]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L1002
[msinc-1012]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L1012
[s413-379-381]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L379-L381
[s601-55-57]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/Core.cpp#L55-L57
[tc-15]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/eectch98.pdf
[i196-481]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/8096_user_manual.pdf
[msinc-451-457]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L451-L457
[msinc-351-353]: https://github.com/OpenEEC-Project/Ghidra-EEC-806x/blob/893cb6d9bfc968f52d3b1e45da6f7c5e6b8f2884/data/languages/806x.sinc#L351-L353
[s601c-511-512]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/command.cpp#L511-L512
[s413-5606-5619]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L5606-L5619
[s601-5243-5260]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/Core.cpp#L5243-L5260
[oeso-427-440]: https://github.com/OpenEEC-Project/SAD806x/blob/09e70da63ac7e238e44a97932b50dc0bb5ae2a48/SADOPCode.cs#L427-L440
[oesb-1714-1732]: https://github.com/OpenEEC-Project/SAD806x/blob/09e70da63ac7e238e44a97932b50dc0bb5ae2a48/SADBin.cs#L1714-L1732
[s806m-24]: https://github.com/OpenEEC-Project/SAD806x/blob/09e70da63ac7e238e44a97932b50dc0bb5ae2a48/Manual/SAD806x.pdf
[s413-10040-10042]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L10040-L10042
[s601-1419-1429]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/Development/sources/6.01/Core.cpp#L1419-L1429
[oeso-394-397]: https://github.com/OpenEEC-Project/SAD806x/blob/09e70da63ac7e238e44a97932b50dc0bb5ae2a48/SADOPCode.cs#L394-L397
[s413-10054-10062]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L10054-L10062
[s413-1700-1719]: https://github.com/tvrfan/EEC-IV-disassembler/blob/19373a0f922bd226ffad7e7d32d193ea11998501/source/4.13/Core.cpp#L1700-L1719
[sw-354]: https://github.com/OpenEEC-Project/Useful-Docs/blob/643ddfe97ece93c828aa386d2ab35805aa4582ec/EEC-IV%20Software%20Manual.pdf
