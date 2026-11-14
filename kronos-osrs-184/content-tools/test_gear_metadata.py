"""Focused compatibility checks; no Java build or production cache writes."""

from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from gear_metadata import build_metadata, load_json


def item(name, action="Wear"):
    return {2: name.encode() + b"\0", 36: action.encode() + b"\0", 39: b"Drop\0",
            12: (1000).to_bytes(4, "big"), 65: b""}


PROFILE = {
    "config": 17, "maxDistance": 1, "attackTicks": 4,
    "attackAnimation": 451, "defendAnimation": 424, "equipSound": -1,
    "attackSound": -1, "attackSets": [{"child": 3, "type": "ACCURATE", "style": "STAB"}],
    "renderAnimations": [808, 823, 819, 820, 821, 822, 824],
}


class GearMetadataTests(unittest.TestCase):
    def build(self, fields, metadata, **kwargs):
        return build_metadata({1: 40000}, {1: fields}, {1: metadata},
                              {"SWORD": PROFILE, "SHORTBOW": PROFILE}, **kwargs)

    def test_raw_bonuses_replace_stale_donor_stats_and_hidden_slots(self):
        result = self.build(item("Torva full helm"),
                            {13: 0, 14: 8, 27: 11, 75: 2721,
                             "params": {3: -5, 10: 8, 11: 1, 434: 1, 436: 80}},
                            gameplay=[{"id": 1, "bonuses": [999] * 14,
                                       "requirements": [0, 70] + [0] * 21}])
        row = result["item_info"][0]
        self.assertEqual(row["id"], 40000)
        self.assertEqual(row["weight"], 2.721)
        self.assertEqual(row["defence_level"], 80)
        self.assertEqual(row["melee_strength_bonus"], 8)
        self.assertEqual(row["magic_attack_bonus"], -5)
        self.assertTrue(row["hide_hair"] and row["hide_beard"])
        self.assertEqual(row["ranged_strength_bonus"], 0)

    def test_all_three_requirement_pairs_and_fractional_magic(self):
        result = self.build(item("Ward"), {13: 5, "params": {
            434: 6, 436: 80, 435: 1, 437: 80, 191: 5, 613: 80,
            299: 15, 12: 2, 189: 3,
        }})
        row, report = result["item_info"][0], result["report"][0]
        self.assertEqual((row["magic_level"], row["defence_level"], row["prayer_level"]), (80, 80, 80))
        self.assertEqual(row["magic_damage_bonus"], 1)
        self.assertEqual(row["ranged_strength_bonus"], 5)
        self.assertEqual(report["source_magic_damage_percent"], 1.5)
        self.assertEqual(report["status"], "basic-equipment-rounded-magic")
        self.assertTrue(any("rounded down" in note for note in report["notes"]))

    def test_melee_uses_raw_speed_and_does_not_mutate_legacy_profile(self):
        fields = item("Osmumten's fang", "Wield")
        metadata = {13: 3, 14: 5, "params": {14: 5, 0: 105, 10: 103}}
        original = deepcopy(PROFILE)
        result = self.build(fields, metadata, weapon_stats={"1": {
            "combat_style": "Stab Sword", "attack_speed": 4, "attack_range": 1}})
        row = result["item_info"][0]
        self.assertEqual(row["weapon_type"], "CONTENT242_SWORD_5_1")
        self.assertTrue(row["two_handed"])
        self.assertEqual(result["weapon_types"][row["weapon_type"]]["attackTicks"], 5)
        self.assertEqual(PROFILE, original)
        result["weapon_types"][row["weapon_type"]]["attackSets"][0]["style"] = "CRUSH"
        self.assertEqual(PROFILE, original)

    def test_powered_weapon_and_unrecognized_ammo_remain_inventory_only(self):
        fields = item("Tumeken's shadow", "Wield")
        fields[37] = b"Check\0"
        result = self.build(fields, {13: 3, "params": {14: 5, 3: 35}},
                            weapon_stats={1: {"combat_style": "Powered Staff"}})
        self.assertNotIn("equip_slot", result["item_info"][0])
        self.assertEqual(result["weapon_types"], {})
        self.assertEqual(result["report"][0]["remove_inventory_options"], [36, 37])
        result = self.build(item("Sunlight antler bolts", "Wield"), {13: 13, "params": {12: 60}})
        self.assertNotIn("equip_slot", result["item_info"][0])

    def test_ordinary_bow_and_existing_ammo_have_working_dispatch_and_level_gate(self):
        result = self.build(item("Scorching bow", "Wield"),
                            {13: 3, 14: 5, "params": {14: 5, 13: 10, 23: 77}},
                            weapon_stats={1: {"combat_style": "Bow"}})
        self.assertEqual(result["item_info"][0]["ranged_weapon"], "NORMAL_BOW")
        self.assertEqual(result["item_info"][0]["ranged_level"], 77)
        result = self.build(item("Dragon arrow", "Wield"), {13: 13, "params": {12: 60, 23: 60}})
        self.assertEqual(result["item_info"][0]["ranged_ammo"], "DRAGON_ARROW")
        self.assertEqual(result["item_info"][0]["ranged_level"], 60)

    def test_zero_cache_bonuses_do_not_resurrect_broken_items(self):
        result = self.build(item("Armour 0"), {13: 4, "params": {}},
                            gameplay={1: {"bonuses": [100] * 14}})
        self.assertEqual(result["item_info"][0]["stab_defence_bonus"], 0)
        self.assertEqual(result["report"][0]["bonuses_source"], "cache default zero")

    def test_linked_templates_and_appearance_overflow_are_not_equippable(self):
        fields = item("Helm")
        fields[98] = (799).to_bytes(2, "big")
        self.assertNotIn("equip_slot", self.build(fields, {13: 0})["item_info"][0])
        result = build_metadata({1: 65024}, {1: item("Helm")}, {1: {13: 0}}, {})
        self.assertNotIn("equip_slot", result["item_info"][0])
        self.assertIn("65023", result["report"][0]["reason"])

    def test_duplicate_target_and_existing_weapon_profile_conflicts_fail(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            build_metadata({1: 40000, 2: 40000}, {}, {}, {})
        with self.assertRaisesRegex(ValueError, "collision"):
            build_metadata({1: 40000}, {1: item("Sword", "Wield")},
                           {1: {13: 3, "params": {14: 4}}},
                           {"SWORD": PROFILE, "CONTENT242_SWORD_4_1": {}},
                           weapon_stats={1: {"combat_style": "Stab Sword"}})

    def test_comment_reader_preserves_hash_and_slashes_inside_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.json"
            path.write_text('[{ # inline comment\n "url": "https://x/#y", // another\n "id": 1}]', encoding="utf-8")
            self.assertEqual(load_json(path), [{"url": "https://x/#y", "id": 1}])


if __name__ == "__main__":
    unittest.main()
