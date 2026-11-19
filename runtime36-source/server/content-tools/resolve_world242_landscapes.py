"""Verify published historical keys against the already installed Newscape scenery bytes."""
import argparse
from collections import defaultdict
from hashlib import file_digest, sha256
from itertools import chain
import json
from pathlib import Path
import re
import zlib

from audit_world242 import Store, audit_landscape, decrypt_container, installed_pairs, load_json, read_archive


NO_KEY = (0, 0, 0, 0)


def checked_key(words):
    if not isinstance(words, list) or len(words) != 4 or any(
        type(word) is not int or not -(1 << 31) <= word < (1 << 31) for word in words
    ):
        raise ValueError("Expected four signed 32-bit XTEA words")
    return tuple(words)


def choices(paths, blocked):
    by_square = defaultdict(lambda: defaultdict(list))
    for path in sorted(paths):
        match = re.fullmatch(r"openrs2-(\d+)-keys\.json", path.name)
        if not match:
            raise ValueError(f"Expected a pinned OpenRS2 key filename, got {path.name}")
        snapshot = int(match[1])
        for record in load_json(path):
            square = record["mapsquare"]
            if record["archive"] == 5 and square in blocked:
                key = checked_key(record["key"])
                by_square[square][key].append(snapshot)
    return by_square


def find_repairs(cache_path, key_path, audit_path, sources, catalogue_path=None):
    audited = load_json(audit_path)
    blocked = {row["mapSquare"]: row["landscapeGroup"] for row in audited["installed"]
               if "landscapeError" in row and row["geography"] == "BLOCKED BY AN IDENTIFIED DEPENDENCY"}
    old = load_json(key_path)
    source_keys = choices(sources, blocked)
    catalogue, catalogue_digest = set(), None
    if catalogue_path is not None:
        catalogue_bytes = catalogue_path.read_bytes()
        catalogue = {checked_key(words) for words in json.loads(catalogue_bytes)} - {NO_KEY}
        catalogue_digest = sha256(catalogue_bytes).hexdigest()
    repairs, unresolved = [], []
    with Store(cache_path) as cache:
        object_ids = set(cache.table(2)[3][6].files)
        pairs, _ = installed_pairs(cache.table(5)[3])
        for square, group in sorted(blocked.items()):
            if pairs.get(square, [None, None])[1] != group:
                raise ValueError(f"The audit does not match installed map square {square}")
            raw = cache.read(5, group)
            verified = []
            candidates = {**source_keys[square], NO_KEY: []}
            additional_keys = catalogue.difference(candidates)
            for key in chain(candidates, sorted(additional_keys)):
                # Reject bad historic versions cheaply. Decode the full archive only
                # after the native container header and GZIP marker look valid.
                if raw[0] == 2:
                    prefix = decrypt_container(raw[:13], key) if any(key) else raw[:13]
                    if prefix[9:12] != b"\x1f\x8b\x08" or not 0 < int.from_bytes(prefix[5:9], "big") <= 1_048_576:
                        continue
                try:
                    plain, digest, _ = read_archive(cache, 5, group, key)
                    count, missing = audit_landscape(plain, object_ids)
                except (IndexError, KeyError, OSError, ValueError, zlib.error):
                    continue
                if count and not missing:
                    snapshots = candidates.get(key, [])
                    repair = {"mapSquare": square, "landscapeGroup": group,
                              "oldKeys": old.get(str(square)), "keys": list(key),
                              "ciphertextSha256": digest, "decodedSha256": sha256(plain).hexdigest(),
                              "placements": count, "openrs2Snapshot": min(snapshots) if snapshots else None}
                    if key in additional_keys:
                        repair["openrs2CatalogueSha256"] = catalogue_digest
                    verified.append(repair)
            if len(verified) == 1:
                repairs.extend(verified)
            else:
                unresolved.append({"mapSquare": square, "landscapeGroup": group,
                                   "verifiedCandidates": len(verified),
                                   "historicalKeysChecked": len(source_keys[square])})
                if catalogue_path is not None:
                    unresolved[-1]["additionalCatalogueKeysChecked"] = len(additional_keys)
    cache_proof = {}
    for name in ("main_file_cache.dat2", "main_file_cache.idx5", "main_file_cache.idx255"):
        with (cache_path / name).open("rb") as source:
            cache_proof[name] = file_digest(source, "sha256").hexdigest()
    report = {"schemaVersion": 1, "baselineRegionKeysSha256": sha256(key_path.read_bytes()).hexdigest(),
              "cacheFilesSha256": cache_proof, "repairs": repairs, "unresolved": unresolved}
    if catalogue_path is not None:
        report["openrs2Catalogue"] = {"sha256": catalogue_digest, "uniqueNonzeroKeys": len(catalogue)}
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("cache", "region-keys", "audit", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--source", nargs="+", required=True, type=Path)
    parser.add_argument("--key-catalogue", type=Path, help="Saved OpenRS2 valid.json or all.json key catalogue")
    args = parser.parse_args()
    result = find_repairs(args.cache, args.region_keys, args.audit, args.source, args.key_catalogue)
    with args.output.open("x", encoding="utf-8") as output:
        output.write(json.dumps(result, indent=2) + "\n")
    print(f"{len(result['repairs'])} verified keys, {len(result['unresolved'])} unresolved landscapes")
