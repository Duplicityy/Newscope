"""Build additive Kronos 184 metadata from decoded revision 242 items.

This is deliberately a data adapter, not a combat-mechanics implementation. The
report distinguishes usable base equipment from inventory-only assets. Existing
item rows, weapon profiles and caller-owned dictionaries are never modified.
See GEAR_METADATA.md for the supported behavior and the source field mappings.
"""

from copy import deepcopy
import json
import math


BONUS_FIELDS = (
    "stab_attack_bonus", "slash_attack_bonus", "crush_attack_bonus",
    "magic_attack_bonus", "range_attack_bonus", "stab_defence_bonus",
    "slash_defence_bonus", "crush_defence_bonus", "magic_defence_bonus",
    "range_defence_bonus", "melee_strength_bonus", "ranged_strength_bonus",
    "magic_damage_bonus", "prayer_bonus",
)
SKILLS = (
    "attack", "defence", "strength", "hitpoints", "ranged", "prayer",
    "magic", "cooking", "woodcutting", "fletching", "fishing", "firemaking",
    "crafting", "smithing", "mining", "herblore", "agility", "thieving",
    "slayer", "farming", "runecrafting", "hunter", "construction",
)
EQUIPMENT_SLOTS = frozenset((0, 1, 2, 3, 4, 5, 7, 9, 10, 12, 13))
EQUIP_ACTIONS = frozenset(("wield", "equip", "wear", "ride", "hold"))
STAT_PARAMS = frozenset((*range(13), 189, 299))
REQUIREMENT_PARAMS = ((434, 436), (435, 437), (191, 613))

# These profiles already exist in the target cache/server and only use legacy
# animations. New weapon-specific animations are intentionally not imported here.
MELEE_STYLES = {
    "stab sword": "SWORD", "slash sword": "LONGSWORD",
    "2h sword": "TWO_HANDED", "axe": "BATTLEAXE", "blunt": "WARHAMMER",
    "spiked": "MACE", "claw": "CLAWS", "spear": "SPEAR", "pickaxe": "PICKAXE",
    "polearm": "HALBERD", "whip": "WHIP", "bludgeon": "BLUDGEON",
    "scythe": "SCYTHE", "staff": "MAGIC_STAFF", "bladed staff": "STAFF_OF_DEAD",
    "unarmed": "UNARMED", "banner": "SPEAR", "partisan": "SPEAR",
    "polestaff": "MAGIC_STAFF", "flail": "FLAIL",
    "extending chain mace": "CHAINMACE", "multi-melee": "SPEAR",
}
INTERFACE_STYLES = {
    "SWORD": "stab sword", "FANG": "stab sword", "DAGGER": "stab sword",
    "DRAGON_DAGGER": "stab sword", "ABYSSAL_DAGGER": "stab sword",
    "GHRAZI_RAPIER": "stab sword", "LONGSWORD": "slash sword",
    "SCIMITAR": "slash sword", "GODSWORD": "2h sword",
    "SARADOMIN_SWORD": "2h sword", "TWO_HANDED_SWORD": "2h sword",
    "BATTLEAXE": "axe", "GREATAXE": "axe", "WARHAMMER": "blunt",
    "GRANITE_MAUL": "blunt", "ELDER_MAUL": "blunt", "MACE": "spiked",
    "CLAWS": "claw", "SPEAR": "spear", "HALBERD": "polearm",
    "WHIP": "whip", "ABYSSAL_BLUDGEON": "bludgeon", "SCYTHE": "scythe",
    "STAFF": "staff", "ANCIENT_STAFF": "staff", "BLADED_STAFF": "bladed staff",
    "UNARMED": "unarmed", "PICKAXE": "pickaxe", "SHORTBOW": "bow", "LONGBOW": "bow",
    "DARK_BOW": "bow", "CROSSBOW": "crossbow", "POWERED_STAFF": "powered staff",
}

# Only an explicit family of ordinary arrow-firing bows receives NORMAL_BOW.
# Crystal, Faerdhinen, Craw/Webweaver, atlatls, blowpipes and unfamiliar weapons
# need mechanics/ammo/projectiles that cannot be expressed by item_info.json.
ARROW_BOW_PREFIXES = (
    "shortbow", "longbow", "oak shortbow", "oak longbow", "willow shortbow",
    "willow longbow", "maple shortbow", "maple longbow", "yew shortbow",
    "yew longbow", "magic shortbow", "magic longbow", "3rd age bow",
    "twisted bow", "corrupted twisted bow", "bone shortbow", "scorching bow",
)
AMMO_NAMES = {
    **{f"{metal} arrow": f"{metal.upper()}_ARROW" for metal in
       ("bronze", "iron", "steel", "mithril", "adamant", "rune", "amethyst", "dragon")},
    "broad arrows": "BROAD_ARROW", "broad arrow": "BROAD_ARROW",
    **{f"{metal} bolts": f"{metal.upper()}_BOLTS" for metal in
       ("bronze", "iron", "steel", "mithril", "adamant", "runite", "blurite",
        "bone", "silver", "dragon")},
    "broad bolts": "BROAD_BOLTS", "amethyst broad bolts": "AMETHYST_BROAD_BOLTS",
    "bolt rack": "BOLT_RACK", "kebbit bolts": "KEBBIT_BOLTS",
    "long kebbit bolts": "LONG_KEBBIT_BOLTS",
    **{f"{metal} javelin": f"{metal.upper()}_JAVELIN" for metal in
       ("bronze", "iron", "steel", "mithril", "adamant", "rune", "amethyst", "dragon")},
}


def load_json(path):
    """Read the target's JSON with Gson-compatible # and // line comments.

    Comment markers inside strings (including wiki page fragments) are retained.
    """
    source = path.read_text(encoding="utf-8-sig")
    out, in_string, escaped, offset = [], False, False, 0
    while offset < len(source):
        char = source[offset]
        if in_string:
            out.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
            out.append(char)
        elif char == "#" or source[offset:offset + 2] == "//":
            end = source.find("\n", offset)
            offset = len(source) if end == -1 else end
            continue
        else:
            out.append(char)
        offset += 1
    return json.loads("".join(out))


def _lookup(mapping, key, default=None):
    return mapping.get(key, mapping.get(str(key), default))


def _number(mapping, key, default=0):
    value = _lookup(mapping, key, default)
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else default


def _string(value, default=""):
    return value.rstrip(b"\0").decode("cp1252", errors="replace") if isinstance(value, bytes) else (value or default)


def _field_number(fields, opcode, default=0):
    value = _lookup(fields, opcode)
    return int.from_bytes(value, "big") if isinstance(value, bytes) else default


def _requirements(params, gameplay, notes):
    result = {}
    raw_pairs = [(s, l) for s, l in REQUIREMENT_PARAMS
                 if _lookup(params, s) is not None and _lookup(params, l) is not None]
    if raw_pairs:
        for skill_param, level_param in raw_pairs:
            skill, level = _number(params, skill_param, -1), _number(params, level_param, -1)
            if isinstance(skill, int) and 0 <= skill < len(SKILLS) and 0 < level <= 99:
                result[SKILLS[skill] + "_level"] = int(level)
            else:
                notes.append(f"Unsupported requirement params {skill_param}/{level_param}: {skill}/{level}")
        return result, "cache params"
    requirements = gameplay.get("requirements", ())
    if isinstance(requirements, list):
        for skill, level in enumerate(requirements[:len(SKILLS)]):
            if isinstance(level, int) and 0 < level <= 99:
                result[SKILLS[skill] + "_level"] = level
    return result, "donor gameplay fallback" if result else "none in source"


def _bonuses(params, notes):
    # Absence means zero, including broken/inactive items. Never restore the
    # donor's stale 2021 bonuses over authoritative raw cache records.
    values = [_number(params, i) for i in range(11)]
    magic = _number(params, 299) / 10
    values.extend((_number(params, 12) + _number(params, 189), math.floor(magic), _number(params, 11)))
    if magic != math.floor(magic):
        notes.append(f"Magic damage {magic:g}% rounded down to {math.floor(magic)}%: legacy server integer bonus")
    return dict(zip(BONUS_FIELDS, (int(x) for x in values))), magic


def _weapon(name, stats, gameplay, params, types, additions, notes):
    style = (stats.get("combat_style") or INTERFACE_STYLES.get(gameplay.get("weaponInterface"), "")).lower()
    ranged_weapon = None
    if style == "bow":
        lower_name = name.lower()
        if not any(lower_name == prefix or lower_name.startswith(prefix + " (") for prefix in ARROW_BOW_PREFIXES):
            return None, None, "This bow needs an explicit ammo/charge/projectile implementation"
        base, ranged_weapon = "SHORTBOW", "NORMAL_BOW"
        notes.append("Ordinary single-arrow attacks only; weapon passives and specials are unavailable")
    elif style in MELEE_STYLES:
        base = MELEE_STYLES[style]
        if style == "slash sword" and _number(params, 14, stats.get("attack_speed", 0)) == 4:
            base = "SCIMITAR"
        if style in ("partisan", "banner", "multi-melee", "polestaff", "bladed staff", "scythe"):
            notes.append(f"Legacy {base} attack styles used; newer weapon-specific style behavior is unavailable")
        if style in ("staff", "bladed staff", "polestaff"):
            notes.append("Legacy staff melee/manual spells only; new autocasts, rune substitution and spell modifiers are unavailable")
    else:
        return None, None, f"Unsupported combat family: {style or 'unknown'}"
    if base not in types:
        raise ValueError(f"Missing existing weapon profile {base}")
    speed = _number(params, 14, stats.get("attack_speed", 0))
    reach = _number(params, 13, stats.get("attack_range", 1))
    if reach == 0:
        reach = stats.get("attack_range", 1)
    if not isinstance(speed, int) or not 2 <= speed <= 20:
        return None, None, "No supported positive attack speed in source"
    if not isinstance(reach, int) or not 1 <= reach <= 10:
        return None, None, "Attack range is outside the legacy combat range"
    key = f"CONTENT242_{base}_{speed}_{reach}"
    profile = deepcopy(types[base])
    profile.update(attackTicks=speed, maxDistance=reach)
    if key in types and types[key] != profile:
        raise ValueError(f"Existing weapon profile collision: {key}")
    if key not in types:
        additions[key] = profile
    notes.append(f"Legacy {base} animations; newer attack animations are not installed")
    return key, ranged_weapon, None


def build_metadata(item_map, item_fields, item_metadata, existing_weapon_types, *,
                   gameplay=None, weapon_stats=None):
    """Return ``item_info``, ``weapon_types`` additions and a per-item ``report``.

    All three item mappings are keyed by donor ID (int or JSON string). fields
    and metadata are the results of legacy_assets.read_item. gameplay may be the
    donor JSON array or an ID-keyed mapping; weapon_stats is its JSON mapping.
    The caller must append the returned rows to a *staging* copy, ensure target
    IDs do not collide with existing items, and remove inventory action opcodes
    listed in each report's ``remove_inventory_options`` before writing items.
    """
    gameplay = gameplay or {}
    if isinstance(gameplay, list):
        gameplay = {entry["id"]: entry for entry in gameplay}
    weapon_stats = weapon_stats or {}
    targets = [int(i) for i in item_map.values()]
    if len(targets) != len(set(targets)):
        raise ValueError("Duplicate target item IDs")
    if any(i < 0 or i > 65534 for i in targets):
        raise ValueError("Item IDs must fit unsigned short packets without the -1 sentinel")
    rows, profiles, report = [], {}, []
    for raw_id, target_id in sorted(item_map.items(), key=lambda entry: int(entry[0])):
        donor_id, target_id = int(raw_id), int(target_id)
        fields = _lookup(item_fields, donor_id)
        meta = _lookup(item_metadata, donor_id)
        if fields is None or meta is None:
            raise ValueError(f"Missing decoded source item {donor_id}")
        data = _lookup(gameplay, donor_id, {})
        stats = _lookup(weapon_stats, donor_id, {})
        params = _lookup(meta, "params", {})
        name = _string(_lookup(fields, 2), data.get("name", f"Item {donor_id}"))
        notes = []
        row = {
            "id": target_id,
            "tradeable": _lookup(fields, 65) is not None,
            "examine": _string(meta.get("examine"), data.get("examine", name)),
            "weight": _number(meta, 75, 0) / 1000,
            # Cache shop price is deterministic; donor GE/drop values are stale.
            "protect_value": _field_number(fields, 12, 0),
        }
        summary = {"donor_id": donor_id, "target_id": target_id, "name": name,
                   "status": "inventory-only", "notes": notes}
        slot = _number(meta, 13, -1)
        is_template = _lookup(fields, 98) is not None or _lookup(fields, 149) is not None
        actions = {op: _string(_lookup(fields, op)).lower() for op in range(35, 40)
                   if _lookup(fields, op) is not None}
        reason = None
        weapon_type = ranged_weapon = ranged_ammo = None
        if is_template:
            reason = "Note/placeholder template; inventory representation only"
        elif slot not in EQUIPMENT_SLOTS:
            reason = "No supported equipment slot in the raw cache"
        elif target_id > 65023:
            reason = "Mapped ID exceeds legacy equipped appearance limit (65023)"
        elif not any(action in EQUIP_ACTIONS for action in actions.values()):
            reason = "No equip inventory action in the raw cache"
        elif slot == 3:
            weapon_type, ranged_weapon, reason = _weapon(name, stats, data, params, existing_weapon_types, profiles, notes)
        elif slot == 13:
            ranged_ammo = AMMO_NAMES.get(name.lower())
            if ranged_ammo is None:
                reason = "Ammunition has no exact existing RangedAmmo entry/projectile"
        if reason is None:
            row["equip_slot"] = slot
            hidden = {_number(meta, 14, -1), _number(meta, 27, -1)}
            if slot == 3 and 5 in hidden:
                row["two_handed"] = True
            for hidden_slot, flag in ((8, "hide_hair"), (11, "hide_beard"), (6, "hide_arms")):
                if hidden_slot in hidden:
                    row[flag] = True
            requirements, source = _requirements(params, data, notes)
            row.update(requirements)
            # This is also the legacy server's weapon-versus-ammo capability
            # gate. Arrows often have param23 but no skill/level pair.
            ammo_level = _number(params, 23, 0)
            if (ranged_weapon or ranged_ammo) and isinstance(ammo_level, int) and 1 <= ammo_level <= 99:
                row["ranged_level"] = max(row.get("ranged_level", 1), ammo_level)
            summary["requirements_source"] = source
            bonuses, magic = _bonuses(params, notes)
            row.update(bonuses)
            summary["bonuses_source"] = "cache params" if any(_lookup(params, p) is not None for p in STAT_PARAMS) else "cache default zero"
            summary["source_magic_damage_percent"] = magic
            summary["status"] = "basic-equipment" if magic == math.floor(magic) else "basic-equipment-rounded-magic"
            for key, value in (("weapon_type", weapon_type), ("ranged_weapon", ranged_weapon), ("ranged_ammo", ranged_ammo)):
                if value:
                    row[key] = value
            notes.append("Base stats and legacy combat only; no new passives, special attacks, set effects, charge/degrade systems or quest unlock gates")
            if data.get("combatRequirement"):
                notes.append(f"Combat-level gate {data['combatRequirement']} is not represented by the legacy item schema")
        else:
            summary["reason"] = reason
        summary["remove_inventory_options"] = [op for op, action in actions.items()
                                                if action != "drop" and not (reason is None and action in EQUIP_ACTIONS)]
        rows.append(row)
        report.append(summary)
    return {"item_info": rows, "weapon_types": profiles, "report": report}
