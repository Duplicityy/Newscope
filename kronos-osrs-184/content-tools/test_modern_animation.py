import struct
import unittest
from animation_import import AnimationImporter
from modern_animation import sequence242
from test_animation_import import MemoryStore, base_store, SKELETON, FRAME


def bind():
    matrix = [1 if i % 5 == 0 else 0 for i in range(16)]
    return SKELETON + b"\0\1\1" + struct.pack(">h19f", -1, *(matrix + [0, 0, 0]))


class ModernAnimationTests(unittest.TestCase):
    def test_cached_animation_moves_from_22_to_0_and_preserves_originals(self):
        base, out = base_store(), base_store()
        raw = b"\1\0\7\0\0\0\3\0\0\0"  # version/base/range/pose/zero curves
        sequence = b"\r" + struct.pack(">I", (83 << 16) | 2) + b"\x0f\0\0\0\3\x11\1\0\0"
        donor = MemoryStore({(1, 7): {0: bind()}, (22, 83): {2: raw}, (2, 12): {123: sequence}})
        importer = AnimationImporter(base, donor, out, allow_weighted=True)
        target = importer.import_sequence(123)
        self.assertIsNotNone(target)
        self.assertEqual(out.writes, [])
        report = importer.finish()
        self.assertEqual(report["weighted_sequences"], [123])
        cached = report["cached_animation_mapping"][(83 << 16) | 2]
        saved = out.files(0, cached >> 16)[cached & 65535]
        self.assertEqual(saved[:1] + saved[3:], raw[:1] + raw[3:])
        self.assertEqual(out.files(1, int.from_bytes(saved[1:3], "big"))[0], bind())
        self.assertEqual(out.files(0, 0)[0], FRAME)
        self.assertEqual(out.files(1, 0)[0], SKELETON)
        self.assertIn(b"\x72" + struct.pack(">I", cached), out.files(2, 12)[target])
        self.assertIn(b"\x73\0\0\0\3\x75\1\0", out.files(2, 12)[target])

    def test_legacy_default_does_not_enable_new_format(self):
        base, out = base_store(), base_store()
        donor = MemoryStore({(2, 12): {1: b"\r\0\0\0\1\x0f\0\0\0\3\0"}})
        importer = AnimationImporter(base, donor, out)
        self.assertIsNone(importer.import_sequence(1))
        self.assertEqual(out.writes, [])

    def test_invalid_timeline_or_missing_held_mapping_fails_before_dependencies(self):
        for raw in (b"\r\0\0\0\1\x0f\0\3\0\3\0", b"\6\2\1\r\0\0\0\1\x0f\0\0\0\3\0"):
            base, out = base_store(), base_store()
            importer = AnimationImporter(base, MemoryStore({(2, 12): {1: raw}}), out, allow_weighted=True)
            self.assertIsNone(importer.import_sequence(1))
            self.assertEqual(importer.pending_frames, {})

    def test_extension_never_reuses_184_sound_opcode(self):
        converted, weighted = sequence242(b"\r\0\0\0\1\x0f\0\0\0\3\0", lambda x:x, lambda x:x, {})
        self.assertTrue(weighted)
        self.assertEqual(converted, b"\x72\0\0\0\1\x73\0\0\0\3\0")


if __name__ == "__main__": unittest.main()
