# World Perfection

This Windows staging build starts from the installed revision 242 backport and the existing Daemonheim and teleporter work. The audit compares two initialized copies of the same world. It records spawn locations, combat classes, sequence IDs, and registered NPC actions. Client rendering and per-player quest transforms require separate checks.

## Combat animation

The import previously treated a list of observed sequences as an ordered attack, block, and death tuple. Those observations have no such order. The importer now selects explicit roles and checks the sequence and rig against the installed cache. The staged combat data changes only animation fields on verified records. In particular, the five red dragon variants retain their native dragonfire handler and regain the ordinary melee, block, and death sequences.

The patch covers 241 NPC definition IDs and 2,242 loaded combat placements. It fills 178 attack, 161 block, and 171 death values. It also corrects 39 attack, 44 block, and 23 death assignments. The patch edits only the attack, block, and death fields.

The coverage audit includes all 7,523 loaded combat placements spanning 978 definition IDs. It records 32 remaining generic combat IDs with no verified attack sequence. A blank animation field on a scripted encounter can be intentional because the handler can select a sequence during combat.

The former New Lands arrival squares contain corrected attack animations for 13 Civitas illa Fortis guards, seven mountain trolls and three bats near Mortimer and Wyrmscraig Cavern, seven praying mantises and five pirates on The Onyx Crest, three birds on The Summer Shore, and one yak at Wyrmscraig. Mortimer and Wyrmscraig Cavern share the same map square. The pirate attack uses Newscape's installed scimitar slash, matched to the pirate's actual weapon mesh and animation rig. Its original OSRS playback is unverified.

## Examine and dialogue

The staged catalogue has original examines for 287 NPC families and 97 scenery families. Ground-item and inventory Examine use the correct unsigned packet values and context. NPC Examine still opens the Loot Compendium. Scenery Examine leaves ongoing movement intact.

Merchant conversations reach the existing stock and its access checks. Town guides and tutors now have original, role-specific choices. Hans reports saved play time. Lokar has separate Talk-to and Travel actions for his existing Rellekka and Pirates' Cove routes. The River Watch ledger now binds after the shared crate-search callback is installed, with active clues retaining priority.

See the [Examine audit](EXAMINES.md) and [dialogue coverage](NPC_DIALOGUE.md) for denominators, known gaps, and reproduction commands. The installed world lacks a complete authored dialogue and examine corpus. The audits identify missing scripts and missing animation evidence so those gaps can be investigated individually.

## Research

- TSPS's role-labelled capture records distinguish attacks, blocks, deaths, and other observations. Its unordered animation lists remain useful as evidence that a sequence was observed on a given NPC. [Pinned TSPS observations](https://github.com/RSPSApp/tsps/blob/1ccb548be725c70900b604968354dd3147b839ea/server/data/definitions/npc-captured-animations.json)
- OpenRune's named combat records and RuneLite's generated NPC constants corroborate the two Nagua variants. The installed cache supplies the sequence, skeleton and frame checks. [Pinned OpenRune NPC data](https://github.com/OpenRune/OpenRune-Server/blob/c072fd273ed0c6149dcdb3017d6167082e328f23/.data/raw-cache/server/npcs.toml) · [Pinned RuneLite NPC IDs](https://github.com/runelite/runelite/blob/0fa3b8489e929a47410c7214ffcb710e67c7bb46/runelite-api/src/main/java/net/runelite/api/gameval/NpcID.java)
- OpenOSRS's recorded sequences corroborate selected same-NPC assignments. That dataset contains observed IDs and provides no combat-role labels. [Pinned OpenOSRS animation observations](https://github.com/open-osrs/service-animations/blob/101f6d1fab8b0c019833ceb821d0144497f9293d/animations.json)
- RSMod's NPC combat events, Lost City's engine operations, and RSBox's cache loader helped distinguish behaviour from cached metadata. None provided a revision-compatible bulk dialogue or examine replacement for Newscape. [RSMod combat events](https://github.com/rsmod/rsmod/blob/fa13b3f67172ef36e15d6b1514358aee61411796/api/npc/src/main/kotlin/org/rsmod/api/npc/events/NpcCombatEvents.kt) · [Lost City NPC operations](https://github.com/LostCityRS/Engine-TS/blob/5522cd0cd82f0df07d99c142574d4706815f1c9c/src/engine/script/handlers/NpcOps.ts) · [RSBox NPC configuration](https://github.com/rsbox/rsbox/blob/187049de600faa9d8de244ee2ed14785d9123475/server/cache/src/main/kotlin/io/rsbox/server/cache/config/NpcConfig.kt)
