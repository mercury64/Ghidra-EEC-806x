#!/usr/bin/env python3
"""Unit tests for convert_banks.py. All image bytes here are invented.

Run from the repository root:  python3 -m unittest discover -s test/image
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import convert_banks as cb  # noqa: E402

SHORT = 0xe000
LONG = 0x10000


def short_blocks(writes, order):
    """A file of 56 KiB blocks (0x2000-0xffff of each bank), 0xff-filled, with bytes written.

    writes maps a banked address (bank * 0x10000 + 16-bit address) to a list of byte values.
    """
    data = bytearray(b"\xff" * (len(order) * SHORT))
    slot = {bank: i for i, bank in enumerate(order)}
    for address, values in writes.items():
        start = slot[address >> 16] * SHORT + (address & 0xffff) - 0x2000
        data[start:start + len(values)] = bytes(values)
    return bytes(data)


def read(image, address, count=1):
    """Bytes of a 256 KiB image in bank order 0, 1, 8, 9."""
    slot = {0: 0, 1: 1, 8: 2, 9: 3}[address >> 16]
    start = slot * LONG + (address & 0xffff)
    return image[start:start + count]


class ConvertTest(unittest.TestCase):

    def test_short_blocks_go_to_their_banks_at_0x2000(self):
        data = short_blocks({0x82000: [0xaa], 0x12000: [0xbb], 0x0fffe: [0xcc, 0xdd],
                             0x92345: [0xee]}, [8, 1, 0, 9])
        image = cb.convert(data, [8, 1, 0, 9])
        self.assertEqual(len(image), 4 * LONG)
        self.assertEqual(read(image, 0x82000), b"\xaa")
        self.assertEqual(read(image, 0x12000), b"\xbb")
        self.assertEqual(read(image, 0x0fffe, 2), b"\xcc\xdd")
        self.assertEqual(read(image, 0x92345), b"\xee")

    def test_bytes_below_0x2000_get_the_fill_byte(self):
        data = short_blocks({0x82000: [0x00]}, [8, 1, 0, 9])
        image = cb.convert(data, [8, 1, 0, 9], fill=0x5a)
        for bank in (0, 1, 8, 9):
            self.assertEqual(read(image, bank << 16, 0x2000), b"\x5a" * 0x2000)

    def test_64k_blocks_are_reordered(self):
        data = bytearray(4 * LONG)
        for slot, bank in enumerate([9, 8, 0, 1]):
            data[slot * LONG + 0x3234] = bank + 1
            data[slot * LONG + 0x1234] = 0x77
        image = cb.convert(bytes(data), [9, 8, 0, 1])
        for bank in (0, 1, 8, 9):
            self.assertEqual(read(image, (bank << 16) + 0x3234), bytes([bank + 1]))
            self.assertEqual(read(image, (bank << 16) + 0x1234), b"\xff")

    def test_missing_bank_is_filled(self):
        data = short_blocks({0x82000: [0x01]}, [8, 1])
        image = cb.convert(data, [8, 1])
        self.assertEqual(read(image, 0x92000, 4), b"\xff" * 4)
        self.assertEqual(read(image, 0x82000), b"\x01")

    def test_unknown_bank_is_an_error(self):
        with self.assertRaises(ValueError):
            cb.convert(bytes(2 * SHORT), [8, 2])

    def test_repeated_bank_is_an_error(self):
        with self.assertRaises(ValueError):
            cb.convert(bytes(2 * SHORT), [8, 8])

    def test_size_that_does_not_fit_the_order_is_an_error(self):
        with self.assertRaises(ValueError):
            cb.convert(bytes(3 * SHORT + 1), [8, 1, 0])


class ReportTest(unittest.TestCase):

    def test_finds_the_reset_block_and_cross_bank_calls(self):
        # Bank 8 calls bank 9 at 0x3000 with ROMBK 9 (10 09) and LCALL (ef + 16-bit offset).
        # The call is at 0x8a000; the next instruction is at 0x8a005; offset 0x3000 - 0xa005.
        offset = (0x3000 - 0xa005) & 0xffff
        data = short_blocks({0x82000: [0xff, 0xfa],
                             0x8a000: [0x10, 0x09, 0xef, offset & 0xff, offset >> 8],
                             0x93000: [0xf2]}, [8, 1, 0, 9])
        blocks = cb.report(data, 4)
        self.assertTrue(blocks[0]["reset"])
        self.assertFalse(blocks[1]["reset"])
        calls = blocks[0]["calls"]
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["bank"], 9)
        self.assertEqual(calls[0]["target"], 0x3000)
        self.assertEqual(calls[0]["pushp_in_blocks"], [3])

    def test_report_text_names_the_blocks(self):
        data = short_blocks({0x82000: [0xff, 0xfa]}, [8, 1, 0, 9])
        text = cb.report_text(cb.report(data, 4))
        self.assertIn("block 0", text)
        self.assertIn("reset entry bytes", text)


class CommandLineTest(unittest.TestCase):

    def test_convert_writes_the_output_file(self):
        data = short_blocks({0x82000: [0x42]}, [8, 1, 0, 9])
        with tempfile.TemporaryDirectory() as tmp:
            src = os.path.join(tmp, "in.bin")
            dst = os.path.join(tmp, "out.bin")
            with open(src, "wb") as f:
                f.write(data)
            self.assertEqual(cb.main(["convert", src, dst, "--order", "8,1,0,9"]), 0)
            with open(dst, "rb") as f:
                image = f.read()
        self.assertEqual(read(image, 0x82000), b"\x42")


if __name__ == "__main__":
    unittest.main()
