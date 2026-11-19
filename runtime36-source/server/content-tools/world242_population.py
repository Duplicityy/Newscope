"""Audit the installed population and reconcile reviewed Newscape-only conflicts."""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
from hashlib import sha256
import json
from pathlib import Path
import re

from cache_store import Store
from modern_definitions import read_npc


SPAWNS = Path("npcs/spawns")
JSON_TOKENS = re.compile(r'"(?:\\.|[^"\\])*"|#[^\n]*|//[^\n]*|/\*.*?\*/', re.S)
BARE_PET = re.compile(r'("pet"\s*:\s*)([A-Z][A-Z_0-9]*)(?=\s*[,}])')
SHOP_ID = re.compile(r'''^identifier:\s*["']?([^\s"']+)''', re.M)


@dataclass(frozen=True)
class DuplicateRule:
    authoritative: str
    copied_into: str
    region: int
    copies: int
    affected_ids: frozenset[int]


RULES = (
    DuplicateRule("dungeons/chasm_of_fire.json", "dungeons/smoke_devil_dungeon.json",
                  5789, 43, frozenset({240, 2030, 2031, 2032, 5874, 5875, 5876, 5877,
                                        7656, 7657, 7664, 7668, 7670})),
    DuplicateRule("dungeons/kraken_cove.json", "dungeons/gnome_stronghold.json",
                  9116, 13, frozenset({2917})),
)


def read_json(path, allow_native_comments=False):
    text = path.read_text(encoding="utf-8-sig")
    if allow_native_comments:
        text = JSON_TOKENS.sub(lambda m: m[0] if m[0].startswith('"') else "", text)
        # The legacy TzKal-Zuk combat record uses Gson's unquoted enum syntax.
        if path.name == "TzKal-Zuk.json":
            text = BARE_PET.sub(r'\1"\2"', text)
    return json.loads(text)


def read_spawns(data):
    return {path.relative_to(data / SPAWNS).as_posix(): read_json(path)
            for path in sorted((data / SPAWNS).rglob("*.json"))}


def region_of(row):
    return (row["x"] >> 6) << 8 | (row["y"] >> 6)


def identity(row):
    return (row["id"], row["x"], row["y"], row["z"], row.get("world"))


def reconcile(rule, sources):
    """Keep one authoritative row for each precisely reviewed cross-file match."""
    canonical = sources[rule.authoritative]
    copied = sources[rule.copied_into]
    canonical_keys = Counter(map(identity, canonical))
    copied_keys = Counter(map(identity, copied))
    overlap = canonical_keys & copied_keys
    reviewed = [row for row in canonical
                if region_of(row) == rule.region and row["id"] in rule.affected_ids
                and row.get("world") is None]
    if len(reviewed) < rule.copies or {row["id"] for row in reviewed} != rule.affected_ids:
        raise ValueError(f"Authoritative population is incomplete in {rule.authoritative}")
    if not overlap:
        return copied, []
    if (len(overlap) != rule.copies or {key[0] for key in overlap} != rule.affected_ids
            or any(canonical_keys[key] != 1 or copied_keys[key] != 1 for key in overlap)):
        raise ValueError(f"Reviewed match set changed in {rule.copied_into}")
    removed = [row for row in copied if identity(row) in overlap]
    if any(region_of(row) != rule.region or row.get("world") is not None or row.get("shopOptions")
           for row in removed):
        raise ValueError(f"Unexpected protected placement in {rule.copied_into}")
    return [row for row in copied if identity(row) not in overlap], removed


def definition_ids(folder, predicate):
    ids = set()
    for path in sorted(folder.rglob("*.json")):
        for row in read_json(path, allow_native_comments=True):
            if predicate(row):
                ids.update(row["ids"])
    return ids


def audit(cache, data, sources, world):
    eligible = [(path, index, row) for path, rows in sources.items() for index, row in enumerate(rows)
                if row.get("world") in (None, world)]
    spawned_ids = {row["id"] for _, _, row in eligible}
    with Store(cache) as store:
        raw = store.files(2, 9)
    if spawned_ids - raw.keys():
        raise ValueError(f"Spawns with missing cache NPCs: {sorted(spawned_ids - raw.keys())}")
    meta = {npc: read_npc(raw[npc], modern=False)[1] for npc in spawned_ids}
    combat = definition_ids(data / "npcs/combat", lambda _: True)
    drops = definition_ids(data / "npcs/drops" / world.lower(),
                           lambda row: bool(row.get("guaranteed") or row.get("tables")))
    world_data = read_json(data / "content242-world.json")
    drops.update(int(npc) for npc, groups in world_data["loot"].items() if groups)
    shop_bindings = {row["npc"] for row in world_data["shopBindings"]}
    shop_uuids = {match for path in (data / "shops").rglob("*.yaml")
                  for match in SHOP_ID.findall(path.read_text(encoding="utf-8-sig"))}
    regions = defaultdict(Counter)
    repeated = defaultdict(list)
    no_combat = []
    shop_evidence = []
    for path, index, row in eligible:
        npc = row["id"]
        region = region_of(row)
        options = {value.lower() for value in meta[npc]["options"] if value}
        location = {"source_file": path, "source_row": index + 1, "npc": npc,
                    "tile": [row["x"], row["y"], row["z"]]}
        repeated[identity(row)].append((path, index + 1))
        regions[region]["static_rows"] += 1
        if path == "content242.json":
            regions[region]["content242_rows"] += 1
        if npc in combat:
            regions[region]["combat_bound_rows"] += 1
            if npc in drops:
                regions[region]["declared_loot_rows"] += 1
        elif "attack" in options:
            no_combat.append(location)
            regions[region]["attack_option_without_combat_rows"] += 1
        if npc in shop_bindings or row.get("shopOptions"):
            regions[region]["declared_shop_rows"] += 1
        for option, key in (row.get("shopOptions") or {}).items():
            if key not in shop_uuids or option.lower() not in options:
                shop_evidence.append({**location, "option": option,
                                      "shop_yaml_present": key in shop_uuids,
                                      "option_in_cache": option.lower() in options})
    duplicates = {key: rows for key, rows in repeated.items() if len(rows) > 1}
    return {
        "world": world,
        "authored_rows": sum(map(len, sources.values())),
        "eligible_static_rows": len(eligible),
        "source_files": len(sources),
        "installed_spawn_npc_ids": len(spawned_ids),
        "repeated_identity_groups": len(duplicates),
        "static_rows_with_repeated_identity": sum(map(len, duplicates.values())),
        "repeat_evidence": [{"npc": key[0], "tile": list(key[1:4]), "world": key[4], "rows": rows}
                            for key, rows in duplicates.items()],
        "regions": {region: dict(regions[region]) for region in sorted(regions)},
        "attack_option_without_static_combat": no_combat,
        "shop_bindings_requiring_runtime_review": shop_evidence,
    }


def write(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--world", choices=("PVP", "ECO"), default="PVP")
    parser.add_argument("--report", type=Path, help="Write detailed audit JSON to a fresh file")
    parser.add_argument("--overlay-data", type=Path, help="Stage changed data files in a fresh directory")
    args = parser.parse_args()
    sources = read_spawns(args.data)
    before = audit(args.cache, args.data, sources, args.world)
    planned, changes = {}, []
    for rule in RULES:
        cleaned, removed = reconcile(rule, sources)
        if not removed:
            continue
        planned[rule.copied_into] = cleaned
        changes.append({"keep_source": rule.authoritative, "clean_source": rule.copied_into,
                        "kept_sha256": sha256((args.data / SPAWNS / rule.authoritative).read_bytes()).hexdigest(),
                        "cleaned_from_sha256": sha256((args.data / SPAWNS / rule.copied_into).read_bytes()).hexdigest(),
                        "region": rule.region, "removed_rows": removed})
    after = audit(args.cache, args.data, sources | planned, args.world) if planned else before
    protected_roots = [args.data.resolve(), args.cache.resolve()]
    for destination in (args.overlay_data, args.report):
        if destination and any(root == destination.resolve() or root in destination.resolve().parents
                               for root in protected_roots):
            raise ValueError("Audit outputs must be outside the active cache and data")
        if destination and destination.exists():
            raise FileExistsError(destination)
    if args.overlay_data:
        args.overlay_data.mkdir(parents=True, exist_ok=False)
        for path, rows in planned.items():
            write(args.overlay_data / SPAWNS / path, rows)
    if args.report:
        write(args.report, {"schema_version": 1, "baseline": before, "reconciled": after,
                            "changes": changes})
    print(f"{before['eligible_static_rows']} eligible NPC rows in {len(before['regions'])} regions for {args.world}")
    print(f"{sum(len(change['removed_rows']) for change in changes)} copied rows matched, "
          f"{after['eligible_static_rows']} eligible after reconciliation")


if __name__ == "__main__":
    main()
