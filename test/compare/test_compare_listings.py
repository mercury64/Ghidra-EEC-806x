#!/usr/bin/env python3
"""Unit tests for compare_listings.py. All listing rows here are invented.

Run from the repository root:  python3 -m unittest discover -s test/compare
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare_listings as cl  # noqa: E402

HEADER = "addr\tkind\tlen\tbytes\tmnemonic\toperands\n"
TEMP_FILES = []


def listing(*rows):
    """Write rows (tuples of 6 strings) to a temporary file and return its path."""
    f = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False, encoding="utf-8")
    f.write(HEADER)
    for row in rows:
        f.write("\t".join(row) + "\n")
    f.close()
    TEMP_FILES.append(f.name)
    return f.name


def tearDownModule():
    for path in TEMP_FILES:
        os.remove(path)


def run(sad_rows, ghidra_rows, multibank_names=False):
    sad, sad_multi = cl.read_listing(listing(*sad_rows))
    gh, gh_multi = cl.read_listing(listing(*ghidra_rows))
    cl.use_register_names("8065" if multibank_names else "8061")
    if not sad_multi:
        sad = cl.rebank(sad, 8)
    if not gh_multi:
        gh = cl.rebank(gh, 8)
    return cl.compare(sad, gh, strip_bank=sad_multi != gh_multi)


class ReadListingTest(unittest.TestCase):
    def test_reads_rows_and_detects_banked_addresses(self):
        rows, multi = cl.read_listing(listing(("83000", "code", "1", "f0", "RET", "")))
        self.assertTrue(multi)
        self.assertEqual(rows[0].addr, 0x83000)
        self.assertEqual(rows[0].mnemonic, "ret")

    def test_single_bank_addresses_move_to_bank_8(self):
        rows, multi = cl.read_listing(listing(("3000", "code", "1", "f0", "ret", ""),
                                              ("0100", "byte", "1", "00", "", "0")))
        self.assertFalse(multi)
        moved = cl.rebank(rows, 8)
        self.assertEqual([r.addr for r in moved], [0x83000, 0x0100])


class ClassifyTest(unittest.TestCase):
    def test_match_with_synonym(self):
        result = run([("83000", "code", "3", "ef0010", "call", "84003")],
                     [("83000", "code", "3", "ef0010", "lcall", "0x084003")])
        self.assertEqual(result["categories"]["match"], 1)
        self.assertEqual(result["targets"]["same"], 1)

    def test_length_and_mnemonic_differences(self):
        result = run([("83000", "code", "4", "fe6c3032", "sml2w", "R32,R30"),
                      ("83010", "code", "2", "0130", "clrw", "R30")],
                     [("83000", "code", "1", "fe", "signds", ""),
                      ("83010", "code", "2", "0130", "cplw", "0x000030")])
        self.assertEqual(result["categories"]["both differ"], 1)
        self.assertEqual(result["categories"]["mnemonic differs"], 1)

    def test_inside_other_invalid_and_not_disassembled(self):
        result = run([("83001", "code", "1", "fa", "di", ""),
                      ("83010", "code", "1", "f4", "regbk", "0"),
                      ("83020", "code", "1", "fb", "ei", "")],
                     [("83000", "code", "3", "a13000", "ldw", "0x000030,#0x0"),
                      ("83010", "other", "1", "f4", "!inv!", "Unable to resolve")])
        cats = result["categories"]
        self.assertEqual(cats["inside other"], 1)
        self.assertEqual(cats["ghidra invalid"], 1)
        self.assertEqual(cats["not disassembled"], 1)

    def test_ghidra_only_instructions_are_counted_by_kind(self):
        result = run([("83000", "other", "4", "00000000", "", "???")],
                     [("83000", "code", "1", "f0", "ret", "")])
        self.assertEqual(result["extras"]["on bytes SAD shows as data"], 1)

    def test_single_bank_ghidra_against_multibank_sad(self):
        result = run([("82000", "code", "2", "27fe", "sjmp", "82000")],
                     [("2000", "code", "2", "27fe", "sjmp", "0x2000")])
        self.assertEqual(result["categories"]["match"], 1)
        self.assertEqual(result["targets"]["same"], 1)


class NormalizeOperandsTest(unittest.TestCase):
    def norm(self, mnemonic, text, names="8065"):
        cl.use_register_names(names)
        return cl.normalize_operands(mnemonic, text, False)

    def test_register_names_and_prefixes(self):
        self.assertEqual(self.norm("ldb", "BANK_Select,#0x01"), self.norm("ldb", "R11,1"))
        self.assertEqual(self.norm("ldw", "0x000030,StackPtr"), self.norm("ldw", "R30,R20"))

    def test_indexed_indirect_and_signed_offsets(self):
        self.assertEqual(self.norm("stw", "0x00003c,-0x10[0x0000de]"),
                         self.norm("stw", "R3c,[Rde+f0]"))
        self.assertEqual(self.norm("ldw", "0x00002c,[0x000024]+"),
                         self.norm("ldw", "R2c,[R24++]"))

    def test_absolute_address_without_bank(self):
        self.assertEqual(self.norm("ldb", "0x00002a,[0x083000]"),
                         self.norm("ldb", "R2a,[R0+3000]"))

    def test_bit_jump_operand_order(self):
        self.assertEqual(self.norm("jb", "0x0000d6,0x5,0x08213b"),
                         self.norm("jb", "B5,Rd6,8213b"))


class SummaryTest(unittest.TestCase):
    def test_summary_has_no_bytes(self):
        result = run([("83000", "code", "3", "a1cafe", "ldw", "R30,feca")],
                     [("83000", "code", "3", "a1cafe", "ldw", "0x000030,#0xfeca")])
        text = "\n".join(cl.summary_lines(result, "t", 5))
        self.assertNotIn("a1cafe", text)
        self.assertIn("| match | 1 | 100.00% |", text)


if __name__ == "__main__":
    unittest.main()
