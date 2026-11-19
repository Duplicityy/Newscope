"""Check the Mobile Ultra emitter IDs against an installed Newscape native cache (read-only)."""
import argparse
import json
from pathlib import Path
import re
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--cache", type=Path, required=True)
p.add_argument("--cache-tools", type=Path, required=True, help="Newscape content-tools checkout")
p.add_argument("--output", type=Path)
args = p.parse_args()
sys.path.insert(0, str(args.cache_tools))
from cache_store import Store
from modern_definitions import read_object

source = (Path(__file__).resolve().parents[1] / "client-overlays/src/net/runelite/standalone/UltraSceneLights.java").read_text()
cases = re.findall(r"((?:case \d+:\s*)+)return (\d+);", source)
groups = {int(style): [int(i) for i in re.findall(r"case (\d+):", labels)] for labels, style in cases}
assert set(groups) == set(range(1, 9))
records = []
with Store(args.cache) as store:
    definitions = store.files(2, 6)
    for style, ids in groups.items():
        for id in ids:
            _, m = read_object(definitions[id], modern=False)
            name = m["name"].lower()
            assert name in ("fire", "campfire", "torch", "brazier", "burning brazier")
            assert not m["morphs"] and m["models"] and "Light" not in m["options"]
            assert name == "burning brazier" or m["animation"] >= 0
            if style == 7: assert name == "torch"
            if style == 8: assert "brazier" in name
            if style < 7: assert name in ("fire", "campfire")
            records.append({"id": id, "name": name, "style": style, "animation": m["animation"]})
assert len(records) == len({r["id"] for r in records})
if args.output: args.output.write_text(json.dumps(records, indent=2)+"\n")
print(f"Verified {len(records)} distinct native lit object definitions across {len(groups)} styles.")
