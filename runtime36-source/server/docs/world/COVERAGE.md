# Installed world coverage

The owner test build `runtime-private-33-pickup-fix` contains 3,183 paired terrain and scenery squares. Its revision 242 expansion records 1,164 explicit square mappings. The original 184-compatible geography accounts for a further 1,784 squares at coordinates present in the pinned revision 242 reference. Those older squares retain their installed layout, including custom changes.

| Revision 242 reference coverage | Squares |
| --- | ---: |
| Actual map squares in archive 5 | 2,949 |
| Documented mappings at original coordinates | 1,157 |
| Documented relocated mappings | 7 |
| Older terrain and scenery at matching coordinates | 1,784 |
| Missing terrain and scenery pair | 1 |

The audit uses [OpenRS2 cache 2757](https://archive.openrs2.org/caches/runescape/2757), the locally pinned Old School revision 242 snapshot dated 2026-11-03. It checks archive 5 revision `1793635803` and container CRC `174413765`. Group `25287` holds 37 three-byte palette entries and is excluded from the map count. Coordinates establish where an older square is available. Substantial gameplay and content differences can exist within those squares, and actual transitions require separate testing.

## Decoding and dependencies

| Geography status | Installed squares | Reference squares |
| --- | ---: | ---: |
| Implemented but not fully verified | 3,151 | 2,930 |
| Blocked by an identified dependency | 32 | 18 |
| Missing map or required data | 0 | 1 |

All installed terrain streams parsed through their complete four-plane tile count, with 1,821 legacy and 1,362 wide-terrain payloads. The audit decoded 4,957,027 scenery placements and found every referenced object definition in the installed cache. These checks establish the availability of the input records for collision. Route finding, projectile clipping, client scene loading, and player interactions still need runtime verification.

- The reference square `16457` at map grid `(64,73)` is the only absent pair. The earlier conversion withheld it after detecting an unsupported terrain environment trailer. Its source landscape contains 6,251 placements. The source remains a comparison reference.
- Seven installed squares have nonempty scenery archives for which a valid key is missing from the server configuration. Their IDs are `6486`, `6487`, `6743`, `9790`, `13128`, `13898`, and `14910`.
- These 25 installed squares fail scenery decode with their configured key: `4924`, `9634`, `9635`, `9890`, `9891`, `11326`, `12086`, `13146`, `13147`, `13402`, `13403`, `15245`, `15246`, `15501`, `15502`, `16012`, `16013`, `16014`, `16268`, `16269`, `16270`, `16781`, `16782`, `17037`, and `17038`. Their terrain remains present. The reported failure is at the archive header after applying the supplied key.
- A further 272 landscapes decode through the existing Java reader’s raw DEFLATE behavior. Their GZIP footer fails a stricter conventional decoder. The audit verifies the original JS5 CRC, declared expansion size, end of the DEFLATE stream, and complete scenery record structure before treating them as readable.
- The remaining 391 squares with no configured key contain empty scenery streams.

The initial historical-key follow-up searched 78 readable local manifests for those 32 blocked squares. It found no alternate key rows. A separate search compared blocked landscape containers against decoded installed containers. The archive CRC narrowed the comparisons, and the complete stored bytes determined identity. No blocked container had an identical decoded counterpart.

## Verified scenery recovery

The next development build fixes **13 of the 32 landscapes** with a targeted key overlay. Map square `12086` uses its existing plaintext scenery. Twelve other landscapes decode with published historical keys tested directly against our installed archive bytes. The resolver checked 81 [OpenRS2 key snapshots](https://archive.openrs2.org/api#get-caches-scope-id-keys-json). The [repair manifest](../../content-tools/registries/world242-landscape-key-repairs.json) records each chosen snapshot and the installed archive and decoded payload fingerprints.

The native JDK 8 reader and region initializer passed for every repaired square. The archives decode to 36,106 placements. The Java initializer placed 35,826 objects under its normal placement rules. Only 13 region-key records differ from the previous build. All 24 cache files match the baseline byte-for-byte.

| Geography coverage | Baseline | Repaired build |
| --- | ---: | ---: |
| Installed terrain and scenery pairs | 3,183 | 3,183 |
| Installed pairs readable by the map audit | 3,151 | 3,164 |
| Installed scenery archives still blocked | 32 | 19 |
| Blocked archives among revision 242 reference squares | 18 | 11 |
| Absent reference squares | 1 | 1 |

The remaining key or archive dependencies are `4924`, `6486`, `6487`, `6743`, `11326`, `13128`, `13146`, `13147`, `13402`, `13403`, `14910`, `16012`, `16013`, `16268`, `16269`, `16781`, `16782`, `17037`, and `17038`. A scan of 121 local caches found no usable alternate plaintext scenery with matching terrain. It also found no installed terrain and scenery pair for `16457`. That square still needs a compatible map pair, and the earlier conversion records an unsupported terrain trailer tied to the reference square.

To re-derive the key overlay, run the resolver against the baseline inputs. Save the historical key lists from the published endpoint as files named `openrs2-ID-keys.json`. The tool verifies decoded structure, object references, and input hashes before emitting a fresh report.

```powershell
python -B content-tools/resolve_world242_landscapes.py --cache <installed-cache> --region-keys <baseline-keys> --audit <baseline-map-audit> --output <review-file> --source <historical-key-files>
```

## Population and gameplay

The installed spawn files contain 10,700 PvP-compatible entries across 545 squares and 10,879 Economy-compatible entries across 549 squares. Counts include entries shared between world types. They measure configured static placements. NPCs from encounter code, region events, fishing systems, and other runtime spawners need separate checks.

| Selected installed anchor | Map square | Static PvP NPC entries in the square |
| --- | ---: | ---: |
| Lumbridge, Misthalin | 12850 | 160 |
| Varrock, Misthalin | 12853 | 46 |
| Falador, Asgarnia | 11828 | 61 |
| Ardougne, Kandarin | 10547 | 46 |
| Chaos Temple, Wilderness | 12856 | 17 |
| Canifis, Morytania | 13878 | 63 |
| Al Kharid, Kharidian Desert | 13105 | 24 |
| Rellekka, Fremennik Province | 10553 | 2 |
| Lunar Isle, Lunar Sea | 8508 | 2 |
| Karamja docks | 11569 | 8 |
| TzHaar entrance | 9808 | 92 |
| Woodcutting Guild, Great Kourend | 6454 | 11 |
| Civitas illa Fortis, Varlamore | 513 | 78 |

Anchor coordinates come from existing destination files. Every anchor in the table has readable terrain and scenery. The generated report lists every installed square, its static object and NPC counts, and every reference mapping. Use the geography statuses only for the map layer. Gameplay classification requires evidence from the affected actions, services, encounters, conditional spawns, and connected routes. This audit supplies no completion percentage for that layer.

## Home baseline

`World.HOME` is `(2028,3577,0)`, square `7991` at map grid `(31,55)`. The map contains 1,082 scenery placements, one configured static object override, and 55 PvP-compatible static spawn rows in the full square. The home-specific override files and both packed archive records have individual SHA-256 fingerprints in the generated report.

| Home input | SHA-256 |
| --- | --- |
| Terrain archive `5/2196` | `d4eacba44451ded680db9809b404fb31a36138e13f97c90937537dc022c2f293` |
| Scenery archive `5/2197` | `db0fa19b57d1444091b2cc830235b155272615554b97c5d1f82e7188ed8dc9e9` |
| `npcs/spawns/home.json` | `b7fc9a1f7e64ee8ead786bf2c5d0e62bac99b26b21e5fdcd235086c3c5c8e134` |
| `objects/spawns/home.json` | `3745395497cd86ff37d4c1235d484ecef5eb3df42889d9aedffda422f673549a` |

## Reproduce the audit

Run from the source worktree with Python 3.10+ and a report path outside the input directories.

```powershell
python -B content-tools/audit_world242.py --cache <runtime>/Cache --data <runtime>/data --reference-cache <local-cache-2757> --report <work>/installed-map-coverage.json
python -B -m unittest discover -s content-tools -p test_audit_world242.py -v
```

The baseline report was generated at `D:/Newscape-World242/installed-map-coverage.json`. Four targeted tests passed for legacy and wide terrain, scenery structure and object definitions, zero map-group IDs, and native XTEA and DEFLATE compatibility. The audit opens both caches read-only and reads static configuration. Player saves and server processes were outside the audit.
