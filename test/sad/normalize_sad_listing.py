#!/usr/bin/env python3
"""Turn a SAD listing into a tab-separated file.

SAD is the "Semi Automatic Disassembler" for Ford EEC-IV and EEC-V code (Intel 8061 and 8065).
This script reads the listing file that SAD writes (<name>_lst.txt) and writes one line per
listing row, with these columns:

    addr  kind  len  bytes  mnemonic  operands

See test/sad/README.md for the exact meaning of each column. Standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
from typing import Collection, Iterable, List, NamedTuple, Optional, Sequence

HEADER = ("addr", "kind", "len", "bytes", "mnemonic", "operands")

# Column where SAD 4.13 starts the pseudo source text on a row. Text that starts at or after this
# column is never an operand. (SAD pads: mnemonic at column 26, operands at 32, pseudo source at 49.)
PSEUDO_COLUMN = 49

# Addresses below this value are registers or RAM. They have no bank.
BANK_START = 0x2000
DEFAULT_BANK = 8

DATA_KINDS = {"byte": "byte", "word": "word", "vect": "vect"}

# Keywords that SAD prints in the mnemonic position for items that are not instructions
# and not byte, word or vect.
OTHER_KEYWORDS = {
    "???", "!inv!", "fill", "triple", "long", "text", "table", "func", "struct", "timer", "args", "xcode",
}

_HEX = "[0-9a-fA-F]"
ROW_RE = re.compile(r"^(?P<addr>%s{4,5}): (?P<bytes>%s{2}(?:,%s{2})*)(?P<rest>.*)$" % (_HEX, _HEX, _HEX))
FILL_RE = re.compile(
    r"^(?P<start>%s{4,5}) -> (?P<end>%s{4,5}) = 0x(?P<value>%s{1,2})\s+## fill ##" % (_HEX, _HEX, _HEX)
)
ADDRESS_START_RE = re.compile(r"^%s{4,5}(?::| ->)" % _HEX)


class ListingError(ValueError):
    """A line looks like a listing row but cannot be read."""


class Row(NamedTuple):
    addr: str
    kind: str
    length: int
    data: str
    mnemonic: str
    operands: str

    def fields(self) -> List[str]:
        return [self.addr, self.kind, str(self.length), self.data, self.mnemonic, self.operands]


def format_address(text: str, single_bank: int) -> str:
    """Return the 5 digit banked address, or the 4 digit address when it is below 0x2000."""
    value = int(text, 16)
    if len(text) == 5:
        return "%05x" % value
    if value < BANK_START:
        return "%04x" % value
    return "%05x" % (single_bank * 0x10000 + value)


def bank_of(addr: str) -> Optional[int]:
    """Bank number of a normalized address, or None when the address has no bank."""
    return int(addr, 16) >> 16 if len(addr) == 5 else None


def first_token(text: str, start_column: int, pseudo_column: int) -> str:
    """The first word of text when it starts before the pseudo source column, else an empty string."""
    stripped = text.lstrip()
    column = start_column + len(text) - len(stripped)
    if not stripped or column >= pseudo_column:
        return ""
    return stripped.split(None, 1)[0]


def classify(keyword: str) -> str:
    low = keyword.lower()
    if low in DATA_KINDS:
        return DATA_KINDS[low]
    if low in OTHER_KEYWORDS or low.startswith("#"):
        return "other"
    return "code"


def build_row(addr: str, byte_list: str, rest: str, line_start: int, pseudo_column: int) -> Row:
    """Make a row from the text that follows the address; line_start is the column of rest."""
    data = byte_list.replace(",", "").lower()
    text = rest.lstrip()
    keyword = text.split(None, 1)[0] if text else ""
    after_column = line_start + len(rest) - len(text) + len(keyword)
    operand = first_token(text[len(keyword):], after_column, pseudo_column)
    kind = classify(keyword) if keyword else "other"
    if kind == "code":
        return Row(addr, kind, len(data) // 2, data, keyword.lower(), operand)
    if kind == "other":
        return Row(addr, kind, len(data) // 2, data, "", " ".join(part for part in (keyword, operand) if part))
    return Row(addr, kind, len(data) // 2, data, "", operand)


def parse_row(match: "re.Match[str]", single_bank: int, pseudo_column: int) -> Row:
    addr = format_address(match.group("addr"), single_bank)
    line_start = len(match.group("addr")) + 2 + len(match.group("bytes"))
    return build_row(addr, match.group("bytes"), match.group("rest"), line_start, pseudo_column)


def parse_fill(match: "re.Match[str]", single_bank: int) -> Row:
    start = format_address(match.group("start"), single_bank)
    end = format_address(match.group("end"), single_bank)
    length = int(end, 16) - int(start, 16) + 1
    value = match.group("value").lower().zfill(2)
    return Row(start, "other", length, value * length, "", "fill 0x%s" % match.group("value").lower())


def parse_line(line: str, number: int, single_bank: int, pseudo_column: int) -> Optional[Row]:
    """Return the row for one listing line, or None for lines that are not rows."""
    row_match = ROW_RE.match(line)
    if row_match:
        return parse_row(row_match, single_bank, pseudo_column)
    fill_match = FILL_RE.match(line)
    if fill_match:
        return parse_fill(fill_match, single_bank)
    if ADDRESS_START_RE.match(line):
        raise ListingError("line %d: cannot read this row: %r" % (number, line))
    return None


def wanted(row: Row, only_banks: Optional[Collection[int]]) -> bool:
    if only_banks is None:
        return True
    return bank_of(row.addr) in only_banks


def normalize_lines(
    lines: Iterable[str],
    single_bank: int = DEFAULT_BANK,
    only_banks: Optional[Collection[int]] = None,
    pseudo_column: int = PSEUDO_COLUMN,
) -> List[Row]:
    """Read the lines of a SAD listing and return its rows in listing order."""
    rows = []
    for number, raw in enumerate(lines, 1):
        row = parse_line(raw.rstrip("\r\n"), number, single_bank, pseudo_column)
        if row is not None and wanted(row, only_banks):
            rows.append(row)
    return rows


def render_tsv(rows: Iterable[Row]) -> str:
    """The header line and one tab-separated line per row. Tabs inside a field become spaces."""
    lines = ["\t".join(HEADER)]
    for row in rows:
        lines.append("\t".join(field.replace("\t", " ") for field in row.fields()))
    return "\n".join(lines) + "\n"


def parse_bank(text: str) -> int:
    """A bank number: 0, 1, 8 or 9 (decimal, as SAD numbers them)."""
    if text not in ("0", "1", "8", "9"):
        raise argparse.ArgumentTypeError("bank must be 0, 1, 8 or 9, not %r" % text)
    return int(text)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Normalize a SAD listing into a tab-separated file.")
    parser.add_argument("listing", help="SAD listing file (<name>_lst.txt)")
    parser.add_argument("-o", "--output", help="output file (default: standard output)")
    parser.add_argument("--single-bank", type=parse_bank, default=DEFAULT_BANK, metavar="BANK",
                        help="bank for 4 digit addresses from 2000 up, as in a single bank listing (default 8)")
    parser.add_argument("--only-bank", type=parse_bank, action="append", metavar="BANK",
                        help="keep only rows in this bank; repeat for several banks (default: all rows)")
    parser.add_argument("--pseudo-column", type=int, default=PSEUDO_COLUMN, metavar="COLUMN",
                        help="column where SAD starts the pseudo source text (default %d)" % PSEUDO_COLUMN)
    return parser


def write_output(text: str, path: Optional[str]) -> None:
    if path is None:
        sys.stdout.write(text)
        return
    with open(path, "w", encoding="ascii", errors="replace", newline="\n") as handle:
        handle.write(text)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    only = set(args.only_bank) if args.only_bank else None
    try:
        with open(args.listing, encoding="latin-1", newline="") as handle:
            rows = normalize_lines(handle, args.single_bank, only, args.pseudo_column)
        write_output(render_tsv(rows), args.output)
    except (OSError, ListingError) as error:
        print("normalize_sad_listing: %s" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
