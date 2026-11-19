# Charter travel

Players can open the booking menu with **Charter** or **Talk-to** on eligible Trader Crewmembers. Trader Stan also accepts bookings at Port Sarim. The menu displays each fare before the player confirms. The activated ring of Charos halves fares while equipped. A ticket uses the existing timed boat movement and native teleport restrictions. The game checks the fare, balance, player location, and destination collision again before taking payment.

| Port | Newscape landing | Crew |
| --- | --- | --- |
| Port Sarim | 3038, 3192 | Existing |
| Brimhaven | 2760, 3238 | Existing |
| Catherby | 2792, 3414 | Existing |
| Musa Point | 2954, 3158 | Existing |
| Port Khazard | 2674, 3144 | Existing |
| Corsair Cove | 2589, 2851 | Added at 2586, 2850 |
| Port Piscarilius | 1824, 3691 | Added at 1825, 3690 |
| Land's End | 1504, 3399 | Added at 1505, 3400 |

Land's End and Port Piscarilius keep a shared first-visit unlock. Newscape records the visit when a player reaches either existing Kourend dock and speaks to a charter crew member. The saved native visit bit also recognizes accounts that already have the unlock. The older installed docks have different coordinates from the current OSRS reference. Both landings were resolved using the installed dock geometry.

The fare table includes 48 offered directions. Missing links between Port Sarim and Musa Point, between Port Sarim and either Kourend port, and between the two Kourend ports are deliberate gaps in the reference fare table. The fixed Trans-Karamja passenger service and all existing passenger scripts retain their original behavior.

The owner excluded destinations and fare discounts that require quests or Sailing. This leaves quest-dependent ports, Cabin Fever pricing, and Sailing-era passenger destinations outside this patch. The eligible ports use the existing Newscape dialogue interface on Android and Windows.

## Verification

The isolated Windows server started and registered all eight ports and 12 crew placements. Native tests passed for all 48 offered directions, every fare, the activated ring, the saved Kourend unlock, insufficient funds, cancellation, a changed quote, a blocked dock, and refusal by the existing travel restrictions. Physical phone and Windows client play were outside these server checks.

The detached check loads the installed cache and static crew records, compares every fare to the pinned reference, and uses the native route finder, NPC action handler, booking choices, and locked movement. It checks Kourend save compatibility and rejects stale, blocked, and underfunded tickets. Run `content-tools/checks/CharterTravelChecks.java` with the existing Java 8 toolchain and `kilim.tools.Kilim`. Pass the frozen release directory, staged crew JSON, and pinned charter TSV in that order. Keep player data and fixture accounts isolated.

## References

- Fares, offered legs, and canonical dock anchors were checked against the pinned route data. The implementation uses original Java and factual route and fare values. [Shortest Path charter data](https://github.com/Skretzo/shortest-path/blob/a7dd4a27f7909b30ea710a6d4a2d5b6706a13865/src/main/resources/transports/charter_ships.tsv) · [Repository license](https://github.com/Skretzo/shortest-path/blob/a7dd4a27f7909b30ea710a6d4a2d5b6706a13865/LICENSE)
- The public fare and access reference corroborates the missing routes, the first-visit rule, and the activated ring. RuneLite identifies the persisted Kourend visit bit. [Charter ships](https://oldschool.runescape.wiki/w/Charter_ship) · [RuneLite varbit definitions](https://github.com/runelite/runelite/blob/0fa3b8489e929a47410c7214ffcb710e67c7bb46/runelite-api/src/main/java/net/runelite/api/gameval/VarbitID.java)
