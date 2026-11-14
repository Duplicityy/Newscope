"""Frame full male/female item meshes for VerifyModels184 --merge.

This tests each item's complete 1-3 mesh assembly. It does not claim every
possible multi-slot outfit fits the legacy renderer's shared scratch arrays.
"""

import argparse
import json
from pathlib import Path
import struct

from cache_store import Store
from legacy_assets import model184, read_item


def export(cache, output, convert=False):
    output.mkdir(parents=True, exist_ok=True)
    cases = {}
    with Store(cache) as store, (output / "wearables.bin").open("wb") as packed:
        for item_id, data in store.files(2, 10).items():
            fields = read_item(data)
            for gender, ops in enumerate(((23, 24, 78), (25, 26, 79))):
                if ops[0] not in fields:
                    continue
                ids = [int.from_bytes(fields[op][:2], "big") for op in ops if op in fields]
                models = [store.files(7, model)[0] for model in ids]
                if convert:
                    models = [model184(model) for model in models]
                case_id = item_id * 2 + gender
                packed.write(struct.pack(">II", case_id, len(models)))
                for model in models:
                    packed.write(struct.pack(">I", len(model)) + model)
                cases[case_id] = dict(item=item_id, gender=("male", "female")[gender], models=ids,
                                      name=fields.get(2, b"null\0")[:-1].decode("cp1252", errors="replace"))
    (output / "wearable-cases.json").write_text(json.dumps(cases, indent=2) + "\n", encoding="utf-8")
    print(f"Exported {len(cases)} complete item/gender assemblies")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--convert", action="store_true", help="Convert a 242 source cache before testing")
    args = parser.parse_args()
    export(args.cache, args.output, args.convert)
