"""Stage NPC and item definitions referencing the selected cache's existing assets."""

import argparse
from functools import lru_cache
from itertools import product
import json
import math
from pathlib import Path
import re
import shutil
import sys


ICON_FIELDS = frozenset((1, 4, 5, 6, 7, 8, 40, 41, 95, 110, 111, 112, 113, 114, 250))
# Inventory camera angles checked through the native sprite renderer.
INVENTORY_VIEWS = {
    6318: (0, 1950),   # water surface
    7121: (1024, 0),   # Zamorak ranger
    13910: (0, 0),    # Big Snake
    13911: (0, 0),    # sea snakes
    14171: (0, 0),    # Swamp snake
    20322: (0, 1950),  # Ogre shaman proxy surface
}


def inventory(data):
    combat = set()
    for path in (data / "npcs/combat").glob("*.json"):
        for row in re.findall(r'"ids"\s*:\s*\[([^\]]*)\]', path.read_text(encoding="utf-8-sig")):
            combat.update(map(int, re.findall(r"\b\d+\b", row)))
    raid = json.loads((data / "raids/near-reality-raid-assets.json").read_text(encoding="utf-8-sig"))
    for source in raid["combat"]:
        mapped = raid["mappings"]["npcs"].get(source)
        if isinstance(mapped, int):
            combat.add(mapped)
    return combat


def encode(fields):
    return b"".join(bytes([op]) + payload for op, payload in sorted(fields.items())) + b"\0"


def geometry(raw):
    if raw.startswith(b"D727UV\x01"):
        length_offset = 7
    elif raw.startswith(b"K242\x01"):
        length_offset = 5
    else:
        return raw
    start = length_offset + 4
    length = int.from_bytes(raw[length_offset:start], "big")
    if length < 18 or start + length > len(raw):
        raise ValueError("Invalid native model envelope")
    return raw[start:start + length]


def model_extent(data, layout, Reader):
    footer_size = layout["footer_size"]
    version = layout["version"]
    footer = Reader(data[-footer_size:-2] if version else data[-footer_size:])
    footer.take(5 + 4 + (version in (1, 3)) + 1 + (version in (2, 3)))
    lengths = [footer.uint(2) for _ in range(3)]
    start = layout["color_offset"] + layout["faces"] * 2
    modern = version in (1, 3)
    if not modern:
        start += layout["textures"] * 6
    streams = []
    for length in lengths:
        streams.append(Reader(data[start:start + length]))
        start += length
    position = [0, 0, 0]
    lower, upper = [0] * 3, [0] * 3
    flags = layout["textures"] if modern else 0
    for index, bits in enumerate(data[flags:flags + layout["vertices"]]):
        for axis, reader in enumerate(streams):
            if bits & (1 << axis):
                value = reader.uint(2) - 49152 if reader.data[reader.offset] & 128 else reader.uint() - 64
                position[axis] += value
            lower[axis] = min(lower[axis], position[axis]) if index else position[axis]
            upper[axis] = max(upper[axis], position[axis]) if index else position[axis]
    if any(reader.offset != len(reader.data) for reader in streams):
        raise ValueError("Unread coordinate bytes")
    return lower, upper


class PetModels:
    """Read the existing body models and calculate an inventory camera for one of them."""
    def __init__(self, store, items):
        self.store = store
        self.items = items

    @lru_cache(maxsize=4)
    def bank(self, group):
        return self.store.files(7, group)

    @lru_cache(maxsize=None)
    def profile(self, model):
        from cache_store import Reader
        from legacy_assets import model_layout
        if model < 65536:
            raw = self.store.files(7, model)[0]
        else:
            group, file = divmod(model - 65536, 512)
            raw = self.bank(65408 + group)[file]
        mesh = geometry(raw)
        layout = model_layout(mesh)
        low, high = model_extent(mesh, layout, Reader)
        face_count = layout["faces"]
        if mesh[-layout["footer_size"] + 7]:
            alpha = layout["skin_offset"] + layout["skin_size"]
            face_count -= sum(value >= 253 for value in mesh[alpha:alpha + face_count])
        return low, high, face_count

    def inventory_fields(self, fields, models):
        from legacy_assets import read_item, short
        from modern_definitions import item_models
        # Many sources have accessories or effects as separate meshes. Prefer
        # the body with the most visible faces, which generally carries the identity.
        model = max(models, key=lambda model: self.profile(model)[2])
        if model == 32321 and 7121 in models:
            model = 7121
        low, high, visible_faces = self.profile(model)
        if visible_faces == 0:
            # Invisible scripted proxies have no inventory silhouette. Use the
            # existing Olmlet for Olm and the existing Pet rock token for others.
            template = 20851 if model == 32709 else 3695
            return {op: value for op, value in read_item(self.items[template]).items() if op in ICON_FIELDS}
        yaw, pitch = INVENTORY_VIEWS.get(model, (1560, 180))
        sy, cy = math.sin(yaw * math.pi / 1024), math.cos(yaw * math.pi / 1024)
        sp, cp = math.sin(pitch * math.pi / 1024), math.cos(pitch * math.pi / 1024)
        # Bound the inventory model radius used by the software renderer. This
        # only affects the token camera/scale; the follower keeps its own scale.
        radius = max(math.sqrt(x*x + y*y + z*z) for x, y, z in product(*zip(low, high)))
        scale = min(128, max(1, int(420 * 128 / max(1, radius))))
        lower, upper = ([int(n * scale / 128) for n in bound] for bound in (low, high))
        half_height = max(0, -lower[1]) // 2
        points = []
        for x, y, z in product(*zip(lower, upper)):
            nx, nz = z * sy + x * cy, z * cy - x * sy
            points.append((nx, (y + half_height) * cp - nz * sp, (y + half_height) * sp + nz * cp))
        shift_x = -round((min(p[0] for p in points) + max(p[0] for p in points)) / 2)
        shift_y = -round((min(p[1] for p in points) + max(p[1] for p in points)) / (2 * (cp - sp)))
        depth_shift = shift_y * (cp + sp)
        zoom = math.ceil(max(max(512 * abs(x + shift_x) / 14, 512 * abs(y + shift_y * (cp - sp)) / 12, 32)
                             - z - depth_shift for x, y, z in points)) + 32
        icon = item_models({1: short(0)}, lambda _: model)
        icon.update({4: short(max(64, zoom)), 5: short(pitch), 6: short(yaw),
                     7: short(shift_x & 65535), 8: short(shift_y & 65535)})
        if scale != 128:
            icon.update({op: short(scale) for op in (110, 111, 112)})
        icon.update({op: value for op, value in fields.items() if op in (40, 41)})
        return icon


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("runtime", "output", "tools"):
        parser.add_argument("--" + flag, type=Path, required=True)
    parser.add_argument("--previous-registry", type=Path,
                        default=Path(__file__).parent / "registries/universal-npc-pets.json")
    parser.add_argument("--dungeon-source", type=Path, required=True)
    args = parser.parse_args()
    sys.path.insert(0, str(args.tools))
    from cache_store import Store
    from legacy_assets import short
    from modern_definitions import npc_models, read_npc

    source, output = (args.runtime / "Cache").resolve(), args.output.resolve()
    if output.exists() or source in output.parents or args.runtime.resolve() in output.parents:
        raise ValueError("Choose a fresh output directory outside the running runtime")
    candidates = inventory(args.runtime / "data")
    previous = json.loads(args.previous_registry.read_text(encoding="utf-8"))["definitions"]
    claimed_npcs = {record["npc"] for record in previous.values()}
    claimed_items = {record["item"] for record in previous.values()}
    skipped = {}
    excluded = {}
    dungeon = json.loads((args.runtime / "data/dungeoneering/darkan-assets.json").read_text(encoding="utf-8-sig"))
    script = args.dungeon_source.read_text(encoding="utf-8-sig")
    def array(method):
        match = re.search(r"int\[\]\s+" + method + r"\(\)\s*\{\s*return new int\[\]\s*\{([^}]+)\}", script)
        if not match:
            raise ValueError("Cannot resolve authored dungeon list " + method)
        return {int(value) for value in re.findall(r"\d+", match[1])}
    visual_only = array("morphNpcs") - array("spawnNpcs")
    dungeon_sources = {}
    definitions = dict(previous)
    with Store(source) as base:
        npcs, items = base.files(2, 9), base.files(2, 10)
        if claimed_npcs & npcs.keys() or claimed_items & items.keys():
            raise ValueError("Previous generated IDs conflict with the selected base cache")
        next_npc = iter(sorted(set(range(1, 16383)) - npcs.keys() - claimed_npcs))
        next_item = iter(sorted(set(range(32768, 65535)) - items.keys() - claimed_items))
        for key, native in dungeon["npcMappings"].items():
            donor = int(key)
            if donor in (1, 3, 5, 8, 13) or donor in visual_only:
                continue
            definition = dungeon["npcDefinitions"][key]
            if not definition.get("combat"):
                continue
            _, meta = read_npc(npcs[native], modern=False)
            if "Attack" not in meta["options"] and definition["name"].strip().lower() != "divine skinweaver":
                continue
            candidates.add(native)
            dungeon_sources[native] = definition["combat"].get("attackAnim", -1)
        visuals = PetModels(base, items)
        npc_output, item_output = dict(npcs), dict(items)

        def size(fields, models):
            bounds = [visuals.profile(model) for model in models]
            dimensions = [max(b[1][axis] for b in bounds) - min(b[0][axis] for b in bounds) for axis in range(3)]
            width = int.from_bytes(fields.get(97, short(128)), "big")
            height = int.from_bytes(fields.get(98, short(128)), "big")
            return max(dimensions[0] * width, dimensions[1] * height, dimensions[2] * width)

        skeleton, skeleton_meta = read_npc(npcs[70], modern=False)
        threshold = size(skeleton, skeleton_meta["models"])
        for source_id in sorted(candidates | {int(n) for n in previous}):
            try:
                fields, meta = read_npc(npcs[source_id], modern=False)
                appearance = source_id
                if meta["morphs"]:
                    forms = {}
                    for model_id in sorted(set(meta["morphs"])):
                        if model_id not in npcs:
                            continue
                        form_fields, form = read_npc(npcs[model_id], modern=False)
                        if "Attack" in form["options"] and form["models"] and not form["morphs"]:
                            forms[model_id] = (form_fields, form)
                    if len(forms) == 1:
                        appearance, (fields, meta) = next(iter(forms.items()))
                    elif not forms:
                        excluded[str(source_id)] = "No attackable morph in the loaded cache"
                        continue
                    else:
                        raise ValueError("Multiple combat forms require encounter mapping")
                if not meta["models"] or meta["name"].lower() == "null":
                    excluded[str(source_id)] = "No named model in the loaded cache"
                    continue
                extent = size(fields, meta["models"])
            except (ValueError, IndexError, KeyError) as failure:
                skipped[str(source_id)] = str(failure)
                continue
            key = str(source_id)
            if key not in definitions:
                definitions[key] = {"npc": next(next_npc), "item": next(next_item)}
            record = definitions[key]
            record["appearance"] = appearance
            if source_id in dungeon_sources:
                animation = dungeon["animationMappings"].get(str(dungeon_sources[source_id]))
                if animation in base.table(2)[3][12].files:
                    record["attack"] = animation
            name = ("Pet " + meta["name"]).encode("cp1252", "replace") + b"\0"
            follower = {op: payload for op, payload in fields.items()
                        if op in (13, 14, 15, 16, 17, 40, 41, 97, 98, 100, 101, 103)}
            follower.update({2: name, 12: b"\x01", 30: b"Pick-up\0", 31: b"Show attack\0",
                             111: b"", 250: npc_models(meta, lambda model: model)})
            if extent >= threshold:
                for op in (97, 98):
                    scale = int.from_bytes(follower.get(op, short(128)), "big")
                    follower[op] = short(max(1, scale // 2))
            npc_output[record["npc"]] = encode(follower)
            icon = visuals.inventory_fields(fields, meta["models"])
            item_output[record["item"]] = encode(icon | {2: name, 16: b"", 39: b"Drop\0"})
        if any(key in skipped or key in excluded for key in previous):
            raise ValueError("Previously generated definition no longer resolves")
        output.mkdir(parents=True)
        shutil.copytree(source, output / "Cache")
        with Store(output / "Cache", writable=True) as target:
            target.put(2, 9, npc_output)
            target.put(2, 10, item_output)
            target.commit_table(2)
        with Store(output / "Cache") as target:
            for group, originals, expected in ((9, npcs, npc_output), (10, items, item_output)):
                records = target.files(2, group)
                assert records == expected
                assert all(records[key] == value for key, value in originals.items())
    for path in source.iterdir():
        if path.is_file() and path.name not in ("main_file_cache.dat2", "main_file_cache.idx2", "main_file_cache.idx255"):
            import filecmp
            assert filecmp.cmp(path, output / "Cache" / path.name, shallow=False), path.name
    (output / "pet-definitions.json").write_text(json.dumps({
        "status": "DEFINITIONS_ONLY", "skeleton": 70, "definitions": definitions,
        "reviewRequired": skipped, "excluded": excluded, "newVisualAssets": False
    }, indent=2) + "\n")
    print(f"Staged {len(definitions)} pet definitions with {len(skipped)} candidates requiring review")


if __name__ == "__main__":
    main()


