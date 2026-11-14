"""Regression checks for the client/server encoding boundaries.

Run with Python; optional --audit-dir validates heights exported by the actual
184 verifier and every model texture reference in its output against the cache.
"""

import argparse
import json
from pathlib import Path
import unittest

from cache_store import Reader, Store
from legacy_assets import (landscape, landscape184, model184, model_texture_ids,
                           terrain184, terrain_height, texture184)


def server_landscape(data):
    """Independent translation of Kronos' one-smart-per-ID server reader."""
    reader = Reader(data)
    object_id = -1
    result = {}
    while True:
        increment = reader.smart()
        if not increment:
            break
        object_id += increment
        position = 0
        placements = []
        while True:
            increment = reader.smart()
            if not increment:
                break
            position += increment - 1
            placements.append((position, reader.uint()))
        if placements:
            result[object_id] = placements
    if reader.offset != len(data):
        raise AssertionError("server landscape left bytes unread")
    return result


class EncodingBoundaries(unittest.TestCase):
    def test_large_object_gaps_decode_identically_in_client_and_server(self):
        for first in (0, 127, 128, 32765, 32766, 32767, 65533, 65534, 131100):
            expected = {first: [(0, 0), (16383, 255)], first + 65534: [(500, 11)]}
            data = landscape184(expected)
            self.assertEqual(expected, server_landscape(data))
            self.assertEqual(expected, {key: value for key, value in landscape(data).items() if value})

    def test_relocation_bakes_only_plane_zero_implicit_heights(self):
        # 16384 implicit donor tiles, each represented by a 16-bit zero.
        data = terrain184(bytes(32768), lambda value: value, lambda value: value, (1600, 3072))
        reader = Reader(data)
        for x in range(64):
            for y in range(64):
                self.assertEqual(1, reader.uint())
                self.assertEqual(terrain_height(1600 + x, 3072 + y), reader.uint())
        self.assertEqual(bytes(3 * 4096), reader.take(3 * 4096))
        self.assertEqual(len(data), reader.offset)

    def test_explicit_terrain_heights_are_preserved(self):
        donor = b"\0\1\x28" + bytes(32766)
        result = terrain184(donor, lambda value: value, lambda value: value, (1600, 3072))
        self.assertEqual(b"\1\x28", result[:2])

    def test_known_terrain_terminator_allowed_unknown_tail_rejected(self):
        self.assertEqual(bytes(16384), terrain184(bytes(32769), lambda value: value, lambda value: value))
        with self.assertRaisesRegex(ValueError, "Unread terrain bytes"):
            terrain184(bytes(32768) + b"\1", lambda value: value, lambda value: value)

    def test_texture_conversion_keeps_color_and_animation_and_remaps_sprite(self):
        self.assertEqual(bytes.fromhex("123401012222000000000407"),
                         texture184(bytes.fromhex("11111234010407"), lambda value: value + 0x1111))


def compare_runtime(audit_dir, source):
    height_count = 0
    for line in (audit_dir / "heights.tsv").read_text().splitlines():
        x, y, value = map(int, line.split("\t"))
        if terrain_height(x, y) != value:
            raise AssertionError(f"184 height mismatch at {x},{y}")
        height_count += 1
    count = 0
    with Store(source) as store:
        for line in (audit_dir / "model-results.tsv").read_text().splitlines():
            fields = line.split("\t")
            if fields[1] != "OK":
                raise AssertionError(line)
            data = store.files(7, int(fields[0]))[0]
            expected = set(json.loads(fields[6]))
            if model_texture_ids(data) != expected:
                raise AssertionError(f"184 texture extraction differs for {fields[0]}")
            remapped = model184(data, lambda texture: texture + 1000)
            if model_texture_ids(remapped) != {texture + 1000 for texture in expected}:
                raise AssertionError(f"Texture remap differs for {fields[0]}")
            count += 1
    print(f"Matched {height_count} actual184 terrain heights; {count} actual184 model texture sets and remaps")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--audit-dir", type=Path)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(EncodingBoundaries)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
    if args.audit_dir:
        compare_runtime(args.audit_dir, args.source)
