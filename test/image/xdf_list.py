#!/usr/bin/env python3
"""List the tables and constants of a TunerPro definition file (XDF) with their bank addresses.

An XDF is XML. Each XDFTABLE has an XDFAXIS with id "z" for its data; each XDFCONSTANT has one
value. EMBEDDEDDATA gives the layout:
  mmedaddress          file offset of the first element (plus BASEOFFSET in the header)
  mmedelementsizebits  8, 16 or 32
  mmedrowcount, mmedcolcount
  mmedmajorstridebits  distance between rows (0: the elements of a row are next to each other)
  mmedminorstridebits  distance between elements (0: the element size)
  mmedtypeflags        0x01 signed, 0x02 least significant byte first, 0x04 column-major order
The x and y axes of a table are listed only when they have their own data.

The bank address comes from the file layout: --block-size (0xe000 for blocks that hold
addresses 0x2000-0xffff of a bank, 0x10000 for 64 KiB blocks) and --order (the bank of each
block in file order). Addresses are bank * 0x10000 + 16-bit address, as in the 8065 language.

Usage:
  xdf_list.py XDF [--order 8,1,0,9] [--block-size e000] [--elements]

The output is tab-separated, one row per table or constant. It holds the titles and addresses
from the XDF: keep it out of git.

The parser refuses files with a DOCTYPE or ENTITY declaration. Uses the Python standard library
only.
"""

import argparse
import re
import sys
import xml.etree.ElementTree as ET

COLUMNS = ["kind", "uniqueid", "title", "categories", "offset", "address", "bits", "rows",
           "cols", "major_stride_bits", "minor_stride_bits", "signed", "lsb_first",
           "column_major", "size", "last_address", "equation", "axes"]


def _int(text, default=0):
    if text is None or text == "":
        return default
    return int(text, 0)


def _layout(data):
    """The layout fields of an EMBEDDEDDATA element."""
    flags = _int(data.get("mmedtypeflags"))
    return {
        "offset": _int(data.get("mmedaddress")),
        "bits": _int(data.get("mmedelementsizebits"), 8),
        "rows": max(1, _int(data.get("mmedrowcount"), 1)),
        "cols": max(1, _int(data.get("mmedcolcount"), 1)),
        "major_stride_bits": _int(data.get("mmedmajorstridebits")),
        "minor_stride_bits": _int(data.get("mmedminorstridebits")),
        "signed": bool(flags & 0x01),
        "lsb_first": bool(flags & 0x02),
        "column_major": bool(flags & 0x04),
        "flags": flags,
    }


def element_offsets(item):
    """File offsets of the elements, row by row (row 0 column 0, row 0 column 1, ...)."""
    size = item["bits"] // 8
    minor = item["minor_stride_bits"] // 8 or size
    rows, cols = item["rows"], item["cols"]
    offsets = []
    for r in range(rows):
        for c in range(cols):
            if item["column_major"]:
                major = item["major_stride_bits"] // 8 or rows * minor
                offsets.append(item["offset"] + c * major + r * minor)
            else:
                major = item["major_stride_bits"] // 8 or cols * minor
                offsets.append(item["offset"] + r * major + c * minor)
    return offsets


def last_offset(item):
    """File offset of the last byte of the last element."""
    return max(element_offsets(item)) + item["bits"] // 8 - 1


def bank_address(offset, block_size, order):
    """The banked address of a file offset, or None if it is past the blocks."""
    slot = offset // block_size
    if slot >= len(order):
        return None
    within = offset % block_size
    base = 0x2000 if block_size == 0xe000 else 0
    return (order[slot] << 16) + base + within


def parse(text, block_size=0xe000, order=(8, 1, 0, 9)):
    """The tables and constants of an XDF, in file order."""
    if re.search(r"<!(DOCTYPE|ENTITY)", text):
        raise ValueError("the XDF has a DOCTYPE or ENTITY declaration; not parsed")
    root = ET.fromstring(text)
    header = root.find("XDFHEADER")
    base = 0
    categories = {}
    if header is not None:
        b = header.find("BASEOFFSET")
        if b is not None:
            base = _int(b.get("offset")) * (-1 if _int(b.get("subtract")) else 1)
        for cat in header.findall("CATEGORY"):
            categories[_int(cat.get("index"))] = cat.get("name", "")
    items = []
    for element in root:
        if element.tag not in ("XDFTABLE", "XDFCONSTANT"):
            continue
        kind = "table" if element.tag == "XDFTABLE" else "constant"
        if kind == "table":
            z = next((a for a in element.findall("XDFAXIS") if a.get("id") == "z"), None)
            holder = z
        else:
            holder = element
        if holder is None or holder.find("EMBEDDEDDATA") is None:
            continue
        item = {"kind": kind, "uniqueid": element.get("uniqueid", ""),
                "title": (element.findtext("title") or "").strip()}
        item.update(_layout(holder.find("EMBEDDEDDATA")))
        item["offset"] += base
        math = holder.find("MATH")
        item["equation"] = math.get("equation", "") if math is not None else ""
        item["categories"] = [categories.get(_int(m.get("category")) - 1, m.get("category"))
                              for m in element.findall("CATEGORYMEM")]
        item["axes"] = []
        if kind == "table":
            for axis in element.findall("XDFAXIS"):
                if axis.get("id") == "z" or axis.find("EMBEDDEDDATA") is None:
                    continue
                link = axis.find("DALINK")
                if link is not None and link.get("objidhash"):
                    continue   # the axis takes its values from another object
                data = _layout(axis.find("EMBEDDEDDATA"))
                count = _int(axis.findtext("indexcount"), data["cols"])
                item["axes"].append((axis.get("id"), data["offset"] + base, data["bits"], count))
        item["address"] = bank_address(item["offset"], block_size, order)
        item["size"] = last_offset(item) - item["offset"] + 1
        last = bank_address(last_offset(item), block_size, order)
        item["last_address"] = last
        items.append(item)
    return items


def row_text(item):
    values = []
    for column in COLUMNS:
        v = item[column]
        if column in ("offset",):
            v = f"{v:x}"
        elif column in ("address", "last_address"):
            v = "" if v is None else f"{v:05x}"
        elif column == "categories":
            v = ";".join(v)
        elif column == "axes":
            v = ";".join(f"{a}:{o:x}:{b}:{n}" for a, o, b, n in v)
        elif isinstance(v, bool):
            v = "1" if v else "0"
        values.append(str(v).replace("\t", " "))
    return "\t".join(values)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("xdf")
    parser.add_argument("--order", default="8,1,0,9",
                        type=lambda t: [int(p) for p in t.split(",")],
                        help="bank of each block of the bin file, in file order (default 8,1,0,9)")
    parser.add_argument("--block-size", default="e000", type=lambda t: int(t, 16),
                        help="block size of the bin file in hex (default e000)")
    parser.add_argument("--elements", action="store_true",
                        help="also print the banked address of every element")
    args = parser.parse_args(argv)
    with open(args.xdf, encoding="utf-8", errors="replace") as f:
        text = f.read()
    try:
        items = parse(text, args.block_size, args.order)
    except (ValueError, ET.ParseError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    print("\t".join(COLUMNS))
    for item in items:
        print(row_text(item))
        if args.elements:
            addresses = [bank_address(o, args.block_size, args.order)
                         for o in element_offsets(item)]
            print("#\telements\t" + " ".join("?" if a is None else f"{a:05x}" for a in addresses))
    return 0


if __name__ == "__main__":
    sys.exit(main())
