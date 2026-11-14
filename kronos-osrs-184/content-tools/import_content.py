"""Build an isolated, reproducible 242 asset backport without touching production.

The source cache and server data are read-only. A new output directory is required.
Unsupported assets are listed in the report rather than passed to the old renderer.
"""
import argparse
import copy
import hashlib
import json
import math
import re
import shutil
from pathlib import Path

from cache_store import Store, Reader, name_hash
from legacy_assets import (read_item, write_fields, ITEM_MODEL, ITEM_LINK, short,
                           model184, model_layout, model_texture_ids, texture184, sprite184,
                           terrain184, landscape, landscape184, object184)

ITEM_VISUAL = ITEM_MODEL | {4, 5, 6, 7, 8, 40, 41, 95, 110, 111, 112, 113, 114, 250}


def json_read(path):
    # Kronos permits hash comments outside quoted JSON strings.
    text = path.read_text(encoding="utf-8-sig")
    out, quoted, escaped, comment = [], False, False, False
    for c in text:
        if comment:
            if c == '\n':
                comment = False
                out.append(c)
            continue
        if c == '#' and not quoted:
            comment = True
            continue
        out.append(c)
        if c == '"' and not escaped:
            quoted = not quoted
        escaped = c == '\\' and not escaped
    return json.loads(''.join(out))


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True) + '\n', encoding='utf-8')


def name(fields):
    return fields.get(2, b'null\0')[:-1].decode('cp1252', errors='replace')


class Allocator:
    def __init__(self, used, start=0, limit=65534):
        self.used = set(used)
        self.next = start
        self.limit = limit

    def get(self, preferred=None):
        if preferred is not None and 0 <= preferred <= self.limit and preferred not in self.used:
            self.used.add(preferred)
            return preferred
        while self.next in self.used:
            self.next += 1
        if self.next > self.limit:
            raise ValueError(f'184 ID capacity exhausted at {self.limit}')
        value = self.next
        self.used.add(value)
        return value


class Importer:
    def __init__(self, original, donor, output, project_data, *, modern=False):
        self.modern = modern
        self.base = original
        self.donor = donor
        self.out = output
        self.data_root = project_data
        self.changed = set()
        self.maps = {}
        self.models = {}
        self.sprites = {}
        self.textures = {}
        self.new_textures = dict(self.base.files(9, 0))
        self.new_items = dict(self.base.files(2, 10))
        self.new_objects = dict(self.base.files(2, 6))
        self.alloc = {i: Allocator(self.base.table(i)[3], max(self.base.table(i)[3])+1)
                      for i in (5, 7, 8)}
        self.texture_alloc = Allocator(self.new_textures, 0, 254)
        self.model_hashes = {}
        self.sprite_hashes = {}
        if modern:
            from modern_definitions import ModelBanks
            self.model_banks = ModelBanks(original, output)
        self.report = {'source_revision': 242, 'client_revision': 184,
                       'source_cache': 'https://archive.openrs2.org/caches/runescape/2757',
                       'source_commit': '1ccb548be725c70900b604968354dd3147b839ea',
                       'excluded_items': [], 'visual_refreshes': [], 'map_regions': [], 'omitted_scenery': [],
                       'limitations': ['242 weighted skeletal animations are not supported by the 184 engine.',
                                       'Importing an item does not implement its special attack, set effect, quest or source boss.',
                                       'New map scenery has no imported quests, NPCs or object scripts.']}

    def index_models(self):
        print('Indexing existing models and texture sprites for content deduplication', flush=True)
        for mid in self.base.table(7)[3]:
            raw = self.base.files(7, mid)[0]
            self.model_hashes.setdefault(hashlib.sha256(raw).digest(), mid)
        for tid, tex in self.new_textures.items():
            if len(tex) == 12 and tex[3] == 1:
                sid = int.from_bytes(tex[4:6], 'big')
                raw = self.base.files(8, sid)[0]
                self.sprite_hashes.setdefault(hashlib.sha256(raw).digest(), sid)

    def sprite(self, source):
        if source in self.sprites:
            return self.sprites[source]
        raw = sprite184(self.donor.files(8, source)[0])
        digest = hashlib.sha256(raw).digest()
        target = self.sprite_hashes.get(digest)
        if target is None:
            target = self.alloc[8].get()
            self.out.put(8, target, {0: raw})
            self.changed.add(8)
            self.sprite_hashes[digest] = target
        self.sprites[source] = target
        return target

    def texture(self, source):
        if source in self.textures:
            return self.textures[source]
        raw = texture184(self.donor.files(9, 0)[source], self.sprite)
        target = next((i for i, existing in self.new_textures.items() if existing == raw), None)
        if target is None:
            target = self.texture_alloc.get()
            self.new_textures[target] = raw
        self.textures[source] = target
        return target

    def model(self, source):
        if source in self.models:
            return self.models[source]
        if self.modern:
            from modern_model import weighted_model184
            raw = weighted_model184(self.donor.files(7, source)[0], self.texture)
        else:
            raw = model184(self.donor.files(7, source)[0], self.texture)
        digest = hashlib.sha256(raw).digest()
        target = self.model_hashes.get(digest)
        if target is None:
            if self.modern:
                target = self.model_banks.add(raw)
            else:
                target = self.alloc[7].get()
                self.out.put(7, target, {0: raw})
            self.changed.add(7)
            self.model_hashes[digest] = target
        self.models[source] = target
        return target

    def items(self, donor_root):
        print('Resolving new items, links and genuinely custom item ID collisions', flush=True)
        original = {i: read_item(raw) for i, raw in self.new_items.items()}
        fields, metadata = {}, {}
        for i, raw in self.donor.files(2, 10).items():
            metadata[i] = {}
            fields[i] = read_item(raw, metadata[i])
        meaningful = lambda f: name(f).lower() not in ('null', '') or any(k in f for k in ITEM_LINK)
        selected = {i for i, f in fields.items() if meaningful(f) and
                    (i not in original or (name(original[i]).lower() == 'null' and name(f).lower() != 'null')
                     or (i >= 24000 and name(f).lower() != name(original[i]).lower()))}
        if self.modern:
            from modern_definitions import animation_item_dependencies
            held = animation_item_dependencies(self.donor.files(2,12),fields)
            selected.update(i for i in held if i not in original or name(fields[i]).lower() in ('null','')
                            or name(fields[i]).lower() != name(original[i]).lower())
            self.report['animation_item_dependencies'] = sorted(held)
        # Anonymous stack-count variants have a model but no name. They must
        # accompany their parent item even though they are not stand-alone items.
        pending = list(selected)
        while pending:
            source_id = pending.pop()
            for op, payload in fields[source_id].items():
                if op not in ITEM_LINK and not 100 <= op < 110:
                    continue
                linked = int.from_bytes(payload[:2], 'big')
                if linked in fields and linked not in original and linked not in selected:
                    selected.add(linked)
                    pending.append(linked)
        # A collision is relocated, never written on top of a Kronos custom item.
        # IDs shared with existing unchanged OSRS items remain original IDs.
        source_models = {}
        unsupported = {}
        for i in sorted(selected):
            if self.modern:
                try:
                    from modern_definitions import retextures
                    retextures(fields[i],self.texture)
                except (ValueError,KeyError) as e:
                    unsupported[i] = f'Retexture dependency: {e}'
            for op in ITEM_MODEL:
                if op not in fields[i]:
                    continue
                mid = int.from_bytes(fields[i][op][:2], 'big')
                if mid not in source_models:
                    try:
                        raw = self.donor.files(7, mid)[0]
                        if self.modern:
                            from modern_model import weighted_model184
                            weighted_model184(raw)
                            self.model(mid)  # Also validate texture/sprite dependencies before selecting the item.
                            source_models[mid] = model_layout(raw)
                        else:
                            source_models[mid] = model_layout(model184(raw))
                    except (ValueError, KeyError) as e:
                        source_models[mid] = str(e)
                if isinstance(source_models[mid], str):
                    unsupported[i] = f'model {mid}: {source_models[mid]}'
                    break
        # Include anonymous link dependencies, but reject dangling chains.
        changed = True
        while changed:
            changed = False
            for i in sorted(selected):
                if i in unsupported:
                    continue
                links = [int.from_bytes(payload[:2], 'big') for op, payload in fields[i].items()
                         if op in ITEM_LINK or 100 <= op < 110]
                for linked in links:
                    if linked in unsupported or (linked not in original and linked not in selected):
                        unsupported[i] = f'Unavailable linked item {linked}'
                        changed = True
                        break
        selected -= unsupported.keys()
        self.report['excluded_items'] = [{'source_id': i, 'name': name(fields[i]), 'reason': reason}
                                         for i, reason in sorted(unsupported.items())]
        alloc = Allocator(set(original) | selected, 34766, 65022)
        mapping = {i: i for i in fields if i in original and i not in selected}
        for i in sorted(selected):
            mapping[i] = alloc.get() if i in original else i
        for i in sorted(selected):
            converted = dict(fields[i])
            if self.modern:
                from modern_definitions import item_models, retextures
                converted = item_models(converted, self.model)
                converted = retextures(converted,self.texture)
            else:
                for op in ITEM_MODEL:
                    if op in converted:
                        payload = converted[op]
                        converted[op] = short(self.model(int.from_bytes(payload[:2], 'big'))) + payload[2:]
            for op in ITEM_LINK | set(range(100, 110)):
                if op in converted:
                    payload = converted[op]
                    converted[op] = short(mapping[int.from_bytes(payload[:2], 'big')]) + payload[2:]
            # Only actions implemented by the legacy client/server are exposed.
            for op in range(30, 40):
                if op in converted and converted[op].lower() not in (b'take\0', b'drop\0', b'wield\0', b'wear\0', b'equip\0', b'hold\0'):
                    converted.pop(op)
            self.new_items[mapping[i]] = write_fields(converted)
        self.item_map = {i: mapping[i] for i in sorted(selected)}
        self.all_item_map = {i: target for i, target in mapping.items()
                             if i in selected or name(original[i]).lower() == name(fields[i]).lower()}
        self.fields = fields
        self.metadata = metadata
        # Refresh exact same-name, same-ID standard OSRS equipment only. Retain
        # names, actions, links and all gameplay metadata. No fuzzy custom matches.
        visual_ops = ITEM_VISUAL
        for i in sorted(set(original) & set(fields)):
            if i in selected or name(original[i]).lower() != name(fields[i]).lower():
                continue
            if 13 not in metadata[i] or not any(op in fields[i] for op in (23, 25)):
                continue
            if not self.modern and all(original[i].get(op) == fields[i].get(op) for op in visual_ops):
                continue
            try:
                converted = {}
                for op in visual_ops:
                    converted.pop(op, None)
                    if op in fields[i]:
                        payload = fields[i][op]
                        if op in ITEM_MODEL and not self.modern:
                            payload = short(self.model(int.from_bytes(payload[:2], 'big'))) + payload[2:]
                        converted[op] = payload
                if self.modern:
                    from modern_definitions import item_models, retextures
                    converted = item_models(converted, self.model)
                    converted = retextures(converted,self.texture)
                spans = []
                read_item(self.new_items[i], spans=spans)
                retained = b''.join(raw for opcode, raw in spans if opcode not in visual_ops)
                self.new_items[i] = retained + write_fields(converted)
                self.report['visual_refreshes'].append({'id': i, 'name': name(original[i])})
            except (ValueError, KeyError):
                # The working original appearance is the safe fallback.
                continue
        print(f'Imported {len(selected)} item records; {len(unsupported)} exclusions; {len(self.report["visual_refreshes"])} visual refreshes', flush=True)
        from gear_metadata import build_metadata
        existing_types = json_read(self.data_root / 'items/weapon_types.json')
        additions = build_metadata(self.item_map, fields, metadata, existing_types,
                                   gameplay=json_read(donor_root / 'server/data/definitions/item-gameplay.json'),
                                   weapon_stats=json_read(donor_root / 'server/data/definitions/weapon-stats.json'))
        if self.modern:
            # These standard184 records already have their genuine Wear/Check
            # actions but the baseline server omitted their equipment metadata.
            existing_metadata = {row['id'] for row in json_read(self.data_root / 'items/item_info.json')}
            crystal = {i:self.all_item_map[i] for i in (23971,23973,23975,23977,23979,23981)
                       if i in self.all_item_map and self.all_item_map[i] not in existing_metadata and i not in self.item_map}
            extra = build_metadata(crystal,fields,metadata,existing_types,
                gameplay=json_read(donor_root / 'server/data/definitions/item-gameplay.json'),
                weapon_stats=json_read(donor_root / 'server/data/definitions/weapon-stats.json'))
            additions['item_info'].extend(extra['item_info']);additions['report'].extend(extra['report'])
            additions['weapon_types'].update(extra['weapon_types'])
        if self.modern:
            from modern_gear_metadata import CHARGED, actions, metadata as charged_metadata
            gameplay = json_read(donor_root / 'server/data/definitions/item-gameplay.json')
            if isinstance(gameplay,list): gameplay = {row['id']:row for row in gameplay}
            rows = {row['id']:row for row in additions['item_info']}
            summaries = {row['donor_id']:row for row in additions['report']}
            for source in sorted(set(CHARGED) & self.item_map.keys()):
                target = self.item_map[source]
                row,profiles,summary = charged_metadata(source,target,fields[source],metadata[source],
                    gameplay.get(source,gameplay.get(str(source),{})),existing_types)
                rows[target].update(row);additions['weapon_types'].update(profiles)
                summaries[source].update(summary)
                converted = read_item(self.new_items[target])
                for op in range(35,40): converted.pop(op,None)
                converted.update(actions(source,fields[source]))
                self.new_items[target] = write_fields(converted)
        from animation_import import AnimationImporter, upgrade_weapon_profiles
        animation_importer = AnimationImporter(self.base, self.donor, self.out,
                                              item_map=self.all_item_map, allow_weighted=self.modern)
        self.animation_importer = animation_importer
        self.report['weapon_animations'] = upgrade_weapon_profiles(
            animation_importer, additions, self.item_map,
            json_read(donor_root / 'server/data/definitions/item-gameplay.json'), existing_weapon_types=existing_types)
        if not self.modern:
            self.report['animations'] = animation_importer.finish()
        for entry in additions['report']:
            if self.modern and entry['donor_id'] not in self.item_map:
                continue  # Existing Crystal armour actions are preserved byte-for-byte.
            target = entry['target_id']
            updated = read_item(self.new_items[target])
            for opcode in entry['remove_inventory_options']:
                updated.pop(opcode, None)
            self.new_items[target] = write_fields(updated)
        existing_info = json_read(self.data_root / 'items/item_info.json')
        existing_ids = {row['id'] for row in existing_info}
        assert not (existing_ids & set(self.item_map.values())), 'Metadata collision'
        save_json(self.data_root / 'items/item_info.json', existing_info + additions['item_info'])
        save_json(self.data_root / 'items/weapon_types.json', {**existing_types, **additions['weapon_types']})
        self.report['equipment'] = additions['report']
        self.report['item_mapping'] = self.item_map
        self.out.put(2, 10, self.new_items)
        self.changed.add(2)

    def area(self):
        print('Converting six Varlamore landscape regions into unused map slots', flush=True)
        used_names = {g.name for g in self.base.table(5)[3].values()}
        destination = next(((x, y) for x, y in [(14,48),(10,42),(24,22),(10,50),(15,45),(16,65)]
                            if all(name_hash(f'{prefix}{x+dx}_{y+dy}') not in used_names
                                   for dx in range(3) for dy in range(2) for prefix in ('m','l'))), None)
        if destination is None:
            raise ValueError('No unused map rectangle available')
        floors = {group: dict(self.base.files(2, group)) for group in (1, 4)}
        floor_maps = {1: {}, 4: {}}
        floor_alloc = {1: Allocator(floors[1], 0, 173), 4: Allocator(floors[4], 0, 254)}

        def floor(source, group):
            if source in floor_maps[group]:
                return floor_maps[group][source]
            raw = self.donor.files(2, group)[source]
            if group == 4:
                r, out = Reader(raw), bytearray()
                while op := r.uint():
                    out.append(op)
                    if op in (1, 7):
                        out.extend(r.take(3))
                    elif op == 2:
                        out.append(self.texture(r.uint()))
                    elif op not in (5, 8):
                        raise ValueError(f'Unsupported overlay opcode {op}')
                raw = bytes(out) + b'\0'
            target = next((i for i, other in floors[group].items() if raw == other), None)
            if target is None:
                target = floor_alloc[group].get()
                floors[group][target] = raw
            floor_maps[group][source] = target
            return target

        source_locs = self.donor.files(2, 6)
        loc_map, loc_fields = {}, {}
        loc_alloc = Allocator(self.new_objects, max(self.new_objects) + 1)
        def loc(source, stack=()):
            if source in loc_map:
                return loc_map[source]
            if source in stack:
                raise ValueError('Cyclic scenery morph')
            fields, morphs = object184(source_locs[source], self.model)
            if not any(op in fields for op in (1, 5)):
                for variant in morphs:
                    if variant not in source_locs or variant in stack:
                        continue
                    vf, _ = object184(source_locs[variant], self.model)
                    if any(op in vf for op in (1, 5)):
                        fields = {**fields, **vf}
                        break
            target = loc_alloc.get()
            self.new_objects[target] = write_fields(fields)
            loc_map[source] = target
            return target

        keys = json_read(self.data_root / 'region_keys.json')
        for sx in range(25, 28):
            for sy in range(48, 50):
                files = self.donor.files(5, (sx << 8) | sy)
                dx, dy = sx - 25 + destination[0], sy - 48 + destination[1]
                terrain = terrain184(files[0], lambda i: floor(i, 4), lambda i: floor(i, 1), source_origin=(sx*64, sy*64))
                objects = landscape(files[1])
                converted = {}
                for source_id, placements in objects.items():
                    if source_id == 59911:
                        # Sailing's decorative hoisted sail has 5710 faces;
                        # the retained software renderer supports only 4700.
                        self.report['omitted_scenery'].append({'source_id': source_id, 'name': 'Hoisted sail',
                                                              'region': [sx, sy], 'placements': placements,
                                                              'reason': '5710 faces exceeds unchanged 184 renderer capacity'})
                        continue
                    converted[loc(source_id)] = placements
                scenery = landscape184(converted)
                for prefix, raw in [('m', terrain), ('l', scenery)]:
                    gid = self.alloc[5].get()
                    self.out.put(5, gid, {0: raw}, name=name_hash(f'{prefix}{dx}_{dy}'))
                keys[str((dx << 8) | dy)] = [0, 0, 0, 0]
                self.report['map_regions'].append({'source': [sx, sy], 'destination': [dx, dy],
                                                   'placements': sum(map(len, objects.values()))})
        for group, values in floors.items():
            self.out.put(2, group, values)
        self.out.put(2, 6, self.new_objects)
        save_json(self.data_root / 'region_keys.json', keys)
        self.report['map_landing'] = [1653 + (destination[0]-25)*64, 3135 + (destination[1]-48)*64, 0]
        self.report['scenery_definitions'] = len(loc_map)
        self.changed.update((2, 5))

    def finish(self):
        if self.new_textures != self.base.files(9, 0):
            self.out.put(9, 0, self.new_textures)
            self.changed.add(9)
        for index in sorted(self.changed):
            self.out.commit_table(index)
        self.report['model_mapping'] = self.models
        self.report['texture_mapping'] = self.textures
        self.report['sprite_mapping'] = self.sprites
        self.report['added_model_groups'] = len(self.out.table(7)[3]) - len(self.base.table(7)[3])
        self.report['added_textures'] = len(self.new_textures) - len(self.base.files(9, 0))
        return self.report


def verify_preserved(base_path, output_path, refreshes, kit_refreshes=()):
    print('Verifying all original map/model groups and unchanged definitions', flush=True)
    checked = 0
    with Store(base_path) as base, Store(output_path) as out:
        for idx in range(21):
            try:
                groups = base.table(idx)[3]
            except (KeyError, FileNotFoundError):
                continue
            for gid in groups:
                if idx == 2 and gid in (1, 3, 4, 6, 9, 10, 12, 13, 14, 16) or idx == 9 and gid == 0:
                    old, new = base.files(idx, gid), out.files(idx, gid)
                    for fid, raw in old.items():
                        if idx == 2 and gid == 3 and fid in kit_refreshes:
                            from modern_definitions import read_kit
                            nonvisual = lambda fields: {k:v for k,v in fields.items() if k != 41}
                            assert nonvisual(read_kit(raw)[0]) == nonvisual(read_kit(new[fid])[0]), f'Nonvisual kit data changed {fid}'
                            continue
                        if idx == 2 and gid == 10 and fid in refreshes:
                            old_spans, new_spans = [], []
                            read_item(raw, spans=old_spans)
                            read_item(new[fid], spans=new_spans)
                            retained = lambda spans: [payload for op, payload in spans if op not in ITEM_VISUAL]
                            assert retained(old_spans) == retained(new_spans), f'Nonvisual item data changed {fid}'
                            continue
                        assert new[fid] == raw, f'Original definition changed {idx}/{gid}/{fid}'
                else:
                    assert out.read(idx, gid) == base.read(idx, gid), f'Original group changed {idx}/{gid}'
                checked += 1
    return {'original_groups_checked': checked, 'original_maps_unchanged': True,
            'original_models_unchanged': True, 'allowed_item_visual_refreshes': len(refreshes)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=Path('kronos-osrs-184/Kronos-master/Cache'))
    parser.add_argument('--donor', type=Path, default=Path('incoming/content-242/cache'))
    parser.add_argument('--source', type=Path, default=Path('incoming/content-242/tsps-1ccb548be725c70900b604968354dd3147b839ea'))
    parser.add_argument('--data', type=Path, default=Path('kronos-osrs-184/Kronos-master/kronos-server/data'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Output must be a new directory; never overwrite an existing cache')
    args.output.mkdir(parents=True)
    shutil.copytree(args.base, args.output / 'Cache')
    shutil.copytree(args.data, args.output / 'data')
    with Store(args.base) as original, Store(args.donor) as donor, Store(args.output / 'Cache', writable=True) as output:
        importer = Importer(original, donor, output, args.output / 'data')
        importer.index_models()
        importer.items(args.source)
        importer.area()
        report = importer.finish()
    report['preservation'] = verify_preserved(args.base, args.output / 'Cache', {i['id'] for i in report['visual_refreshes']})
    save_json(args.output / 'data/content242.json', {
        'enabled': True, 'landing': report['map_landing'],
        'equipment': [{'id': entry['target_id'], 'name': entry['name'], 'status': entry['status']}
                      for entry in report['equipment'] if entry['status'].startswith('basic-equipment')]
    })
    save_json(args.output / 'import-report.json', report)
    print(json.dumps({k: report[k] for k in ('added_model_groups', 'added_textures', 'map_landing', 'preservation')}, indent=2))
    print('Staging cache ready; no production files or processes changed.', flush=True)


if __name__ == '__main__':
    main()
