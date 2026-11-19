"""Summarize native NPC Talk-to bindings from the isolated WorldNpcAudit files."""

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path


def rows(source):
    with source.open(encoding="utf-8", newline="") as file:
        yield from csv.DictReader(file, delimiter="\t")


def audit(snapshot, missing):
    definitions = {npc["id"]: npc for npc in rows(snapshot / "npc-definitions.tsv")}
    counts = defaultdict(int)
    ids = defaultdict(set)
    with missing.open("w", encoding="utf-8", newline="") as detail:
        writer = csv.writer(detail, delimiter="\t")
        writer.writerow(["id", "name", "x", "y", "z", "talk_slot", "status"])
        for spawn in rows(snapshot / "npc-spawns.tsv"):
            definition = definitions[spawn["id"]]
            scope = "authored" if spawn["default_spawn"] == "true" else "scripted"
            key = scope + "/all"
            counts[key] += 1
            ids[key].add(spawn["id"])
            options = definition["options"][1:-1].casefold().split(", ")
            if "talk-to" not in options:
                continue
            talk = str(options.index("talk-to") + 1)
            if talk in spawn["instance_action_slots"]:
                status = "instance"
            elif talk in definition["definition_action_slots"]:
                status = "definition"
            elif definition["clue"] == "true":
                status = "clue-only"
            else:
                status = "unbound"
            for key in [scope + "/talk-to", scope + "/" + status]:
                counts[key] += 1
                ids[key].add(spawn["id"])
            if status in ("clue-only", "unbound"):
                writer.writerow([spawn["id"], definition["name"], spawn["x"], spawn["y"], spawn["z"], talk, status])
    return {key: {"placements": counts[key], "ids": len(ids[key])} for key in sorted(counts)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", type=Path)
    parser.add_argument("unbound_report", type=Path)
    args = parser.parse_args()
    print(json.dumps(audit(args.snapshot, args.unbound_report), indent=2))


if __name__ == "__main__":
    main()
