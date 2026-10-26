#!/usr/bin/env python3
"""Convert an EEC-IV image to the 256 KiB layout that EecLoadBanks.java and the run scripts use.

The 256 KiB layout is four 64 KiB blocks in bank order 0, 1, 8, 9. The bytes of each bank start
at offset 0x2000 in its block; this script fills offsets 0x0000-0x1fff of each block with a fill
byte (default 0xff), because these addresses are registers and RAM, not ROM.

Input files have one block per bank, in any bank order (--order gives the bank of each block in
file order). A block is either 64 KiB (the bank's bytes start at offset 0x2000 in the block) or
56 KiB (0xe000 bytes: the block holds addresses 0x2000-0xffff of the bank). The block size comes
from the file size and the number of banks in --order.

The report command helps to find the bank order. For each block it shows:
  * whether the block starts with the reset entry bytes ff fa (the code bank 8 starts there),
  * the first interrupt vectors (the words at 0x2010-0x205f),
  * each ROMBK n prefix (10 0n) followed by LCALL (ef), its 16-bit target, and the blocks that
    have PUSHP (f2) at that target. Ford's cross-bank subroutines start with PUSHP (EEC-IV
    Software Manual 6-63), so a block that has PUSHP at most targets of ROMBK n is bank n.
Check the result: the report only lists evidence.

Usage:
  convert_banks.py convert INPUT OUTPUT --order 8,1,0,9 [--fill ff]
  convert_banks.py report INPUT [--blocks 4]

The report prints addresses and a few bytes of the image. Do not commit its output.

Uses the Python standard library only.
"""

import argparse
import sys

OUTPUT_BANKS = (0, 1, 8, 9)
LONG_BLOCK = 0x10000
SHORT_BLOCK = 0xe000
ROM_START = 0x2000
RESET_BYTES = b"\xff\xfa"
ROMBK = 0x10
LCALL = 0xef
PUSHP = 0xf2


def block_size(file_size, blocks):
    """The block size (0x10000 or 0xe000) of a file with this many blocks."""
    for size in (LONG_BLOCK, SHORT_BLOCK):
        if file_size == blocks * size:
            return size
    raise ValueError(f"{file_size} bytes is not {blocks} blocks of 0x10000 or 0xe000 bytes")


def rom_of_block(data, slot, size):
    """Addresses 0x2000-0xffff of the bank in block slot, as bytes."""
    start = slot * size + (ROM_START if size == LONG_BLOCK else 0)
    return data[start:start + LONG_BLOCK - ROM_START]


def convert(data, order, fill=0xff):
    """The 256 KiB image (banks 0, 1, 8, 9) of a file whose blocks have the banks in order."""
    for bank in order:
        if bank not in OUTPUT_BANKS:
            raise ValueError(f"bank {bank} is not one of {OUTPUT_BANKS}")
    if len(set(order)) != len(order):
        raise ValueError(f"a bank is given more than once in {order}")
    size = block_size(len(data), len(order))
    image = bytearray(bytes([fill]) * (len(OUTPUT_BANKS) * LONG_BLOCK))
    for slot, bank in enumerate(order):
        start = OUTPUT_BANKS.index(bank) * LONG_BLOCK + ROM_START
        image[start:start + LONG_BLOCK - ROM_START] = rom_of_block(data, slot, size)
    return bytes(image)


def report(data, blocks):
    """Evidence for the bank order: one dict for each block of the file."""
    size = block_size(len(data), blocks)
    roms = [rom_of_block(data, slot, size) for slot in range(blocks)]
    result = []
    for slot, rom in enumerate(roms):
        vectors = [int.from_bytes(rom[0x10 + 2 * i:0x12 + 2 * i], "little") for i in range(40)]
        calls = []
        for i in range(len(rom) - 4):
            if rom[i] == ROMBK and rom[i + 1] < 0x10 and rom[i + 2] == LCALL:
                offset = int.from_bytes(rom[i + 3:i + 5], "little")
                target = (ROM_START + i + 5 + offset) & 0xffff
                if target < ROM_START:
                    continue
                pushp = [s for s, other in enumerate(roms)
                         if other[target - ROM_START] == PUSHP]
                calls.append({"at": ROM_START + i, "bank": rom[i + 1], "target": target,
                              "pushp_in_blocks": pushp})
        result.append({"block": slot, "reset": rom[:2] == RESET_BYTES,
                       "reset_bytes": rom[:16].hex(" "), "vectors": vectors, "calls": calls})
    return result


def report_text(blocks):
    """The report as text."""
    lines = []
    for b in blocks:
        lines.append(f"block {b['block']}: reset entry bytes {b['reset_bytes']}"
                     f"{' (ff fa: reset entry)' if b['reset'] else ''}")
        lines.append("  vectors: " + " ".join(f"{v:04x}" for v in b["vectors"][:16]) + " ...")
        by_bank = {}
        for call in b["calls"]:
            by_bank.setdefault(call["bank"], []).append(call)
        for bank, calls in sorted(by_bank.items()):
            votes = {}
            for call in calls:
                for slot in call["pushp_in_blocks"]:
                    votes[slot] = votes.get(slot, 0) + 1
            counts = ", ".join(f"block {s}: {n}" for s, n in sorted(votes.items())) or "none"
            lines.append(f"  ROMBK {bank} + LCALL: {len(calls)} calls; PUSHP at the target in "
                         f"{counts}")
    return "\n".join(lines) + "\n"


def parse_order(text):
    return [int(part) for part in text.split(",")]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    conv = sub.add_parser("convert", help="write the 256 KiB image")
    conv.add_argument("input")
    conv.add_argument("output")
    conv.add_argument("--order", required=True, type=parse_order,
                      help="bank of each block in file order, for example 8,1,0,9")
    conv.add_argument("--fill", default="ff", type=lambda t: int(t, 16),
                      help="fill byte for 0x0000-0x1fff of each block (hex, default ff)")
    rep = sub.add_parser("report", help="show evidence for the bank order")
    rep.add_argument("input")
    rep.add_argument("--blocks", type=int, default=4, help="number of blocks (default 4)")
    args = parser.parse_args(argv)
    with open(args.input, "rb") as f:
        data = f.read()
    try:
        if args.command == "convert":
            image = convert(data, args.order, args.fill)
            with open(args.output, "wb") as f:
                f.write(image)
        else:
            sys.stdout.write(report_text(report(data, args.blocks)))
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
