"""Compare a mature native server snapshot after staging the world animation data."""

import argparse
from collections import Counter
from pathlib import Path

from world_combat_animations import (
    BASIC_COMBAT, CHROMATIC_DRAGON, FIELDS, RED_DRAGONS_WITH_MISSING_BASIC_ANIMATIONS,
    installed_combat, read_json, read_tsv,
)


def stripped(row):
    excluded = {"ids", *(column + "_animation" for column in FIELDS.values())}
    return {key: value for key, value in row.items() if key not in excluded}


def verify(data, audit, overlay, before, after):
    sources = installed_combat(data)
    repairs = {row["npc"]: row for row in read_json(audit)["changes"]}
    overlays = {}
    for row in read_json(overlay):
        if len(row["ids"]) != 1 or row["ids"][0] in overlays:
            raise ValueError("The verified animation stage has a duplicate or shared override")
        overlays[row["ids"][0]] = row
    if repairs.keys() != overlays.keys():
        raise ValueError("Animation overlay NPC IDs disagree with its audit")
    for id, row in overlays.items():
        if stripped(row) != stripped(sources[id][1]):
            raise ValueError(f"The overlay changed non-animation combat settings on NPC {id}")

    baseline = {int(row["id"]): row for row in read_tsv(before / "npc-definitions.tsv")}
    candidate = {int(row["id"]): row for row in read_tsv(after / "npc-definitions.tsv")}
    for id, row in baseline.items():
        applied = repairs.get(id, {}).get("sequences", {})
        for role, field in FIELDS.items():
            expected = str(applied[role]["after"]) if role in applied else row[field]
            if candidate[id][field] != expected:
                raise ValueError(f"Unexpected {role} sequence on NPC definition {id}")

    keys = ("id", "default_spawn", "x", "y", "z", "walk_range", "combat_class", "style")
    base_spawns = [row for row in read_tsv(before / "npc-spawns.tsv") if row["combat_class"]]
    candidate_spawns = [row for row in read_tsv(after / "npc-spawns.tsv") if row["combat_class"]]
    expected = Counter()
    for row in base_spawns:
        id = int(row["id"])
        applied = repairs.get(id, {}).get("sequences", {})
        red_dragon = id in RED_DRAGONS_WITH_MISSING_BASIC_ANIMATIONS and row["combat_class"] == CHROMATIC_DRAGON
        if applied and row["combat_class"] != BASIC_COMBAT and not red_dragon:
            raise ValueError(f"NPC {id} uses scripted combat")
        sequences = tuple(str(applied[role]["after"]) if role in applied else row[field] for role, field in FIELDS.items())
        expected[tuple(row[key] for key in keys) + sequences] += 1
    observed = Counter(tuple(row[key] for key in keys) + tuple(row[field] for field in FIELDS.values())
                       for row in candidate_spawns)
    if observed != expected:
        raise ValueError(f"Native combat spawns differ from expected overrides ({sum((expected - observed).values())} mismatches)")
    stationary = sum(int(row["id"]) in repairs and row["walk_range"] == "0" for row in candidate_spawns)
    return len(candidate), len(candidate_spawns), len(repairs), stationary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("data", "audit", "overlay", "before", "after"):
        parser.add_argument("--" + field, type=Path, required=True)
    definitions, spawns, changed, stationary = verify(**vars(parser.parse_args()))
    print(f"PASS {definitions} definitions and {spawns} native combat spawns; "
          f"{changed} NPC profiles updated, including {stationary} stationary placements.")
