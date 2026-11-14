# Additive242 cache on the184 protocol

`bulk_import.py` requires the matching extended184 client and server readers.
It never modifies its source cache or source data, and refuses an existing output
directory. The cache marker is `content242-format.properties`, with
`format=k242-skeletal-v1` and updater capability `js5_revision=184242`.
The game protocol remains184. This is not a protocol242 server.

## Models and definitions

Original model IDs still mean index7/groupID/file0. Logical IDs65536–130559 use
reserved index7 groups65408–65534, each containing at most512 files:

```
group = 65408 + ((logicalID - 65536) >> 9)
file  = (logicalID - 65536) & 511
```

The baseline must have none of these reserved groups. Banks have an8MiB
uncompressed limit and an independent8MiB compressed-container guard; JS5 group
requests retain their original16-bit width. The updater's reserved-bank reader
must support that bound; the original API's1,000,000-byte limit rejects three
actual stage04 banks (maximum1,213,311 bytes).
The matching client's `ArchiveDisk` applies the same 8MiB limit only to those
reserved index7 groups, for both reads and writes. Ordinary models, other
indexes and the master index retain their original limits. Network delivery
alone does not prove persistence: `ArchiveDisk242Probe` verifies native writes,
reads, close/reopen, overwrite and legacy boundaries against the packaged client.
Definition opcode250 supplies explicit32-bit model references:

* Item: count byte followed by original model-field opcode byte and model u32.
* Object: typed byte, count byte, then model u32 and optional model-type byte.
* NPC: body count byte and models u32, then head count byte and models u32.
* Graphic: model u32.
* Identity kit: body count byte and models u32, then head count byte and
  pairs of head-slot byte and model u32.

Old records retain their old encodings. All model fetch/readiness paths use the
same bank addressing, including kits, equipment, object mirrors and widgets.
Weighted model envelopes, skeleton trailers and sequence114/115/117 are specified
in `Kronos-master/tools/content242/SKELETAL_COMPATIBILITY.md`.

Texture references in geometry and definition retexture pairs share one verified
mapping. Floor texture opcodes remain bytes, and allocation rejects IDs above254.
No texture ID is truncated to fit the old renderer.

## Regions, palettes and state

New terrain begins with ASCII `K242TERR` plus version byte1. Tile opcodes and
overlay IDs are unsigned shorts; explicit height operands remain bytes. Existing
unmarked terrain retains the exact184 byte reader. Client floor arrays hold
unsigned-short values. Plane-zero implicit heights are resolved at original
source coordinates before relocation; upper-plane relative heights remain intact.

Original map names/groups are preserved. Naturally absent regions keep their
coordinates. Conflicting regions move together with their selected connected
component into an unused rectangle below worldX6400, outside the dynamic-map
allocator. The manifest's `mappings.regions` uses source region IDs as keys, with
integer `destination` region IDs and tile-coordinate `dx`/`dy` translations.
New landscape archives are unencrypted and receive explicit zero XTEA keys.

Object and NPC morph dependencies are closed before allocation. New varps start
at4000 or higher, preserving original state IDs; imported varbit parent references
are remapped. New NPC network IDs are strictly below16384. Compatible definitions
and model/animation payloads are deduplicated; genuinely custom original IDs are
preserved. Unsupported structural assets withhold the complete affected region.

## Registry and selection

`data/content242.json` schema2 includes sourceRevision242/clientProtocol184 and
explicit items, NPCs, objects, animations, graphics, regions, varbits and varps
mappings. Sidecars under `data/content242/` contain source-ID-keyed NPC/object
metadata and item values. Item values include original identity mappings.

All compatible sequences and graphics are attempted. NPCs are selected from
imported-region spawns, explicit mechanics requirements and morph closure.
Objects come from imported landscapes, explicit scripted-object requirements
and morph closure. Anonymous equipment records referenced only by animation
held-item overrides are included; they are not represented as new ordinary loot.

Requirements from `mechanics-assets.json` and `mechanics-burn-assets.json` are
merged. `--requirements` can explicitly supply one or more files. The importer
records missing bindings, and leaves every gameplay feature disabled for the
runtime integration/verification step. Asset availability is not mechanics proof.

## Checks and boundaries

`test_modern_definitions.py` checks bank boundaries/reservations, wide palettes,
kit/model references, retexture mappings, anonymous held dependencies, landscape
delta32767 and static/dynamic map separation.

`export_foundation_fixtures.py` exports assembled cache records and independent
terrain-field hashes. `Foundation242Probe.java` exercises actual packaged client
readers, verifies complete byte consumption, compares native terrain arrays,
and decodes/lights every banked model with triangle and weight bounds.
The final asset receipt is `asset-audit/foundation-native-receipt-stage04.json`.
It identifies the exact client/cache manifest/fixtures and does not claim a
rendered scene or completed encounter test. It passed103,678 native definition
decodes,1,164 terrain regions and18,452 banked model decodes with lighting
(167 weighted,8 above the former4700-mesh limit). Original preservation covers
81,358 groups. The registry has33,918 unique item targets and15,166 supported
source sequences; source8850 has a malformed zero-pose skeletal trailer.

Stage04 extends immutable stage03 through `extend_object_requirements.py`, adding
five explicit Muspah objects and one model. The extension receipt records both
parent hashes and every addition. Existing definitions and bank files are checked
unchanged. This small extension cannot silently import a new region, animation,
NPC, texture or morph closure; those require the full importer.

Known boundaries are recorded in each import report: six source meshes with
referenced complex texture coordinates are unsupported; sounds, modern NPC
head icons/map icons, graphic rotate=no flags and NPC run/crawl variants are
not fully ported. Matching identity-kit visuals retain original selection IDs;
genuine custom appearances remain intact. Source asset presence does not add
quests, travel scripts, boss mechanics, shops or rewards by itself.
