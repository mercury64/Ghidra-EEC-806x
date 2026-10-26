#!/usr/bin/env python3
"""Unit tests for ford_function.py. All tables and image bytes here are invented.

Run from the repository root:  python3 -m unittest discover -s test/emulator
"""

import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ford_function as ff  # noqa: E402

BLOCK = 0x10000


def image_with(writes, banks=ff.IMAGE_BANKS):
    """A zero-filled image with one 64 KiB block per bank and the given bytes written.

    writes maps a banked address (bank * 0x10000 + 16-bit address) to a list of byte values.
    """
    data = bytearray(len(banks) * BLOCK)
    slot = {bank: i for i, bank in enumerate(banks)}
    for address, values in writes.items():
        start = slot[address >> 16] * BLOCK + (address & 0xffff)
        data[start:start + len(values)] = bytes(values)
    return ff.BankedImage(bytes(data), banks)


class BankedImageTest(unittest.TestCase):

    def test_reads_each_bank_from_its_block(self):
        image = image_with({0x12345: [0x11], 0x82000: [0x22], 0x9fffe: [0x33]})
        self.assertEqual(image.byte(0x12345), 0x11)
        self.assertEqual(image.byte(0x82000), 0x22)
        self.assertEqual(image.byte(0x9fffe), 0x33)

    def test_words_are_little_endian(self):
        image = image_with({0x14000: [0x34, 0x12]})
        self.assertEqual(image.value(0x14000, 2), 0x1234)

    def test_unknown_bank_is_an_error(self):
        image = image_with({})
        with self.assertRaises(ValueError):
            image.byte(0x24000)

    def test_short_file_is_an_error(self):
        with self.assertRaises(ValueError):
            ff.BankedImage(bytes(100))


class ReadFunctionTest(unittest.TestCase):

    def test_reads_byte_pairs_up_to_the_lowest_x(self):
        image = image_with({0x14000: [0xff, 100, 0x80, 50, 0x00, 0, 0xff, 7]})
        self.assertEqual(ff.read_function(image, 0x14000),
                         [(255, 100), (128, 50), (0, 0)])

    def test_signed_x_ends_at_0x80(self):
        image = image_with({0x14000: [0x7f, 1, 0x00, 2, 0x80, 3, 0x00, 9]})
        self.assertEqual(ff.read_function(image, 0x14000, signed_x=True),
                         [(0x7f, 1), (0x00, 2), (0x80, 3)])

    def test_reads_word_pairs(self):
        image = image_with({0x14000: [0xff, 0xff, 0xe8, 0x03, 0x00, 0x00, 0x00, 0x00]})
        self.assertEqual(ff.read_function(image, 0x14000, width=2),
                         [(0xffff, 1000), (0, 0)])

    def test_missing_end_is_an_error(self):
        image = image_with({0x14000: [0xff, 1] * 8})
        with self.assertRaises(ValueError):
            ff.read_function(image, 0x14000, max_pairs=8)


class LookupTest(unittest.TestCase):

    PAIRS = [(255, 100), (128, 50), (0, 0)]

    def test_breakpoints_give_their_y(self):
        self.assertEqual(ff.lookup(self.PAIRS, 255), 100)
        self.assertEqual(ff.lookup(self.PAIRS, 128), 50)
        self.assertEqual(ff.lookup(self.PAIRS, 0), 0)

    def test_interpolation_truncates(self):
        self.assertEqual(ff.lookup(self.PAIRS, 192), 50 + 64 * 50 // 127)
        self.assertEqual(ff.lookup(self.PAIRS, 64), 25)

    def test_falling_y_subtracts_the_truncated_step(self):
        self.assertEqual(ff.lookup([(255, 0), (0, 200)], 100), 200 - 200 * 100 // 255)

    def test_x_above_the_first_breakpoint_extrapolates(self):
        self.assertEqual(ff.lookup([(200, 100), (0, 0)], 250), 125)

    def test_quotient_overflow_is_an_error(self):
        with self.assertRaises(OverflowError):
            ff.lookup([(10, 255), (0, 0)], 255)

    def test_equal_first_breakpoints_divide_by_zero(self):
        with self.assertRaises(ZeroDivisionError):
            ff.lookup([(100, 1), (100, 2), (0, 3)], 150)

    def test_signed_x_search(self):
        pairs = [(0x7f, 10), (0x00, 20), (0x80, 30)]
        self.assertEqual(ff.lookup(pairs, 0x7f, signed_x=True), 10)
        self.assertEqual(ff.lookup(pairs, 0xff, signed_x=True), 30 - 10 * 127 // 128)
        self.assertEqual(ff.lookup(pairs, 0x80, signed_x=True), 30)

    def test_signed_x_search_uses_the_true_sign_when_the_difference_overflows(self):
        # 0x10 - 0x80 is 0x90 in 8 bits, but the true result (16 + 128) is positive: the CPU
        # sets N to the true sign, so the routine stops at the first pair.
        pairs = [(0x7f, 10), (0x80, 20)]
        self.assertEqual(ff.lookup(pairs, 0x10, signed_x=True), 20 - 10 * 0x90 // 0xff)

    def test_search_past_the_last_pair_is_an_error(self):
        with self.assertRaises(ValueError):
            ff.lookup([(0x7f, 1), (0x10, 2)], 0x05, signed_x=True)

    def test_signed_y_uses_the_sign_of_the_difference(self):
        pairs = [(0xff, 0xfb), (0x00, 0x05)]
        self.assertEqual(ff.lookup(pairs, 0x80, signed_y=True), 0x00)
        self.assertEqual(ff.lookup(pairs, 0x80, signed_y=False), 5 + 246 * 128 // 255)

    def test_signed_y_uses_the_true_sign_when_the_difference_overflows(self):
        # y0 - y1 = 127 - (-128) = 255: positive, although 0xff has the sign bit set.
        pairs = [(0xff, 0x7f), (0x00, 0x80)]
        self.assertEqual(ff.lookup(pairs, 0x80, signed_y=True), (0x80 + 255 * 128 // 255) & 0xff)

    def test_word_function(self):
        pairs = [(0xffff, 1000), (0x8000, 500), (0, 0)]
        self.assertEqual(ff.lookup(pairs, 0xc000, width=2), 500 + 500 * 0x4000 // 0x7fff)


class RequiredInputsTest(unittest.TestCase):

    def test_breakpoints_midpoints_and_ends(self):
        self.assertEqual(ff.required_inputs([(255, 1), (128, 2), (0, 3)]),
                         [0, 64, 128, 191, 255])

    def test_word_ends(self):
        self.assertEqual(ff.required_inputs([(0x8000, 1), (0x100, 2)], width=2),
                         [0, 0x100, 0x4080, 0x8000, 0xffff])


class CompareTest(unittest.TestCase):

    TABLE = 0x14000
    HEADER = "mode\tstart\troutine\ttable\twidth\tsigned_x\tsigned_y\tx\tresult\tsteps\n"

    def setUp(self):
        self.image = image_with({self.TABLE: [0xff, 100, 0x80, 50, 0x00, 0]})
        self.files = []

    def tearDown(self):
        for path in self.files:
            os.remove(path)

    def rows_file(self, rows):
        f = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False, encoding="utf-8")
        f.write(self.HEADER)
        for x, result in rows:
            f.write(f"call\t87dd2\t87dd2\t{self.TABLE:05x}\t1\t0\t0\t{x:x}\t{result:x}\t40\n")
        f.close()
        self.files.append(f.name)
        return f.name

    def test_counts_matches_per_table(self):
        path = self.rows_file([(x, ff.lookup([(255, 100), (128, 50), (0, 0)], x))
                               for x in ff.required_inputs([(255, 100), (128, 50), (0, 0)])])
        out = io.StringIO()
        summary = ff.compare(self.image, path, out)
        self.assertEqual(summary[self.TABLE].matches, 5)
        self.assertEqual(summary[self.TABLE].mismatches, 0)
        self.assertEqual(summary[self.TABLE].missing_inputs, [])

    def test_reports_a_mismatch_and_missing_inputs(self):
        path = self.rows_file([(255, 100), (128, 51)])
        out = io.StringIO()
        summary = ff.compare(self.image, path, out)
        self.assertEqual(summary[self.TABLE].matches, 1)
        self.assertEqual(summary[self.TABLE].mismatches, 1)
        self.assertEqual(summary[self.TABLE].missing_inputs, [0, 64, 191])
        self.assertNotIn("51", out.getvalue())   # values are shown only on request


if __name__ == "__main__":
    unittest.main()
