import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from world242_population import DuplicateRule, read_json, reconcile


class PopulationReconciliationTests(unittest.TestCase):
    def setUp(self):
        self.rule = DuplicateRule("keep.json", "copy.json", 1026, 1, frozenset({5}))
        self.original = {"id": 5, "x": 260, "y": 130, "z": 1, "direction": "E", "walkRange": 0}
        self.duplicate = dict(self.original, direction="EAST", walkRange=3)
        self.unique = dict(self.original, z=2)

    def test_reconciliation_preserves_authoritative_rows_and_different_planes(self):
        sources = {"keep.json": [self.original], "copy.json": [self.unique, self.duplicate]}
        cleaned, removed = reconcile(self.rule, sources)
        self.assertEqual([self.unique], cleaned)
        self.assertEqual([self.duplicate], removed)
        self.assertEqual([self.original], sources["keep.json"])
        self.assertEqual([self.unique, self.duplicate], sources["copy.json"])
        self.assertEqual((cleaned, []), reconcile(self.rule, sources | {"copy.json": cleaned}))

    def test_retains_world_specific_placement(self):
        other_world = dict(self.original, world="ECO")
        sources = {"keep.json": [self.original], "copy.json": [other_world, self.duplicate]}
        self.assertEqual(([other_world], [self.duplicate]), reconcile(self.rule, sources))

    def test_rejects_ambiguous_repeats(self):
        with self.assertRaisesRegex(ValueError, "Reviewed match set changed"):
            reconcile(self.rule, {"keep.json": [self.original], "copy.json": [self.duplicate] * 2})

    def test_rejects_shop_override(self):
        custom = dict(self.duplicate, shopOptions={"Trade": "custom-store"})
        with self.assertRaisesRegex(ValueError, "Unexpected protected placement"):
            reconcile(self.rule, {"keep.json": [self.original], "copy.json": [custom]})

    def test_rejects_missing_authoritative_population(self):
        with self.assertRaisesRegex(ValueError, "Authoritative population is incomplete"):
            reconcile(self.rule, {"keep.json": [], "copy.json": [self.duplicate]})

    def test_reads_native_comments_without_altering_quoted_strings(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "TzKal-Zuk.json"
            path.write_text(
                '[{ # native comment\n"pet": TZREK_ZUK, '
                '"label": "https://example.org/#hash" /* comment */}]', encoding="utf-8")
            self.assertEqual([{"pet": "TZREK_ZUK", "label": "https://example.org/#hash"}],
                             read_json(path, allow_native_comments=True))
            with self.assertRaises(json.JSONDecodeError):
                read_json(path)


if __name__ == "__main__":
    unittest.main()
