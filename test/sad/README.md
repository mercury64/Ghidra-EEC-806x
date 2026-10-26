# SAD reference listing tools

SAD is the Semi Automatic Disassembler for Ford EEC-IV and EEC-V code (Intel 8061 and 8065 CPUs).
Its source is at <https://github.com/tvrfan/EEC-IV-disassembler>. SAD is a second disassembler that
was written independently of this Ghidra module. Its listing gives a reference to compare with the
output of the module.

The tools in this directory change a SAD listing (`<name>_lst.txt`) into a tab-separated file that
another program can read.

| File | Purpose |
|---|---|
| `normalize_sad_listing.py` | Reads a SAD listing and writes the tab-separated file. Python 3, standard library only. |
| `test_normalize.py` | Unit tests for the script. All listing text in them is invented. |
| `verify_reference.py` | Checks a tab-separated file: the branch targets against the bytes of the rows, overlaps and gaps between rows, and (with the image) the bytes of every row. |
| `test_verify.py` | Unit tests for the check script. All rows and image bytes in them are invented. |

Do not commit a program image, a SAD listing or message file made from one, or the tab-separated
files made from them. Keep these files outside the git work tree.

## Run the tests

From the repository root:

```
python3 -m unittest discover -s test/sad
```

## Use the normalizer

```
python3 test/sad/normalize_sad_listing.py LISTING [-o OUTPUT] [--single-bank BANK]
                                          [--only-bank BANK ...] [--pseudo-column N]
```

| Option | Meaning |
|---|---|
| `LISTING` | The SAD listing file. |
| `-o`, `--output` | The output file. The default is standard output. |
| `--single-bank BANK` | The bank for 4-digit addresses from 2000 up. A single-bank listing has such addresses. The default is 8. |
| `--only-bank BANK` | Keep only the rows in this bank. Use the option more than once for more banks. Rows with an unbanked address (below 2000) are dropped when the option is used. |
| `--pseudo-column N` | The column where SAD starts its pseudo source text. The default is 49, as in SAD 4.13. |

Exit code 0 means success. Exit code 1 means that the file cannot be read, or that a line looks like
a listing row but cannot be parsed (the message gives the line number). Exit code 2 means wrong
options.

Examples:

```
# single-bank listing, bank 8 is assumed
python3 test/sad/normalize_sad_listing.py single_lst.txt -o single_ref.tsv
# multi-bank listing, keep only the rows of bank 8
python3 test/sad/normalize_sad_listing.py multi_lst.txt --only-bank 8 -o bank8_ref.tsv
```

## Check a tab-separated file

```
python3 test/sad/verify_reference.py REF.tsv [--image IMAGE --blocks 0,1,8,9]
                                    [--bank-offset BANK=OFFSET ...]
```

The script makes these checks:

- **targets**: for every relative branch (`jb`, `jnb`, `djnz`, the conditional jumps, `sjmp`,
  `scall`, `jump`, `call`) it calculates the target from the bytes of the row and compares it with the
  target that SAD printed. A bank prefix row (`rombk`) before the branch sets the bank of the target.
  A target printed with 4 digits is compared without the bank. It also counts the branches whose
  target is the next instruction.
- **tiling**: rows must not overlap. Gaps between rows are counted and shown, but they are not an
  error. SAD leaves a gap after each `!INV!` row.
- **bytes** (only with `--image`): the bytes of every row must be the same as the bytes of the image.
  `--blocks 0,1,8,9` means that the image is made of 64 KiB blocks in that bank order, and address 0
  of each bank is at the start of its block. `--bank-offset BANK=OFFSET` gives the file offset of
  address 0 of one bank in hex. It can be negative, for a file whose first byte is address 2000.

The exit code is 1 when a target is wrong, rows overlap, a row differs from the image, or the file
cannot be read.

## Output format

The first line is the header `addr`, `kind`, `len`, `bytes`, `mnemonic`, `operands`, separated by
tabs. Then there is one line for each row of the listing, in listing order.

| Column | Content |
|---|---|
| `addr` | Lowercase hex, no `0x`. A banked address has 5 digits: bank x 0x10000 + address. For example `83002` is address 3002 in bank 8, and `02075` is address 2075 in bank 0. An address below 0x2000 has no bank and has 4 digits. A 4-digit address from 2000 up in the listing gets the `--single-bank` bank (default 8). |
| `kind` | `code` for an instruction. `byte`, `word` or `vect` for a data row that SAD printed with that keyword. `other` for all other rows (see below). |
| `len` | The number of bytes in the row, in decimal. |
| `bytes` | The bytes of the row, lowercase hex, no separators. It has 2 x `len` characters. |
| `mnemonic` | For `code` rows: the mnemonic in lowercase, as SAD prints it. Empty for all other rows. |
| `operands` | The operand text as SAD printed it, with the case unchanged. For `byte`, `word` and `vect` rows: the printed value without its alignment spaces. For `other` rows: the keyword that SAD printed, then its operand if there is one. SAD's pseudo source text and symbol names are not kept. |

How SAD rows map to the columns:

| SAD prints | `kind` | `mnemonic` | `operands` |
|---|---|---|---|
| An instruction, for example `ldw R30,1234` | `code` | `ldw` | `R30,1234` |
| A bank prefix pair, for example bytes `10,08` as `rombk 8` | `code` | `rombk` | `8` |
| A register bank byte, for example `f4` as `regbk 0` | `code` | `regbk` | `0` |
| `byte`, `word` or `vect` and a value | `byte`, `word`, `vect` | empty | the value |
| `???` (bytes that SAD did not identify, up to 16 bytes in a row) | `other` | empty | `???` |
| `!INV!` (a byte that SAD cannot decode) | `other` | empty | `!INV!` |
| `#arg 1` (an inline argument that follows a subroutine call) | `other` | empty | `#arg 1` |
| A fill range, `A -> B = 0xNN  ## fill ##` | `other` | empty | `fill 0xNN` |
| `text`, `table`, `func`, `struct`, `timer`, `args`, `xcode`, `long`, `triple` | `other` | empty | the keyword and its first operand |

Notes:

- The bank prefix pair is its own row, as SAD prints it. The instruction that it applies to is the
  next row. SAD prints the bank number of the prefix in decimal as the operand.
- A fill range row has `len` equal to B - A + 1, and `bytes` is the byte NN repeated `len` times.
- SAD prints no row for the 1 or 2 bytes that follow an `!INV!` row, so the next row starts after
  a gap.
- These lines are not rows and are ignored: blank lines, comment lines that start with `#`, label
  lines (`   Sub_83001:`), continuation lines of pseudo source, and the SAD banner and footer.
- A line that starts with a hex address followed by `:` or ` ->` must parse. If it does not, the
  script stops with an error.

### Where the operands end

SAD 4.13 prints a row in columns (counted from 0): the mnemonic starts at column 26, the operands at
column 32 and the pseudo source at column 49. A row with 7 bytes pushes the mnemonic right. SAD can
also print the mnemonic directly after the bytes with no space. The script takes the first word after
the mnemonic as the operand when it starts before the pseudo source column. Text that starts at or
after that column is pseudo source. Operands never contain spaces. An operand can be so long that it
ends at column 48 or later. The script handles both cases.

## Build SAD 4.13 on macOS

SAD has no makefile. The source is `source/4.13` in the SAD repository. Copy the source files to a
new directory, make the four changes in the next list, and compile:

```
cp source/4.13/*.cpp source/4.13/*.h build-4.13/
cd build-4.13
# make the four changes below in this copy, then:
clang++ -O2 -D__linux__=1 -o sad413 chain.cpp Core.cpp debug.cpp main.cpp sign.cpp
```

The compile gives 16 warnings about `sprintf` and no errors. Some of the SAD source files use
Windows line endings (CRLF).

`-D__linux__=1` is needed because SAD chooses its file open modes and its path separator with
`#ifdef __linux__` and `#ifdef __MINGW32__`. macOS defines neither name, so the build fails
without the option. SAD reports its version as `4.0.13.10 (26 Jan 2025)`.

The four changes apply only to the copy of the source. They do not change the SAD repository. This
repository does not include them as patch files, because the SAD repository has no license; the
links below go to the lines in SAD's own repository (commit `800b8c2d`, SAD 4.15.2), where
`source/4.13` is unchanged.

| Change | Why it is needed |
|---|---|
| Mask the shift count in `scale_val` ([Core.cpp:3734-3759](https://github.com/tvrfan/EEC-IV-disassembler/blob/800b8c2d49c86f5a7ac98096872dda1fcd2588e9/source/4.13/Core.cpp#L3734-L3759)) | In `Core.cpp`, `scale_val` shifts by `fend`. For a signed field `fend` has the flag 0x20 added, so the shift count is 39 or 47. A shift count above 31 is undefined behavior in C++. A gcc build on x86 happens to give the right result. A clang build with `-O2` gives 0, so every signed offset reads as 0. Every conditional jump, `djnz`, `jb`, `jnb`, long jump and long call then prints a wrong target, and SAD follows the wrong paths in its code scan. The change masks the count to 0-31. `sjmp` and `scall` do not use `scale_val` and are not affected. |
| Read each bank's bytes in the `bank` directive ([Core.cpp:11424-11431](https://github.com/tvrfan/EEC-IV-disassembler/blob/800b8c2d49c86f5a7ac98096872dda1fcd2588e9/source/4.13/Core.cpp#L11424-L11431)) | The `bank` directive of SAD 4.13 sets the bank map, but the code that reads the bank bytes from the file is commented out. A bank that the automatic search did not find stays empty. For an image with a calibration bank that has no interrupt vectors, the automatic search finds only one bank. SAD then analyses with an empty data bank, and its decisions about code and data are not the decisions that it makes with the real bank data. The change reads the bytes of each bank from the file, as the commented code was meant to do. |
| Zero new memory in `mem()` ([chain.cpp:193-208](https://github.com/tvrfan/EEC-IV-disassembler/blob/800b8c2d49c86f5a7ac98096872dda1fcd2588e9/source/4.13/chain.cpp#L193-L208)) | SAD takes memory from `mem()` and assumes that it is zero. `realloc` does not promise that. With a fill pattern in new memory (`MallocScribble=1` on macOS) the listing changed. The change zeroes new memory. The listing is the same as with the normal allocator. |
| Limit the bank index in the signature search ([sign.cpp:698-700](https://github.com/tvrfan/EEC-IV-disassembler/blob/800b8c2d49c86f5a7ac98096872dda1fcd2588e9/source/4.13/sign.cpp#L698-L700)) | The signature search in `sign.cpp` reads `bkmap[16]`, which is one entry after the end of the array, when the code bytes are `10 0f`. The change limits the index. |

Checks that were done on the listings of one four-bank image, with the four changes:

- The listing is byte-for-byte the same for `-O0`, `-O2`, a build with AddressSanitizer and
  UndefinedBehaviorSanitizer, and a run with `MallocScribble=1 MallocPreScribble=1`.
- The target of every relative branch in the listing is the same as the target that is calculated
  from the bytes of the row. A build without the scale_val change fails this check for most of the
  branch rows (`verify_reference.py`, check "targets").
- The bytes of every row are the same as the bytes of the image at that address
  (`verify_reference.py --image`, check "bytes").

Not patched: UndefinedBehaviorSanitizer still reports undefined behavior in the arithmetic of SAD's
code emulator (`Core.cpp`, functions `mlx`, `dvx`, `shl` and `shr`: a multiplication overflow, a left
shift of a negative number, and shifts by large counts). It did not change any listing.

## Run SAD

`sad.ini` has five lines. Each line is a directory that ends with `/` (no spaces in the path). The
directories are, in this order, for the `.bin` file, the listing, the messages, the directive file and
the comment file. Run SAD with the directory of `sad.ini` and the name of the `.bin` file without its
suffix:

```
./sad413 -c /path/to/directory-with-sad.ini name
```

SAD reads `name.bin` and the optional `name_dir.txt`. It writes `name_lst.txt` and `name_msg.txt`.

The directive file is read in the order of its lines. Lines that start with `#` are comments.

For a 256 KiB image with the banks in the order 0, 1, 8, 9, where each bank starts 0x2000 bytes into
its own 64 KiB block, use these bank lines in this order:

```
bank 1 12000 12000 1ffff
bank 0 2000 02000 0ffff
bank 9 32000 92000 9ffff
bank 8 22000 82000 8ffff
```

The order is important. SAD 4.13 checks the wrong slot of its bank map, so it rejects `bank 1` after
`bank 0`, and `bank 9` after `bank 8`, with the false error `Bank already defined`.

Without bank lines, SAD 4.13 reports `Found multiple Start Code banks [bank 8]` when two banks hold
the same code, keeps the first and treats the file as a single bank.

A `code A B` line in the directive file is a user command. SAD obeys it, so data in the range, for
example tables, can be decoded as code. Leave it out to get SAD's own decision about code and data.
