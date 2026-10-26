"""Tests for verify_reference.py.

The rows and image bytes in this file are invented. They contain no bytes from any real image.

Run from the repository root:  python3 -m unittest discover -s test/sad
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import verify_reference as vr  # noqa: E402


def row(addr, data_hex, mnemonic="", operands="", kind="code"):
    return vr.Row(addr, kind, len(data_hex) // 2, data_hex, mnemonic, operands)


class BranchTargetTests(unittest.TestCase):
    def problems(self, *rows):
        return vr.check_targets(list(rows))

    def test_conditional_jump_backwards_is_correct(self):
        self.assertEqual(self.problems(row("83010", "d3f6", "jnc", "83008")), [])

    def test_conditional_jump_with_a_wrong_target_is_reported(self):
        found = self.problems(row("83010", "d3f6", "jnc", "83012"))
        self.assertEqual(len(found), 1)
        self.assertIn("83008", found[0].detail)

    def test_conditional_jump_forwards(self):
        self.assertEqual(self.problems(row("83010", "df20", "je", "83032")), [])

    def test_bit_jump_uses_the_last_operand(self):
        self.assertEqual(self.problems(row("83020", "32cd06", "jnb", "B2,Rcd,83029")), [])

    def test_decrement_and_jump(self):
        self.assertEqual(self.problems(row("83030", "e02a1f", "djnz", "R2a,83052")), [])

    def test_short_jump_has_an_11_bit_signed_offset(self):
        self.assertEqual(self.problems(row("83040", "27e7", "sjmp", "83029")), [])
        self.assertEqual(self.problems(row("83040", "2005", "sjmp", "83047")), [])

    def test_short_call(self):
        self.assertEqual(self.problems(row("83050", "28be", "scall", "83110")), [])

    def test_long_jump_and_long_call_have_a_16_bit_offset(self):
        self.assertEqual(self.problems(row("83060", "e7effe", "jump", "82f52")), [])
        self.assertEqual(self.problems(row("83070", "efd039", "call", "86a43")), [])

    def test_single_bank_operand_is_compared_without_the_bank_digit(self):
        self.assertEqual(self.problems(row("83010", "d3f6", "jnc", "3008")), [])

    def test_bank_prefix_row_sets_the_bank_of_the_target(self):
        prefix = row("83080", "1001", "rombk", "1")
        jump = row("83082", "e70001", "jump", "13185")
        self.assertEqual(self.problems(prefix, jump), [])
        wrong = row("83082", "e70001", "jump", "83185")
        self.assertEqual(len(self.problems(prefix, wrong)), 1)

    def test_rows_that_are_not_branches_are_ignored(self):
        self.assertEqual(self.problems(row("83090", "a1341230", "ldw", "R30,1234"), row("83094", "3412", "", "3412", "word")), [])

    def test_branch_whose_target_is_the_next_instruction_is_counted(self):
        self.assertEqual(vr.count_next_instruction_targets([row("830a0", "d100", "jleu", "830a2")]), 1)
        self.assertEqual(vr.count_next_instruction_targets([row("830a0", "d103", "jleu", "830a5")]), 0)


class TilingTests(unittest.TestCase):
    def test_rows_that_follow_each_other_have_no_problem(self):
        overlaps, gaps = vr.check_tiling([row("83000", "ff"), row("83001", "fa"), row("83002", "2100")])
        self.assertEqual((overlaps, gaps), ([], []))

    def test_gap_is_reported(self):
        overlaps, gaps = vr.check_tiling([row("83000", "ff"), row("83003", "fa")])
        self.assertEqual(overlaps, [])
        self.assertEqual([(g.start, g.end) for g in gaps], [(0x83001, 0x83002)])

    def test_overlap_is_reported(self):
        overlaps, gaps = vr.check_tiling([row("83000", "a1341230"), row("83002", "fa")])
        self.assertEqual(len(overlaps), 1)
        self.assertEqual(gaps, [])


class ImageByteTests(unittest.TestCase):
    IMAGE = bytes(range(256)) * 4          # 1024 bytes of invented data

    def test_bytes_that_match_the_image_have_no_problem(self):
        rows = [row("80010", self.IMAGE[0x10 + 0x100:0x14 + 0x100].hex())]
        self.assertEqual(vr.check_bytes(rows, self.IMAGE, {8: 0x100}), [])

    def test_bytes_that_differ_are_reported(self):
        found = vr.check_bytes([row("80010", "00000000")], self.IMAGE, {8: 0x100})
        self.assertEqual(len(found), 1)

    def test_bank_without_an_offset_is_reported(self):
        found = vr.check_bytes([row("90010", "00000000")], self.IMAGE, {8: 0x100})
        self.assertEqual(len(found), 1)
        self.assertIn("bank 9", found[0].detail)

    def test_negative_offset_for_a_file_that_starts_at_address_2000(self):
        image = bytes([0xaa, 0xbb, 0xcc, 0xdd])           # byte 0 of the file is address 2000
        self.assertEqual(vr.check_bytes([row("82001", "bb")], image, {8: -0x2000}), [])


class OffsetOptionTests(unittest.TestCase):
    def test_blocks_give_a_64_kib_offset_for_each_bank(self):
        self.assertEqual(vr.offsets_from_blocks("0,1,8,9"), {0: 0, 1: 0x10000, 8: 0x20000, 9: 0x30000})

    def test_bank_offset_accepts_hex_and_negative_values(self):
        self.assertEqual(vr.parse_bank_offset("8=-2000"), (8, -0x2000))
        self.assertEqual(vr.parse_bank_offset("1=10000"), (1, 0x10000))

    def test_bank_offset_rejects_a_wrong_bank(self):
        with self.assertRaises(ValueError):
            vr.parse_bank_offset("5=0")


class MainTests(unittest.TestCase):
    def write(self, path, text):
        with open(path, "w") as handle:
            handle.write(text)

    def run_main(self, argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = vr.main(argv)
        return code, out.getvalue()

    def test_good_file_exits_zero_and_prints_a_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "ref.tsv")
            self.write(path, "addr\tkind\tlen\tbytes\tmnemonic\toperands\n83000\tcode\t2\td3fe\tjnc\t83000\n")
            code, text = self.run_main([path])
        self.assertEqual(code, 0, text)
        self.assertIn("branch rows checked: 1", text)

    def test_wrong_target_exits_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "ref.tsv")
            self.write(path, "addr\tkind\tlen\tbytes\tmnemonic\toperands\n83000\tcode\t2\td3fe\tjnc\t83002\n")
            code, text = self.run_main([path])
        self.assertEqual(code, 1, text)
        self.assertIn("83000", text)

    def test_image_check_runs_when_an_image_is_given(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "ref.tsv")
            image = os.path.join(tmp, "image.bin")
            self.write(path, "addr\tkind\tlen\tbytes\tmnemonic\toperands\n80002\tcode\t1\tfa\tdi\t\n")
            with open(image, "wb") as handle:
                handle.write(bytes([0xff, 0xff, 0xfa, 0xff]))
            code, text = self.run_main([path, "--image", image, "--bank-offset", "8=0"])
        self.assertEqual(code, 0, text)
        self.assertIn("rows compared with the image: 1", text)

    def test_unreadable_file_exits_one(self):
        code, _ = self.run_main(["/nonexistent/ref.tsv"])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
