import struct
import unittest
import zlib

from audit_world242 import (
    MASK, XTEA_DELTA, audit_landscape, audit_terrain, installed_pairs, read_archive,
)
from cache_store import Group, name_hash, pack_container
from legacy_assets import landscape184
from modern_definitions import TERRAIN_MAGIC


class MapAuditTest(unittest.TestCase):
    def test_terrain_counts_flags_and_parses_both_formats(self):
        tiles = 4 * 64 * 64
        for width in (1, 2):
            with self.subTest(opcode_width=width):
                data = (TERRAIN_MAGIC if width == 2 else b"")
                data += (49 + 7).to_bytes(width, "big") + bytes(tiles * width)
                report = audit_terrain(data)
                self.assertEqual(report["blockFlags"], 1)
                self.assertEqual(report["bridgeFlags"], 1)
                self.assertEqual(report["roofFlags"], 1)
                self.assertEqual(report["trailingBytes"], 0)
                with self.assertRaises(ValueError):
                    audit_terrain(data[:-width] + (1).to_bytes(width, "big"))

    def test_landscape_counts_placements_and_reports_missing_definition(self):
        data = landscape184({10: [(2, 0), (130, 3)], 11: [(3200, 1)]})
        self.assertEqual(audit_landscape(data, {10}), (3, [11]))
        with self.assertRaises(ValueError):
            audit_landscape(data + b"\x00", {10, 11})

    def test_group_zero_is_a_valid_map_group(self):
        groups = {0: Group(name=name_hash("m31_55")), 1: Group(name=name_hash("l31_55"))}
        self.assertEqual(installed_pairs(groups), ({7991: [0, 1]}, []))

    def test_legacy_xtea_map_with_nonmatching_gzip_footer(self):
        payload = b"\x00" * 25
        container = bytearray(pack_container(payload, revision=1))
        container[-10] ^= 1
        keys = [42, 35, 84, 58]
        for offset in range(5, len(container) - 7, 8):
            left, right = struct.unpack_from(">II", container, offset)
            total = 0
            for _ in range(32):
                left = (left + (((right << 4 ^ right >> 5) + right) ^ (keys[total & 3] + total))) & MASK
                total = (total + XTEA_DELTA) & MASK
                right = (right + (((left << 4 ^ left >> 5) + left) ^ (keys[(total >> 11) & 3] + total))) & MASK
            struct.pack_into(">II", container, offset, left, right)
        group = Group(crc=zlib.crc32(container[:-2]))

        class Cache:
            def read(self, archive, group_id):
                return bytes(container)

            def table(self, archive):
                return 7, 0, True, {3: group}

        decoded, _, ignored_footer = read_archive(Cache(), 5, 3, keys)
        self.assertEqual(decoded, payload)
        self.assertTrue(ignored_footer)
        container[5] ^= 1
        with self.assertRaisesRegex(ValueError, "CRC"):
            read_archive(Cache(), 5, 3, keys)


if __name__ == "__main__":
    unittest.main()
