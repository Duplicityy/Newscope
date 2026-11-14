"""Build an additive242 cache for the explicitly extended184 client.

The original cache/data and donor are read-only. Existing records, map names,
home regions and the dynamic-map allocation range are never overwritten.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

from cache_store import Reader, Store, name_hash
from import_content import Allocator, Importer, json_read, save_json, verify_preserved
from legacy_assets import landscape, landscape184, short, write_fields
from modern_definitions import (TERRAIN_MAGIC, read_npc, read_object, read_spot,
                                npc_models, object_models, terrain242, read_kit, retextures)
from modern_gear_metadata import charged_catalogue

PRIORITY_NPCS = {7221, 7222, 7223, 13685, 13688, *range(12077, 12084)}
PRIORITY_REGIONS = {(x << 8) | y for x in range(25, 28) for y in (48, 49)}


def load_requirements(paths):
    result = {}
    for path in paths:
        payload = json_read(path)
        if payload.get('sourceRevision') != 242: raise ValueError('Mechanics requirements must use source revision242')
        for feature,groups in payload['features'].items():
            target = result.setdefault(feature,{})
            for kind,values in groups.items():
                if kind not in ('items','npcs','objects','animations','graphics','regions') or not isinstance(values,list):
                    raise ValueError(f'Unsupported mechanics asset category {feature}/{kind}')
                if any(not isinstance(i,int) or not 0 <= i <= 65534 for i in values):
                    raise ValueError('Mechanics asset ID outside source bounds')
                target[kind] = sorted(set(target.get(kind,[]))|set(values))
    return result


def region_xy(region):
    return region >> 8, region & 255


def plan_regions(base, donor, extra=()):
    names = {g.name for g in base.table(5)[3].values()}
    occupied = {(x, y) for x in range(100) for y in range(256)
                if any(name_hash(f'{p}{x}_{y}') in names for p in ('m', 'l'))}
    available = {i for i, g in donor.table(5)[3].items() if {0, 1}.issubset(g.files)}
    selected = {i for i in available if region_xy(i) not in occupied} | PRIORITY_REGIONS | set(extra)
    selected &= available
    pending, components = set(selected), []
    while pending:
        component, todo = set(), [min(pending)]
        while todo:
            region = todo.pop()
            if region not in pending:
                continue
            pending.remove(region); component.add(region)
            x, y = region_xy(region)
            todo.extend((xx << 8) | yy for xx, yy in ((x-1,y),(x+1,y),(x,y-1),(x,y+1))
                        if 0 <= xx < 256 and 0 <= yy < 256)
        components.append(component)
    # Reserve all natural placements before relocating overlapping components.
    natural, relocated = [], []
    for component in components:
        cells = {region_xy(i) for i in component}
        (natural if all(x < 100 for x, _ in cells) and not cells & occupied else relocated).append(component)
    result, excluded = {}, []
    used = set(occupied)
    for component in natural:
        for region in component:
            result[region] = {'destination': region, 'dx': 0, 'dy': 0}
            used.add(region_xy(region))
    for component in sorted(relocated, key=lambda c: (-len(c), min(c))):
        cells = {region_xy(i) for i in component}
        minx, miny = min(x for x,y in cells), min(y for x,y in cells)
        width, height = max(x for x,y in cells)-minx+1, max(y for x,y in cells)-miny+1
        destination = next(((x,y) for x,y in [(14,48)] + [(x,y) for x in range(1,100-width+1) for y in range(1,256-height)]
                            if x+width <= 100 and y+height <= 256 and
                            all((x+xx,y+yy) not in used for xx in range(width) for yy in range(height))), None)
        if destination is None:
            excluded.append({'regions': sorted(component), 'reason': 'No contiguous low-X static-map rectangle available'})
            continue
        ox, oy = destination[0]-minx, destination[1]-miny
        for region in component:
            x,y = region_xy(region)
            target = ((x+ox)<<8)|(y+oy)
            result[region] = {'destination': target, 'dx': ox*64, 'dy': oy*64}
            used.add((x+ox,y+oy))
    assert all((v['destination'] >> 8) < 100 for v in result.values())
    assert len({v['destination'] for v in result.values()}) == len(result)
    return dict(sorted(result.items())), excluded


def closure(selected, metadata):
    seen, todo = set(selected), list(selected)
    while todo:
        source = todo.pop()
        for other in metadata.get(source, {}).get('morphs', []):
            if other not in (-1,65535) and other not in seen:
                seen.add(other); todo.append(other)
    return seen


def canonical(fields, models, heads=(), types=None):
    return (tuple(sorted(fields.items())), tuple(models), tuple(heads), None if types is None else tuple(types))


class BulkImporter(Importer):
    def __init__(self, base, donor, output, data, source, regions, requirements=None):
        super().__init__(base, donor, output, data, modern=True)
        self.source = source
        self.requirements = requirements or {}
        self.region_plan = regions
        self.npc_map, self.object_map, self.graphic_map = {}, {}, {}
        self.varp_map, self.varbit_map = {}, {}
        self.variables = {g: dict(base.files(2,g)) for g in (14,16)}
        self.var_alloc = {g: Allocator(self.variables[g], max(self.variables[g])+1) for g in (14,16)}
        self.var_alloc[16].next = max(4000,self.var_alloc[16].next)
        self.source_variables = {g: donor.files(2,g) for g in (14,16)}
        self.floors = {g: dict(base.files(2,g)) for g in (1,4)}
        self.floor_maps = {g: {} for g in (1,4)}
        self.floor_hashes = {g: {raw:i for i,raw in self.floors[g].items()} for g in (1,4)}
        self.floor_alloc = {g: Allocator(self.floors[g], max(self.floors[g])+1, 65453 if g == 1 else 65534) for g in (1,4)}
        self.report.update(excluded_regions=[], excluded_npcs=[], excluded_objects=[], excluded_graphics=[])
        self.report['limitations'] = [
            'Extended cache requires the matching K242-capable184 desktop/Android client build.',
            'Asset presence does not implement quests, bosses, special attacks, or object scripts.',
            'Original map regions remain unchanged; new regions use one shared translation per connected component.',
            'Imported ambient sounds, modern NPC head icons and modern map icons are not yet ported.',
            'Modern graphic rotate=no flags and NPC run/crawl variants retain184 rendering/movement behavior.',
            'Imported morph variables are isolated from original game state and initially zero.',
            'Unsupported structural dependencies withhold the entire affected map region.']

    def sequence(self, source):
        if source in (-1,65535):
            return 65535
        target = self.animation_importer.import_sequence(source)
        if target is None:
            raise ValueError(f'Animation {source}: {self.animation_importer.exclusions.get(source)}')
        return target

    def animations(self):
        print('Importing verified legacy and weighted animation dependencies', flush=True)
        for source in self.donor.files(2,12):
            self.animation_importer.import_sequence(source)

    def kits(self):
        values = dict(self.base.files(2,3));donor = self.donor.files(2,3)
        refreshed,excluded = [],[]
        for kit,raw in list(values.items()):
            if kit not in donor: continue
            try:
                fields,old_models,old_heads = read_kit(raw)
                modern,models,heads = read_kit(donor[kit])
                if fields != modern or old_models != models or old_heads != heads:
                    raise ValueError('Kit identity/appearance fields differ; original choice preserved')
                fields = retextures(fields,self.texture)
                fields[250] = bytes([len(models)]) + b''.join(self.model(i).to_bytes(4,'big') for i in models) + \
                    bytes([len(heads)]) + b''.join(bytes([slot])+self.model(i).to_bytes(4,'big') for slot,i in sorted(heads.items()))
                values[kit] = write_fields(fields);refreshed.append(kit)
            except (ValueError,KeyError,IndexError) as error:
                excluded.append({'id':kit,'reason':str(error)})
        self.out.put(2,3,values);self.changed.add(2)
        self.report['kit_visual_refreshes'] = refreshed
        self.report['excluded_kits'] = excluded

    def varp(self, source):
        if source in (-1,65535): return 65535
        if source not in self.varp_map:
            target = self.var_alloc[16].get()
            self.variables[16][target] = self.source_variables[16].get(source,b'\0')
            self.varp_map[source] = target
        return self.varp_map[source]

    def varbit(self, source):
        if source in (-1,65535): return 65535
        if source not in self.varbit_map:
            r = Reader(self.source_variables[14][source])
            if r.uint() != 1: raise ValueError('Unknown source varbit encoding')
            parent, low, high = r.uint(2),r.uint(),r.uint()
            if r.uint() != 0 or r.offset != len(r.data) or not 0 <= low <= high <= 31:
                raise ValueError('Invalid source varbit bounds')
            target = self.var_alloc[14].get()
            self.variables[14][target] = b'\1' + short(self.varp(parent)) + bytes([low,high,0])
            self.varbit_map[source] = target
        return self.varbit_map[source]

    def morph(self, meta, mapping):
        values = [65535 if i in (-1,65535) else mapping[i] for i in meta['morphs']]
        if not 2 <= len(values) <= 257: raise ValueError('Invalid morph variant count')
        return short(self.varbit(meta['varbit'])) + short(self.varp(meta['varp'])) + short(values[-1]) + \
            bytes([len(values)-2]) + b''.join(short(i) for i in values[:-1])

    def floor(self, source, group):
        if source in self.floor_maps[group]: return self.floor_maps[group][source]
        raw = self.donor.files(2,group)[source]
        if group == 4:
            r, result = Reader(raw),bytearray()
            while (op := r.uint()):
                result.append(op)
                if op in (1,7): result.extend(r.take(3))
                elif op == 2: result.append(self.texture(r.uint()))
                elif op not in (5,8): raise ValueError(f'Unsupported floor-overlay opcode {op}')
            if r.offset != len(raw): raise ValueError('Trailing floor-overlay bytes')
            raw = bytes(result)+b'\0'
        target = self.floor_hashes[group].get(raw)
        if target is None:
            target = self.floor_alloc[group].get()
            self.floors[group][target] = raw
            self.floor_hashes[group][raw] = target
        self.floor_maps[group][source] = target
        return target

    def definitions(self, kind, selected, source_defs, destination, parser, limit):
        metadata, fields = {}, {}
        for source, raw in source_defs.items():
            fields[source], metadata[source] = parser(raw)
        selected = closure(selected, metadata)
        encoded, rejected, semantic = {}, {}, {}
        for target, raw in destination.items():
            try:
                f,m = parser(raw,modern=False)
                if kind == 'object' and m['animation'] != -1: f[24] = short(m['animation'])
                if not m['morphs']:
                    semantic.setdefault(canonical(f,m['models'],m.get('headModels',()),m.get('modelTypes')),target)
            except ValueError:
                continue
        for source in sorted(selected):
            try:
                m,f = metadata[source],retextures(fields[source],self.texture)
                if 'per-model offsets' in m['unsupported']:
                    raise ValueError('NPC per-model translations require a native reader extension')
                models,heads = [self.model(i) for i in m['models']], [self.model(i) for i in m.get('headModels',[])]
                if kind == 'npc':
                    for op in (13,14,15,16,17):
                        if op in f:
                            f[op] = b''.join(short(self.sequence(int.from_bytes(f[op][i:i+2],'big'))) for i in range(0,len(f[op]),2))
                elif m['animation'] != -1:
                    f[24] = short(self.sequence(m['animation']))
                if m['morphs']:
                    self.varbit(m['varbit']);self.varp(m['varp'])
                key = canonical(f,models,heads,m.get('modelTypes'))
                encoded[source] = (f,key)
            except (KeyError,ValueError,IndexError) as error:
                rejected[source] = str(error)
        changed = True
        while changed:
            changed = False
            for source in selected - rejected.keys():
                missing = next((i for i in metadata[source]['morphs'] if i not in (-1,65535) and i in rejected),None)
                if missing is not None:
                    rejected[source] = f'Unsupported morph dependency {missing}';changed = True
        allocator = Allocator(destination,0,limit)
        mapping = {}
        for source in sorted(selected-rejected.keys()):
            f,key = encoded[source]
            target = semantic.get(key) if not metadata[source]['morphs'] else None
            if target is not None and target > limit: target = None
            if target is None:
                target = allocator.get(source)
                if not metadata[source]['morphs']: semantic[key] = target
            mapping[source] = target
        for source,target in mapping.items():
            if target in destination: continue
            f = encoded[source][0]
            m = metadata[source]
            f[250] = npc_models(m,self.model) if kind == 'npc' else object_models(m,self.model)
            if m['morphs']: f[118 if kind == 'npc' else 92] = self.morph(m,mapping)
            destination[target] = write_fields(f)
        self.report['excluded_'+('npcs' if kind == 'npc' else 'objects')] = [
            {'source_id':i,'name':metadata.get(i,{}).get('name'),'reason':reason} for i,reason in sorted(rejected.items())]
        for source,m in metadata.items():
            if source in mapping: m['targetId'] = mapping[source]
        return mapping,metadata

    def npcs(self):
        print('Resolving reachable NPCs and morphs into the14-bit protocol namespace', flush=True)
        spawns = json_read(self.source/'server/data/definitions/npc-spawns.json')
        selected = set(PRIORITY_NPCS)
        for requirements in self.requirements.values(): selected.update(requirements.get('npcs',[]))
        for row in spawns:
            if all(isinstance(row.get(k),int) for k in ('id','x','y')):
                if ((row['x']//64)<<8)|(row['y']//64) in self.region_plan:
                    selected.add(row['id'])
        values = dict(self.base.files(2,9))
        self.npc_map,self.npc_metadata = self.definitions('npc',selected,self.donor.files(2,9),values,read_npc,16383)
        self.out.put(2,9,values);self.changed.add(2)
        self.report['npcs'] = {'mapped':len(self.npc_map),'added':len(values)-len(self.base.files(2,9)),
                               'maximumNetworkId':max(self.npc_map.values(),default=-1)}

    def graphics(self):
        print('Importing spot graphics with verified models and sequence dependencies', flush=True)
        values = dict(self.base.files(2,13));semantic = {}
        for target,raw in values.items():
            f,m,a = read_spot(raw);semantic.setdefault((tuple(sorted(f.items())),m,a),target)
        allocator = Allocator(values,max(values)+1)
        for source,raw in self.donor.files(2,13).items():
            try:
                f,m,a = read_spot(raw)
                f = retextures(f,self.texture)
                m = self.model(m)
                a = -1 if a == -1 else self.sequence(a)
                key = (tuple(sorted(f.items())),m,a)
                target = semantic.get(key)
                if target is None:
                    target = allocator.get(source)
                    f[250] = m.to_bytes(4,'big')
                    if a != -1: f[2] = short(a)
                    values[target] = write_fields(f);semantic[key] = target
                self.graphic_map[source] = target
            except (ValueError,KeyError,IndexError) as error:
                self.report['excluded_graphics'].append({'source_id':source,'reason':str(error)})
        self.out.put(2,13,values);self.changed.add(2)
        self.report['graphics'] = {'mapped':len(self.graphic_map),'added':len(values)-len(self.base.files(2,13))}

    def areas(self):
        print(f'Converting {len(self.region_plan)} new map regions and reachable scenery', flush=True)
        names = {g.name:gid for gid,g in self.base.table(5)[3].items()}
        # A marker collision in any old terrain would invalidate detection.
        for x in range(256):
            for y in range(256):
                gid = names.get(name_hash(f'm{x}_{y}'))
                if gid is not None and self.base.files(5,gid)[0].startswith(TERRAIN_MAGIC[:8]):
                    raise ValueError('Terrain marker collides with a legacy map')
        source_maps, selected = {},set()
        for requirements in self.requirements.values(): selected.update(requirements.get('objects',[]))
        for source in self.region_plan:
            try:
                files = self.donor.files(5,source)
                objects = landscape(files[1]);selected.update(objects)
                source_maps[source] = (files[0],objects)
            except (ValueError,KeyError,IndexError) as error:
                self.report['excluded_regions'].append({'source_id':source,'reason':f'Invalid source landscape: {error}'})
        self.object_map,self.object_metadata = self.definitions('object',selected,self.donor.files(2,6),self.new_objects,read_object,65534)
        keys = json_read(self.data_root/'region_keys.json')
        mapped = {}
        for source,entry in self.region_plan.items():
            if source not in source_maps: continue
            try:
                terrain,objects = source_maps[source]
                missing = set(objects)-self.object_map.keys()
                if missing: raise ValueError(f'Unsupported structural object dependencies: {sorted(missing)}')
                sx,sy = region_xy(source);dx,dy = region_xy(entry['destination'])
                terrain = terrain242(terrain,lambda i:self.floor(i,4),lambda i:self.floor(i,1),(sx*64,sy*64))
                converted = {}
                for oid,placements in objects.items(): converted.setdefault(self.object_map[oid],[]).extend(placements)
                scenery = landscape184(converted)
                for prefix,raw in (('m',terrain),('l',scenery)):
                    assert name_hash(f'{prefix}{dx}_{dy}') not in names, 'Original map replacement forbidden'
                    self.out.put(5,self.alloc[5].get(),{0:raw},name=name_hash(f'{prefix}{dx}_{dy}'))
                keys[str(entry['destination'])] = [0,0,0,0]
                mapped[source] = entry
                self.report['map_regions'].append({'source':[sx,sy],'destination':[dx,dy],'placements':sum(map(len,objects.values()))})
            except (ValueError,KeyError,IndexError) as error:
                self.report['excluded_regions'].append({'source_id':source,'reason':str(error)})
        self.region_plan = mapped
        for group,values in self.floors.items(): self.out.put(2,group,values)
        self.out.put(2,6,self.new_objects)
        save_json(self.data_root/'region_keys.json',keys)
        self.report['scenery_definitions'] = len(self.object_map)
        self.report['floor_mapping'] = self.floor_maps
        self.changed.update((2,5))

    def finish(self):
        self.report['animations'] = self.animation_importer.finish()
        self.report['model_banks'] = self.model_banks.finish()
        for group,values in self.variables.items(): self.out.put(2,group,values)
        self.changed.add(2)
        report = super().finish()
        maps = {'items':self.all_item_map,'npcs':self.npc_map,'objects':self.object_map,
                'animations':self.animation_importer.sequences,'graphics':self.graphic_map,
                'regions':self.region_plan,'varps':self.varp_map,'varbits':self.varbit_map}
        report['mechanics_asset_requirements'] = {
            feature:{'available':all(source in maps[kind] for kind,ids in groups.items() for source in ids),
                     'missing':{kind:[source for source in ids if source not in maps[kind]] for kind,ids in groups.items()
                                if any(source not in maps[kind] for source in ids)}}
            for feature,groups in self.requirements.items()}
        landing_region = (25<<8)|48
        landing = None
        if landing_region in self.region_plan:
            entry = self.region_plan[landing_region]
            landing = [1653+entry['dx'],3135+entry['dy'],0]
        report['map_landing'] = landing
        manifest = {'schemaVersion':2,'sourceRevision':242,'clientProtocol':184,
                    'requiredClientCapability':'k242-skeletal-v1','enabled':True,'landing':landing,
                    'equipment':[{'id':e['target_id'],'name':e['name'],'status':e['status']} for e in report['equipment']
                                 if e['status'].startswith('basic-equipment')] + charged_catalogue(report['equipment']),
                    'mappings':maps,'mechanics':{'enabled':False,'features':{feature:False for feature in self.requirements}}}
        save_json(self.data_root/'content242.json',manifest)
        save_json(self.data_root/'content242/npc-metadata.json',self.npc_metadata)
        save_json(self.data_root/'content242/object-metadata.json',self.object_metadata)
        save_json(self.data_root/'content242/item-values.json',{i:int.from_bytes(f.get(12,b'\0\0\0\1'),'big') for i,f in self.fields.items()})
        (self.out.root/'content242-format.properties').write_text('format=k242-skeletal-v1\njs5_revision=184242\n',encoding='ascii')
        report['manifestBytes'] = (self.data_root/'content242.json').stat().st_size
        return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base',type=Path,required=True);p.add_argument('--donor',type=Path,required=True)
    p.add_argument('--source',type=Path,required=True);p.add_argument('--data',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--region',type=int,action='append',default=[])
    p.add_argument('--requirements',type=Path,action='append')
    p.add_argument('--plan-only',action='store_true')
    a = p.parse_args()
    paths = a.requirements
    if paths is None:
        folder = Path(__file__).resolve().parent.parent/'Kronos-master/tools/content242'
        paths = [folder/'mechanics-assets.json',folder/'mechanics-burn-assets.json']
    requirements = load_requirements(paths)
    extra = set(a.region)
    for values in requirements.values(): extra.update(values.get('regions',[]))
    if a.output.exists(): raise ValueError('Output must be a new directory')
    with Store(a.base) as base,Store(a.donor) as donor:
        regions,excluded = plan_regions(base,donor,extra)
        a.output.mkdir(parents=True)
        save_json(a.output/'region-plan.json',regions)
        save_json(a.output/'region-plan-exclusions.json',excluded)
        if a.plan_only:
            print(f'Planned {len(regions)} regions; {len(excluded)} unplaceable components');return
        shutil.copytree(a.base,a.output/'Cache');shutil.copytree(a.data,a.output/'data')
        with Store(a.output/'Cache',writable=True) as out:
            importer = BulkImporter(base,donor,out,a.output/'data',a.source,regions,requirements)
            importer.index_models();importer.items(a.source);importer.kits();importer.animations()
            importer.areas();importer.npcs();importer.graphics()
            report = importer.finish()
    report['preservation'] = verify_preserved(a.base,a.output/'Cache',{e['id'] for e in report['visual_refreshes']},report['kit_visual_refreshes'])
    save_json(a.output/'import-report.json',report)
    print(json.dumps({k:report[k] for k in ('npcs','graphics','model_banks','manifestBytes','preservation')},indent=2))
    print('Extended242 staging cache assembled; no live data or process changed.',flush=True)


if __name__ == '__main__': main()
