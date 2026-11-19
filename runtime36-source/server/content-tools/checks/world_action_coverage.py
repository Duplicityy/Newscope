"""Count advertised world actions and their native callback coverage."""

import argparse
import csv
import json
from collections import Counter
from pathlib import Path


def rows(path):
    with path.open(encoding="utf-8", newline="") as source:
        yield from csv.DictReader(source, delimiter="\t")


def slot(values, index):
    return values[index] if 0 <= index < len(values) else ""


def objects(path):
    summary, unbound, unpaired = Counter(), Counter(), Counter()
    ids = set()
    for row in rows(path):
        summary["placements"] += 1
        ids.add(row["id"])
        native = row["definition_handlers"].split("|")
        local = row["instance_handlers"].split("|")
        for index, label in enumerate(row["options"].split("|")):
            if not label:
                continue
            summary["visible_options"] += 1
            handler = slot(local, index) or slot(native, index)
            if not handler:
                summary["no_callback"] += 1
                unbound[label] += 1
            elif handler.endswith(".Door") and row["opposite"] == "-1":
                summary["missing_door_state"] += 1
                unpaired[f"{row['id']} {row['name']}"] += 1
            else:
                summary["callback"] += 1
    summary["definitions"] = len(ids)
    return {
        "summary": dict(summary),
        "unbound_options": dict(unbound.most_common()),
        "door_states_to_review": dict(unpaired.most_common()),
    }


def npcs(directory):
    definitions = {
        row["id"]: row for row in rows(directory / "npc-definitions.tsv")
    }
    summary, unbound = Counter(), Counter()
    ids = set()
    for spawn in rows(directory / "npc-spawns.tsv"):
        if spawn["default_spawn"] != "true":
            continue
        definition = definitions[spawn["id"]]
        summary["placements"] += 1
        ids.add(spawn["id"])
        native = definition["definition_action_slots"]
        local = spawn["instance_action_slots"]
        options = definition["options"].removeprefix("[").removesuffix("]").split(", ")
        for index, label in enumerate(options, 1):
            if not label or label == "null":
                continue
            summary["visible_options"] += 1
            if label.casefold() == "attack":
                summary["combat" if spawn["combat_class"] else "attack_without_combat"] += 1
            elif str(index) in local or str(index) in native:
                summary["callback"] += 1
            elif label.casefold() == "talk-to" and definition["clue"] == "true":
                summary["clue_only"] += 1
            else:
                unbound[label] += 1
                summary["no_callback"] += 1
    summary["definitions"] = len(ids)
    return {"summary": dict(summary), "unbound_options": dict(unbound.most_common())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--objects", required=True, type=Path)
    parser.add_argument("--npc-snapshot", type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    result = {"world_objects": objects(args.objects)}
    if args.npc_snapshot:
        result["authored_npcs"] = npcs(args.npc_snapshot)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({kind: data["summary"] for kind, data in result.items()}, indent=2))


if __name__ == "__main__":
    main()
