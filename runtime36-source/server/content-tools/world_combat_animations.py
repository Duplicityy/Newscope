"""Audit and patch active static world NPCs without writing the live cache or data."""

import argparse
from collections import Counter, defaultdict
import csv
from functools import lru_cache
import hashlib
import json
from pathlib import Path
import re

from animation_import import skeleton184, validate_frame
from cache_store import Reader, Store
from combat_animation_roles import ROLES, RoleEvidence, normalize, sequence_role


FIELDS = {"attack": "attack", "block": "defend", "death": "death"}
MOTION_FIELDS = ("stand", "walk", "back", "left", "right", "turn_a", "turn_b")
BASIC_COMBAT = "io.ruin.model.activities.miscpvm.BasicCombat"
CHROMATIC_DRAGON = "io.ruin.model.activities.miscpvm.dragons.ChromaticDragon"
RED_DRAGONS_WITH_MISSING_BASIC_ANIMATIONS = frozenset(range(247, 252))
OVERLAY = "zz_world_combat_animations.json"


def read_json(path):
    # Kronos accepts hash, line and block comments in the older combat files.
    value = path.read_text(encoding="utf-8-sig")
    value = re.sub(r'("(?:\\.|[^"\\])*")|(?:#|//)[^\r\n]*|/\*[\s\S]*?\*/',
                   lambda match: match[1] or "", value)
    value = re.sub(r'(?m)^(\s*"pet"\s*:\s*)([A-Z][A-Z_]+)(?=\s*[,}])', r'\1"\2"', value)
    value = re.sub(r'("(?:\\.|[^"\\])*")|,(?=\s*[}\]])', lambda match: match[1] or "", value)
    return json.loads(value)


def read_tsv(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


class InstalledSequences:
    """Read target sequence, frame and skeleton archives in the actual wire format."""

    def __init__(self, cache):
        self.cache = cache
        self.sequences = cache.files(2, 12)

    @lru_cache(maxsize=None)
    def frame_group(self, id):
        return self.cache.files(0, id)

    @lru_cache(maxsize=None)
    def skeleton(self, id):
        raw = self.cache.files(1, id)[0]
        prefix, types = skeleton184(raw, allow_extended_types=True)
        return hashlib.sha256(raw).digest(), hashlib.sha256(prefix).digest(), types

    @lru_cache(maxsize=None)
    def rigs(self, seq):
        if not 0 <= seq < 65535:
            return frozenset()
        try:
            reader = Reader(self.sequences[seq])
            rigs = set()
            weighted = False
            duration = 0
            while op := reader.uint():
                if op in (1, 12):
                    count = reader.uint(2 if op == 1 else 1)
                    if op == 1:
                        reader.take(count * 2)
                    frames = [reader.uint(2) for _ in range(count)]
                    groups = [reader.uint(2) for _ in range(count)]
                    for group, frame in zip(groups, frames):
                        if op == 12 and frame == 65535:
                            continue
                        raw = self.frame_group(group)[frame]
                        _, legacy_rig, types = self.skeleton(int.from_bytes(raw[:2], "big"))
                        validate_frame(raw, types)
                        rigs.add(("legacy", legacy_rig))
                elif op in (3, 117):
                    reader.take(reader.uint())
                elif 2 <= op <= 11:
                    reader.take(2 if op in (2, 6, 7) else 0 if op == 4 else 1)
                elif op == 13:
                    reader.take(reader.uint() * 3)
                elif op == 114:
                    packed = reader.uint(4)
                    raw = self.frame_group(packed >> 16)[packed & 65535]
                    if len(raw) < 8 or raw[0] not in (1, 2):
                        return frozenset()
                    weighted_rig, _, _ = self.skeleton(int.from_bytes(raw[1:3], "big"))
                    rigs.add(("weighted", weighted_rig))
                    weighted = True
                elif op == 115:
                    start, end = reader.uint(2), reader.uint(2)
                    duration = end - start
                else:
                    return frozenset()
            if reader.offset != len(reader.data) or weighted and not 0 < duration <= 32768:
                return frozenset()
            return frozenset(rigs)
        except (KeyError, IndexError, ValueError):
            return frozenset()


def installed_combat(data):
    """Match the native DataFile loader's case-sensitive lexical order."""
    rows = {}
    for file in sorted((data / "npcs" / "combat").glob("*.json"), key=lambda path: path.name):
        if file.name == OVERLAY:
            raise ValueError("Run against the unpatched combat directory")
        for row in read_json(file):
            for id in row["ids"]:
                rows[id] = file.name, row
    return rows


def patch_world(data, cache, snapshot, definitions, reviewed_roles):
    profiles = installed_combat(data)
    manifest = read_json(data / "content242.json")
    source_npcs = {target: int(source) for source, target in manifest["mappings"]["npcs"].items()}
    target_sequences = {int(source): int(target) for source, target in manifest["mappings"]["animations"].items()}
    source_sequences = {target: source for source, target in target_sequences.items()}
    source_metadata = read_json(data / "content242" / "npc-metadata.json")
    evidence = RoleEvidence(definitions, read_json(definitions / "npc-combat-defs.json"))
    npcs = {int(row["id"]): row for row in read_tsv(snapshot / "npc-definitions.tsv")}
    spawns = read_tsv(snapshot / "npc-spawns.tsv")
    combat = Counter(int(row["id"]) for row in spawns if row["combat_class"])
    classes = defaultdict(set)
    for row in spawns:
        if row["combat_class"]:
            classes[int(row["id"])].add(row["combat_class"])
    sequences = InstalledSequences(cache)
    repairs, changes, unresolved, untreated = [], [], [], []
    audited = Counter()
    sibling_roles = defaultdict(lambda: defaultdict(set))

    def signature(row):
        return (normalize(row["name"]), row["models"], *[row[field] for field in MOTION_FIELDS], row["style"])

    def prior(row, role):
        return int(row[FIELDS[role]] or -1)

    def motion(row):
        return [int(row[field] or -1) for field in MOTION_FIELDS if int(row[field] or -1) >= 0]

    for id, row in npcs.items():
        origin, _ = profiles.get(id, (None, {}))
        if origin in (None, "content242.json") or not row["models"] or row["models"] == "[]" or row["handler"]:
            continue
        for role in ROLES:
            current = prior(row, role)
            if sequences.rigs(current) and current not in motion(row):
                sibling_roles[signature(row)][role].add(current)

    for id, instances in sorted(combat.items()):
        row = npcs[id]
        scripted = classes[id] != {BASIC_COMBAT} or bool(row["handler"])
        # ChromaticDragon.basicAttack delegates to the missing profile sequence.
        # Its separately authored dragonfire sequence remains in the handler.
        red_dragon = id in RED_DRAGONS_WITH_MISSING_BASIC_ANIMATIONS and classes[id] == {CHROMATIC_DRAGON}
        audited["scripted" if scripted else "basic"] += 1
        origin, profile = profiles.get(id, (None, {}))
        if scripted and not red_dragon or not origin or not profile.get("max_damage", 0):
            if not sequences.rigs(prior(row, "attack")):
                untreated.append({"npc": id, "name": row["name"], "instances": instances,
                                  "reason": "scripted profile" if scripted else "no positive generic damage"})
            continue
        for role, column in FIELDS.items():
            if prior(row, role) != profile.get(column + "_animation", 0):
                raise ValueError(f"Configured {column} for NPC {id} disagrees with the initialized server")
        imported = origin == "content242.json"
        source_id = source_npcs.get(id, id) if imported else id
        metadata = source_metadata.get(str(source_id), {})
        exact_source = normalize(metadata.get("name")) == normalize(row["name"])
        resolved = evidence.roles(source_id, row["name"]) if exact_source else {}
        reviewed = reviewed_roles.get(str(source_id), {}) if exact_source else {}
        combined_rigs = set().union(*(sequences.rigs(seq) for seq in motion(row)))
        if not combined_rigs:
            combined_rigs = set().union(*(sequences.rigs(prior(row, role)) for role in ROLES))
        source_motion = [seq for op, values in metadata.get("animations", {}).items()
                         if op in ("13", "14", "15", "16", "17") for seq in values]
        replacement = dict(profile, ids=[id])
        changed = {}

        def suitable(target):
            rigs = sequences.rigs(target)
            return bool(rigs and combined_rigs and rigs <= combined_rigs and target not in motion(row))

        def mapped(source):
            if imported:
                target = target_sequences.get(source)
                return target if target is not None and suitable(target) else None
            return source if suitable(source) else None

        for role, column in FIELDS.items():
            current = prior(row, role)
            was_present = bool(sequences.rigs(current))
            if not was_present:
                audited["missing_" + role] += 1
            if was_present and not imported:
                continue
            candidate = resolved.get(role)
            target = None
            if candidate:
                source_seq, basis = candidate
                # Explicit omissions are honored; captures alone do not delete animations.
                if source_seq < 0:
                    continue
                target = mapped(source_seq)
            if target is None and not was_present and exact_source:
                if role in reviewed:
                    target = mapped(reviewed[role])
                    basis = reviewed["basis"]
                if target is None:
                    source_seq = evidence.unique_family(source_motion, role)
                    if source_seq is not None:
                        target = mapped(source_seq)
                    basis = "unique-compatible-named-family"
            if target is None and not was_present:
                siblings = sibling_roles[signature(row)][role]
                if len(siblings) == 1:
                    sibling = next(iter(siblings))
                    if suitable(sibling):
                        target, basis = sibling, "identical-name-model-movement-style"
            if target is None and was_present and imported:
                # Repair a known role mix-up even when the other correct roles are absent.
                # A replacement itself still needs role evidence and a compatible rig.
                wrong_role = sequence_role(evidence.names.get(source_sequences.get(current)))
                if wrong_role and wrong_role != role and exact_source:
                    source_seq = evidence.unique_family(source_motion, role)
                    if source_seq is not None:
                        target = mapped(source_seq)
                        basis = "unique-compatible-named-family"
            if target is not None and target != current:
                replacement[column + "_animation"] = target
                changed[role] = {"before": current, "after": target, "basis": basis}
                audited["restored_" + role if not was_present else "corrected_" + role] += 1
            elif not was_present:
                unresolved.append({"npc": id, "name": row["name"], "role": role,
                                   "instances": instances, "sourceNpc": source_id,
                                   "sourceCandidate": (candidate or (None,))[0]})
        if changed:
            changes.append({"npc": id, "name": row["name"], "instances": instances,
                            "originalFile": origin, "sequences": changed})
            repairs.append(replacement)
            if red_dragon:
                audited["repaired_chromatic_dragon_profiles"] += 1
    missing_combat = Counter(int(row["id"]) for row in spawns if not row["combat_class"]
                             and int(npcs[int(row["id"])]["attack_option"] or -1) > 0)
    report = {"summary": dict(audited, world_placements=len(spawns), combat_npcs=len(combat),
                              combat_placements=sum(combat.values()), repaired_npcs=len(repairs),
                              repaired_placements=sum(row["instances"] for row in changes),
                              all_configured_combat_definitions=sum(bool(row["attack"]) for row in npcs.values()),
                              attack_without_combat_ids=len(missing_combat),
                              attack_without_combat_placements=sum(missing_combat.values())),
              "changes": changes, "unresolved": unresolved, "scripted_or_non_damage": untreated,
              "attack_without_combat": [{"npc": id, "name": npcs[id]["name"], "instances": instances}
                                        for id, instances in sorted(missing_combat.items())]}
    return repairs, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("release", "snapshot", "donor-definitions", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.resolve().is_relative_to(args.release.resolve()):
        parser.error("A fresh output directory outside the frozen release is required")
    review_file = Path(__file__).parent / "registries/world_combat_sequence_roles.json"
    reviewed = read_json(review_file)
    if reviewed["revision"] != 242:
        parser.error("Reviewed animation identifiers must match revision 242")
    with Store(args.release / "Cache") as cache:
        repairs, report = patch_world(args.release / "data", cache, args.snapshot, args.donor_definitions, reviewed["npcs"])
    report["references"] = reviewed["evidence"]
    report["inputs"] = {name: hashlib.sha256((args.donor_definitions / name).read_bytes()).hexdigest() for name in (
        "npc-combat-defs.json", "npc-animations.json", "npc-captured-animations.json", "sequence-names.json")}
    data_output = args.output / "data/npcs/combat"
    data_output.mkdir(parents=True)
    (data_output / OVERLAY).write_text(json.dumps(repairs, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    (args.output / "npc-combat-animation-audit.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], sort_keys=True))


if __name__ == "__main__":
    main()
