# World stairs, ladders, and trapdoors

Newscape's loaded revision 242 world contains the scenery for these routes. The old generic ladder action used the player's current tile and changed its plane. It could return to the wrong side of an offset ladder and could not cross between the surface and an underground map square. Many staircases had visible climb options with no game action.

## Coverage

The native survey checked 877 reference route rows across 23 areas against the installed map. The startup manifest covers 166 two-way connections. Those connections bind 186 staircase actions, 145 ladder actions, and the already-open Lumbridge cellar trapdoor. The routes include Lumbridge Castle, Falador, Varrock, East Ardougne, the Tree Gnome Stronghold, the Arceuus Library, and Kourend Castle. The cellar under Lumbridge Castle and the Wizards' Tower basement are supported separately from any deeper quest routes.

The two Draynor Sewers routes use the actual closed trapdoor. Opening the hatch changes its visible object, and Climb-down enters the sewer. The exit ladder works whether the hatch is open or has closed on its normal timeout.

Each manifest entry specifies the allowed object, its exact anchor, plane, shape, rotation, and option, plus the verified standing tiles at each end. Registration checks both directions together against the loaded world. Specific actions and nongeneric handlers have priority. Both routes and landings are checked again when a player climbs. Draynor uses a placement-scoped handler for its closed and open states.

Locations outside the selected public districts and cross-square routes with unresolved access rules still need individual research. The reference table includes routes whose access depends on quests, items, level, or a changed object state. Those need an implemented condition and a verified reverse route before registration.

## Evidence and validation

- The pinned reference contributes possible travel coordinates. Native data supplies the actual object anchors, placement rotation, menu slots, handler priority, and floor clipping. [Pinned TSPS transport data](https://github.com/RSPSApp/tsps/blob/1ccb548be725c70900b604968354dd3147b839ea/client/public/shortestpath/transports/transports.tsv) · [TSPS license](https://github.com/RSPSApp/tsps/blob/1ccb548be725c70900b604968354dd3147b839ea/LICENSE)
- The section-format reference distinguishes player standing coordinates from the source object anchor. [Shortest Path transport format](https://github.com/Skretzo/shortest-path/wiki/Transports)
- The Draynor Sewers reference describes both public entrances. The Wizards' Tower reference describes the stairs leading to the basement and the separate quest travel available there. [Draynor Sewers](https://oldschool.runescape.wiki/w/Draynor_Sewers) · [Wizards' Tower](https://oldschool.runescape.wiki/w/Wizards%27_Tower)
- `content-tools/reconcile_world_vertical_travel.py` takes the ungated, native placement survey TSV and regenerates the deterministic manifest. The staging evidence is in `world-traversal/checks/vertical-survey-baseline.tsv`.
- `content-tools/checks/WorldVerticalTravelChecks.java` uses the frozen world cache, woven server code, native object interaction handler, and detached player movement. It checks both bindings for each eligible pair, protects a deliberately supplied existing handler, and exercises six round trips. The fixture also checks refusal of clipped landings, both Draynor opening transitions, and timed closure before climbing back up. Physical client interaction remains unverified.
