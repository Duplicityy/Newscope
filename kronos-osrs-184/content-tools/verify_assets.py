"""Read-only asset audit and input builder for VerifyModels184.java.

The cache is never opened writable. Output files are diagnostic artifacts only.
"""

import argparse
from collections import Counter
import json
from pathlib import Path
import struct

from cache_store import Store
from legacy_assets import ITEM_MODEL, model184, model_layout, model_texture_ids, read_item, sprite184, texture184


def audit(source, base, output, model_ids=None):
    output.mkdir(parents=True, exist_ok=True)
    report = {"models": {}, "textures": {}, "items": {}}
    with Store(source) as donor, Store(base) as original:
        new_items = {}
        original_items = original.files(2, 10)
        for item_id, record in donor.files(2, 10).items():
            if item_id in original_items and item_id < 24000:
                continue
            fields = read_item(record)
            name = fields.get(2, b"null\0")
            if name in (b"null\0", b"Null\0", b"\0"):
                continue
            if item_id in original_items and read_item(original_items[item_id]).get(2) == name:
                continue
            new_items[item_id] = {int.from_bytes(fields[op][:2], "big") for op in ITEM_MODEL if op in fields}
        referenced = set().union(*new_items.values())
        report["items"]["candidate_count"] = len(new_items)
        report["items"]["referenced_models"] = len(referenced)
        groups = donor.table(7)[3]
        if model_ids is None:
            model_ids = sorted(groups)
        rejected = {}
        versions = Counter()
        refs = Counter()
        weighted = legacy = max_vertices = max_faces = converted = 0
        with (output / "models.bin").open("wb") as packed:
            for index, model_id in enumerate(model_ids):
                try:
                    data = donor.files(7, model_id)[0]
                    layout = model_layout(data)
                    versions[layout["version"]] += 1
                    weighted += layout["weighted"]
                    legacy += layout["legacy_vertex_groups"]
                    max_vertices = max(max_vertices, layout["vertices"])
                    max_faces = max(max_faces, layout["faces"])
                    data = model184(data)
                    refs.update(model_texture_ids(data))
                    packed.write(struct.pack(">II", model_id, len(data)) + data)
                    converted += 1
                except (ValueError, KeyError) as error:
                    rejected[model_id] = str(error)
                if index % 10000 == 0:
                    print(f"model audit {index}/{len(model_ids)}", flush=True)
        report["models"] = dict(total=len(model_ids), converted=converted, versions=versions,
                                 weighted=weighted, legacy_vertex_groups=legacy,
                                 max_vertices=max_vertices, max_faces=max_faces,
                                 rejected=rejected, texture_references=refs)
        unavailable = set(rejected) | (referenced - set(groups))
        report["items"]["unavailable_model_items"] = {
            item: sorted(models & unavailable) for item, models in new_items.items()
            if models & unavailable
        }
        old_textures = original.files(9, 0)
        textures = donor.files(9, 0)
        changed = []
        unchanged = []
        missing_sprites = []
        unsupported_sprites = {}
        sprite_shapes = Counter()
        for texture_id, data in textures.items():
            texture = texture184(data, lambda value: value)
            sprite_id = int.from_bytes(data[:2], "big")
            try:
                sprite = donor.files(8, sprite_id)[0]
                sprite_count = int.from_bytes(sprite[-2:], "big")
                trailer = len(sprite) - 7 - 8 * sprite_count
                width, height = struct.unpack(">HH", sprite[trailer:trailer + 4])
                sprite_shapes[(width, height, sprite_count)] += 1
                try:
                    sprite184(sprite)
                except ValueError as error:
                    unsupported_sprites[texture_id] = dict(sprite=sprite_id, reason=str(error))
            except KeyError:
                missing_sprites.append(sprite_id)
                continue
            same_sprite = False
            try:
                same_sprite = sprite == original.files(8, sprite_id)[0]
            except KeyError:
                pass
            if texture_id in old_textures:
                (unchanged if texture == old_textures[texture_id] and same_sprite else changed).append(texture_id)
        report["textures"] = dict(source_count=len(textures), base_count=len(old_textures),
                                  changed_existing=changed, unchanged_existing=unchanged,
                                  new_ids=sorted(set(textures) - set(old_textures)),
                                  missing_referenced_ids=sorted(set(refs) - set(textures)),
                                  missing_sprite_ids=missing_sprites,
                                  unsupported_sprites=unsupported_sprites,
                                  sprite_shapes={str(key): value for key, value in sprite_shapes.items()})
    (output / "asset-audit.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"models": {k: v for k, v in report["models"].items() if k not in ("rejected", "texture_references")},
                      "rejected": len(rejected), "textures": report["textures"],
                      "items": {k: len(v) if isinstance(v, dict) else v for k, v in report["items"].items()}}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model-ids", type=Path, help="JSON list of source model IDs; default all models")
    args = parser.parse_args()
    audit(args.source, args.base, args.output,
          json.loads(args.model_ids.read_text()) if args.model_ids else None)
