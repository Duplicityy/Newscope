# Fairy-ring travel

Newscape's existing dial and travel log stay in place. Configure a world ring with an equipped dramen or lunar staff, choose the three letters, and select Teleport. Rings outside Zanaris can return to Zanaris. The server records a discovery and updates the last destination after native movement accepts the trip.

- **CIQ, Yanille:** Corrected the original ring anchor to `(2528, 3127, 0)`.
- **DJR, Chasm of Fire:** Corrected the ring anchor to `(1455, 3658, 0)`. The persisted enum name remains `SINCLAIR_WEST` to keep existing saves readable.
- **BJS, Zul-Andra:** Corrected the anchor to `(2150, 3070, 0)`.
- **BKQ, Enchanted Valley:** Restored travel to the installed ring. The installed map has a walkable landing and a return ring.
- **Four newer rings:** Reused the same dial, return, and repeat actions for object 39320 at the installed anchors `(3284, 2706, 0)`, `(3178, 2447, 0)`, `(2926, 10455, 0)`, and `(3447, 9824, 0)`.

All 42 supported static destinations resolve to a real ring object and an adjacent clear floor tile in the installed cache. Static travel uses the normal movement and teleport restrictions. The existing player-owned house destination remains instance-based. Fisher King (BJR) needs Holy Grail progression. Arceuus Library (CIS) lacks a matching ring object at the expected installed anchor. New inbound codes with missing quest prerequisites remain unavailable. The existing Newscape staff requirement continues because Fairytale II and the relevant elite diary exemption have no implemented progression in this build. Modern ring Favourites opens guidance to the existing dial and travel log. The custom favourites list is unavailable. [Fairy-ring requirements and revision history](https://oldschool.runescape.wiki/w/Fairy_ring).

Candidate verification uses `content-tools/checks/WorldFairyRingChecks.java` with the frozen runtime35 cache and native server classes. Launch the compiled fixture through `kilim.tools.Kilim WorldFairyRingChecks <release>` so its stock player movement events run through the normal weaving loader. The fixture checks native floor clipping, return object identity, both eligible staves, blocked travel, dial and repeat actions, return paths, and save decoding. Physical phone and desktop interaction testing is pending.

The pinned route dataset and RuneLite world-map markers independently support the CIQ and DJR corrections. Newscape's native object layer provides the exact destination and floor evidence. [TSPS fairy-ring coordinates at 1ccb548b](https://github.com/RSPSApp/tsps/blob/1ccb548be725c70900b604968354dd3147b839ea/client/public/shortestpath/transports/fairy_rings.tsv), [RuneLite fairy-ring markers at 0fa3b848](https://github.com/runelite/runelite/blob/0fa3b8489e929a47410c7214ffcb710e67c7bb46/runelite-client/src/main/java/net/runelite/client/plugins/worldmap/FairyRingLocation.java).
