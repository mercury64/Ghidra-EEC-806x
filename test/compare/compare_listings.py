#!/usr/bin/env python3
"""Compare a Ghidra instruction listing with a SAD reference listing.

Both files use the comparison format: tab-separated, with the header

    addr  kind  len  bytes  mnemonic  operands

addr   lowercase hex without 0x. 4 digits for 0x0000-0x1fff and for single-bank listings,
       5 digits (bank * 0x10000 + 16-bit address) for banked addresses in multi-bank listings.
kind   code, byte, word, vect or other.
len    byte count, decimal.
bytes  lowercase hex, no separators.

Only rows of kind "code" are instructions. A code row whose mnemonic is "!inv!" is an
address where the tool found an opcode it could not decode; EecListing.java writes
Ghidra decode errors as kind "other" with mnemonic "!inv!".

The script reports, for the SAD code rows:
  match             Ghidra has an instruction at the same address with the same length
                    and the same mnemonic (after the synonyms below).
  mnemonic differs  same address and length, different mnemonic.
  length differs    same address and mnemonic, different length.
  both differ       same address, different length and mnemonic.
  ghidra invalid    Ghidra reports a decode error at that address.
  inside other      the address is inside a Ghidra instruction that starts earlier.
  not disassembled  no Ghidra instruction covers the address.
and the Ghidra instructions that are not at a SAD code address. Coverage is the share of
SAD code rows where Ghidra has an instruction that starts at the same address.

Addresses: in a single-bank file, addresses at 0x2000 and above are read as bank
--single-bank (default 8), so that a single-bank file and a multi-bank file can be
compared.

Representation rules (see test/RESULTS.md):
  * Mnemonics are compared in lowercase after mapping the Ghidra names in
    GHIDRA_TO_SAD to the SAD names.
  * A ROM bank prefix (opcode 10, then a bank byte) is its own 2-byte row in both
    listings: SAD prints "rombk n", the 8065 Ghidra language decodes it as the
    instruction ROMBK, which sets the bank for the next instruction only.
  * SKIP (opcode 00) is a 2-byte no-operation on the CPU. Both tools list it as a 1-byte
    instruction that jumps over the next byte, because code can also jump to that byte.

Operands are compared after normalize_operands() as a separate, secondary figure.

Uses the Python standard library only.
"""

import argparse
import collections
import re
import sys

HEADER = ["addr", "kind", "len", "bytes", "mnemonic", "operands"]

# Ghidra mnemonic (lowercase) -> SAD mnemonic. Names that are the same in both tools
# are not listed.
GHIDRA_TO_SAD = {
    "clrc": "clc",      # clear carry
    "setc": "stc",      # set carry
    "divu": "divw",     # unsigned divide, long by word
    "divub": "divb",    # unsigned divide, word by byte
    "ext": "sexw",      # sign-extend word to long
    "extb": "sexb",     # sign-extend byte to word
    "inc": "incw",
    "neg": "negw",
    "jbc": "jnb",       # jump if bit clear
    "jbs": "jb",        # jump if bit set
    "jnh": "jleu",      # jump if not higher (C clear or Z set)
    "jh": "jgtu",       # jump if higher (C set and Z clear)
    "lcall": "call",
    "ljmp": "jump",
    "ldbse": "ldsbw",   # load byte, sign-extended to word
    "ldbze": "ldzbw",   # load byte, zero-extended to word
    "norml": "norm",
    "or": "orw",
    "xor": "xrw",
    "popw": "pop",
    "pushw": "push",
    "shra": "asrw",     # arithmetic shift right, word
    "shrab": "asrb",    # arithmetic shift right, byte
    "shral": "asrdw",   # arithmetic shift right, long
}

Row = collections.namedtuple("Row", "addr kind length data mnemonic operands")


def read_listing(path):
    """Read a listing. Returns (rows, multibank)."""
    rows, multibank = [], False
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        if header != HEADER:
            sys.exit("%s: header must be %s" % (path, "\\t".join(HEADER)))
        for number, line in enumerate(f, start=2):
            cols = line.rstrip("\n").split("\t")
            if len(cols) != len(HEADER):
                sys.exit("%s:%d: expected %d columns" % (path, number, len(HEADER)))
            multibank = multibank or len(cols[0]) == 5
            rows.append(Row(int(cols[0], 16), cols[1], int(cols[2]), cols[3],
                            cols[4].lower(), cols[5]))
    return rows, multibank


def rebank(rows, bank):
    """Move single-bank ROM addresses (0x2000 and above) into the given bank."""
    return [r._replace(addr=r.addr + bank * 0x10000) if r.addr >= 0x2000 else r
            for r in rows]


def in_bank(rows, bank):
    return [r for r in rows if r.addr < 0x2000 or (r.addr >> 16) == bank]


def canonical(mnemonic):
    return GHIDRA_TO_SAD.get(mnemonic, mnemonic)


def is_code(row):
    return row.kind == "code" and row.mnemonic != "!inv!"


def is_invalid(row):
    return row.mnemonic == "!inv!"


class GhidraIndex:
    """Ghidra rows by start address, plus a byte map for 'inside another instruction'."""

    def __init__(self, rows):
        self.code = {r.addr: r for r in rows if is_code(r)}
        self.invalid = {r.addr for r in rows if is_invalid(r)}
        self.owner = {}
        for r in self.code.values():
            for a in range(r.addr + 1, r.addr + r.length):
                self.owner[a] = r.addr


def classify_sad_row(row, gidx):
    """Return (category, ghidra row or None) for one SAD code row."""
    g = gidx.code.get(row.addr)
    if g is None:
        if row.addr in gidx.invalid:
            return "ghidra invalid", None
        if row.addr in gidx.owner:
            return "inside other", gidx.code[gidx.owner[row.addr]]
        return "not disassembled", None
    name_ok = canonical(g.mnemonic) == row.mnemonic
    len_ok = row.length == g.length
    if name_ok and len_ok:
        return "match", g
    if name_ok:
        return "length differs", g
    return ("mnemonic differs" if len_ok else "both differ"), g


def sad_byte_map(rows):
    """Map each byte address in the SAD listing to 'code' or 'data'."""
    kinds = {}
    for r in rows:
        kind = "code" if is_code(r) else "data"
        for a in range(r.addr, r.addr + max(r.length, 1)):
            kinds.setdefault(a, kind)
    return kinds


def classify_extra(g, sad_kinds):
    """Category for a Ghidra instruction that does not start at a SAD code address."""
    covered = [sad_kinds.get(a) for a in range(g.addr, g.addr + g.length)]
    if "code" in covered:
        return "overlaps SAD code at another start"
    if "data" in covered:
        return "on bytes SAD shows as data"
    return "outside the SAD listing"


HEX = re.compile(r"\b(?:0x)?([0-9a-f]+)\b")

# Names that the Ghidra languages give to special function registers, and their addresses.
# normalize_operands() replaces them with the address, because SAD prints R<address>.
REGISTER_NAMES_8061 = {
    # byte names
    "zrlo": 0x0, "zrhi": 0x1, "lsoport": 0x2, "bidirport": 0x3, "ad_resultlo": 0x4,
    "ad_resulthi": 0x5, "mstriotmrlo": 0x6, "mstriotmrhi": 0x7, "int_mask": 0x8,
    "int_pend": 0x9, "iostatus": 0xa, "inputsample": 0xb, "hsi_mask": 0xc,
    "hsi_holding": 0xd, "hsi_timelo": 0xe, "hsi_timehi": 0xf, "stackptrlo": 0x10,
    "stackptrhi": 0x11,
    # word names
    "zr": 0x0, "bidirection": 0x2, "ad_result": 0x4, "masteriotmr": 0x6, "interrupt": 0x8,
    "input": 0xa, "hsi_data": 0xc, "hsi_time": 0xe, "stack": 0x10,
}
REGISTER_NAMES_8065 = {
    # byte names
    "zrlo": 0x0, "zrhi": 0x1, "lso_port": 0x2, "lio_port": 0x3, "ad_low": 0x4,
    "ad_high": 0x5, "ad_timed_lo": 0x7, "int_mask": 0x8, "int_pend": 0x9, "io_status": 0xa,
    "hsi_sample": 0xb, "hsi_mask": 0xc, "hsi_data": 0xd, "ad_timed_hi": 0xf,
    "bank_select": 0x11, "io_timer_hi": 0x13, "lssi_a": 0x15, "lssi_b": 0x17,
    "lssi_c": 0x19, "hsi_mode": 0x1a, "hso_usedcnt": 0x1b, "lssi_d": 0x1d,
    "hso_lastused": 0x1e, "hso_slotsel": 0x1f,
    # word names
    "zr": 0x0, "io_timer": 0x6, "hsi_time": 0xe, "hso_intpend1": 0x10, "hso_intmask1": 0x12,
    "hso_intpend2": 0x14, "hso_intmask2": 0x16, "hso_status": 0x18, "hso_timecnt": 0x1c,
    "stackptr": 0x20, "stackptr2": 0x22,
}
REGISTER_NAMES = REGISTER_NAMES_8061


def name_pattern(names):
    return re.compile(r"\b(" + "|".join(sorted(names, key=len, reverse=True)) + r")\b")


REGISTER_NAME = name_pattern(REGISTER_NAMES)


def use_register_names(processor):
    """Select the register names of the language that made the Ghidra listing."""
    global REGISTER_NAMES, REGISTER_NAME
    REGISTER_NAMES = REGISTER_NAMES_8065 if processor == "8065" else REGISTER_NAMES_8061
    REGISTER_NAME = name_pattern(REGISTER_NAMES)

# SAD mnemonics whose last operand is a jump or call target.
JUMPS = {"sjmp", "scall", "jump", "call", "djnz", "jb", "jnb", "jnst", "jst", "jnc", "jc",
         "jnv", "jv", "jnvt", "jvt", "jgtu", "jleu", "jgt", "jle", "jge", "jlt", "je", "jne",
         "calla", "sclla"}


def jump_target(text):
    """The last operand as a number, or None."""
    last = text.lower().replace("#", "").split(",")[-1].strip()
    last = last[2:] if last.startswith("0x") else last
    try:
        return int(last, 16)
    except ValueError:
        return None


def normalize_operands(mnemonic, text, strip_bank):
    """Reduce SAD and Ghidra operand text to a common form (best effort)."""
    t = text.lower().replace(" ", "").replace("#", "").replace("0x", "")
    t = REGISTER_NAME.sub(lambda m: "%x" % REGISTER_NAMES[m.group(1)], t)
    # Ghidra shows signed 8-bit fields as negative numbers; SAD shows the unsigned byte.
    t = re.sub(r"-([0-9a-f]+)", lambda m: "%x" % (-int(m.group(1), 16) & 0xFF), t)
    t = re.sub(r"\[([0-9a-f]+)\]\+", r"[\1++]", t)                     # Ghidra [x]+
    t = re.sub(r"([0-9a-f]+)\[([0-9a-f]+)\]", r"[\2+\1]", t)            # Ghidra d[x]
    t = re.sub(r"\[r0\+([0-9a-f]+)\]", r"[\1]", t)                     # SAD [R0+x]
    # Ghidra shows the bank in an absolute address ([0x083000]); SAD shows [R0+3000].
    t = re.sub(r"\[0*([0-9a-f])([0-9a-f]{4})\]", r"[\2]", t)
    t = re.sub(r"\br([0-9a-f]+)", r"\1", t)                            # SAD R30
    t = re.sub(r"\[([0-9a-f]+)\+0\]", r"[\1]", t)
    if mnemonic in ("jb", "jnb"):
        t = re.sub(r"^b([0-7]),([0-9a-f]+),", r"\2,\1,", t)           # SAD B6,R15,...
    t = HEX.sub(lambda m: _number(m.group(1), strip_bank), t)
    return t


def _number(digits, strip_bank):
    value = int(digits, 16)
    if strip_bank and len(digits.lstrip("0")) == 5:
        value &= 0xFFFF
    return "%x" % value


def compare(sad_rows, ghidra_rows, strip_bank):
    """Return a dict of counters and examples."""
    gidx = GhidraIndex(ghidra_rows)
    sad_code = [r for r in sad_rows if is_code(r)]
    sad_code_addrs = {r.addr for r in sad_code}
    result = {
        "categories": collections.Counter(),
        "pairs": collections.Counter(),
        "examples": collections.defaultdict(list),
        "operands": collections.Counter(),
        "targets": collections.Counter(),
        "operand_pairs": collections.Counter(),
        "extras": collections.Counter(),
        "extra_names": collections.Counter(),
        "sad_mnemonics": collections.Counter(r.mnemonic for r in sad_code),
        "sad_code": len(sad_code),
        "sad_invalid": sum(1 for r in sad_rows if is_invalid(r)),
        "ghidra_code": len(gidx.code),
        "ghidra_invalid": len(gidx.invalid),
    }
    for row in sad_code:
        record_sad_row(result, row, gidx, strip_bank)
    record_extras(result, gidx, sad_code_addrs, sad_byte_map(sad_rows))
    return result


def record_sad_row(result, row, gidx, strip_bank):
    category, g = classify_sad_row(row, gidx)
    result["categories"][category] += 1
    gname = canonical(g.mnemonic) if g else "-"
    if category != "match":
        result["pairs"][(category, row.mnemonic, gname)] += 1
        result["examples"][category].append((row, g))
        return
    if row.mnemonic in JUMPS:
        record_jump_target(result, row, g, strip_bank)
    sad_text = row.operands
    if row.mnemonic == "rombk" and sad_text.isdigit():
        sad_text = "%x" % int(sad_text)        # SAD prints the bank number in decimal
    sad_ops = normalize_operands(row.mnemonic, sad_text, strip_bank)
    gh_ops = normalize_operands(row.mnemonic, g.operands, strip_bank)
    same = sad_ops == gh_ops
    result["operands"]["same" if same else "different"] += 1
    if not same:
        result["operand_pairs"][row.mnemonic] += 1
        result["examples"]["operands differ"].append((row, g))


def record_jump_target(result, row, g, strip_bank):
    sad_t, gh_t = jump_target(row.operands), jump_target(g.operands)
    if sad_t is not None and gh_t is not None and strip_bank:
        sad_t, gh_t = sad_t & 0xFFFF, gh_t & 0xFFFF
    same = sad_t is not None and sad_t == gh_t
    result["targets"]["same" if same else "different"] += 1
    if not same:
        result["examples"]["jump target differs"].append((row, g))


def record_extras(result, gidx, sad_code_addrs, sad_kinds):
    for addr, g in sorted(gidx.code.items()):
        if addr in sad_code_addrs:
            continue
        category = classify_extra(g, sad_kinds)
        result["extras"][category] += 1
        result["extra_names"][canonical(g.mnemonic)] += 1
        result["examples"]["ghidra only: " + category].append((None, g))


def pct(part, whole):
    return "%.2f%%" % (100.0 * part / whole) if whole else "n/a"


CATEGORY_ORDER = ["match", "mnemonic differs", "length differs", "both differ",
                  "ghidra invalid", "inside other", "not disassembled"]


def summary_lines(result, title, top):
    """The summary: numbers and instruction names only, no bytes."""
    cats, total = result["categories"], result["sad_code"]
    covered = sum(cats[c] for c in CATEGORY_ORDER[:4])
    out = ["## " + title, "",
           "| Figure | Count | Share of SAD code rows |", "|---|---:|---:|",
           "| SAD code rows | %d | |" % total]
    out += ["| %s | %d | %s |" % (c, cats[c], pct(cats[c], total)) for c in CATEGORY_ORDER]
    out += ["| coverage (Ghidra instruction starts at the SAD address) | %d | %s |"
            % (covered, pct(covered, total)), "",
            "SAD rows marked invalid: %d. Ghidra instructions: %d. Ghidra decode errors: %d."
            % (result["sad_invalid"], result["ghidra_code"], result["ghidra_invalid"]), ""]
    out += operand_lines(result)
    out += pair_lines(result, top) + extra_lines(result, top)
    return out


def operand_lines(result):
    ops, tgt = result["operands"], result["targets"]
    matched, jumps = ops["same"] + ops["different"], tgt["same"] + tgt["different"]
    return ["Jump and call targets of matching instructions: %d of %d the same (%s)."
            % (tgt["same"], jumps, pct(tgt["same"], jumps)),
            "Operands of matching instructions, after normalization: %d of %d the same (%s)."
            % (ops["same"], matched, pct(ops["same"], matched)), ""]


def pair_lines(result, top):
    if not result["pairs"]:
        return []
    out = ["Differences by SAD mnemonic and Ghidra mnemonic (top %d):" % top, "",
           "| Category | SAD | Ghidra | Count |", "|---|---|---|---:|"]
    for (cat, sad, gh), n in result["pairs"].most_common(top):
        out.append("| %s | %s | %s | %d |" % (cat, sad, gh, n))
    return out + [""]


def extra_lines(result, top):
    extras = result["extras"]
    out = ["Ghidra instructions that do not start at a SAD code address: %d."
           % sum(extras.values()), ""]
    out += ["- %s: %d" % (cat, n) for cat, n in sorted(extras.items())]
    names = ", ".join("%s %d" % (m, n) for m, n in result["extra_names"].most_common(top))
    return out + (["- most common mnemonics: " + names] if names else []) + [""]


def write_details(path, result):
    """All non-matching rows, with bytes, for local inspection only."""
    with open(path, "w", encoding="utf-8") as f:
        f.write("category\taddr\tsad_len\tsad_bytes\tsad_text\tghidra_addr\tghidra_len"
                "\tghidra_bytes\tghidra_text\n")
        for category, items in sorted(result["examples"].items()):
            for sad, g in items:
                f.write("\t".join([category] + row_fields(sad) + row_fields(g)) + "\n")


def row_fields(r):
    if r is None:
        return ["", "", "", ""]
    return ["%x" % r.addr, str(r.length), r.data, (r.mnemonic + " " + r.operands).strip()]


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--sad", required=True, help="SAD reference listing")
    p.add_argument("--ghidra", required=True, help="Ghidra listing")
    p.add_argument("--bank", type=int, help="compare only this bank (after rebanking)")
    p.add_argument("--single-bank", type=int, default=8,
                   help="bank of the ROM addresses in a single-bank file")
    p.add_argument("--names", choices=["8061", "8065"],
                   help="register names of the Ghidra language (default: 8065 if the "
                        "Ghidra listing is multi-bank, else 8061)")
    p.add_argument("--title", default="Ghidra compared with SAD")
    p.add_argument("--top", type=int, default=25, help="rows in the difference lists")
    p.add_argument("--report", help="also write the summary to this file")
    p.add_argument("--details", help="write every non-matching row to this TSV file")
    a = p.parse_args(argv)
    sad, sad_multi = read_listing(a.sad)
    gh, gh_multi = read_listing(a.ghidra)
    use_register_names(a.names or ("8065" if gh_multi else "8061"))
    if not sad_multi:
        sad = rebank(sad, a.single_bank)
    if not gh_multi:
        gh = rebank(gh, a.single_bank)
    if a.bank is not None:
        sad, gh = in_bank(sad, a.bank), in_bank(gh, a.bank)
    result = compare(sad, gh, strip_bank=sad_multi != gh_multi)
    text = "\n".join(summary_lines(result, a.title, a.top)) + "\n"
    sys.stdout.write(text)
    if a.report:
        with open(a.report, "w", encoding="utf-8") as f:
            f.write(text)
    if a.details:
        write_details(a.details, result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
