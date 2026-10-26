#!/usr/bin/env python3
"""Expected outputs of the Ford EEC-IV function lookup, to check the Ghidra emulator.

A Ford function is a list of (x, y) pairs. The x values go down from the highest value (0xff,
or 0x7f when x is signed) to the lowest value (0x00, or 0x80 when x is signed). Byte functions
have 1-byte x and y, word functions 2-byte x and y (little-endian). The lookup routine reads
the pairs from the start of the function:

1. It compares x with the x of the next pair. While x is lower, it goes to the next pair.
   Unsigned x: "lower" is the carry flag of the subtraction (x < next x).
   Signed x: "lower" is the N flag of the subtraction (JGE). The CPU sets N to the sign of the
   true result, also when the 8-bit or 16-bit result overflows (EEC-IV Software Manual,
   CMPB and SB2B; Intel 8096 user manual, PSW), so this is a signed x < next x.
   No x is lower than the lowest value, so the routine never reads past the first pair with
   that x: pairs after it (for example unused breakpoints with x 0) have no effect.
2. With the pair (x0, y0) and the next pair (x1, y1) it computes, in 8 or 16 bits,
   dx = x - x1, span = x0 - x1 and dy = y0 - y1. If dy is negative (unsigned y: y0 < y1, the
   carry flag; signed y: y0 < y1 signed, the N flag), it negates dy and remembers the sign.
3. It multiplies dy by dx (16 or 32 bits), divides by span (unsigned, the quotient is
   truncated), negates the quotient if dy was negative, and adds y1. The result has the
   width of y; the addition wraps.

lookup() follows these steps. compare() reads the rows that test/ghidra/EecEmulatorCheck.java
writes and checks each emulated result against lookup().

Addresses are banked: bank * 0x10000 + 16-bit address, as in the 8065 Ghidra language.

Usage:
  ford_function.py compare --image IMAGE --rows ROWS [--show-values]
  ford_function.py lookup --image IMAGE --table ADDR [--width 1|2] [--signed-x]
                          [--signed-y] [X ...]

compare prints counts only, unless --show-values is given. Do not commit output that shows
values from a program image.

Uses the Python standard library only.
"""

import argparse
import collections
import sys

IMAGE_BANKS = (0, 1, 8, 9)
BANK_SIZE = 0x10000
MAX_PAIRS = 64


class BankedImage:
    """An image of 64 KiB blocks, one block per bank, read by banked address."""

    def __init__(self, data, banks=IMAGE_BANKS):
        if len(data) < len(banks) * BANK_SIZE:
            raise ValueError(f"image has {len(data)} bytes; {len(banks)} banks need "
                             f"{len(banks) * BANK_SIZE}")
        self._data = data
        self._slot = {bank: i for i, bank in enumerate(banks)}

    def byte(self, address):
        bank = address >> 16
        if bank not in self._slot:
            raise ValueError(f"bank {bank:x} of address {address:05x} is not in the image")
        return self._data[self._slot[bank] * BANK_SIZE + (address & 0xffff)]

    def value(self, address, width):
        """Unsigned little-endian value of width bytes."""
        return sum(self.byte(address + i) << (8 * i) for i in range(width))


def lowest_x(width, signed_x):
    return (0x80 << (8 * (width - 1))) if signed_x else 0


def read_function(image, address, width=1, signed_x=False, max_pairs=MAX_PAIRS):
    """The (x, y) pairs of a function, as raw unsigned values, up to the pair with the lowest x."""
    pairs = []
    end = lowest_x(width, signed_x)
    for i in range(max_pairs):
        x = image.value(address + 2 * width * i, width)
        y = image.value(address + 2 * width * i + width, width)
        pairs.append((x, y))
        if x == end:
            return pairs
    raise ValueError(f"no pair with x {end:x} in the first {max_pairs} pairs at {address:05x}")


def to_signed(value, bits):
    return value - (1 << bits) if value >> (bits - 1) else value


def is_below(x, x_next, bits, signed_x):
    """True if the routine moves on to the next pair: the flag test after CMP x, x_next."""
    if signed_x:
        return to_signed(x, bits) < to_signed(x_next, bits)
    return x < x_next


def find_pair(pairs, x, bits, signed_x):
    i = 0
    while i + 1 < len(pairs) and is_below(x, pairs[i + 1][0], bits, signed_x):
        i += 1
    if i + 1 >= len(pairs):
        raise ValueError(f"x {x:x} is below the last pair; the routine reads past the end")
    return i


def lookup(pairs, x, width=1, signed_x=False, signed_y=False):
    """Output of the Ford lookup routine for input x (raw unsigned values of width bytes)."""
    bits = 8 * width
    mask = (1 << bits) - 1
    i = find_pair(pairs, x, bits, signed_x)
    (x0, y0), (x1, y1) = pairs[i], pairs[i + 1]
    span, dx, dy = (x0 - x1) & mask, (x - x1) & mask, (y0 - y1) & mask
    negative = to_signed(y0, bits) < to_signed(y1, bits) if signed_y else y0 < y1
    if negative:
        dy = -dy & mask
    if span == 0:
        raise ZeroDivisionError(f"pairs {i} and {i + 1} have the same x")
    quotient = dy * dx // span
    if quotient > mask:
        raise OverflowError(f"quotient {quotient:x} does not fit in {bits} bits")
    if negative:
        quotient = -quotient & mask
    return (quotient + y1) & mask


def required_inputs(pairs, width=1):
    """The inputs the check must cover: every breakpoint, the midpoints, the lowest and the
    highest input value."""
    xs = {0, (1 << (8 * width)) - 1}
    xs.update(x for x, _ in pairs)
    xs.update((a[0] + b[0]) // 2 for a, b in zip(pairs, pairs[1:]))
    return sorted(xs)


Summary = collections.namedtuple("Summary", "width signed rows matches mismatches errors "
                                 "missing_inputs")


def read_rows(path):
    """The rows of an emulator result file (tab-separated, with a header)."""
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    names = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        if line.strip():
            rows.append(dict(zip(names, line.split("\t"))))
    return rows


def row_key(row):
    return (int(row["table"], 16), int(row["width"]), row["signed_x"] == "1",
            row["signed_y"] == "1")


def check_row(image, row):
    """(expected, error text): the expected result of one emulator row."""
    table, width, signed_x, signed_y = row_key(row)
    try:
        pairs = read_function(image, table, width, signed_x)
        return lookup(pairs, int(row["x"], 16), width, signed_x, signed_y), ""
    except (ValueError, ZeroDivisionError, OverflowError) as e:
        return None, str(e)


def missing_inputs(image, key, rows):
    """Required inputs of a table that no "call" row covered."""
    table, width, signed_x, _ = key
    covered = {int(r["x"], 16) for r in rows if r["mode"] == "call"}
    if not covered:
        return []
    pairs = read_function(image, table, width, signed_x)
    return [x for x in required_inputs(pairs, width) if x not in covered]


def compare(image, rows_path, out, show_values=False):
    """Check every row against lookup(). Returns a Summary per table address."""
    groups = collections.defaultdict(list)
    for row in read_rows(rows_path):
        groups[row_key(row)].append(row)
    summary = {}
    for key, rows in sorted(groups.items()):
        summary[key[0]] = summarize(image, key, rows, out, show_values)
    print_summary(summary, out)
    return summary


def summarize(image, key, rows, out, show_values):
    matches = mismatches = errors = 0
    for row in rows:
        expected, error = check_row(image, row)
        if error:
            errors += 1
            print(f"error: table {key[0]:05x}: {error}", file=out)
        elif expected == int(row["result"], 16):
            matches += 1
        else:
            mismatches += 1
            if show_values:
                print(f"differ: table {key[0]:05x} x {row['x']} emulated {row['result']} "
                      f"expected {expected:x} ({row['mode']} from {row['start']})", file=out)
    signed = ("s" if key[2] else "u") + ("s" if key[3] else "u")
    return Summary(key[1], signed, len(rows), matches, mismatches, errors,
                   missing_inputs(image, key, rows))


def print_summary(summary, out):
    print("table\twidth\tx,y\trows\tmatch\tdiffer\terror\tinputs not covered", file=out)
    for table, s in summary.items():
        print(f"{table:05x}\t{s.width}\t{s.signed}\t{s.rows}\t{s.matches}\t{s.mismatches}\t"
              f"{s.errors}\t{len(s.missing_inputs)}", file=out)
    total = [sum(getattr(s, f) for s in summary.values()) for f in ("rows", "matches",
                                                                      "mismatches", "errors")]
    print("total\t\t\t" + "\t".join(str(n) for n in total), file=out)


def parse_number(text):
    return int(text, 0) if text.lower().startswith("0x") else int(text, 16)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    cmp_parser = sub.add_parser("compare", help="check emulator rows")
    cmp_parser.add_argument("--image", required=True)
    cmp_parser.add_argument("--rows", required=True)
    cmp_parser.add_argument("--show-values", action="store_true")
    lu = sub.add_parser("lookup", help="print the expected outputs of one function")
    lu.add_argument("--image", required=True)
    lu.add_argument("--table", required=True, type=parse_number)
    lu.add_argument("--width", type=int, default=1, choices=(1, 2))
    lu.add_argument("--signed-x", action="store_true")
    lu.add_argument("--signed-y", action="store_true")
    lu.add_argument("x", nargs="*", type=parse_number)
    args = parser.parse_args(argv)
    with open(args.image, "rb") as f:
        image = BankedImage(f.read())
    if args.command == "compare":
        return run_compare(image, args)
    return run_lookup(image, args)


def run_compare(image, args):
    summary = compare(image, args.rows, sys.stdout, args.show_values)
    bad = sum(s.mismatches + s.errors + len(s.missing_inputs) for s in summary.values())
    return 1 if bad or not summary else 0


def run_lookup(image, args):
    pairs = read_function(image, args.table, args.width, args.signed_x)
    for x in args.x or required_inputs(pairs, args.width):
        y = lookup(pairs, x, args.width, args.signed_x, args.signed_y)
        print(f"{x:x}\t{y:x}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
