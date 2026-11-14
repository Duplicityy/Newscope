"""Create a disabled, independently fingerprinted profile from the unmodified 184 cache.

This never writes cache files. A separate real-server collision test must produce
collision-audit.properties before production can be enabled.
"""
import argparse
import bz2
import hashlib
import json
from pathlib import Path
import struct
import sys
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "content242"))
from cache_store import Store, Reader
from legacy_assets import LOC_KEEP, read_params

NPCS = dict(zip("""grave_thrall crypt_rat ash_reaver bone_guard rusted_knight
hollow_swordsman fetid_crawler tomb_stalker chainbound_dead ossuary_archer
shard_slinger drowned_marksman crypt_skirmisher bone_javeliner cinder_acolyte
hollow_invoker rune_wraith mourning_seer veil_cultist black_iron_sentinel
crypt_executioner gloom_huntress sepulchral_magus veil_knight guardian_ash
guardian_bone guardian_veil boss_hollow_warden boss_mourning_regent boss_ashen_arbiter""".split(),
[70, 2492, 71, 70, 508, 71, 449, 2514, 72, 3160, 2211, 3160, 2211, 2242, 443,
 444, 85, 86, 512, 508, 2514, 2242, 445, 508, 2514, 2242, 87, 2515, 7258, 2516]))
OBJECTS = {
    "portal.entry": 23311, "portal.exit": 25014,
    "chest.closed": 75, "chest.open": 76,
    "door.closed": 2010, "door.open": 19871,
    "shrine.ready": 11018, "shrine.used": 11017,
    "obstacle.trigger": 23556, "obstacle.landing": 23556, "obstacle.gap": 13334,
    "wall.ashen_catacombs": 6950, "wall.drowned_ossuary": 6950,
    "wall.ironbound_crypt": 6950, "wall.hollow_sanctum": 6950,
    "decor.ashen_catacombs": 11019, "decor.drowned_ossuary": 414,
    "decor.ironbound_crypt": 2440, "decor.hollow_sanctum": 155,
}
GRAPHICS = {"trap.warning": 85, "trap.success": 436, "trap.failure": 85,
    "boss.warning": 85, "boss.impact": 85, "portal.departure": 343,
    "portal.arrival": 1039, "shrine.activation": 436,
    "combat.magic-projectile": 130, "combat.ranged-projectile": 1384}
TEMPLATES = """crypt_hall broken_refectory sunken_archive ossuary_crossing
sentry_chamber sealed_reliquary ritual_chamber burial_gallery watchers_crypt
marrow_avenue forgotten_shrine iron_vault gauntlet_straight gauntlet_corner""".split()
# One independently verified solid brown floor reused across themes. Transparent
# magenta overlays erase client floor triangles even when an underlay is present.
# Theme scenery remains distinct; barriers use a low, solid native boulder mesh.
FLOORS = [(322,659)]
MAX_SOURCE_ARCHIVE = 4 * 1024 * 1024

def sha(data): return hashlib.sha256(data).hexdigest()

def name_hash(value):
    result = 0
    for c in value: result = (result * 31 + ord(c)) & 0xffffffff
    return result

def npc(data):
    r = Reader(data); result = {"name":"", "size":1, "models":[], "options":[]}
    while (op := r.uint()):
        if op in (1,60):
            models = [r.uint(2) for _ in range(r.uint())]
            if op == 1: result["models"] = models
        elif op == 2: result["name"] = r.string()[:-1].decode("cp1252", "replace")
        elif op == 12: result["size"] = r.uint()
        elif op in (13,14,15,16,18,95,97,98,102,103): r.take(2)
        elif op == 17: r.take(8)
        elif 30 <= op <= 34: result["options"].append(r.string()[:-1].decode("cp1252", "replace"))
        elif op in (40,41): r.take(r.uint()*4)
        elif op in (93,99,107,109,111): pass
        elif op in (100,101): r.take(1)
        elif op in (106,118): raise ValueError("Morphing skin is unsuitable")
        elif op == 249: read_params(r)
        else: raise ValueError(f"Unknown NPC opcode {op}")
    assert r.offset == len(data)
    return result

def object184(data, unused=None):
    # Native 184 sound opcodes 78/79 have shorter payloads than the 242 importer.
    r=Reader(data);fields={}
    while (op:=r.uint()):
        if op in LOC_KEEP:fields[op]=r.take(LOC_KEEP[op])
        elif op in (1,5):
            count=r.uint();fields[op]=bytes([count])+r.take(count*(3 if op==1 else 2))
        elif op==2:fields[op]=r.string()
        elif 30<=op<=34:r.string()
        elif op in (40,41):
            count=r.uint();fields[op]=bytes([count])+r.take(count*4)
        elif op in (24,60,68,82):r.take(2)
        elif op in (77,92):raise ValueError('Morphing scenery is unsuitable')
        elif op==78:r.take(3)
        elif op==79:r.take(5);r.take(r.uint()*2)
        elif op==249:read_params(r)
        else:raise ValueError(f'Unknown object opcode {op}')
    assert r.offset==len(data), (r.offset,len(data))
    return fields,[]

def terrain(data):
    r = Reader(data); tiles = {}
    for z in range(4):
        for x in range(64):
            for y in range(64):
                un = ov = flags = 0; height = -1
                while True:
                    op = r.uint()
                    if op == 0: break
                    if op == 1: height = r.uint(); break
                    if op <= 49: ov = r.uint()
                    elif op <= 81: flags = op-49
                    else: un = op-81
                tiles[z,x,y] = (un,ov,flags,height)
    assert r.offset == len(data)
    return tiles

def chunk_hash(x,y,z,data):
    return sha(struct.pack(">iiii", x,y,z,len(data)) + data)

def solid_floor(data, overlay=False):
    r = Reader(data); rgb = 0; texture = -1
    while (op := r.uint()):
        if op == 1: rgb = r.uint(3)
        elif overlay and op == 2: texture = r.uint()
        elif overlay and op in (5,8): pass
        elif overlay and op == 7: r.take(3)
        else: raise ValueError(f"Unknown floor definition opcode {op}")
    return (r.offset == len(data) and texture == -1 and rgb not in (0, 0xff00ff)
            and max(rgb >> 16, (rgb >> 8) & 255, rgb & 255) >= 32)

def decrypt_source(raw, keys):
    if len(keys) != 4 or len(raw) < 5 or len(raw) > MAX_SOURCE_ARCHIVE:
        raise ValueError("Invalid source landscape keys/container")
    data = bytearray(raw)
    if any(keys):
        keys = [key & 0xffffffff for key in keys]
        for pos in range(5, len(data) - 7, 8):
            a, b = struct.unpack_from(">II", data, pos); total = 0xc6ef3720
            for _ in range(32):
                b = (b - ((((a << 4) ^ (a >> 5)) + a) ^ (total + keys[(total >> 11) & 3]))) & 0xffffffff
                total = (total - 0x9e3779b9) & 0xffffffff
                a = (a - ((((b << 4) ^ (b >> 5)) + b) ^ (total + keys[total & 3]))) & 0xffffffff
            struct.pack_into(">II", data, pos, a, b)
    r = Reader(data); codec, size = r.uint(), r.uint(4)
    if codec not in (0, 1, 2) or size > MAX_SOURCE_ARCHIVE:
        raise ValueError("Invalid source landscape compression header")
    if codec == 0:
        return bytes(r.take(size))
    expanded = r.uint(4)
    if expanded > MAX_SOURCE_ARCHIVE:
        raise ValueError("Invalid decrypted source landscape length (wrong XTEA keys)")
    payload = bytes(r.take(size))
    if codec == 2:
        decoder = zlib.decompressobj(31)
        plain = decoder.decompress(payload, expanded + 1)
        if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
            raise ValueError("Invalid source landscape gzip stream")
    else:
        decoder = bz2.BZ2Decompressor()
        plain = decoder.decompress(b"BZh1" + payload, max_length=expanded + 1)
        if not decoder.eof or decoder.unused_data:
            raise ValueError("Invalid source landscape bzip stream")
    if len(plain) != expanded:
        raise ValueError("Wrong source landscape decompressed length")
    return plain

def landscape184(data, valid_objects):
    r = Reader(data); object_id = -1; placements = 0
    while (delta := r.smart()):
        # The native server uses one smart; the 184 client treats 32767 as continuation.
        if delta == 32767: raise ValueError("Ambiguous native/client object delta")
        object_id += delta; position = 0
        if object_id not in valid_objects: raise ValueError(f"Missing source object {object_id}")
        while (delta := r.smart()):
            position += delta - 1; kind = r.uint() >> 2
            if position >= 16384 or kind > 22: raise ValueError("Invalid source object placement")
            placements += 1
    if r.offset != len(data): raise ValueError("Unread source landscape bytes")
    return placements

def generate(cache, build, output, report, region_keys=None):
    region_keys = region_keys or output.parent.parent / "region_keys.json"
    keys = json.loads(region_keys.read_text(encoding="utf8"))
    p = {"manifest.schema":"1", "production.enabled":"false", "cache.revision":"184",
         "server.build-id":sha(build.read_bytes()), "collision.audit.required":"true",
         "adapter.geometry.version":"1"}
    evidence = {"cache":str(cache), "server_build":str(build), "npcs":{}, "objects":{}, "graphics":{}, "sources":{}, "source_landscapes":{}}
    with Store(cache) as s:
        h = hashlib.sha256()
        for index in range(256):
            try: raw = s.read(255,index)
            except KeyError: continue
            h.update(struct.pack(">ii",index,len(raw))); h.update(raw)
        p["cache.sha256"] = h.hexdigest()
        for key,ident in NPCS.items():
            data=s.files(2,9)[ident]; actual=npc(data)
            assert actual["size"] == 1 and "Attack" in actual["options"] and actual["models"], (key,actual)
            for model in actual["models"]: assert s.read(7,model)
            p[f"npc.{key}.id"]=str(ident); p[f"npc.{key}.expected-name"]=actual["name"]
            p[f"npc.{key}.expected-sha256"]=sha(data)
            evidence["npcs"][key]={"id":ident, **actual, "sha256":sha(data)}
        for key,ident in OBJECTS.items():
            data=s.files(2,6)[ident]; f,m=object184(data,lambda i:i)
            assert not m and (1 in f or 5 in f), key
            width=int.from_bytes(f.get(14,b"\1"),"big"); length=int.from_bytes(f.get(15,b"\1"),"big")
            if key != "portal.entry": assert width == length == 1, (key,width,length)
            name=f[2][:-1].decode("cp1252", "replace")
            types=list(f[1][3::3]) if 1 in f else [10]
            models=[int.from_bytes(f[1][i:i+2],"big") for i in range(1,len(f[1]),3)] if 1 in f else [int.from_bytes(f[5][i:i+2],"big") for i in range(1,len(f[5]),2)]
            for model in models: assert s.read(7,model)
            p[f"object.{key}.id"]=str(ident); p[f"object.{key}.expected-name"]=name
            p[f"object.{key}.expected-sha256"]=sha(data)
            evidence["objects"][key]={"id":ident,"name":name,"width":width,"length":length,"types":types,"models":models,"sha256":sha(data)}
            if key.startswith("wall."):
                evidence["objects"][key].update({
                    "cache_decorative_flag":74 in f,
                    "collision_owner":"HollowDepthsMap per-instance tile masks; checked after visible object placement",
                    "instance_movement_mask":"0x200000", "instance_projectile_mask":"0x20100"})
        for key,ident in GRAPHICS.items():
            data=s.files(2,13)[ident];r=Reader(data); model=animation=-1
            while (op:=r.uint()):
                if op in (1,2,4,5,6):
                    val=r.uint(2)
                    if op==1:model=val
                    if op==2:animation=val
                elif op in (7,8):r.take(1)
                elif op in (40,41):r.take(r.uint()*4)
                else:raise ValueError(op)
            assert model>=0 and s.read(7,model)
            if animation>=0:assert s.files(2,12)[animation]
            p[f"graphic.{key}.id"]=str(ident); p[f"graphic.{key}.expected-sha256"]=sha(data)
            evidence["graphics"][key]={"id":ident,"model":model,"animation":animation,"sha256":sha(data)}
        names={g.name:i for i,g in s.table(5)[3].items()}
        valid_objects = s.files(2,6)
        underlays = {i+1:solid_floor(data) for i,data in s.files(2,1).items()}
        overlays = {i+1:solid_floor(data, True) for i,data in s.files(2,4).items()}
        for ordinal,key in enumerate(TEMPLATES):
            sx,sy=FLOORS[ordinal%len(FLOORS)]
            for rotation in range(4 if key.startswith("gauntlet_") else 1):
                prefix=f"template.{key}.r{rotation}"
                p[prefix+".source-chunk-x"]=str(sx);p[prefix+".source-chunk-y"]=str(sy)
                p[prefix+".plane"]="0";p[prefix+".chunk-rotation"]="0"
                for dx in range(3):
                    for dy in range(3):
                        x,y=sx+dx,sy+dy
                        region = (x//8)*256+y//8
                        if region not in evidence["source_landscapes"]:
                            source_keys = keys.get(str(region))
                            if source_keys is None: raise ValueError(f"Missing configured source keys: {region}")
                            group = names[name_hash(f"l{x//8}_{y//8}")]
                            raw = s.read(5, group)
                            plain = decrypt_source(raw, source_keys)
                            placements = landscape184(plain, valid_objects)
                            key_hash = sha(struct.pack(">4I", *(key & 0xffffffff for key in source_keys)))
                            p[f"source.region.{region}.landscape-sha256"] = sha(plain)
                            p[f"source.region.{region}.keys-sha256"] = key_hash
                            evidence["source_landscapes"][region] = {"group":group, "raw_sha256":sha(raw),
                                "sha256":sha(plain), "keys_sha256":key_hash, "expanded_bytes":len(plain),
                                "placements":placements, "bounded_decompression":True, "native_client_delta_compatible":True}
                        data=s.files(5,names[name_hash(f"m{x//8}_{y//8}")])[0]; ts=terrain(data)
                        for xx in range((x%8)*8,(x%8)*8+8):
                            for yy in range((y%8)*8,(y%8)*8+8):
                                un,ov,flags,height=ts[0,xx,yy]
                                assert un>0 and flags==0 and height==30, (prefix,xx,yy,ts[0,xx,yy])
                                assert underlays.get(un) and (ov == 0 or overlays.get(ov)), ("Invisible/textured source floor",prefix,xx,yy,un,ov)
                        digest=chunk_hash(x,y,0,data)
                        p[f"{prefix}.chunk.{dx}.{dy}.sha256"]=digest
                        evidence["sources"][f"{x},{y},0"]={"sha256":digest,"explicit_height":30,"tiles":64,"visible_underlay":True,"solid_untextured_floor":True,"flags":0}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text("# Independently audited revision-184 assets. Entry stays disabled until retained runtime collision audit passes.\n"+"\n".join(f"{k}={v}" for k,v in p.items())+"\n",encoding="utf8")
    evidence["cache_sha256"]=p["cache.sha256"];evidence["build_sha256"]=p["server.build-id"]
    evidence["collision_audit"]="REQUIRED: real server map construction and clipping checks"
    report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps(evidence,indent=2)+"\n",encoding="utf8")
    print(json.dumps({"npcs":len(NPCS),"objects":len(OBJECTS),"graphics":len(GRAPHICS),"templates":len(TEMPLATES),"source_chunks":len(evidence["sources"]),"profile":str(output)}))

if __name__ == "__main__":
    a=argparse.ArgumentParser();a.add_argument("--cache",type=Path,required=True);a.add_argument("--server-build",type=Path,required=True)
    a.add_argument("--output",type=Path,required=True);a.add_argument("--report",type=Path,required=True)
    a.add_argument("--region-keys",type=Path,help="Defaults to output's data/region_keys.json")
    args=a.parse_args()
    generate(args.cache,args.server_build,args.output,args.report,args.region_keys)
