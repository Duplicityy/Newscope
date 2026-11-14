"""Assemble a fresh, isolated QA content directory; never select a production release."""
import argparse
import json
import shutil
from pathlib import Path

from cache_store import Store
from world_data import build, read, dump


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--base-cache', type=Path, required=True)
    parser.add_argument('--donor', type=Path, required=True)
    parser.add_argument('--requirements', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Use a new QA directory; previous builds remain untouched')
    manifest = read(args.assets / 'data/content242.json')
    requirements = read(args.requirements)['features']
    flags, unavailable = {}, {}
    for feature, groups in requirements.items():
        missing = {kind: [value for value in values if str(value) not in manifest['mappings'].get(kind, {})]
                   for kind, values in groups.items()}
        missing = {kind: values for kind, values in missing.items() if values}
        flags[feature] = not missing
        if missing:
            unavailable[feature] = missing
    flags['world'] = True
    namespace = 'io.ruin.content.content242.bosses.'
    handlers = {}
    if flags.get('scurrius'):
        handlers.update({'7221': namespace + 'Scurrius242', '7222': namespace + 'Scurrius242', '7223': namespace + 'GiantRat242'})
    if flags.get('amoxliatl'):
        handlers.update({'13685': namespace + 'Amoxliatl242', '13688': namespace + 'UnstableIce242'})
    if flags.get('phantom_muspah'):
        handlers.update({str(source): namespace + 'PhantomMuspah242' for source in [12077, 12078, 12079, 12080, 12082]})
    manifest['mechanics'] = {'enabled': True, 'features': flags, 'npcHandlers': handlers,
        'instanceOnlyNpcs': [7221, 7222, 7223, 13685, 13688, 12077, 12078, 12079, 12080, 12082, 12083], 'npcCombatOverrides': {}}
    if flags.get('amoxliatl'):
        manifest['mechanics']['npcCombatOverrides']['13688'] = {
            'stats': {'hitpoints': 10, 'max_hit': 0, 'attack_speed': 4, 'attack_type': ['stab'],
                      'attack_level': 1, 'strength_level': 1, 'defence_level': 1, 'magic_level': 1,
                      'ranged_level': 1, 'aggressive': False, 'immune_poison': True, 'immune_venom': True},
            'combat': {'respawn': -1, 'deathTicks': 1, 'anims': {'spawn': 11536, 'attack': -1, 'block': -1, 'death': 11035}}}
    with Store(args.base_cache) as baseline:
        result = build(manifest, args.donor, set(baseline.files(2, 9)),
                       read(args.assets / 'data/content242/npc-metadata.json'),
                       read(args.assets / 'data/content242/item-values.json'))
    args.output.mkdir(parents=True)
    shutil.copytree(args.assets / 'Cache', args.output / 'Cache')
    shutil.copytree(args.assets / 'data', args.output / 'data')
    data = args.output / 'data'
    dump(data / 'content242.json', manifest)
    dump(data / 'npcs/combat/content242.json', result['combat'])
    dump(data / 'npcs/spawns/content242.json', result['spawns'])
    dump(data / 'content242-world.json', {'schemaVersion': 1, 'enabled': True,
         **{key: result[key] for key in ['loot', 'shopBindings', 'destinations', 'objectLinks']}})
    for shop in result['shops']:
        dump(data / 'shops' / (shop['identifier'] + '.yaml'), shop)
    dump(args.output / 'world-data-report.json', result['report'])
    dump(args.output / 'qa-activation.json', {'scope': 'isolated QA only; runtime and visual checks pending',
         'assetStage': str(args.assets.resolve()), 'enabledFeatures': flags, 'missingDependencies': unavailable})
    print(json.dumps({'world': result['report']['counts'], 'features': flags, 'missing': unavailable}, indent=2))


if __name__ == '__main__':
    main()
