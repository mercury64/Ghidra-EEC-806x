#!/usr/bin/env python3
"""Check a tab-separated file made by normalize_sad_listing.py.

The checks find faults in a SAD build or in the normalized file:

  targets  The target of every relative branch (jb, jnb, djnz, the conditional jumps, sjmp,
           scall, jump, call) is calculated from the bytes of the row and compared with the
           target that SAD printed. A SAD build that has the shift fault of SAD 4.13 under
           clang prints wrong targets and fails this check.
  tiling   Rows must not overlap. Gaps are reported (SAD leaves a gap after an !INV! row).
  bytes    With --image: the bytes of every row must equal the bytes of the image.

Standard library only.
"""

from __future__ import annotations

import argparse
import csv
import sys
from typing import Dict, List, NamedTuple, Optional, Sequence, Tuple

BANKS = (0, 1, 8, 9)
BLOCK = 0x10000
MAX_SHOWN = 10

CONDITIONAL = {"jnst", "jst", "jnc", "jc", "jnv", "jv", "jnvt", "jvt",
               "jgtu", "jleu", "jgt", "jle", "jge", "jlt", "je", "jne"}
BIT_JUMPS = {"jnb", "jb"}
SHORT_JUMPS = {"sjmp", "scall"}
LONG_JUMPS = {"jump", "call"}


class Row(NamedTuple):
    addr: str
    kind: str
    length: int
    data: str
    mnemonic: str
    operands: str


class Problem(NamedTuple):
    check: str
    addr: str
    detail: str


class Gap(NamedTuple):
    start: int
    end: int


def read_rows(path: str) -> List[Row]:
    with open(path, newline="", encoding="ascii") as handle:
        reader = csv.reader(handle, delimiter="\t")
        header = next(reader, None)
        if header is None or header[:2] != ["addr", "kind"]:
            raise ValueError("%s: the first line is not the header of a normalized file" % path)
        return [Row(r[0], r[1], int(r[2]), r[3], r[4], r[5]) for r in reader]


def signed(value: int, bits: int) -> int:
    return value - (1 << bits) if value & (1 << (bits - 1)) else value


def relative_offset(row: Row) -> Optional[int]:
    """The offset from the start of the row to the branch target, or None if not a relative branch."""
    data = bytes.fromhex(row.data)
    name = row.mnemonic
    if name in CONDITIONAL and len(data) == 2:
        return 2 + signed(data[1], 8)
    if (name in BIT_JUMPS or name == "djnz") and len(data) == 3:
        return 3 + signed(data[2], 8)
    if name in SHORT_JUMPS and len(data) == 2:
        return 2 + signed(((data[0] & 7) << 8) | data[1], 11)
    if name in LONG_JUMPS and len(data) == 3:
        return 3 + signed(data[1] | (data[2] << 8), 16)
    return None


def target_bank(row: Row, previous: Optional[Row]) -> int:
    """The bank of the branch target: the bank of the row, or the bank of a bank prefix row before it."""
    address = int(row.addr, 16)
    if previous is not None and previous.mnemonic == "rombk" and int(previous.addr, 16) + 2 == address:
        return int(previous.operands)
    return address >> 16


def expected_target(row: Row, previous: Optional[Row]) -> Tuple[int, int]:
    """(bank, address) that the bytes of the branch row give."""
    offset = relative_offset(row)
    return target_bank(row, previous), ((int(row.addr, 16) & 0xFFFF) + offset) & 0xFFFF


def printed_target_matches(printed: str, bank: int, address: int) -> bool:
    try:
        value = int(printed, 16)
    except ValueError:
        return False
    if len(printed) == 5:
        return value == (bank << 16) | address
    return value == address


def check_targets(rows: Sequence[Row]) -> List[Problem]:
    problems = []
    for index, row in enumerate(rows):
        if row.kind != "code" or relative_offset(row) is None:
            continue
        previous = rows[index - 1] if index else None
        bank, address = expected_target(row, previous)
        printed = row.operands.split(",")[-1]
        if not printed_target_matches(printed, bank, address):
            detail = "printed %r, bytes give %05x" % (printed, (bank << 16) | address)
            problems.append(Problem("targets", row.addr, detail))
    return problems


def count_branch_rows(rows: Sequence[Row]) -> int:
    return sum(1 for row in rows if row.kind == "code" and relative_offset(row) is not None)


def count_next_instruction_targets(rows: Sequence[Row]) -> int:
    """Branches whose target is the next instruction (an offset equal to the length of the row)."""
    return sum(1 for row in rows if row.kind == "code" and relative_offset(row) == row.length)


def check_tiling(rows: Sequence[Row]) -> Tuple[List[Problem], List[Gap]]:
    overlaps: List[Problem] = []
    gaps: List[Gap] = []
    for before, after in zip(rows, rows[1:]):
        end = int(before.addr, 16) + before.length
        start = int(after.addr, 16)
        if start < end:
            overlaps.append(Problem("tiling", after.addr, "starts before the previous row ends (%05x)" % end))
        elif start > end:
            gaps.append(Gap(end, start - 1))
    return overlaps, gaps


def image_slice(row: Row, image: bytes, offsets: Dict[int, int]) -> Optional[bytes]:
    address = int(row.addr, 16)
    bank = address >> 16
    if bank not in offsets:
        return None
    start = offsets[bank] + (address & 0xFFFF)
    if start < 0:
        return None
    return image[start:start + row.length]


def check_bytes(rows: Sequence[Row], image: bytes, offsets: Dict[int, int]) -> List[Problem]:
    problems = []
    for row in rows:
        if len(row.addr) != 5:
            continue
        if (int(row.addr, 16) >> 16) not in offsets:
            problems.append(Problem("bytes", row.addr, "no file offset is given for bank %d" % (int(row.addr, 16) >> 16)))
            continue
        want = image_slice(row, image, offsets)
        if want is None or want.hex() != row.data:
            shown = "outside the file" if want is None else want.hex()[:32]
            problems.append(Problem("bytes", row.addr, "row has %s, image has %s" % (row.data[:32], shown)))
    return problems


def offsets_from_blocks(text: str) -> Dict[int, int]:
    """File offset of address 0 of each bank, for 64 KiB blocks in the given bank order."""
    banks = [int(part) for part in text.split(",")]
    if any(bank not in BANKS for bank in banks):
        raise ValueError("banks must be 0, 1, 8 or 9: %r" % text)
    return {bank: index * BLOCK for index, bank in enumerate(banks)}


def parse_bank_offset(text: str) -> Tuple[int, int]:
    """BANK=OFFSET, the offset in hex (may be negative): the file offset of address 0 of the bank."""
    bank_text, _, offset_text = text.partition("=")
    bank = int(bank_text)
    if bank not in BANKS or not offset_text:
        raise ValueError("expected BANK=OFFSET with a bank of 0, 1, 8 or 9: %r" % text)
    return bank, int(offset_text, 16)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Check a normalized SAD reference file.")
    parser.add_argument("tsv", help="file made by normalize_sad_listing.py")
    parser.add_argument("--image", help="program image; compare the bytes of every row with it")
    parser.add_argument("--blocks", metavar="BANKS",
                        help="the image is made of 64 KiB blocks in this bank order, for example 0,1,8,9")
    parser.add_argument("--bank-offset", action="append", default=[], metavar="BANK=OFFSET",
                        help="file offset (hex, may be negative) of address 0 of a bank; repeat for more banks")
    return parser


def image_offsets(args: argparse.Namespace) -> Dict[int, int]:
    offsets = offsets_from_blocks(args.blocks) if args.blocks else {}
    offsets.update(dict(parse_bank_offset(item) for item in args.bank_offset))
    return offsets


def report(title: str, problems: Sequence[Problem]) -> None:
    for problem in problems[:MAX_SHOWN]:
        print("  %s: %s %s" % (problem.check, problem.addr, problem.detail))
    if len(problems) > MAX_SHOWN:
        print("  ... %d more %s problems" % (len(problems) - MAX_SHOWN, title))


def run_checks(rows: List[Row], args: argparse.Namespace) -> int:
    errors = 0
    target_problems = check_targets(rows)
    print("branch rows checked: %d, wrong targets: %d, targets that equal the next instruction: %d"
          % (count_branch_rows(rows), len(target_problems), count_next_instruction_targets(rows)))
    overlaps, gaps = check_tiling(rows)
    print("rows: %d, overlaps: %d, gaps: %d (%d bytes)"
          % (len(rows), len(overlaps), len(gaps), sum(g.end - g.start + 1 for g in gaps)))
    report("target", target_problems)
    report("overlap", overlaps)
    errors += len(target_problems) + len(overlaps)
    if args.image:
        with open(args.image, "rb") as handle:
            image = handle.read()
        byte_problems = check_bytes(rows, image, image_offsets(args))
        print("rows compared with the image: %d, different: %d" % (len(rows), len(byte_problems)))
        report("byte", byte_problems)
        errors += len(byte_problems)
    return errors


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        rows = read_rows(args.tsv)
        return 1 if run_checks(rows, args) else 0
    except (OSError, ValueError) as error:
        print("verify_reference: %s" % error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
