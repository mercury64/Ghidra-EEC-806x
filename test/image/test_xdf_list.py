#!/usr/bin/env python3
"""Unit tests for xdf_list.py. The XDF text here is invented.

Run from the repository root:  python3 -m unittest discover -s test/image
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import xdf_list as xl  # noqa: E402

XDF = """<!-- invented -->
<XDFFORMAT version="1.60">
  <XDFHEADER>
    <BASEOFFSET offset="0" subtract="0" />
    <DEFAULTS datasizeinbits="16" sigdigits="2" outputtype="1" signed="0" lsbfirst="1" float="0" />
    <CATEGORY index="0x0" name="Fuel" />
    <CATEGORY index="0x1" name="Trans" />
  </XDFHEADER>
  <XDFTABLE uniqueid="0x10" flags="0x30">
    <title>Table A</title>
    <CATEGORYMEM index="0" category="2" />
    <XDFAXIS id="x" uniqueid="0x0">
      <EMBEDDEDDATA mmedtypeflags="0x02" mmedaddress="0x35E" mmedelementsizebits="16" mmedcolcount="16" />
      <indexcount>3</indexcount>
      <MATH equation="X"><VAR id="X" /></MATH>
    </XDFAXIS>
    <XDFAXIS id="z">
      <EMBEDDEDDATA mmedtypeflags="0x06" mmedaddress="0xE0A4" mmedelementsizebits="16"
        mmedrowcount="1" mmedcolcount="3" mmedmajorstridebits="0" mmedminorstridebits="32" />
      <MATH equation="X*.5"><VAR id="X" /></MATH>
    </XDFAXIS>
  </XDFTABLE>
  <XDFTABLE uniqueid="0x11" flags="0x30">
    <title>Table B</title>
    <XDFAXIS id="z">
      <EMBEDDEDDATA mmedtypeflags="0x03" mmedaddress="0x1C010" mmedelementsizebits="8"
        mmedrowcount="2" mmedcolcount="4" />
      <MATH equation="X"><VAR id="X" /></MATH>
    </XDFAXIS>
  </XDFTABLE>
  <XDFCONSTANT uniqueid="0x20">
    <title>Const &amp; C</title>
    <EMBEDDEDDATA mmedtypeflags="0x02" mmedaddress="0x04" mmedelementsizebits="16" />
    <MATH equation="X/4"><VAR id="X" /></MATH>
  </XDFCONSTANT>
</XDFFORMAT>
"""


class ParseTest(unittest.TestCase):

    def setUp(self):
        self.items = xl.parse(XDF, block_size=0xe000, order=[8, 1, 0, 9])

    def test_reads_tables_and_constants(self):
        self.assertEqual([i["title"] for i in self.items], ["Table A", "Table B", "Const & C"])
        self.assertEqual([i["kind"] for i in self.items], ["table", "table", "constant"])

    def test_z_axis_layout(self):
        a = self.items[0]
        self.assertEqual(a["offset"], 0xe0a4)
        self.assertEqual(a["bits"], 16)
        self.assertEqual((a["rows"], a["cols"]), (1, 3))
        self.assertEqual(a["minor_stride_bits"], 32)
        self.assertTrue(a["lsb_first"])
        self.assertTrue(a["column_major"])
        self.assertFalse(a["signed"])
        self.assertEqual(a["equation"], "X*.5")

    def test_banked_address_from_the_block_order(self):
        self.assertEqual(self.items[0]["address"], 0x120a4)   # block 1 = bank 1
        self.assertEqual(self.items[1]["address"], 0x02010)   # block 2 = bank 0
        self.assertEqual(self.items[2]["address"], 0x82004)   # block 0 = bank 8

    def test_signed_flag(self):
        self.assertTrue(self.items[1]["signed"])

    def test_category_names(self):
        self.assertEqual(self.items[0]["categories"], ["Trans"])
        self.assertEqual(self.items[1]["categories"], [])

    def test_element_offsets_follow_the_strides(self):
        a = self.items[0]
        self.assertEqual(xl.element_offsets(a), [0xe0a4, 0xe0a8, 0xe0ac])
        b = self.items[1]
        self.assertEqual(xl.element_offsets(b), [0x1c010 + i for i in range(8)])

    def test_last_byte(self):
        self.assertEqual(xl.last_offset(self.items[0]), 0xe0ad)

    def test_rejects_entity_declarations(self):
        with self.assertRaises(ValueError):
            xl.parse('<!DOCTYPE x [<!ENTITY a "b">]><XDFFORMAT/>')


if __name__ == "__main__":
    unittest.main()
