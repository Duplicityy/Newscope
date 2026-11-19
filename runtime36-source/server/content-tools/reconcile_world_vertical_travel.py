"""Select reciprocal, publicly accessible climbs from a native placement survey."""

import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path


SOURCE = "RSPSApp/tsps at 1ccb548be725c70900b604968354dd3147b839ea"
GENERIC = "io.ruin.model.map.object.actions.impl.Ladder$$Lambda"
PUBLIC_BASEMENTS = {
    ("Lumbridge", frozenset({14880, 17385}), frozenset({(3210, 3216, 0), (3210, 9616, 0)})),
    ("Wizard Tower", frozenset({2147, 2148}), frozenset({(3105, 3162, 0), (3104, 9576, 0)})),
}
PUBLIC_DISTRICTS = {
    "Arceuus Library stairs (the railings only lead down)", "Catherby", "Dark Wizards' Tower",
    "Draynor Village", "East Ardougne", "Falador", "Kourend Castle, first to second floor",
    "Kourend Castle, ground to first floor", "Lumbridge", "Lunar Isle", "Pirates' Cove",
    "Port Sarim", "Rimmington", "Seers Village and Camelot Castle", "Shayzien", "Taverley",
    "Tree Gnome Stronghold", "Varrock", "Wizard Tower",
}


def point(value):
    return tuple(map(int, value.split()))


def endpoint(row):
    return tuple(map(int, (row["id"], row["object_x"], row["object_y"], row["object_z"],
                           row["type"], row["direction"], row["native_slot"])))


def eligible(row):
    default = row["default"]
    return (row["section"] in PUBLIC_DISTRICTS and row["native_slot"] not in (None, "", "-1")
            and row["from_free"] == row["to_free"] == "true" and not row["local"]
            and (not default or default.startswith(GENERIC)))


def permitted(area, a, b, first, second):
    if abs(a[2] - b[2]) == 1 and max(abs(a[0] - b[0]), abs(a[1] - b[1])) <= 12:
        return True
    return (area, frozenset({int(first["id"]), int(second["id"])}), frozenset({a, b})) in PUBLIC_BASEMENTS


def describe(row):
    id_, x, y, z, type_, rotation, slot = endpoint(row)
    return {"id": id_, "at": [x, y, z], "type": type_, "rotation": rotation,
            "slot": slot, "name": row["native_name"], "option": row["option"],
            "ticks": min(4, max(1, int(row["ticks"])))}


def reconcile(rows):
    directions = defaultdict(list)
    for row in rows:
        if eligible(row):
            directions[(row["section"], point(row["from"]), point(row["to"]))].append(row)

    proposals = {}
    for (area, start, end), forward in directions.items():
        reverse = directions.get((area, end, start), [])
        if start >= end or len(forward) != 1 or len(reverse) != 1:
            continue
        first, second = forward[0], reverse[0]
        if endpoint(first) == endpoint(second) or not permitted(area, start, end, first, second):
            continue
        key = endpoint(first), endpoint(second)
        pair = proposals.setdefault(key, {"area": area, "first": describe(first),
                                           "second": describe(second), "landings": []})
        pair["landings"].append({"first": start, "second": end})

    count = Counter(key for pair in proposals for key in pair)
    return [item for key, item in proposals.items() if all(count[anchor] == 1 for anchor in key)]


if __name__ == "__main__":
    with Path(sys.argv[1]).open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    pairs = reconcile(rows)
    with Path(sys.argv[2]).open("w", encoding="utf-8", newline="\n") as output:
        output.write('{"schema":1,"source":' + json.dumps(SOURCE) + ',"pairs":[\n')
        output.write(',\n'.join("  " + json.dumps(pair, separators=(",", ":")) for pair in pairs))
        output.write("\n]}\n")
    print(f"Selected {len(pairs)} reciprocal placements ({2 * len(pairs)} actions).")
    print(dict(Counter(row["area"] for row in pairs)))
