"""Tests for normalize_sad_listing.py.

All listing text in this file is invented. It follows the column layout that SAD 4.13 prints
(mnemonic at column 26, operands at column 32, pseudo source at column 49), but it contains no
bytes from any real calibration or program image.

Run from the repository root:  python3 -m unittest discover -s test/sad
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import normalize_sad_listing as nsl  # noqa: E402

HEADER_LINE = "addr\tkind\tlen\tbytes\tmnemonic\toperands"


def _pad(text, column):
    """Pad like SAD: to the column, or add two spaces when the text already passed it."""
    if len(text) < column:
        return text + " " * (column - len(text))
    return text + "  "


def sad_row(address, byte_list, keyword, operands="", pseudo=""):
    """Format one listing row the way SAD 4.13 pads its columns."""
    line = _pad("%s: %s" % (address, byte_list), 26) + keyword
    line = _pad(line, 32) + operands
    if pseudo:
        line = _pad(line, 49) + pseudo
    return line


def fields(row):
    return (row.addr, row.kind, row.length, row.data, row.mnemonic, row.operands)


class InstructionRowTests(unittest.TestCase):
    def one(self, line, **options):
        rows = nsl.normalize_lines([line], **options)
        self.assertEqual(len(rows), 1, rows)
        return rows[0]

    def test_instruction_with_operands(self):
        row = self.one(sad_row("83002", "a1,34,12,30", "ldw", "R30,1234", "R30 = 1234;"))
        self.assertEqual(fields(row), ("83002", "code", 4, "a1341230", "ldw", "R30,1234"))

    def test_instruction_without_operands_drops_pseudo_source(self):
        row = self.one(sad_row("83001", "fa", "di", "", "interrupts OFF;"))
        self.assertEqual(fields(row), ("83001", "code", 1, "fa", "di", ""))

    def test_instruction_with_trailing_spaces_only(self):
        row = self.one(sad_row("83006", "88,2e,30", "cmpw", "R30,R2e") + " " * 10)
        self.assertEqual(fields(row), ("83006", "code", 3, "882e30", "cmpw", "R30,R2e"))

    def test_mnemonic_is_lowercase_and_operands_keep_their_case(self):
        row = self.one(sad_row("83010", "b3,f4,b6,04,6b", "LDB", "R6B,[Rf4+4B6]"))
        self.assertEqual((row.mnemonic, row.operands), ("ldb", "R6B,[Rf4+4B6]"))

    def test_operand_that_ends_one_column_before_the_pseudo_source(self):
        row = self.one(sad_row("83019", "4b,aa,12,34,56", "sb3w", "R56,R34,[Raa+12]", "R56 = R34 - [Raa+12];"))
        self.assertEqual(row.operands, "R56,R34,[Raa+12]")

    def test_operand_that_runs_past_the_pseudo_source_column(self):
        row = self.one(sad_row("8301e", "4b,aa,12,34,56", "sb3w", "R256,R234,[R2aa+12]", "R256 = R234 - [R2aa+12];"))
        self.assertEqual(row.operands, "R256,R234,[R2aa+12]")

    def test_pseudo_column_decides_where_the_operands_end(self):
        line = _pad("83000: fa", 26) + "di" + " " * 15 + "text here"          # first word at column 43
        self.assertEqual(self.one(line).operands, "text")
        self.assertEqual(self.one(line, pseudo_column=40).operands, "")

    def test_seven_byte_instruction_pushes_the_mnemonic_right(self):
        row = self.one(sad_row("83030", "fe,4f,01,02,03,04,05", "sml3w", "R30,R24,[Rfe+10]", "text;"))
        self.assertEqual(fields(row), ("83030", "code", 7, "fe4f0102030405", "sml3w", "R30,R24,[Rfe+10]"))

    def test_mnemonic_printed_directly_after_the_bytes(self):
        line = "4d00: fe,4f,01,02,03,04,05sml3w R30,R24,[Rfe+10]  slR30 = swR24 * [Rfe+10];"
        row = self.one(line)
        self.assertEqual(fields(row), ("84d00", "code", 7, "fe4f0102030405", "sml3w", "R30,R24,[Rfe+10]"))

    def test_bank_prefix_pair_is_its_own_row(self):
        row = self.one(sad_row("8300b", "10,08", "rombk", "8"))
        self.assertEqual(fields(row), ("8300b", "code", 2, "1008", "rombk", "8"))

    def test_register_bank_byte_row(self):
        row = self.one(sad_row("83013", "f4", "regbk", "0"))
        self.assertEqual(fields(row), ("83013", "code", 1, "f4", "regbk", "0"))


class DataRowTests(unittest.TestCase):
    def one(self, line, **options):
        rows = nsl.normalize_lines([line], **options)
        self.assertEqual(len(rows), 1, rows)
        return rows[0]

    def test_word_row_value_loses_its_alignment_padding(self):
        row = self.one(sad_row("83050", "12,34", "word", "%5x" % 0x3412))
        self.assertEqual(fields(row), ("83050", "word", 2, "1234", "", "3412"))

    def test_byte_row(self):
        row = self.one(sad_row("83052", "08", "byte", "%5x" % 8))
        self.assertEqual(fields(row), ("83052", "byte", 1, "08", "", "8"))

    def test_vect_row_drops_the_symbol_name(self):
        row = self.one(sad_row("83060", "72,30", "vect", "83072", "Sub_83072"))
        self.assertEqual(fields(row), ("83060", "vect", 2, "7230", "", "83072"))

    def test_unknown_bytes_row(self):
        row = self.one(sad_row("83040", "aa,bb,cc,dd,ee,ff", "???"))
        self.assertEqual(fields(row), ("83040", "other", 6, "aabbccddeeff", "", "???"))

    def test_invalid_opcode_row(self):
        row = self.one(sad_row("83020", "e2", "!INV!"))
        self.assertEqual(fields(row), ("83020", "other", 1, "e2", "", "!INV!"))

    def test_fill_range_expands_to_its_bytes(self):
        row = self.one("8bd20 -> 8bd25 = 0xff  ## fill ## ")
        self.assertEqual(fields(row), ("8bd20", "other", 6, "ff" * 6, "", "fill 0xff"))

    def test_fill_range_with_one_digit_value(self):
        row = self.one("02060 -> 02063 = 0x0  ## fill ## ")
        self.assertEqual(fields(row), ("02060", "other", 4, "00" * 4, "", "fill 0x0"))

    def test_single_bank_fill_range_gets_the_bank_prefix(self):
        row = self.one("bd20 -> bd23 = 0xff  ## fill ## ")
        self.assertEqual((row.addr, row.length), ("8bd20", 4))


class AddressTests(unittest.TestCase):
    def addr_of(self, text, **options):
        rows = nsl.normalize_lines([sad_row(text, "ff", "nop")], **options)
        return rows[0].addr

    def test_five_digit_addresses_are_kept(self):
        self.assertEqual(self.addr_of("12d6a"), "12d6a")

    def test_bank_zero_address_keeps_its_leading_zero(self):
        self.assertEqual(self.addr_of("02000"), "02000")

    def test_four_digit_address_from_2000_gets_bank_8(self):
        self.assertEqual(self.addr_of("2075"), "82075")

    def test_single_bank_option_selects_another_bank(self):
        self.assertEqual(self.addr_of("2075", single_bank=9), "92075")

    def test_address_below_2000_stays_four_digits(self):
        self.assertEqual(self.addr_of("1f80"), "1f80")

    def test_uppercase_hex_is_lowercased(self):
        self.assertEqual(self.addr_of("8ABC"), "88abc")


class ListingStructureTests(unittest.TestCase):
    LISTING = "\n".join([
        "################################################################################",
        "   SAD Version 4.0.13.10 (26 Jan 2025)",
        "################################################################################",
        "Appears to be 8065  4 banks ",
        "",
        "# Bank 8  file offset 22000-2ffff, 82000 - 8ffff  CODE/BOOT starts HERE",
        "",
        sad_row("83000", "ff", "nop"),
        "",
        "   Sub_83001:",
        sad_row("83001", "e0,2a,1f", "djnz", "R2a,83022", "R2a--;"),
        " " * 49 + "if (R2a != 0) goto 83022;",
        sad_row("83004", "f0", "ret", "", "return;"),
        " ##########   END of Listing   ##########",
    ])

    def test_only_address_rows_become_rows(self):
        rows = nsl.normalize_lines(self.LISTING.splitlines())
        self.assertEqual([r.addr for r in rows], ["83000", "83001", "83004"])

    def test_windows_line_endings_are_accepted(self):
        rows = nsl.normalize_lines(self.LISTING.replace("\n", "\r\n").splitlines(True))
        self.assertEqual([r.addr for r in rows], ["83000", "83001", "83004"])

    def test_row_like_line_that_cannot_be_read_is_an_error(self):
        with self.assertRaises(nsl.ListingError) as caught:
            nsl.normalize_lines(["", "83000: zz"])
        self.assertIn("line 2", str(caught.exception))

    def test_only_bank_keeps_the_requested_banks(self):
        lines = [
            sad_row("02000", "ff", "nop"),
            sad_row("82000", "ff", "nop"),
            sad_row("92000", "ff", "nop"),
            sad_row("1f80", "ff", "nop"),
        ]
        rows = nsl.normalize_lines(lines, only_banks={8, 9})
        self.assertEqual([r.addr for r in rows], ["82000", "92000"])

    def test_only_bank_applies_to_single_bank_addresses_too(self):
        rows = nsl.normalize_lines([sad_row("2000", "ff", "nop")], only_banks={8})
        self.assertEqual([r.addr for r in rows], ["82000"])
        rows = nsl.normalize_lines([sad_row("2000", "ff", "nop")], only_banks={1})
        self.assertEqual(rows, [])


class OutputTests(unittest.TestCase):
    def test_render_has_the_exact_header_and_tab_separated_rows(self):
        rows = nsl.normalize_lines([sad_row("83002", "a1,34,12,30", "ldw", "R30,1234", "R30 = 1234;")])
        text = nsl.render_tsv(rows)
        self.assertEqual(text, HEADER_LINE + "\n" + "83002\tcode\t4\ta1341230\tldw\tR30,1234\n")

    def test_render_of_no_rows_is_only_the_header(self):
        self.assertEqual(nsl.render_tsv([]), HEADER_LINE + "\n")

    def test_main_writes_the_output_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "listing.txt")
            target = os.path.join(tmp, "out.tsv")
            with open(source, "w") as handle:
                handle.write(sad_row("2000", "ff", "nop") + "\n" + sad_row("0100", "fa", "di") + "\n")
            self.assertEqual(nsl.main([source, "-o", target]), 0)
            with open(target) as handle:
                self.assertEqual(handle.read(), HEADER_LINE + "\n82000\tcode\t1\tff\tnop\t\n0100\tcode\t1\tfa\tdi\t\n")

    def test_main_reports_a_missing_input_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            with contextlib.redirect_stderr(io.StringIO()) as messages:
                code = nsl.main([os.path.join(tmp, "missing.txt"), "-o", os.path.join(tmp, "x.tsv")])
            self.assertEqual(code, 1)
            self.assertIn("missing.txt", messages.getvalue())

    def test_main_passes_the_bank_options_through(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = os.path.join(tmp, "listing.txt")
            target = os.path.join(tmp, "out.tsv")
            with open(source, "w") as handle:
                handle.write(sad_row("2000", "ff", "nop") + "\n")
            self.assertEqual(nsl.main([source, "-o", target, "--single-bank", "9", "--only-bank", "9"]), 0)
            with open(target) as handle:
                self.assertEqual(handle.read(), HEADER_LINE + "\n92000\tcode\t1\tff\tnop\t\n")


if __name__ == "__main__":
    unittest.main()
