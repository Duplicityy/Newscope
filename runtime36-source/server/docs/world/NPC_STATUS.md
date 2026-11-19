# NPC population

The population audit reads the cache and configured data from the selected Newscape runtime. The baseline is `runtime-private-33-pickup-fix`, with the revision 242 backport and the existing legacy, custom, and Dungeoneering layers. It counts static records accepted by the PVP world filter. Script-spawned, transformed, and instanced NPCs require runtime checks.

| Static evidence | Baseline | Reconciled | With gameplay repairs |
| --- | ---: | ---: | ---: |
| Spawn files | 125 | 125 | 125 |
| PVP-eligible rows | 10,700 | 10,644 | 10,644 |
| Regions with eligible static placements | 545 | 545 | 545 |
| Distinct installed NPC IDs used | 2,182 | 2,182 | 2,182 |
| Rows in the existing revision 242 spawn layer | 3,225 | 3,225 | 3,225 |
| Rows with a static combat definition | 7,523 | 7,469 | 7,487 |
| Rows with static combat and declared loot | 6,402 | 6,348 | 6,366 |
| Rows with a declared shop binding | 86 | 86 | 86 |
| Attack options without a static combat record | 333 | 333 | 315 |
| Repeated NPC and tile identities | 190 | 134 | 134 |

Combat, loot, and shop counts describe configured data. Runtime scripts can add actions, choose encounter forms, and handle valid no-drop outcomes. The current raw report flags 18 shop options for runtime review. The runtime Lisa fix covers one of these without changing the cached definition. These options reference existing shop identifiers, but their labels are absent from the raw NPC cache definition. Some labels are assigned by Newscape at startup.

## Reconciled placements

- Region 5789 held 43 overlapping placements from both `dungeons/chasm_of_fire.json` and `dungeons/smoke_devil_dungeon.json`. The Chasm file keeps all 43 placements and its authored movement settings. The revision removes those copies from the Smoke Devil file, which retains 49 other records.
- Region 9116 held 13 overlapping Waterfiend placements from both `dungeons/kraken_cove.json` and `dungeons/gnome_stronghold.json`. The Kraken Cove file keeps its 40 records, including the 14 Waterfiends. The Gnome Stronghold file retains its other 57 records.

The reconciler checks the NPC ID, full tile and plane, world, match multiplicity, and reviewed region. It rejects ambiguous matches and shop overrides. It leaves the other 134 repeated identities for individual review because some same-tile placements are intentional. The overlay contains only the two patched spawn files. Home and Dungeoneering data are outside its write scope.

## Reproduce

Run the following commands from the feature worktree using Python 3.12 or newer. Supply the installed cache and data directories as the inputs. Choose fresh paths outside the input directories for both outputs.

```powershell
python -B content-tools/world242_population.py --cache <installed-cache> --data <installed-data> --report <new-report.json>
python -B content-tools/world242_population.py --cache <installed-cache> --data <installed-data> --overlay-data <new-staging-data>
python -B -m unittest discover -s content-tools -p test_world242_population.py
```

The report contains region totals, original data file SHA-256 values for the two fixes, the exact removed rows, unresolved repeat evidence, and flagged action bindings. The staging command emits two native JSON files for integration into a development runtime. Applying the same audit to a reconciled runtime plans zero additional removals.

Six unit tests passed. The integrated-data check matched both staged files to the worktree byte-for-byte, checked the authoritative files against the installed runtime, and confirmed that the corrected data plans zero changes. The Python audit peaked at 42.8 MiB. These results cover data and reconciliation. Spawn visibility and combat in a client require a separate runtime check.

## Reference review

The applied changes come from comparing Newscape’s own installed placement files. The external references were reviewed for provenance and data-model context.

- TSPS commit `d36cc7ebd89b96ed2caa0e2dfc7fa3fc6c0ad494` uses a separate server spawn catalogue and conditional plugin hooks. Its repository licence is BSD-2-Clause. [Spawn format and provenance](https://github.com/RSPSApp/tsps/blob/d36cc7ebd89b96ed2caa0e2dfc7fa3fc6c0ad494/docs/npc-spawns.md) · [Licence](https://github.com/RSPSApp/tsps/blob/d36cc7ebd89b96ed2caa0e2dfc7fa3fc6c0ad494/LICENSE)
- OpenRune Server commit `c072fd273ed0c6149dcdb3017d6167082e328f23` uses separate data and content modules. Its repository licence is ISC. [Repository at reviewed commit](https://github.com/OpenRune/OpenRune-Server/tree/c072fd273ed0c6149dcdb3017d6167082e328f23) · [Licence](https://github.com/OpenRune/OpenRune-Server/blob/c072fd273ed0c6149dcdb3017d6167082e328f23/LICENSE.md)
