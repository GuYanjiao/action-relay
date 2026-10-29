"""Synthetic malformed-file checks; executed in GitHub Actions only."""
import importlib.util
import pathlib
import struct
import unittest

spec = importlib.util.spec_from_file_location('check_uf2', pathlib.Path(__file__).parents[1] / 'check_uf2.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def block(address=0x26000, number=0, total=1, flags=0x2000, family=0xADA52840):
    data = bytearray(512)
    struct.pack_into('<8I', data, 0, 0x0A324655, 0x9E5D5157, flags, address, 256, number, total, family)
    struct.pack_into('<2I', data, 32, 0x20040000, 0x26041)
    struct.pack_into('<I', data, 508, 0x0AB16F30)
    return data


class PartitionChecks(unittest.TestCase):
    def test_application(self):
        self.assertEqual(checker.inspect(block())['start'], '0x26000')

    def test_reordered_blocks(self):
        self.assertEqual(checker.inspect(block(0x26100, 1, 2) + block(total=2))['blocks'], 2)

    def test_reject_unlinked_vectors(self):
        for stack, reset in ((0, 0x26041), (0x20040004, 0x26041),
                             (0x2003FFFF, 0x26041), (0x20040000, 0x41),
                             (0x20040000, 0x26040), (0x20040000, 0x26101)):
            data = block()
            struct.pack_into('<2I', data, 32, stack, reset)
            with self.subTest(stack=stack, reset=reset):
                with self.assertRaises(ValueError):
                    checker.inspect(data)

    def test_reject_invalid(self):
        for data in (b'', block()[:-1], block(address=0), block(address=0xEC000),
                     block(flags=0), block(family=0), block(number=1), block(total=2),
                     block(total=2) + block(total=2),
                     block(total=2) + block(address=0x26080, number=1, total=2),
                     block(address=0x26100)):
            with self.subTest(length=len(data)):
                with self.assertRaises(ValueError):
                    checker.inspect(data)


if __name__ == '__main__':
    unittest.main()
