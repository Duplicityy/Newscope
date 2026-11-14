"""Extract bounded real-cache records for the Java skeletal compatibility probe."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "content-tools"))
from cache_store import Store
from legacy_assets import model_layout
from modern_animation import sequence242
from modern_model import weighted_model184


def blob(out, raw):
    out.write(struct.pack(">I", len(raw)))
    out.write(raw)


def main():
    args = argparse.ArgumentParser()
    args.add_argument("cache", type=Path)
    args.add_argument("output", type=Path)
    a = args.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    store = Store(a.cache)
    animations = []
    errors = []
    identity_items = {i: i for i in range(65024)}
    for seq_id, raw in store.files(2, 12).items():
        ids = []
        try:
            converted, weighted = sequence242(raw, lambda x: x, lambda x: ids.append(x) or x, identity_items)
            if not weighted:
                continue
            if len(ids) != 1:
                raise ValueError("Expected one cached animation")
            cached_id = ids[0]
            curve = store.files(22, cached_id >> 16)[cached_id & 65535]
            base = int.from_bytes(curve[1:3], "big")
            skeleton = store.files(1, base)[0]
            animations.append((seq_id, base, converted, skeleton, curve))
        except Exception as e:
            errors.append({"sequence": seq_id, "error": str(e)})
    with (a.output / "animations.bin").open("wb") as out:
        out.write(struct.pack(">II", 0x4b32414e, len(animations)))
        for seq_id, base, seq, skeleton, curve in animations:
            out.write(struct.pack(">II", seq_id, base))
            for raw in (seq, skeleton, curve): blob(out, raw)
    models, rejected = [], []
    for model in store.table(7)[3]:
        raw = store.files(7, model)[0]
        try:
            layout = model_layout(raw)
            if layout["weighted"] or max(layout["vertices"], layout["faces"]) > 4700:
                models.append((model, layout, weighted_model184(raw)))
        except ValueError as e:
            rejected.append({"model": model, "reason": str(e)})
    with (a.output / "models.bin").open("wb") as out:
        out.write(struct.pack(">II", 0x4b324d4f, len(models)))
        for model, layout, raw in models:
            out.write(struct.pack(">III", model, layout["vertices"], layout["faces"]))
            blob(out, raw)
    report = {"weighted_sequences": len(animations), "model_fixtures": len(models),
              "weighted_models": sum(x[1]["weighted"] for x in models),
              "oversize_models": sum(max(x[1]["vertices"], x[1]["faces"]) > 4700 for x in models),
              "sequence_errors": errors, "model_exclusions": rejected,
              "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in a.output.glob("*.bin")}}
    (a.output / "fixture-manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k not in ("sequence_errors", "model_exclusions")}, indent=2))
    print("Sequence errors:", len(errors), "model exclusions:", len(rejected))


if __name__ == "__main__": main()
