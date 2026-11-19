"""Generate additive Kronos world data from pinned242 exports and verified mappings.

Inputs are read-only. Existing NPC definitions/spawns/economy are never replaced.
Unmapped dependencies and conditional rewards are recorded, never guessed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path

from gear_metadata import load_json
from drop_subtables import convert as shared_drop_table
from combat_animation_roles import RoleEvidence

SCRIPTED_BOSSES = re.compile(
    r"scurrius|amoxliatl|muspah|^nex$|^nightmare$|phosani|vardorvis|duke sucellus|"
    r"leviathan|whisperer|araxxor|hueycoatl|royal titan|branda|eldric|^yama$|"
    r"sol heredit|^akkha|^baba$|ba-ba|^kephri|^zebak|warden|blood moon|blue moon|eclipse moon",
    re.IGNORECASE,
)
CONDITIONAL = re.compile(r"quest.?requirement|quest_|requires? (?:completion|a |the )|"
                         r"only dropped|only during|slayer task|while (?:wearing|on)|if (?:the |you )",
                         re.IGNORECASE)
MAX_INT = 2_147_483_647


def read(path):
    return load_json(Path(path))


def dump(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def number(value, default=0, low=-MAX_INT, high=MAX_INT):
    if value is None:
        value = default
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"Invalid numeric source value: {value!r}")
    if int(value) != value or not low <= value <= high:
        raise ValueError(f"Source integer outside bounds: {value!r}")
    return int(value)


def ids(mapping, limit):
    result = {int(k): number(v, low=0, high=limit) for k, v in mapping.items()}
    if any(k < 0 for k in result):
        raise ValueError("Negative source identifier")
    return result


def relocate(x, y, regions):
    x, y = number(x, low=0, high=16383), number(y, low=0, high=16383)
    source = ((x >> 6) << 8) | (y >> 6)
    region = regions.get(str(source))
    if region is None:
        return None
    destination = number(region["destination"], low=0, high=65535)
    dx, dy = ((destination >> 8) - (source >> 8)) * 64, ((destination & 255) - (source & 255)) * 64
    if region.get("dx", dx) != dx or region.get("dy", dy) != dy:
        raise ValueError("Region translation and destination disagree")
    tx, ty = x + dx, y + dy
    if tx >= 6400:
        raise ValueError("Static content overlaps the native dynamic-region pool")
    return tx, ty


def build(manifest, donor, baseline_npcs, npc_metadata, item_values):
    if manifest.get("schemaVersion") != 2 or manifest.get("sourceRevision") != 242:
        raise ValueError("Expected verified content242 schema2")
    maps = manifest["mappings"]
    npcs, items = ids(maps["npcs"], 16383), ids(maps["items"], 65534)
    animations = ids(maps["animations"], 65534)
    regions = maps["regions"]
    handlers = manifest.get("mechanics", {}).get("npcHandlers", {})
    encounter_overrides = manifest.get("mechanics", {}).get("npcCombatOverrides", {})
    instance_only = set(manifest.get("mechanics", {}).get("instanceOnlyNpcs", []))
    definitions = Path(donor) / "server/data/definitions"
    monsters = read(definitions / "monsters-complete.json")
    combat_defs = read(definitions / "npc-combat-defs.json")
    overrides = combat_defs.get("npcs", {})
    role_evidence = RoleEvidence(definitions, combat_defs)
    source_spawns = read(definitions / "npc-spawns.json")
    source_drops = read(definitions / "npc-drops.json")
    shared_path = definitions / "npc-drop-subtables.json"
    source_shared = read(shared_path) if shared_path.is_file() else {}
    source_shops = read(definitions / "shops.json")
    report = {"excludedSpawns": [], "excludedCombat": [], "excludedRewards": [], "excludedTravel": [],
              "excludedShops": [], "approximations": [
                  "Legacy ranged defence uses the source standard-ranged defence value.",
                  "Imported shops use base stock prices and native restocking, not stock-sensitive OSRS pricing.",
                  "Imported shops currently sell stock to players only; selling items back is disabled.",
                  "Shared loot uses base table odds; Ring of wealth modifiers and conditional shared rewards are not applied.",
                  "Shop stock is capped at100000; conditional quest rewards remain unavailable."]}
    combat, spawns, shops, loot, bindings = [], [], [], {}, []
    combat_targets, denied_sources, spawn_sources = set(), set(), set()

    def animation(source, fallback=-1):
        source = number(source, default=-1, low=-1, high=65534)
        return -1 if source == -1 else animations.get(source, fallback)

    for source, target in sorted(npcs.items()):
        if target in baseline_npcs or target in combat_targets:
            continue
        meta = npc_metadata.get(str(source), {})
        monster = monsters.get(str(source))
        encounter = encounter_overrides.get(str(source), {})
        if encounter and str(source) not in handlers:
            raise ValueError("Encounter metadata requires a native handler")
        if encounter.get("stats"):
            monster = {**(monster or {}), **encounter["stats"]}
        name = str(meta.get("name") or (monster or {}).get("name") or "")
        handler = handlers.get(str(source))
        if SCRIPTED_BOSSES.search(name) and not handler:
            denied_sources.add(source)
            report["excludedCombat"].append({"npc": source, "reason": "Scripted encounter has no enabled native handler"})
            continue
        if not monster:
            if number(meta.get("combatLevel", 0), low=0) > 0:
                denied_sources.add(source)
                report["excludedCombat"].append({"npc": source, "reason": "Combat stats unavailable"})
            continue
        override = {**overrides.get(str(source), {}), **encounter.get("combat", {})}
        custom = override.get("anims", {})
        roles = role_evidence.roles(source, name)
        attack = custom.get("attack", roles.get("attack", (-1, None))[0])
        block = custom.get("block", roles.get("block", (-1, None))[0])
        death = custom.get("death", roles.get("death", (-1, None))[0])
        if attack == -1 and not handler and number(monster.get("max_hit"), low=0, high=10000) > 0:
            denied_sources.add(source)
            report["excludedCombat"].append({"npc": source, "reason": "Damage-dealing NPC has no verified attack animation"})
            continue
        if attack != -1 and animation(attack) == -1:
            denied_sources.add(source)
            report["excludedCombat"].append({"npc": source, "reason": "Attack animation dependency unmapped"})
            continue
        styles = monster.get("attack_type") or ["crush"]
        styles = [styles] if isinstance(styles, str) else styles
        native_styles = {"stab": "STAB", "slash": "SLASH", "crush": "CRUSH", "melee": "CRUSH", "magic": "MAGIC", "ranged": "RANGED"}
        if len(styles) != 1 and not handler:
            denied_sources.add(source)
            report["excludedCombat"].append({"npc": source, "reason": "Multiple attack styles require an encounter handler"})
            continue
        style = native_styles.get(str(styles[0]).lower())
        if style is None:
            denied_sources.add(source)
            continue
        if not isinstance(monster.get("hitpoints"), (int, float)) or not 1 <= monster["hitpoints"] <= 100000:
            denied_sources.add(source)
            report["excludedCombat"].append({"npc": source, "reason": "Hitpoints unavailable or outside native bounds"})
            continue
        hp = number(monster.get("hitpoints"), low=1, high=100000)
        row = {"ids": [target], "hitpoints": hp, "max_damage": number(monster.get("max_hit"), low=0, high=10000),
               "aggressive_level": 999 if monster.get("aggressive") else 0, "attack_style": style,
               "slayer_level": number(monster.get("slayer_level"), low=0, high=99),
               "slayer_xp": float(monster.get("slayer_xp") or 0),
               "attack_ticks": number(monster.get("attack_speed"), 4, low=1, high=100),
               "death_ticks": number(override.get("deathTicks"), 2, low=0, high=100),
               "respawn_ticks": number(override.get("respawn"), 25, low=-1, high=10000),
               "spawn_animation": animation(custom.get("spawn", -1)), "attack_animation": animation(attack),
               "defend_animation": animation(block), "death_animation": animation(death),
               "poison_immunity": bool(monster.get("immune_poison")), "venom_immunity": bool(monster.get("immune_venom"))}
        for stat in ("attack", "strength", "defence", "ranged", "magic"):
            row[stat] = number(monster.get(stat + "_level"), 1, low=0, high=10000)
        for kind in ("stab", "slash", "crush"):
            row[kind + "_attack"] = number(monster.get("attack_bonus"))
            row[kind + "_defence"] = number(monster.get("defence_" + kind))
        row.update(magic_attack=number(monster.get("attack_magic")), ranged_attack=number(monster.get("attack_ranged")),
                   magic_defence=number(monster.get("defence_magic")),
                   ranged_defence=number(monster.get("defence_ranged_standard", monster.get("defence_ranged", 0))))
        if handler:
            if not handler.startswith("io.ruin.content.content242."):
                raise ValueError("Encounter handler must belong to the port namespace")
            row["handler"] = handler
        combat.append(row)
        combat_targets.add(target)

    seen_spawns = set()
    for spawn in source_spawns:
        source = number(spawn.get("id"), low=0)
        point = relocate(spawn.get("x"), spawn.get("y"), regions)
        if point is None:
            continue
        if source not in npcs or source in denied_sources:
            report["excludedSpawns"].append({"npc": source, "reason": "Missing definition or unavailable combat"})
            continue
        if source in instance_only:
            report["excludedSpawns"].append({"npc": source, "reason": "Created only by its private encounter handler"})
            continue
        x, y = point
        z = number(spawn.get("level", 0), low=0, high=3)
        identity = (npcs[source], x, y, z)
        if identity in seen_spawns:
            continue
        seen_spawns.add(identity)
        spawn_sources.add(source)
        spawns.append({"id": npcs[source], "name": spawn.get("name", ""), "x": x, "y": y, "z": z,
                       "walkRange": min(12, number(spawn.get("wanderRadius", 0), low=0, high=16383))})

    def reward(entry, source, table):
        if entry.get("nothing"):
            return None
        reason = None
        if CONDITIONAL.search(" ".join(map(str, entry.get("notes", [])))):
            reason = "Conditional gameplay prerequisite unavailable"
        if "shared_table" in entry and reason is None:
            if entry.get("unresolved_roll_count"):
                report["excludedRewards"].append({"npc": source, "table": table, "reason": "Shared roll count unresolved"})
                return None
            group = shared_drop_table(entry["shared_table"], source_shared, items, report["excludedRewards"])
            if group is None:
                return None
            result = {"table": group}
            if "roll_count_weights" in entry:
                result["rollCounts"] = [{"rolls": number(r["rolls"], low=1, high=8),
                                         "weight": number(r["weight"], low=1, high=MAX_INT)}
                                        for r in entry["roll_count_weights"]]
            return result
        source_item = entry.get("item_id")
        if source_item not in items:
            reason = "Reward item dependency unmapped"
        if reason:
            report["excludedRewards"].append({"npc": source, "table": table, "item": source_item, "reason": reason})
            return None
        quantity = entry.get("quantity", [1, 1])
        if len(quantity) != 2:
            raise ValueError("Reward quantity must have two bounds")
        low, high = (number(v, low=1, high=MAX_INT) for v in quantity)
        if high < low:
            raise ValueError("Reversed reward quantity")
        return {"id": items[source_item], "min": low, "max": high}

    for source, target in sorted(npcs.items()):
        if target in baseline_npcs or target not in combat_targets or str(target) in loot:
            continue
        groups = []
        for table_id in source_drops.get("npcs", {}).get(str(source), {}).get("tables", []):
            table = source_drops.get("tables", {}).get(table_id)
            if not table:
                report["excludedRewards"].append({"npc": source, "table": table_id, "reason": "Unresolved shared table"})
                continue
            source_total = sum(number(e.get("weight"), 0, low=0) for e in table.get("entries", [])
                               if not e.get("always") and not e.get("separate_roll"))
            group = {"guaranteed": [], "main": [], "independent": [], "denominator": number(table.get("main_max_roll"), source_total, low=0)}
            for entry in table.get("entries", []):
                item = reward(entry, source, table_id)
                if item is None:
                    continue
                if entry.get("always"):
                    group["guaranteed"].append(item)
                elif entry.get("separate_roll"):
                    item.update(weight=number(entry.get("weight"), 0, low=0),
                                denominator=number(entry.get("out_of"), low=1))
                    if item["weight"] > item["denominator"]:
                        raise ValueError("Independent reward probability exceeds one")
                    group["independent"].append(item)
                else:
                    item["weight"] = number(entry.get("weight"), 0, low=0)
                    group["main"].append(item)
            total = sum(i["weight"] for i in group["main"])
            # Excluded entries retain their original probability as an empty roll.
            group["denominator"] = max(group["denominator"], total)
            for entry in table.get("tertiary", []):
                item = reward(entry, source, table_id)
                if item is None:
                    continue
                if not isinstance(entry.get("out_of"), (int, float)) or entry["out_of"] < 1:
                    report["excludedRewards"].append({"npc": source, "table": table_id, "reason": "Independent reward odds unavailable"})
                    continue
                item.update(weight=number(entry.get("weight"), 0, low=0), denominator=number(entry["out_of"], low=1))
                if item["weight"] > item["denominator"]:
                    raise ValueError("Reward probability exceeds one")
                group["independent"].append(item)
            groups.append(group)
        if groups:
            loot[str(target)] = groups

    for shop in source_shops:
        interactions = [i for i in shop.get("npcInteractions", []) if any(n in spawn_sources for n in i.get("npcIds", []))]
        if not interactions:
            continue
        currency = shop.get("currency", 995)
        if currency not in (995, "COINS", "coins"):
            report["excludedShops"].append({"shop": shop["id"], "reason": "Currency implementation unavailable"})
            continue
        rate = number(shop.get("sellsAt"), 1000, low=1, high=100000)
        stock = []
        for entry in shop.get("originalStock", []):
            source_item = entry["id"]
            amount = min(100000, number(entry.get("amount"), low=0))
            if amount == 0:
                report["excludedShops"].append({"shop": shop["id"], "item": source_item,
                                                "reason": "Zero-stock buyback offer unavailable in sell-only import"})
                continue
            value = item_values.get(str(source_item))
            if source_item not in items or value is None:
                report["excludedShops"].append({"shop": shop["id"], "item": source_item, "reason": "Item or raw value unmapped"})
                continue
            price = max(1, number(value, low=0) * rate // 1000)
            if price > MAX_INT:
                raise ValueError("Shop price exceeds native integer")
            stock.append({"id": items[source_item], "amount": amount, "price": price})
        if not stock:
            continue
        # Preserve every supported stock row using ordinary40-slot shop pages.
        pages = []
        for start in range(0, len(stock), 40):
            key = f"content242-shop-{shop['id']}-{start // 40}"
            pages.append(key)
            shops.append({"identifier": key, "title": shop["name"] + (f" ({start // 40 + 1})" if len(stock) > 40 else ""),
                          "currency": "COINS", "canSellToStore": False, "defaultStock": stock[start:start + 40]})
        for interaction in interactions:
            for source in interaction["npcIds"]:
                if source in spawn_sources:
                    if npcs[source] in baseline_npcs:
                        report["excludedShops"].append({"shop": shop["id"], "npc": source,
                                                        "reason": "Existing NPC keeps its original action handlers"})
                        continue
                    bindings.append({"npc": npcs[source], "option": number(interaction["optionSlot"], low=1, high=5), "shops": pages})
    destinations, links = travel(manifest, donor, report)
    report["counts"] = {"combatDefinitions": len(combat), "spawns": len(spawns), "dropTables": len(loot),
                        "shopPages": len(shops), "destinations": len(destinations), "objectLinks": len(links)}
    return {"combat": combat, "spawns": spawns, "loot": loot, "shops": shops, "shopBindings": bindings,
            "destinations": destinations, "objectLinks": links, "report": report}


def travel(manifest, donor, report):
    """Add travel only inside imported regions; never replace original-world actions."""
    mappings = manifest["mappings"]
    regions = mappings["regions"]
    objects, animations = ids(mappings.get("objects", {}), 65534), ids(mappings["animations"], 65534)

    def point(tile):
        if not isinstance(tile, list) or len(tile) != 3:
            raise ValueError("Travel tile requires x, y, plane")
        moved = relocate(tile[0], tile[1], regions)
        return None if moved is None else [*moved, number(tile[2], low=0, high=3)]

    destinations, links, seen = [], [], set()
    portal_file = Path(donor) / "server/plugins/interface/data/teleports.json"
    if portal_file.is_file():
        for row in read(portal_file).get("destinations", []):
            # Town exploration only. Encounter-specific entry is enabled by its own script.
            if row.get("tab") != "places" or row.get("requires") or row.get("wilderness"):
                continue
            name = re.sub(r"[<>\x00-\x1f]", "", str(row["name"]))[:80]
            if SCRIPTED_BOSSES.search(name):
                continue
            destination = point(row["tile"])
            if destination is None or name in seen:
                continue
            seen.add(name)
            destinations.append({"name": name, "tile": destination})
    link_file = Path(donor) / "server/plugins/world/data/loc-teleports.json"
    if link_file.is_file():
        for row in read(link_file).get("locs", []):
            origin = point([row["x"], row["y"], row["z"]])
            if origin is None:
                continue
            reason = None
            if row["id"] not in objects:
                reason = "Object unmapped"
            elif row.get("requires") or row.get("recordings", 0) < 1:
                reason = "Unimplemented requirements or uncaptured route"
            elif row.get("anim", -1) != -1 and row["anim"] not in animations:
                reason = "Traversal animation unmapped"
            paths = row.get("sides") or [{"to": row.get("to")}]
            routes = []
            for path in paths:
                if not path.get("to"):
                    reason = "Unsupported traversal destination"
                    break
                target = point(path["to"])
                start = point(path["from"]) if "from" in path else None
                if target is None or ("from" in path and start is None):
                    reason = "Traversal crosses an unimported region"
                    break
                route = {"to": target}
                if start is not None:
                    route["from"] = start
                routes.append(route)
            if reason:
                report["excludedTravel"].append({"object": row["id"], "origin": origin, "reason": reason})
                continue
            ticks = number(row.get("tick"), 1, low=0, high=20)
            links.append({"object": objects[row["id"]], "origin": origin,
                          "option": number(row["op"], low=1, high=5), "optionName": row["option"],
                          "animation": animations.get(row.get("anim"), -1),
                          "animationTick": min(ticks, number(row.get("animTick"), 0, low=0, high=20)),
                          "ticks": ticks, "routes": routes})
    return sorted(destinations, key=lambda row: row["name"]), links


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--donor", type=Path, required=True)
    parser.add_argument("--base-cache", type=Path, required=True)
    parser.add_argument("--npc-metadata", type=Path, required=True)
    parser.add_argument("--item-values", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("World-data output must be a fresh directory")
    from cache_store import Store
    with Store(args.base_cache) as baseline:
        result = build(read(args.manifest), args.donor, set(baseline.files(2, 9)), read(args.npc_metadata), read(args.item_values))
    dump(args.output / "npcs/combat/content242.json", result["combat"])
    dump(args.output / "npcs/spawns/content242.json", result["spawns"])
    dump(args.output / "content242-world.json", {"schemaVersion": 1, "enabled": True, "loot": result["loot"],
         "shopBindings": result["shopBindings"], "destinations": result["destinations"], "objectLinks": result["objectLinks"]})
    # JSON is valid YAML; use the ordinary shop loader without a new parser.
    for shop in result["shops"]:
        dump(args.output / "shops" / (shop["identifier"] + ".yaml"), shop)
    dump(args.output / "world-data-report.json", result["report"])
    print(json.dumps(result["report"]["counts"], sort_keys=True))


if __name__ == "__main__":
    main()
