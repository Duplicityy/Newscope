"""Explicit extensions for the upgraded 184 readers; never feed these to an old client."""
import hashlib
import struct

from cache_store import Reader, join_files, pack_container
from legacy_assets import ITEM_MODEL, LOC_KEEP, LOC_SKIP, read_params, short, terrain_height

BANK_FIRST = 65408
BANK_FILES = 512
BANK_COUNT = 127
FIRST_MODEL = 65536
MAX_BANK_BYTES = 8 * 1024 * 1024
TERRAIN_MAGIC = b"K242TERR\x01"


class ModelBanks:
    def __init__(self, base, output):
        assert not (set(base.table(7)[3]) & set(range(BANK_FIRST, 65535))), "Reserved model banks already occupied"
        self.output = output
        self.groups = {}
        self.group_bytes = {}
        self.bank = 0

    def add(self, payload):
        if len(payload) + 5 > MAX_BANK_BYTES:
            raise ValueError("Single model exceeds the bounded model-bank payload")
        while self.bank < BANK_COUNT:
            values = self.groups.setdefault(self.bank, {})
            size = self.group_bytes.get(self.bank, 1)
            if len(values) < BANK_FILES and size + len(payload) + 4 <= MAX_BANK_BYTES:
                file = len(values)
                values[file] = payload
                self.group_bytes[self.bank] = size + len(payload) + 4
                return FIRST_MODEL + self.bank * BANK_FILES + file
            self.bank += 1
        raise ValueError("Reserved model-bank capacity exhausted")

    def finish(self):
        containers = []
        for bank, files in self.groups.items():
            if not files:
                continue
            joined = join_files(files)
            if len(joined) > MAX_BANK_BYTES:
                raise ValueError("Model bank exceeds verified uncompressed bound")
            container_size = len(pack_container(joined, 0))
            if container_size > MAX_BANK_BYTES:
                raise ValueError("Model bank exceeds the native8MiB compressed-container bound")
            containers.append(container_size)
            self.output.put(7, BANK_FIRST + bank, files)
        return {"firstGroup": BANK_FIRST, "filesPerGroup": BANK_FILES,
                "groups": len(self.groups), "models": sum(map(len, self.groups.values())),
                "maxUncompressedBytes": max(self.group_bytes.values(), default=0),
                "maxContainerBytes": max(containers, default=0)}


def item_models(fields, model):
    result = dict(fields)
    wide = []
    for op in sorted(ITEM_MODEL):
        if op not in result:
            continue
        payload = result[op]
        target = model(int.from_bytes(payload[:2], "big"))
        result[op] = short(target if target < FIRST_MODEL else 0) + payload[2:]
        if target >= FIRST_MODEL:
            wide.append(bytes([op]) + struct.pack(">I", target))
    if wide:
        result[250] = bytes([len(wide)]) + b"".join(wide)
    return result


def retextures(fields, texture):
    result = dict(fields)
    if 41 in result:
        r = Reader(result[41]);count = r.uint()
        values = [r.uint(2) for _ in range(count*2)]
        if r.offset != len(r.data): raise ValueError('Trailing retexture bytes')
        result[41] = bytes([count])+b''.join(short(texture(i) if i != 65535 else i) for i in values)
    return result


def animation_item_dependencies(sequences, known_items):
    """Include anonymous held-model records that are reachable only via sequences."""
    from modern_animation import sequence242
    seen = set()
    class Mapping:
        def __contains__(self, key):
            if key in known_items: seen.add(key); return True
            return False
        def __getitem__(self,key): return key
    for raw in sequences.values():
        try:
            sequence242(raw,lambda group:group,lambda cached:cached,Mapping())
        except (ValueError,KeyError,IndexError):
            # The animation importer records the precise unsupported record.
            # Already parsed valid held dependencies remain explicit assets.
            pass
    return seen


def read_kit(data):
    r, fields, models, heads = Reader(data), {}, [], {}
    while (op := r.uint()):
        if op == 1: fields[op] = r.take(1)
        elif op in (2,5): models = [r.uint(2 if op == 2 else 4) for _ in range(r.uint())]
        elif op == 3: fields[op] = b''
        elif op in (40,41):
            count = r.uint(); fields[op] = bytes([count])+r.take(count*4)
        elif 60 <= op <= 64: heads[op-60] = r.uint(2)
        elif 70 <= op <= 74: heads[op-70] = r.uint(4)
        elif op == 250:
            models = [r.uint(4) for _ in range(r.uint())]
            heads = {r.uint():r.uint(4) for _ in range(r.uint())}
        else: raise ValueError(f'Unsupported identity-kit opcode {op}')
    if r.offset != len(data): raise ValueError('Trailing identity-kit bytes')
    return fields,models,heads


def terrain242(data, overlay, underlay, source_origin):
    r = Reader(data)
    out = bytearray(TERRAIN_MAGIC)
    for tile in range(4 * 64 * 64):
        while True:
            op = r.uint(2)
            if op == 0 and tile < 4096:
                x, y = divmod(tile, 64)
                out.extend(short(1) + bytes([terrain_height(source_origin[0] + x, source_origin[1] + y)]))
                break
            if op <= 1:
                out.extend(short(op))
                if op == 1:
                    out.extend(r.take(1))
                break
            if op <= 49:
                source = r.uint(2)
                value = overlay(source - 1) + 1 if source else 0
                out.extend(short(op) + short(value))
            elif op <= 81:
                out.extend(short(op))
            else:
                out.extend(short(82 + underlay(op - 82)))
    if data[r.offset:] not in (b"", b"\0"):
        raise ValueError("Unsupported modern terrain environment trailer")
    return bytes(out)


def read_npc(data, modern=True):
    r = Reader(data)
    fields = {}
    meta = {"name": "null", "size": 1, "combatLevel": -1, "options": [None] * 5,
            "models": [], "headModels": [], "animations": {}, "morphs": [], "unsupported": []}
    fixed = {12:1,13:2,14:2,15:2,16:2,17:8,93:0,95:2,97:2,98:2,99:0,
             100:1,101:1,103:2,107:0,109:0,111:0}
    skipped = {18:2,42:2,44:2,45:2,74:2,75:2,76:2,77:2,78:2,79:2,
               112:0,113:4,114:2,115:8,116:2,117:8,119:1,122:0,123:0,124:2,
               125:1,126:2,127:2,128:1,130:0,134:8,135:3,136:3,137:2,138:2,
               139:2,140:1,141:0,142:2,143:0,144:2,145:0,146:2,147:0,
               155:4,158:0,159:0,161:0,162:0,163:1,164:4,165:1,168:1,
               **{i:2 for i in range(170,176)}}
    while (op := r.uint()):
        start = r.offset
        if op in (1,60,61,62):
            values = [r.uint(4 if op in (61,62) else 2) for _ in range(r.uint())]
            meta["models" if op in (1,61) else "headModels"] = values
        elif op == 250 and not modern:
            meta["models"] = [r.uint(4) for _ in range(r.uint())]
            meta["headModels"] = [r.uint(4) for _ in range(r.uint())]
        elif op == 2:
            fields[2] = r.string(); meta["name"] = fields[2][:-1].decode("cp1252", "replace")
        elif op == 3:
            meta["examine"] = r.string()[:-1].decode("cp1252", "replace")
        elif op in fixed:
            fields[op] = r.take(fixed[op])
            if op == 12: meta["size"] = fields[op][0]
            if op == 95: meta["combatLevel"] = int.from_bytes(fields[op], "big")
            if op in (13,14,15,16,17):
                meta["animations"][str(op)] = [int.from_bytes(fields[op][i:i+2],"big") for i in range(0,len(fields[op]),2)]
        elif 30 <= op <= 34 or 150 <= op <= 154:
            target = op if op < 150 else op - 120
            fields[target] = r.string(); text = fields[target][:-1].decode("cp1252", "replace")
            meta["options"][target-30] = None if text.lower() == "hidden" else text
        elif op in (40,41):
            count = r.uint(); fields[op] = bytes([count]) + r.take(count * 4)
        elif op == 102:
            if not modern:
                fields[102] = r.take(2)
            else:
                mask = r.uint(); values = []
                while mask:
                    if mask & 1: values.append([r.big_smart(), r.smart() - 1])
                    mask >>= 1
                meta["headIcons"] = values
                if values: meta["unsupported"].append("modern NPC head-icon sprite mapping")
        elif op in (106,118):
            varbit, varp = r.uint(2), r.uint(2)
            fallback = r.uint(2) if op == 118 else 65535
            values = [r.uint(2) for _ in range(r.uint() + 1)]
            meta.update(varbit=varbit, varp=varp, morphs=values+[fallback])
        elif op == 249:
            read_params(r); fields[op] = data[start:r.offset]
        elif op in skipped:
            value = r.take(skipped[op])
            if op in (114,115,116,117):
                meta["animations"][str(op)] = [int.from_bytes(value[i:i+2],"big") for i in range(0,len(value),2)]
        elif op == 121:
            r.take(r.uint()*4); meta["unsupported"].append("per-model offsets")
        elif op == 160:
            r.take(r.uint()*2)
        elif op in (251,252,253):
            r.take({251:2,252:13,253:15}[op]); r.string()
        else:
            raise ValueError(f"Unsupported NPC opcode {op}")
    if r.offset != len(data): raise ValueError("Unread NPC definition bytes")
    return fields, meta


def npc_models(meta, model):
    return bytes([len(meta["models"])]) + b"".join(struct.pack(">I", model(i)) for i in meta["models"]) + \
           bytes([len(meta["headModels"])]) + b"".join(struct.pack(">I", model(i)) for i in meta["headModels"])


def read_spot(data):
    r = Reader(data); fields = {}; model = 0; animation = -1
    while (op := r.uint()):
        if op in (1,3,250): model = r.uint(2 if op == 1 else 4)
        elif op == 2: animation = r.uint(2)
        elif op in (4,5,6): fields[op] = r.take(2)
        elif op in (7,8): fields[op] = r.take(1)
        elif op == 9: r.string()  # Diagnostic name, not gameplay or geometry.
        elif op == 10: pass  # Modern rotate=no is not represented by the184 spot renderer.
        elif op == 42: raise ValueError('Spot recolour-all requires a renderer extension')
        elif op in (40,41):
            count = r.uint(); fields[op] = bytes([count])+r.take(count*4)
        else: raise ValueError(f"Unsupported spot animation opcode {op}")
    if r.offset != len(data): raise ValueError("Unread spot animation bytes")
    return fields, model, animation


def read_object(data, modern=True):
    r = Reader(data); fields = {}
    meta = {"name":"null", "options":[None]*5, "models":[], "modelTypes":None,
            "animation":-1, "morphs":[], "unsupported":[]}
    while (op := r.uint()):
        start = r.offset
        if op in LOC_KEEP: fields[op] = r.take(LOC_KEEP[op])
        elif op in (1,5,6,7):
            count = r.uint(); models = []; types = []
            for _ in range(count):
                models.append(r.uint(4 if op in (6,7) else 2))
                if op in (1,6): types.append(r.uint())
            meta["models"],meta["modelTypes"] = models, types or None
        elif op == 250 and not modern:
            typed = r.uint(); count = r.uint(); models = []; types = []
            for _ in range(count):
                models.append(r.uint(4))
                if typed: types.append(r.uint())
            meta["models"],meta["modelTypes"] = models,types or None
        elif op == 2:
            fields[op]=r.string();meta["name"]=fields[op][:-1].decode("cp1252","replace")
        elif op == 3: meta["examine"]=r.string()[:-1].decode("cp1252","replace")
        elif 30 <= op <= 38 or 150 <= op <= 154:
            value=r.string();target=op if op<150 else op-120
            if target <= 34:
                fields[target]=value;meta["options"][target-30]=value[:-1].decode("cp1252","replace")
        elif op in (40,41):
            count=r.uint();fields[op]=bytes([count])+r.take(count*4)
        elif op == 24: meta["animation"]=r.uint(2)
        elif op in LOC_SKIP: r.take(LOC_SKIP[op])
        elif op in (77,92,97,98):
            varbit,varp=r.uint(2),r.uint(2);width=3 if op in (97,98) else 2
            fallback=r.uint(width) if op in (92,98) else (1<<(width*8))-1
            values=[r.uint(width) for _ in range(r.uint()+1)]
            sentinel=(1<<(width*8))-1
            meta.update(varbit=varbit,varp=varp,morphs=[v if v!=sentinel else -1 for v in values+[fallback]])
        elif op == 78: r.take(4 if modern else 3);meta["unsupported"].append("ambient sound")
        elif op == 79: r.take(6 if modern else 5);r.take(r.uint()*2);meta["unsupported"].append("ambient sound variants")
        elif op in (80,106,160):r.take(r.uint()*{80:4,106:3,160:2}[op])
        elif op in (100,101,102):r.take({100:2,101:13,102:15}[op]);r.string()
        elif op in (170,171):r.smart()
        elif op == 249:read_params(r);fields[op]=data[start:r.offset]
        else:raise ValueError(f"Unsupported object opcode {op}")
    if r.offset!=len(data):raise ValueError("Unread object definition bytes")
    meta["width"]=int.from_bytes(fields.get(14,b"\1"),"big")
    meta["length"]=int.from_bytes(fields.get(15,b"\1"),"big")
    return fields,meta


def object_models(meta, model):
    types=meta["modelTypes"]
    return bytes([int(types is not None),len(meta["models"])]) + b"".join(
        struct.pack(">I",model(source))+(bytes([types[i]]) if types is not None else b"")
        for i,source in enumerate(meta["models"]))
