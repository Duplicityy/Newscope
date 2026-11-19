"""Inventory the installed Newscape map squares and compare with a local OSRS 242 reference."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import struct
import zlib

from cache_store import Reader, Store, name_hash, unpack_container
from gear_metadata import load_json
from modern_definitions import TERRAIN_MAGIC


REFERENCE_INDEX_REVISION = 1793635803
REFERENCE_INDEX_CRC = 174413765
WORLD_TYPES = ("ECO", "PVP")
HOME = (2028, 3577, 0)
MASK = (1 << 32) - 1
XTEA_DELTA = 0x9E3779B9


def region_id(x, y):
    return ((x >> 6) << 8) | (y >> 6)


def coordinates(region):
    return [region >> 8, region & 255]


def decrypt_container(raw, keys):
    """Mirror Kronos InBuffer.decode, with unsigned Java int shifts."""
    if len(keys) != 4:
        raise ValueError("Expected four XTEA words")
    output = bytearray(raw)
    for offset in range(5, len(raw) - 7, 8):
        left, right = struct.unpack_from(">II", raw, offset)
        total = (XTEA_DELTA * 32) & MASK
        for _ in range(32):
            right = (right - (((left << 4 ^ left >> 5) + left) ^ (keys[(total >> 11) & 3] + total))) & MASK
            total = (total - XTEA_DELTA) & MASK
            left = (left - (((right << 4 ^ right >> 5) + right) ^ (keys[total & 3] + total))) & MASK
        struct.pack_into(">II", output, offset, left, right)
    return output


def read_archive(cache, archive, group, keys=None):
    raw = cache.read(archive, group)
    size = 5 + (4 if raw[0] else 0) + int.from_bytes(raw[1:5], "big")
    if len(raw) not in (size, size + 2):
        raise ValueError("Archive length differs from container header")
    checksum = zlib.crc32(raw[:size])
    if checksum != cache.table(archive)[3][group].crc:
        raise ValueError("Archive CRC differs from index")
    encrypted = keys is not None and any(keys)
    decoded = decrypt_container(raw, keys) if encrypted else raw
    try:
        data = unpack_container(decoded)
        ignored_footer = False
    except (gzip.BadGzipFile, zlib.error):
        if not encrypted or decoded[0] != 2 or decoded[9:12] != b"\x1f\x8b\x08":
            raise
        # The Java 184 reader consumes raw deflate and ignores the gzip footer.
        # Legacy encryption can straddle the gzip footer and disk version.
        # Match the native read while requiring a complete, correctly sized payload.
        expected = int.from_bytes(decoded[5:9], "big")
        if not 0 < expected <= 1_048_576:
            raise ValueError("Decoded map payload size is outside the audit bound")
        inflater = zlib.decompressobj(-15)
        data = inflater.decompress(decoded[19:-8], max_length=expected + 1)
        if not inflater.eof or len(data) != expected:
            raise ValueError("Native deflate path did not finish at the declared length")
        ignored_footer = True
    return data, hashlib.sha256(raw).hexdigest(), ignored_footer


def audit_terrain(data):
    wide = data.startswith(TERRAIN_MAGIC)
    offset = len(TERRAIN_MAGIC) if wide else 0
    flags = Counter()
    for _ in range(4 * 64 * 64):
        while True:
            if wide:
                opcode = (data[offset] << 8) | data[offset + 1]
                offset += 2
            else:
                opcode = data[offset]
                offset += 1
            if opcode <= 1:
                offset += opcode
                break
            if opcode <= 49:
                offset += 2 if wide else 1
            elif opcode <= 81:
                flag = opcode - 49
                flags.update(name for name, bit in (("blocked", 1), ("bridge", 2), ("roof", 4)) if flag & bit)
    if offset > len(data):
        raise ValueError("Missing final terrain height")
    return {
        "encoding": "wide" if wide else "legacy",
        "blockFlags": flags["blocked"],
        "bridgeFlags": flags["bridge"],
        "roofFlags": flags["roof"],
        "trailingBytes": len(data) - offset,
    }


def audit_landscape(data, object_ids):
    reader = Reader(data)
    last_id = -1
    count, missing = 0, set()
    while delta := reader.smart():
        last_id += delta
        position = 0
        while advance := reader.smart():
            position += advance - 1
            if position >= 4 * 64 * 64:
                raise ValueError("Object coordinates exceed the square")
            reader.uint()  # object type and direction
            count += 1
            if last_id not in object_ids:
                missing.add(last_id)
    if reader.offset != len(data):
        raise ValueError("Bytes remain after landscape terminator")
    return count, sorted(missing)


def installed_pairs(groups):
    names = {group.name: index for index, group in groups.items()}
    if len(names) != len(groups):
        raise ValueError("Map index contains duplicate group name hashes")
    pairs, matched = {}, set()
    # Region.LOADED on the server contains slots 0..32767.
    for x in range(128):
        for y in range(256):
            pair = [names.get(name_hash(f"{prefix}{x}_{y}")) for prefix in ("m", "l")]
            if any(group is not None for group in pair):
                pairs[(x << 8) | y] = pair
                matched.update(group for group in pair if group is not None)
    return pairs, sorted(set(groups) - matched)


def configured_spawns(data, kind):
    compatible, errors = defaultdict(lambda: Counter()), []
    for path in sorted((data / kind / "spawns").rglob("*.json")):
        try:
            rows = load_json(path)
        except ValueError:
            errors.append(path.relative_to(data).as_posix())
            continue
        for row in rows:
            square = region_id(row["x"], row["y"])
            for world in WORLD_TYPES:
                if row.get("world") in (None, world):
                    compatible[square][world] += 1
    return compatible, errors


def installed_square(cache, square, pair, xteas, object_ids):
    terrain, scenery = pair
    result = {"mapSquare": square, "grid": coordinates(square), "terrainGroup": terrain, "landscapeGroup": scenery}
    if terrain is not None:
        try:
            raw_terrain, result["terrainSha256"], _ = read_archive(cache, 5, terrain)
            result["terrain"] = audit_terrain(raw_terrain)
        except (ValueError, IndexError, KeyError, OSError, zlib.error) as problem:
            result["terrainError"] = str(problem)
    if scenery is not None:
        configured = xteas.get(str(square))
        try:
            raw_scenery, result["landscapeSha256"], ignored_footer = read_archive(cache, 5, scenery, configured)
            no_key_status = "empty_without_key" if raw_scenery == b"\0" else "server_skips_no_key"
            result["landscapeStatus"] = "configured_key" if configured is not None else no_key_status
            if ignored_footer:
                result["landscapeStatus"] = "configured_key_native_deflate"
            result["landscapeObjects"], result["missingObjectDefinitions"] = audit_landscape(raw_scenery, object_ids)
        except (ValueError, IndexError, KeyError, OSError, zlib.error) as problem:
            result["landscapeStatus"] = "invalid_configured_key_or_archive" if configured is not None else "blocked_no_key"
            result["landscapeError"] = str(problem)
    if None in pair:
        result["geography"] = "MISSING MAP OR REQUIRED DATA"
    elif ("terrainError" in result or "landscapeError" in result or result["missingObjectDefinitions"]
          or result["landscapeStatus"] == "server_skips_no_key"):
        result["geography"] = "BLOCKED BY AN IDENTIFIED DEPENDENCY"
    else:
        result["geography"] = "IMPLEMENTED BUT NOT FULLY VERIFIED"
    return result


def reference_squares(reference, mappings, installed):
    protocol, revision, named, groups = reference.table(5)
    raw_index = reference.read(255, 5)
    if (protocol, revision, named) != (7, REFERENCE_INDEX_REVISION, False) or zlib.crc32(raw_index) != REFERENCE_INDEX_CRC:
        raise ValueError("Use the pinned OpenRS2 OSRS 242 cache, archive ID 2757")
    squares, excluded = [], []
    for source, group in sorted(groups.items()):
        if group.files != (0, 1, 2, 3, 4):
            excluded.append({"group": source, "files": list(group.files)})
            continue
        mapping = mappings.get(str(source))
        dest = mapping["destination"] if mapping is not None else source
        audited = installed.get(dest)
        association = ("documented_relocation" if source != dest else "documented_native_242") if mapping else "legacy_coordinate_overlap"
        squares.append({"source": source, "target": dest, "association": association,
                        "geography": audited["geography"] if audited else "MISSING MAP OR REQUIRED DATA"})
    return squares, {"revision": revision, "containerSha256": hashlib.sha256(raw_index).hexdigest(),
                     "nonMapGroups": excluded}


def audit(cache_path, data, reference_path):
    npc_spawns, npc_errors = configured_spawns(data, "npcs")
    objects, object_errors = configured_spawns(data, "objects")
    content = load_json(data / "content242.json")
    xteas = load_json(data / "region_keys.json")
    with Store(cache_path) as cache, Store(reference_path) as reference:
        pairs, unmatched = installed_pairs(cache.table(5)[3])
        object_ids = set(cache.table(2)[3][6].files)
        installed = {}
        for square, pair in sorted(pairs.items()):
            row = installed_square(cache, square, pair, xteas, object_ids)
            row["staticNpcSpawns"] = dict(npc_spawns[square])
            row["staticObjectOverrides"] = dict(objects[square])
            installed[square] = row
        official, pinned = reference_squares(reference, content["mappings"]["regions"], installed)
        total_spawns = {world: sum(row[world] for row in npc_spawns.values()) for world in WORLD_TYPES}
        missing_squares = [row for row in official if row["target"] not in installed]
        return {
            "scope": "Disk map, configuration, and static-spawn evidence. Gameplay and runtime collision remain unverified.",
            "reference": pinned,
            "counts": {
                "installedMapSquares": len(installed),
                "installedPairedSquares": sum(None not in pair for pair in pairs.values()),
                "installedGeographyStatus": dict(Counter(row["geography"] for row in installed.values())),
                "installedLandscapeStatus": dict(Counter(row.get("landscapeStatus", "missing") for row in installed.values())),
                "referenceMapSquares": len(official),
                "referenceAssociations": dict(Counter(row["association"] for row in official if row["target"] in installed)),
                "referenceMissingSquares": len(missing_squares),
                "staticNpcSpawns": total_spawns,
                "staticNpcSquares": {world: sum(row[world] > 0 for row in npc_spawns.values()) for world in WORLD_TYPES},
            },
            "unmatchedInstalledIndexGroups": unmatched,
            "unreadableSpawnFiles": sorted(npc_errors + object_errors),
            "home": {"arrival": HOME, "mapSquare": installed.get(region_id(*HOME[:2])),
                     "npcSpawnFileSha256": hashlib.sha256((data / "npcs/spawns/home.json").read_bytes()).hexdigest(),
                     "objectSpawnFileSha256": hashlib.sha256((data / "objects/spawns/home.json").read_bytes()).hexdigest()},
            "installed": list(installed.values()),
            "referenceSquares": official,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--reference-cache", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    output = args.report.resolve()
    if any(path.resolve() in output.parents for path in (args.cache, args.data, args.reference_cache)):
        parser.error("Report must be outside the read-only inputs")
    result = audit(args.cache, args.data, args.reference_cache)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result["counts"], indent=2))


if __name__ == "__main__":
    main()
