# World action audit

The audit reads the initialized Windows test world using the current mixed-revision cache. It checks every visible option on loaded static world objects and authored NPC placements. It records each object's tile, plane, model, native handler, and placement-specific handler. A callback count measures wiring. Condition checks, transformed NPCs, instanced copies, and actions that have no visible menu label need their own coverage.

## Traversal and services

- Rellekka fur doors, Lunar galleon cabin doors, farming shed doors, and the Barbarian Village longhall now have supported Open and Close states. The audit found 28 matching door placements with newly resolved states. Generic doors preserve a protected neighboring leaf's own action.
- Staircases, ladders, and trapdoors have 166 new two-way routes. Both Draynor Sewers hatches also open, descend, and lead back to their matching surface entrances. Placement-specific scripts and their requirements retain priority. [Vertical travel audit](VERTICAL_TRAVEL.md)
- Fairy rings use the existing control panel, equipment checks, walkable arrival tiles, and native return actions. The review verified 42 static destination and return bindings. [Fairy ring audit](FAIRY_RING_TRAVEL.md)
- Passenger crews run Port Sarim–Musa Point and Port Sarim–Void Knights’ Outpost in both directions. Four dock-to-deck gangplanks now work both ways. The Karamja crew charges 30 coins, or 15 with equipped Karamja gloves. The dialogue also supports the activated ring of charos discount. The Void route is free. Customs turns passengers with Karamjan rum away and preserves their items. [Passenger routes implementation](../../Kronos-master/kronos-server/src/main/java/io/ruin/model/entity/npc/actions/traveling/PassengerBoats.java)
- Eight matching tree definitions restore native Woodcutting at 280 placed trees. Candidate ore aliases failed the definition-equivalence check and did not bind.

## Verification

The combined server started on Windows loopback with the frozen runtime35 cache. Startup bound all 166 staircase, ladder, and trapdoor routes and both Draynor hatches. Detached checks used the same Kilim event weaving as production and exercised door collision, six vertical round trips, fairy-ring movement and save state, passenger fares and customs, and all four gangplanks. Dungeoneering passed 1,722 existing expedition checks. Connected client and phone play remain unverified.

The final snapshot counted 84,337 object placements with 89,613 visible options and 10,649 authored NPC placements with 11,357 visible options. Comparing identical object slots across builds found 491 newly bound options and zero lost callbacks. Four of those bindings give guidance for the unsupported fairy-ring Favourites action. Puro-Puro's changing wheat explains the variation in raw snapshot size.

## Remaining gaps

Open cases include additional door states, route-specific quest and skill checks, Entrana equipment checks, and missing crew or dock bindings for some fixed passenger services to Kourend and Unkah. Pirate's Treasure smuggling progress is absent. The static scan reports 50,259 object slots and 2,089 NPC slots without a direct callback. Many cover conditional, instanced or client-driven actions. Review those in context before assigning behavior. The subsequent charter stage covers all eligible pre-Sailing, non-quest ports. [Charter travel](CHARTER_TRAVEL.md)

## Reproduce

`content-tools/checks/WorldTraversalAudit.java` records the initialized object placements and definitions as tab-separated files. `WorldNpcAudit.java` records the NPC placements and action slots. `world_action_coverage.py` takes those native exports and counts each advertised option by callback, missing callback, or unresolved door state. The output also lists all affected action labels for follow-up.

The installed cache supplies the definitive object IDs, placement, collision, and visible options. External route records serve as leads. Resolve the exact origin action, check both landings, and verify requirements before attaching a route.

## Source references

- OpenRune's authored door definitions map each door leaf to its open or closed state. Its auto-door table corroborates the Viking fur doors and Lunar galleon cabin states. [OpenRune door definitions](https://github.com/OpenRune/OpenRune-Server/blob/15009ceaadc28d87fac66c7cbf9b753ec852036a/.data/raw-cache/server/loc/doors.toml) · [Auto-door table](https://github.com/OpenRune/OpenRune-Server/blob/15009ceaadc28d87fac66c7cbf9b753ec852036a/content/generic/generic-locs/pack/src/main/resources/pack/configs/auto_doors.toml)
- RuneLite's object constants link named state pairs to IDs. Newscape's actual cache confirms both definitions, menu options, and geometry. [Pinned RuneLite object IDs](https://github.com/runelite/runelite/blob/0fa3b8489e929a47410c7214ffcb710e67c7bb46/runelite-api/src/main/java/net/runelite/api/gameval/ObjectID.java)
- Shortest Path identifies transport anchors, paired landing tiles, and gates. Its anchor-drift tests explain why a world map route still needs native validation. [Transport format](https://github.com/Skretzo/shortest-path/blob/a7dd4a27f7909b30ea710a6d4a2d5b6706a13865/docs/Transport-TSV-format.md) · [Anchor validation](https://github.com/osrs-pathfinding/shortest-path-tooling/blob/f9e8722b8db03465eff4041602f887fc62cc617e/src/test/java/shortestpath/dump/TransportAnchorDriftTest.java)
- OpenRune's ship travel implementation describes the two passenger routes and their interaction policy. [Ship travel reference](https://github.com/OpenRune/OpenRune-Server/blob/15009ceaadc28d87fac66c7cbf9b753ec852036a/content/areas/city/port-sarim/src/main/kotlin/org/rsmod/content/areas/city/portsarim/travel/ShipTravel.kt)
