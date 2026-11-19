# NPC dialogue and service coverage

The read-only probe records the installed definition action labels, registered callbacks, and active spawn tiles from the isolated server. The November 19 baseline contained 10,649 authored NPC placements across 2,183 definition IDs. Talk-to appeared on 1,811 placements spanning 968 IDs. Registered instance or definition callbacks covered 396 of those placements. Another 88 had only conditional clue logic, and 1,327 had no registered Talk-to path. Thirty-nine of the latter already offered a mapped Trade action.

| Authored Talk-to status | Baseline placements | Final placements |
| --- | ---: | ---: |
| Registered normal action | 396 | 458 |
| Conditional clue only | 88 | 86 |
| Unbound | 1,327 | 1,267 |

The final readout counted 209 IDs with registered normal conversation actions across authored placements. The placement-level worklist records 692 IDs with at least one unbound placement.

## Implemented changes

The revision 242 import includes 33 validated stock bindings across 37 active merchant placements. Talk-to now opens a conversation and offers the merchant's existing stock. The Trade action remains the authority for stock, currencies, and any access checks. Authored dialogue and per-spawn actions keep their priority. The loader checks that each trade slot maps to the installed Trade label before registering it.

Fifteen verified NPC definitions gained original dialogue choices about their skills and landmarks. They cover the Lumbridge tutors, the Varrock smithing tutor, Duke Horacio, both border guards, and the Varrock town crier. These conversations use the native NPC dialogue and options interfaces. Hans's Talk-to and Age options report the same recorded play time used by Newscape's journal.

Vanessa in Catherby, the Falador Garden supplier, and Trader Stan in Port Sarim gained stock conversations on their verified placements. They use each NPC's existing per-spawn shop handler. Active clue conversations keep their existing precedence.

The packet handler reports an unavailable action when it finds no callback. Previously it showed the same unrelated conversation for missing Talk-to and several unrelated options.

## Reproduce

`content-tools/checks/WorldNpcAudit.java` snapshots definitions and spawns from the isolated Windows test world after startup. Run `world_dialogue_coverage.py SNAPSHOT_DIR UNBOUND_REPORT.tsv` to calculate instance, definition, conditional clue, and unbound coverage and produce a placement-level worklist. A registered action still needs gameplay testing to establish what it does. Active scripted spawns are a point-in-time sample. NPCs created only in unentered activities and player-specific transformed forms need separate coverage.

`Check-World242Access.ps1` runs the detached native tests. The dialogue checks cover the installed Artima and Vanessa shops, a Prayer tutor topic, native chatbox actions, instance Trade precedence, a stale dialogue on the wrong plane, and Hans's recorded play time.

The remaining unbound conversations include quest actors, unimplemented services, and conditional states. Dialogue for those mechanics needs the corresponding progression or service and original Newscape wording. Separate options such as tutors' Claim actions require their own coverage checks.

## Implementation references

- OpenRune separates the talk and right-click trade bindings for both shopkeeper variants and shares the native stock operation. [OpenRune GeneralStore.kt](https://github.com/OpenRune/OpenRune-Server/blob/15009ceaadc28d87fac66c7cbf9b753ec852036a/content/areas/city/lumbridge/src/main/kotlin/org/rsmod/content/areas/city/lumbridge/npcs/GeneralStore.kt)
- RSMod keeps the banker conversation, direct opening, collection service, and approach behavior in their respective option hooks. [RSMod Banker.kt](https://github.com/rsmod/rsmod/blob/fa13b3f67172ef36e15d6b1514358aee61411796/content/generic/generic-npcs/src/main/kotlin/org/rsmod/content/generic/npcs/banker/Banker.kt)
- Lost City binds the two sword shop NPCs to one interaction procedure and uses the shop assigned to the active NPC. [Lost City sword shop](https://github.com/LostCityRS/Content/blob/65b754f768b79b941b21b2a1eb3b0d1ecae3cdfe/scripts/areas/area_varrock/scripts/varrock_swordshop.rs2)
- TSPS validates inventory rows and source priority during shop loading. Newscape's existing shop definitions provide the stock and prices for these changes. [TSPS ShopDefinitionLoader.ts](https://github.com/RSPSApp/tsps/blob/5d2acaed5e3a113508d9cd802c34820adf875ee9/server/src/main/typescript/elvarg/game/definition/loader/impl/ShopDefinitionLoader.ts)
