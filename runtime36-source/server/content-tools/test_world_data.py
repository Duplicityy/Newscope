import copy
import json
from pathlib import Path
import tempfile
import unittest

from world_data import build, relocate, number, travel


class WorldDataTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = self.root / 'server/data/definitions'
        self.data.mkdir(parents=True)
        self.manifest = {'schemaVersion': 2, 'sourceRevision': 242, 'mappings': {
            'npcs': {'1': 1, '12000': 10000, '12001': 10001},
            'items': {'995': 995, '10': 30000}, 'animations': {'100': 15000},
            'regions': {'10000': {'destination': 10001, 'dx': 0, 'dy': 64}}}}
        self.metadata = {'12000': {'name': 'New rat', 'combatLevel': 2},
                         '12001': {'name': 'Scurrius', 'combatLevel': 87}}
        self.monster = {'name': 'New rat', 'hitpoints': 10, 'max_hit': 2, 'attack_type': ['crush'], 'attack_speed': 4}
        self.files = {
            'monsters-complete.json': {'12000': self.monster, '12001': {**self.monster, 'name': 'Scurrius'}},
            'npc-combat-defs.json': {'npcs': {}}, 'npc-animations.json': {'12000': [101, 100]},
            'sequence-names.json': {'100': 'NEW_RAT_ATTACK', '101': 'NEW_RAT_DEATH'},
            'npc-spawns.json': [{'id': n, 'x': 2497, 'y': 1025, 'level': 0, 'wanderRadius': 2} for n in (1, 12000, 12001)],
            'npc-drops.json': {'npcs': {'12000': {'tables': ['new_rat']}}, 'tables': {'new_rat': {
                'main_max_roll': 100, 'entries': [
                    {'item_id': 995, 'quantity': [1, 5], 'weight': 40},
                    {'item_id': 9999, 'quantity': [1, 1], 'weight': 60}],
                'tertiary': [{'item_id': 10, 'quantity': [1, 1], 'weight': 1, 'out_of': 128}]}}},
            'shops.json': [{'id': 1000, 'name': 'New shop', 'currency': 995, 'sellsAt': 1300,
                           'originalStock': [{'id': 10, 'amount': 15}],
                           'npcInteractions': [{'npcIds': [12000], 'optionSlot': 3}]}],
        }

    def tearDown(self):
        self.temp.cleanup()

    def run_build(self):
        for name, data in self.files.items():
            (self.data / name).write_text(json.dumps(data), encoding='utf8')
        return build(self.manifest, self.root, {1}, self.metadata, {'10': 100})

    def test_preserves_existing_definition_and_relocates_only_mapped_regions(self):
        result = self.run_build()
        self.assertEqual([10000], [r['ids'][0] for r in result['combat']])
        self.assertEqual([1, 10000], [r['id'] for r in result['spawns']])
        self.assertTrue(all(r['x'] == 2497 and r['y'] == 1089 for r in result['spawns']))
        self.assertTrue(any('handler' in r['reason'] for r in result['report']['excludedCombat']))

    def test_drops_preserve_empty_weight_and_tertiary_odds(self):
        group = self.run_build()['loot']['10000'][0]
        self.assertEqual(100, group['denominator'])
        self.assertEqual([{'id': 995, 'min': 1, 'max': 5, 'weight': 40}], group['main'])
        self.assertEqual(128, group['independent'][0]['denominator'])
        self.assertEqual(30000, group['independent'][0]['id'])

    def test_missing_main_denominator_still_retains_excluded_weight(self):
        del self.files['npc-drops.json']['tables']['new_rat']['main_max_roll']
        self.assertEqual(100, self.run_build()['loot']['10000'][0]['denominator'])

    def test_separate_roll_inside_main_source_entries_remains_independent(self):
        table=self.files['npc-drops.json']['tables']['new_rat']
        del table['main_max_roll']
        table['entries'].append({'item_id': 10, 'quantity': [1, 1], 'separate_roll': True,
                                 'weight': 1, 'out_of': 512})
        group=self.run_build()['loot']['10000'][0]
        self.assertEqual(100, group['denominator'])
        self.assertEqual([40], [entry['weight'] for entry in group['main']])
        self.assertEqual([512, 128], [entry['denominator'] for entry in group['independent']])

    def test_shared_table_preserves_nested_odds_and_weighted_roll_counts(self):
        self.files['npc-drop-subtables.json'] = {'herb': {'entries': [
            {'item_id': 10, 'quantity_raw': '2-4', 'rarity_raw': '1/4'},
            {'nothing': True, 'rarity_raw': '3/4'}]}}
        self.files['npc-drops.json']['tables']['new_rat']['entries'] = [
            {'shared_table': 'herb', 'weight': 40, 'out_of': 100,
             'roll_count_weights': [{'rolls': 1, 'weight': 11}, {'rolls': 3, 'weight': 4}]}]
        parent = self.run_build()['loot']['10000'][0]['main'][0]
        self.assertEqual(40, parent['weight'])
        self.assertEqual(4, parent['table']['denominator'])
        self.assertEqual([{'id': 30000, 'min': 2, 'max': 4, 'weight': 1}], parent['table']['main'])
        self.assertEqual([{'rolls': 1, 'weight': 11}, {'rolls': 3, 'weight': 4}], parent['rollCounts'])

    def test_quest_conditional_reward_is_not_granted_unconditionally(self):
        self.files['npc-drops.json']['tables']['new_rat']['tertiary'][0]['notes'] = ['Quest requirement: quest_example']
        result = self.run_build()
        self.assertEqual([], result['loot']['10000'][0]['independent'])
        self.assertTrue(any('prerequisite' in e['reason'] for e in result['report']['excludedRewards']))

    def test_price_uses_source_thousandths_and_remapped_item(self):
        result = self.run_build()
        self.assertEqual({'id': 30000, 'amount': 15, 'price': 130}, result['shops'][0]['defaultStock'][0])
        self.assertEqual(10000, result['shopBindings'][0]['npc'])

    def test_shop_pages_keep_all_supported_stock(self):
        stock = self.files['shops.json'][0]['originalStock']
        stock[:] = [copy.deepcopy(stock[0]) for _ in range(85)]
        result = self.run_build()
        self.assertEqual([40, 40, 5], [len(s['defaultStock']) for s in result['shops']])
        self.assertEqual(3, len(result['shopBindings'][0]['shops']))

    def test_zero_stock_entries_do_not_reach_native_item_container(self):
        self.files['shops.json'][0]['originalStock'].append({'id': 10, 'amount': 0})
        result = self.run_build()
        self.assertEqual([{'id': 30000, 'amount': 15, 'price': 130}], result['shops'][0]['defaultStock'])

    def test_native_boss_handler_receives_stats_without_public_world_spawn(self):
        self.manifest['mechanics'] = {
            'npcHandlers': {'12001': 'io.ruin.content.content242.bosses.Scurrius242'},
            'instanceOnlyNpcs': [12001],
            'npcCombatOverrides': {'12001': {'stats': self.files['monsters-complete.json']['12001'],
                'combat': {'anims': {'spawn': 100, 'attack': 100, 'block': -1, 'death': 100},
                           'respawn': -1, 'deathTicks': 1}}}}
        result = self.run_build()
        boss = next(row for row in result['combat'] if row['ids'] == [10001])
        self.assertEqual(15000, boss['attack_animation'])
        self.assertEqual(15000, boss['spawn_animation'])
        self.assertNotIn(10001, [row['id'] for row in result['spawns']])

    def test_existing_npc_shop_actions_are_preserved(self):
        self.files['shops.json'][0]['npcInteractions'][0]['npcIds'] = [1]
        result = self.run_build()
        self.assertEqual([], result['shopBindings'])
        self.assertTrue(any('original action' in e['reason'] for e in result['report']['excludedShops']))

    def test_traversal_remaps_both_ends_and_omits_unsupported_requirements(self):
        directory = self.root / 'server/plugins/world/data'
        directory.mkdir(parents=True)
        base = {'id': 50000, 'x': 2497, 'y': 1025, 'z': 0, 'op': 1, 'option': 'Climb-up',
                'to': [2498, 1026, 1], 'anim': 100, 'tick': 3, 'animTick': 1, 'recordings': 1}
        (directory / 'loc-teleports.json').write_text(json.dumps({'locs': [base,
            {**base, 'requires': [{'quest': 'unimplemented'}]}, {**base, 'to': [3200, 3200, 0]},
            {**base, 'x': 3200, 'y': 3200}]}), encoding='utf8')
        self.manifest['mappings']['objects'] = {'50000': 45000}
        report = {'excludedTravel': []}
        _, links = travel(self.manifest, self.root, report)
        self.assertEqual(1, len(links))
        self.assertEqual([2497, 1089, 0], links[0]['origin'])
        self.assertEqual([2498, 1090, 1], links[0]['routes'][0]['to'])
        self.assertEqual(45000, links[0]['object'])
        self.assertEqual(15000, links[0]['animation'])
        self.assertEqual(2, len(report['excludedTravel']))

    def test_missing_attack_animation_blocks_spawn_instead_of_invisible_combat(self):
        self.manifest['mappings']['animations'].clear()
        result = self.run_build()
        self.assertEqual([], result['combat'])
        self.assertEqual([1], [r['id'] for r in result['spawns']])

    def test_observed_combat_sequences_are_assigned_by_role_not_position(self):
        self.manifest['mappings']['animations']['101'] = 15001
        row = self.run_build()['combat'][0]
        self.assertEqual((15000, -1, 15001),
                         (row['attack_animation'], row['defend_animation'], row['death_animation']))

    def test_unclassified_attack_sequence_does_not_enable_invisible_combat(self):
        self.files['sequence-names.json'].clear()
        result = self.run_build()
        self.assertEqual([], result['combat'])
        self.assertIn('no verified attack animation', result['report']['excludedCombat'][0]['reason'])

    def test_invalid_bounds_and_instance_pool_rejected(self):
        with self.assertRaises(ValueError):
            number(None, low=1)
        with self.assertRaises(ValueError):
            relocate(2497, 1025, {'10000': {'destination': 25600}})
        with self.assertRaises(ValueError):
            relocate(2497, 1025, {'10000': {'destination': 10001, 'dx': 1}})

    def test_output_is_deterministic_and_does_not_mutate_inputs(self):
        before = copy.deepcopy(self.files)
        self.assertEqual(self.run_build(), self.run_build())
        self.assertEqual(before, self.files)


if __name__ == '__main__':
    unittest.main()
