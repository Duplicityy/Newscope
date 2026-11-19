# World examines

Newscape's staged catalogue uses original descriptions for 287 NPC families and 97 scenery families. The entries match 2,378 active-cache NPC definitions and 5,538 active-cache object definitions. The baseline world snapshot matched 8,389 of 10,733 named NPC placements, representing 989 of 2,126 distinct named spawned NPC IDs. Coverage counts apply to the installed cache and baseline placements.

The frozen server used the NPC Examine command to open the Loot Compendium. The new handler sends an available description before opening the same Compendium page. Object Examine was limited to owner diagnostics. It now prints scenery descriptions and keeps ongoing movement and other activity intact. Loose items share the inventory Examine opcode. The handler separates scene coordinates from widget addresses and validates the live inventory slot before examining an inventory item. Both paths preserve unsigned item IDs and ignore the no-target value.

The staged server keeps the installed item catalogue, including existing item descriptions and custom attribute output. The baseline reported 34,874 nonblank item descriptions across 41,492 item definitions. NPC and object definitions lack an installed description field. Uncovered families need authored text supported by their actual role, model, or region. The catalogue keeps no default description for an unknown family.

Run `WorldExamineChecks` with the staged `data/world-examines.json` and frozen cache directory. It uses the real packet handlers with a detached player and tests high unsigned IDs, packet byte order, sentinel values, inventory slot validity, reload failure, and preservation of activity while examining scenery.

## Engine and data research

- RuneLite's Examine plugin matches an incoming game message with a client examine action for item value display. The installed Newscape client source supplies the exact codec used for this patch. [RuneLite Examine plugin](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/examine/ExaminePlugin.java)
- OSRS Reboxed documents its Wiki-derived NPC and item metadata separately from its tools. Its published facts can help check missing families against Newscape's frozen ID mappings. [OSRS Reboxed](https://github.com/RSPSApp/osrsreboxed-db)
- Lost City's historical content repository targets game revision 274. Its architecture is a useful reference for placing authored descriptions behind entity definitions. [Lost City Content](https://github.com/LostCityRS/Content)
- TSPS credits the original owners of the game assets separately from its server implementation. [TSPS project](https://github.com/RSPSApp/tsps)
