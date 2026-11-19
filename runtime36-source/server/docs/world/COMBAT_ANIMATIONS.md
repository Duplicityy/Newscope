# World combat animations

The runtime 35 audit captured 10,849 loaded NPC placements. The native server initialized combat on 7,523 of those placements, representing 978 NPC IDs. The audit also recorded 3,845 definitions with configured combat, including definitions reserved for instances and scripted spawns. Combat flags, models, movement, styles, sequence IDs and handler classes came from the initialized Windows server.

The staged patch updates 241 NPC IDs across 2,242 loaded placements. It restores 178 attack, 161 block and 171 death animations that lacked a usable sequence. It corrects a further 39 attack, 44 block and 23 death roles in the revision 242 import. The original importer assigned roles by the order of an unordered list of observed sequences. Future imports look up roles by their evidence and exclude positive-damage generic records that lack a verified attack sequence.

The repair uses the installed cache's sequence, frame and skeleton archives. Each chosen animation has a compatible movement rig and an exact authored role, a named sequence observed on the same NPC, a uniquely named family, or a matching local model and combat profile. Reviewed overrides preserve the distinction between movement, weapon variants, attacks, blocks and death. The patch is additive and contains the original non-animation combat settings for every affected NPC.

Red dragons 247–251 use a scripted handler for dragonfire and a configured sequence for their ordinary melee hit. The staged data assigns 80 to melee, 89 to block and 92 to death. The detached test runs the actual native melee and dragonfire actions on those five profiles and confirms the authored fire sequence, graphics and hit style.

The former New lands audit checked Civitas illa Fortis Guard 13100 against a capture of that NPC. The guard uses mapped sequence 9033 for the observed attack, with its current STAB hit retained. The Onyx Crest Pirate 14933 carries the verified scimitar equipment mesh 490 and uses a SLASH hit. Its Newscape attack plays the matching scimitar sequence 9030. The pirate sequence is a weapon-specific reconstruction. A same-NPC observation for it remains unavailable. [Latest guard capture](https://github.com/RSPSApp/tsps/blob/5d2acaed5e3a113508d9cd802c34820adf875ee9/server/data/definitions/npc-captured-animations.json)

## Open cases

- The audit reports 32 damage-dealing generic IDs across 210 loaded placements with an unresolved attack sequence. Most fall into families where weapon variants or rigs differ. The report records each ID and its remaining candidate if one was found.
- The lineage report lists 46 unresolved block and 46 unresolved death roles separately. Authored omissions remain distinct from unresolved roles.
- There are 54 IDs across 306 loaded placements with an Attack menu option but no initialized combat. Some are quest and encounter helpers. These require a combat implementation or a case-by-case review of the Attack option.
- An initialized snapshot proves the active sequence bindings and preserves the native spawn positions and handlers. A detached test verifies the red dragon action output. Visual playback in a connected client and NPCs that only spawn later in instances require separate verification.

## Reproduction

Run `content-tools/world_combat_animations.py` with the frozen release, the initialized baseline NPC snapshot, the pinned donor definitions and a fresh Windows output directory. The tool writes `npc-combat-animation-audit.json` and the additive `data/npcs/combat/zz_world_combat_animations.json`. The audit includes input checksums and the basis for every change. On an isolated candidate, `content-tools/check_world_combat_animations.py` compares the merged result against the baseline using both native snapshots.

## Evidence sources

- The previously used TSPS snapshot supplies authored roles, cached sequence names and role observations. Its original observed-sequence list carries a BSD 2-Clause notice. [Pinned TSPS definitions and notices](https://github.com/RSPSApp/tsps/tree/1ccb548be725c70900b604968354dd3147b839ea/server/data/definitions)
- OpenRune independently names the Sulphur and Frost Nagua attack sequences. RuneLite game values correlate the precise cache NPC keys with their numeric IDs. [OpenRune NPC configuration](https://github.com/OpenRune/OpenRune-Server/blob/c072fd273ed0c6149dcdb3017d6167082e328f23/.data/raw-cache/server/npcs.toml), [RuneLite NPC IDs](https://github.com/runelite/runelite/blob/0fa3b8489e929a47410c7214ffcb710e67c7bb46/runelite-api/src/main/java/net/runelite/api/gameval/NpcID.java)
- The OpenOSRS animation service lists additional same-NPC sightings. The role audit uses semantic names and validated animation rigs to classify those observations. [OpenOSRS observations](https://github.com/open-osrs/service-animations/blob/101f6d1fab8b0c019833ceb821d0144497f9293d/animations.json)
- RSMod's public NPC cache enricher was checked for outstanding cases. Its sampled exact entries provide data other than the needed combat sequences. [Pinned RSMod NPC configuration](https://github.com/rsmod/rsmod/blob/fa13b3f67172ef36e15d6b1514358aee61411796/api/cache-enricher/src/main/resources/org/rsmod/api/cache/enricher/npc/npcs.toml)
