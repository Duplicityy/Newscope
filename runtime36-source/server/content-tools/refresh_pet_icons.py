"""Update only existing universal pet item icons, preserving frozen IDs and cache assets."""

import argparse
from dataclasses import asdict
import filecmp
import json
from pathlib import Path
import shutil

from cache_store import Store
from legacy_assets import read_item
from modern_definitions import read_npc
from stage_npc_pet_definitions import ICON_FIELDS, PetModels, encode


def inventory_model(fields):
    model = int.from_bytes(fields[1], "big")
    wide = fields.get(250, b"")
    for i in range(1, len(wide), 5):
        if wide[i] == 1:
            return int.from_bytes(wide[i + 1:i + 5], "big")
    return model


def refresh(runtime, output):
    source = (runtime / "Cache").resolve()
    output = output.resolve()
    if output.exists() or runtime.resolve() in output.parents:
        raise ValueError("Use a fresh repair stage outside the active runtime")
    registry = json.loads((runtime / "data/pet-definitions.json").read_text(encoding="utf-8-sig"))
    with Store(source) as cache:
        old_items = cache.files(2, 10)
        npcs = cache.files(2, 9)
        original_table = cache.table(2)[3]
        models = PetModels(cache, old_items)
        generated = dict(old_items)
        icon_models = {}
        borrowed_icons = {}
        for entry in registry["definitions"].values():
            item = entry["item"]
            fields, npc = read_npc(npcs[entry["appearance"]], modern=False)
            old = read_item(old_items[item])
            follower = read_npc(npcs[entry["npc"]], modern=False)[1]
            if old[2][:-1].decode("cp1252") != follower["name"] or old[39] != b"Drop\0":
                raise ValueError(f"Unexpected pet token record {item}")
            kept = {op: value for op, value in old.items() if op not in ICON_FIELDS}
            icon = models.inventory_fields(fields, npc["models"])
            generated[item] = encode(kept | icon)
            icon_models[item] = inventory_model(icon)
            if icon_models[item] not in npc["models"]:
                borrowed_icons[item] = icon_models[item]

    output.mkdir()
    shutil.copytree(source, output / "Cache")
    with Store(output / "Cache", writable=True) as target:
        target.put(2, 10, generated)
        target.commit_table(2)
    with Store(output / "Cache") as target:
        expected = target.files(2, 10)
        table = target.table(2)[3]
        if expected != generated or target.files(2, 9) != npcs:
            raise ValueError("The written definitions differ from the planned cache edit")
        if any(asdict(table[group]) != asdict(old) for group, old in original_table.items() if group != 10):
            raise ValueError("An unrelated configuration archive changed")
        if any(expected[item] != value for item, value in old_items.items() if item not in icon_models):
            raise ValueError("An existing item definition changed")
    for path in source.iterdir():
        if path.is_file() and path.name not in ("main_file_cache.dat2", "main_file_cache.idx2", "main_file_cache.idx255"):
            if not filecmp.cmp(path, output / "Cache" / path.name, shallow=False):
                raise ValueError(f"Unrelated cache file changed: {path.name}")
    report = {
        "petItemCount": len(icon_models),
        "distinctInventoryModels": len(set(icon_models.values())),
        "unchangedOtherItemDefinitions": len(old_items) - len(icon_models),
        "borrowedExistingIcons": borrowed_icons,
        "modelReferences": icon_models,
        "newModelsOrTextures": 0,
    }
    (output / "ICON-SCOPE.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Prepared {len(icon_models)} pet icons from {report['distinctInventoryModels']} existing models")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    refresh(args.runtime, args.output)
