"""Read-only animation preservation/semantic audit and actual-184 Java input."""

import argparse
import json
from pathlib import Path
import struct

from animation_import import _rewrite_equipment, skeleton184
from cache_store import Store
from gear_metadata import load_json
from legacy_assets import read_item, sequence184, short


def export(stage, base, donor, output):
    output.mkdir(parents=True, exist_ok=True)
    report = load_json(stage / "import-report.json")
    mappings = {kind: {int(k): v for k, v in report["animations"][kind].items()}
                for kind in ("sequence_mapping", "frame_group_mapping", "skeleton_mapping")}
    smap, fmap, bmap = (mappings[key] for key in ("sequence_mapping", "frame_group_mapping", "skeleton_mapping"))
    with Store(base) as old, Store(donor) as source, Store(stage / "Cache") as target:
        preserved = 0
        for index in (0, 1):
            for group in old.table(index)[3]:
                assert target.read(index, group) == old.read(index, group), (index, group)
                preserved += 1
        old_sequences, target_sequences, donor_sequences = old.files(2, 12), target.files(2, 12), source.files(2, 12)
        for seq, raw in old_sequences.items():
            assert target_sequences[seq] == raw, seq
            preserved += 1
        item_map = {i: i for i in old.files(2, 10)}
        item_map.update({int(k): v for k, v in report["item_mapping"].items()})
        for source_id, target_id in smap.items():
            expected = _rewrite_equipment(sequence184(donor_sequences[source_id], fmap.__getitem__), item_map)
            assert target_sequences[target_id] == expected, ("sequence semantics", source_id, target_id)
        for source_id, target_id in bmap.items():
            expected, _ = skeleton184(source.files(1, source_id)[0])
            assert target.files(1, target_id)[0] == expected, ("skeleton semantics", source_id, target_id)
        for source_id, target_id in fmap.items():
            expected = {fid: short(bmap[int.from_bytes(raw[:2], "big")]) + raw[2:]
                        for fid, raw in source.files(0, source_id).items()}
            assert target.files(0, target_id) == expected, ("frame semantics", source_id, target_id)
        sequences = {i: target_sequences[i] for i in sorted(set(smap.values()))}
        frames = {i: target.files(0, i) for i in sorted(set(fmap.values()))}
        skeletons = {i: target.files(1, i)[0] for i in sorted(set(bmap.values()))}
        items = target.files(2, 10)
        outfit = [item_map[i] for i in (26382, 26384, 26386, 26219)]
        infos = {row["id"]: row for row in load_json(stage / "data/items/item_info.json")}
        profiles = load_json(stage / "data/items/weapon_types.json")
        fang = profiles[infos[item_map[26219]]["weapon_type"]]
        outfit_sequences = sorted(set(fang["renderAnimations"] + [fang["attackAnimation"], fang["defendAnimation"]]
                                      + [s["attackAnimation"] for s in fang["attackSets"] if s and "attackAnimation" in s]))
        assert all(i in sequences for i in outfit_sequences), "Representative outfit contains unexported legacy fallback sequences"

        with (output / "animations.bin").open("wb") as stream:
            def integer(value):
                stream.write(struct.pack(">I", value))

            def blob(raw):
                integer(len(raw))
                stream.write(raw)

            integer(len(skeletons))
            for i, raw in skeletons.items():
                integer(i)
                blob(raw)
            integer(len(frames))
            for group, files in frames.items():
                integer(group)
                integer(len(files))
                for i, raw in files.items():
                    integer(i)
                    blob(raw)
            integer(len(sequences))
            for i, raw in sequences.items():
                integer(i)
                blob(raw)
            integer(2)
            for gender, ops in enumerate(((23, 24, 78), (25, 26, 79))):
                meshes = []
                for i in outfit:
                    fields = read_item(items[i])
                    meshes.extend(target.files(7, int.from_bytes(fields[op][:2], "big"))[0] for op in ops if op in fields)
                integer(gender)
                integer(len(meshes))
                for raw in meshes:
                    blob(raw)
                integer(len(outfit_sequences))
                for i in outfit_sequences:
                    integer(i)
    summary = dict(original_animation_records_preserved=preserved,
                   imported_sequence_ids=len(sequences), imported_frame_groups=len(frames),
                   imported_frames=sum(len(files) for files in frames.values()),
                   imported_skeletons=len(skeletons), source_sequence_mappings_verified=len(smap),
                   outfit_target_items=outfit, outfit_sequences=outfit_sequences,
                   source_to_target_animation_semantics="exact after explicit remapping and documented trailer removal")
    (output / "animation-audit.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--donor", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.stage, args.base, args.donor, args.output)
