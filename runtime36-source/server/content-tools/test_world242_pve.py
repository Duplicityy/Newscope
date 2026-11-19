"""Verify reviewed PvE bindings against Newscape data and the installed cache."""

from collections import Counter
import os
from pathlib import Path
import re
import unittest

from cache_store import Store
from modern_definitions import read_npc
from world242_population import read_json, read_spawns


SOURCE = Path(__file__).resolve().parents[1] / "Kronos-master/kronos-server"
DATA = Path(os.environ.get("NEWSCAPE_WORLD242_TEST_DATA", SOURCE / "data"))
CALVES = (2792, 2794, 2801)
FISHING_SHOP = "4ea4cb73-305a-445c-a7ab-131a90a1d8b5"
LMS_REWARD_SHOP = "093f2aef-46b4-4b40-943b-0934954ee124"


class PveBindingsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spawns = read_spawns(DATA)

    def assert_single_binding(self, relative_directory, ids, file_name):
        counts = Counter()
        for path in (DATA / relative_directory).glob("*.json"):
            for row in read_json(path, allow_native_comments=True):
                bound_ids = [npc for npc in row["ids"] if npc in ids]
                if bound_ids:
                    self.assertEqual(file_name, path.name)
                    counts.update(bound_ids)
        self.assertEqual(Counter(ids), counts)

    def test_calf_variants_share_one_combat_record(self):
        rows = read_json(DATA / "npcs/combat/Cow_calf.json", allow_native_comments=True)
        self.assertEqual(1, len(rows))
        self.assertTrue(set(CALVES).issubset(rows[0]["ids"]))
        self.assertEqual(6, rows[0]["hitpoints"])
        self.assertEqual("CRUSH", rows[0]["attack_style"])
        self.assertEqual([5849, 5850, 5851], [rows[0][key + "_animation"]
                                             for key in ("attack", "defend", "death")])
        self.assert_single_binding("npcs/combat", CALVES, "Cow_calf.json")

    def test_calf_guaranteed_drops_in_both_world_types(self):
        for world in ("eco", "pvp"):
            with self.subTest(world=world):
                directory = f"npcs/drops/{world}"
                rows = read_json(DATA / directory / "Cow_calf.json", allow_native_comments=True)
                self.assertEqual(1, len(rows))
                self.assertCountEqual(CALVES, rows[0]["ids"])
                self.assertCountEqual([(1739, 1, 1), (2132, 1, 1), (526, 1, 1)],
                                      [(row["id"], row["min"], row["max"])
                                       for row in rows[0]["guaranteed"]])
                self.assert_single_binding(directory, CALVES, "Cow_calf.json")
        overlay = DATA / "content242-world.json"
        if overlay.is_file():
            self.assertTrue(set(map(str, CALVES)).isdisjoint(read_json(overlay)["loot"]))

    def test_calf_repairs_cover_existing_regional_spawns(self):
        counts = Counter((path, row["id"]) for path, rows in self.spawns.items() for row in rows
                         if row["id"] in (2794, 2801) and row.get("world") is None)
        self.assertEqual({("ardougne.json", 2794): 10, ("lumbridge.json", 2794): 5,
                          ("lumbridge.json", 2801): 3}, counts)

    def test_fishing_shop_uses_the_existing_stock_and_one_placement(self):
        rows = [(path, row) for path, group in self.spawns.items() for row in group if row["id"] == 8684]
        self.assertEqual([("fishing_guild.json", {
            "id": 8684, "name": "Roachey", "x": 2596, "y": 3401, "z": 0,
            "direction": "S", "walkRange": 3, "shopOptions": {"trade": FISHING_SHOP}
        })], rows)
        self.assertFalse(any(row["id"] == 1299 for row in self.spawns["fishing_guild.json"]))
        shop = (DATA / "shops/Fishing_Guild_Shop.yaml").read_text(encoding="utf-8-sig")
        self.assertRegex(shop, re.compile(r'^identifier: "' + FISHING_SHOP + r'"$', re.M))
        self.assertIn("defaultStock:", shop)
        self.assertIn('currency: "COINS"', shop)

    def test_lisa_retains_the_scoped_survival_token_shop(self):
        rows = [(path, row) for path, group in self.spawns.items() for row in group if row["id"] == 7317]
        self.assertEqual([("clan_wars.json", {
            "id": 7317, "name": "Lisa", "x": 3407, "y": 3180, "z": 0,
            "direction": "S", "walkRange": 0, "shopOptions": {"Trade": LMS_REWARD_SHOP}
        })], rows)
        shop = (DATA / "shops/LMS_Reward_Shop.yaml").read_text(encoding="utf-8-sig")
        self.assertRegex(shop, re.compile(r'^identifier: "' + LMS_REWARD_SHOP + r'"$', re.M))
        self.assertIn('currency: "SURVIVAL_TOKENS"', shop)
        self.assertRegex(shop, r"(?m)^  price: 450$")


class PveCacheTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cache = os.environ.get("NEWSCAPE_WORLD242_TEST_CACHE")
        if not cache:
            raise unittest.SkipTest("Set NEWSCAPE_WORLD242_TEST_CACHE to validate installed definitions")
        with Store(Path(cache)) as store:
            cls.npcs = store.files(2, 9)
            cls.items = store.files(2, 10)
            cls.animations = store.files(2, 12)

    def test_calf_models_and_actions_match_working_id(self):
        self.assertEqual(self.npcs[2792], self.npcs[2794])
        working, meta = read_npc(self.npcs[2792], modern=False)
        self.assertEqual("Cow calf", meta["name"])
        self.assertEqual("Attack", meta["options"][1])
        self.assertFalse(meta["morphs"])
        smaller, smaller_meta = read_npc(self.npcs[2801], modern=False)
        self.assertEqual(b"\x02", working.pop(12))
        self.assertEqual(working, smaller)
        self.assertEqual(meta["models"], smaller_meta["models"])
        self.assertEqual(meta["animations"], smaller_meta["animations"])
        self.assertTrue({5849, 5850, 5851}.issubset(self.animations))
        self.assertTrue({526, 1739, 2132}.issubset(self.items))

    def test_fishing_shop_identity_exposes_native_menu_options(self):
        _, current = read_npc(self.npcs[8684], modern=False)
        self.assertEqual("Roachey", current["name"])
        self.assertEqual(["Talk-to", None, "Trade", None, None], current["options"])
        self.assertFalse(current["morphs"])
        _, stale = read_npc(self.npcs[1299], modern=False)
        self.assertEqual("Sheep", stale["name"])
        self.assertFalse(any(stale["options"]))

    def test_lisa_cache_keeps_existing_actions_and_the_fourth_slot_available(self):
        _, lisa = read_npc(self.npcs[7317], modern=False)
        self.assertEqual("Lisa", lisa["name"])
        self.assertEqual(["Talk-to", None, "Join", None, None], lisa["options"])


if __name__ == "__main__":
    unittest.main()
