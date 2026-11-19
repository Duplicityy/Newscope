"""Audit the installed placements and protected areas for the 242 resource bindings."""

import argparse
from collections import Counter
import json
from pathlib import Path
import re

from cache_store import Store, name_hash
from verify_conversion import server_landscape


RESOURCE_SOURCES = {
    "mining": (11161, 10943, *range(11360, 11378), 11386, 11387, 11380, 11381, 11390, 11391),
    "woodcutting": (1278, 1276, 2091, 1286, 1282, 1289, 10820, 10833, 10831, 10829, 9036,
                    10832, 10822, 10834, 9034),
    "cooking": (7183, 7184, 9682, 16641, 25730, 26181, 27517, 32739, 37728, 39391, 39490),
}


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def audit(runtime):
    data = runtime / "data"
    mappings = load(data / "content242.json")["mappings"]
    keys = load(data / "region_keys.json")
    teaser = load(data / "home/arceuus-teaser.json")
    lobby = load(data / "dungeoneering/daemonheim-lobby.json")
    dungeons = load(data / "dungeoneering/darkan-assets.json")
    assignments = {kind: {mappings["objects"][str(source)] for source in sources}
                   for kind, sources in RESOURCE_SOURCES.items()}
    targets = set().union(*assignments.values())
    if len(targets) != sum(map(len, RESOURCE_SOURCES.values())):
        raise ValueError("Overlapping or omitted resource definition")

    protected = {"Newscape home": 7991, "Edgeville": 12342, "Arceuus teaser": teaser["region"]}
    protected.update({f"Daemonheim {entry['source']}": entry["id"] for entry in lobby["regions"]})
    counts = Counter()
    occupied = {kind: set() for kind in assignments}
    all_occupied = set()
    with Store(runtime / "Cache") as cache:
        named = {group.name: key for key, group in cache.table(5)[3].items()}

        def scenery(region):
            if any(keys[str(region)]):
                raise ValueError(f"Encrypted landscape requires explicit audit: {region}")
            archive = named[name_hash(f"l{region >> 8}_{region & 255}")]
            return server_landscape(cache.files(5, archive)[0])

        for region in sorted({part["destination"] for part in mappings["regions"].values()}):
            objects = scenery(region)
            for kind, assigned in assignments.items():
                present = objects.keys() & assigned
                if present:
                    occupied[kind].add(region)
                    all_occupied.add(region)
                    counts.update({obj: len(objects[obj]) for obj in present})
        for label, region in protected.items():
            overlap = scenery(region).keys() & targets
            if overlap:
                raise ValueError(f"{label} contains affected definitions {sorted(overlap)}")

    authored = {row["source"]["id"] for row in teaser["objects"] + teaser["stairs"]}
    authored.update(row["id"] for row in teaser["originalTraversalRetainedAtMainHome"])
    for path in (data / "objects/spawns").rglob("*.json"):
        # Native spawn files allow comments.
        authored.update(int(value) for value in re.findall(r'"id"\s*:\s*(\d+)', path.read_text(encoding="utf-8-sig")))
    authored.update(dungeons["objectMappings"].values())
    authored.update(lobby["objectMappings"].values())
    if authored & targets:
        raise ValueError(f"Protected authored objects overlap {sorted(authored & targets)}")
    if counts.keys() != targets:
        raise ValueError("One or more resource definitions have no installed scenery placements")
    return {
        "groups": {kind: {"definitions": len(ids), "sceneryPlacements": sum(counts[obj] for obj in ids),
                          "regions": sorted(occupied[kind]), "placementsById": {i: counts[i] for i in sorted(ids)}}
                   for kind, ids in assignments.items()},
        "affectedRegions": len(all_occupied),
        "protectedRegionsWithoutAffectedDefinitions": protected,
        "protectedAuthoredDefinitionOverlap": [],
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("runtime", type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.runtime), indent=2))
