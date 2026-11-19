# World development verification

| Check | Result |
| --- | --- |
| Map decoder and population reconciler | Ten focused Python tests passed. The map report covers 3,183 installed square pairs. The two spawn files remove exactly 56 copied records and reconcile idempotently. |
| Minigame Teleporter | The detached JDK 8 test opened the native minigame interface, selected OSRS World, checked the PvP-origin restriction, completed the native teleport to Lumbridge, and returned through the regular Home spell. It used a detached player with the installed cache. |
| Resource bindings | Eighty-eight detached JDK 8 checks passed against installed definitions and native skill initializers. The five tested resource class files match the development overlay byte-for-byte. |
| Home and custom content | All 24 copied cache files match the owner build. The original home map, spawn data, and overrides retain their baseline hashes. None of the modified resource IDs intersect the protected home layouts or the native Dungeoneering object registries. |
| Isolated server boot | Development game and cache completed their protocol handshakes on localhost. Startup took 55.82 seconds. Host memory rose from 50.80% to a sampled peak of 76.22%. Both test processes then shut down normally. |
| Loaded native content | The game registered 13 New lands destinations, 43 authored traversal bindings, 175 loot tables, and 33 shop bindings. It loaded 1,038 Dungeoneering room variants and the Daemonheim and Arceuus lobby data. |

The measured startup ran with zero player sessions. The host memory measurements include the operating system and background processes. They do not measure multiplayer capacity or sustained tick latency.

Earlier detached tests exposed missing boot fixture dependencies for equipment definitions and the wilderness obelisk objects. Those were loaded in the passing test. Initial development boots exposed a missing fixture registry, an omitted adventurer settings file, and excessive JVM working-set growth at the default GC settings. The passing boot uses its own synthetic account, an isolated adventurer settings file, and the owner launcher's GC tuning. The inherited Hollow Depths build-fingerprint gate remains active.

The [build and test commands](ARCHITECTURE.md#build-and-test) reproduce the development run. Receipts are in the development stage's `BOOT-CHECK.json` and `WORLD242-DEV.json`. The desktop or Android scene, multiplayer visibility, individual combat and quest completions, and sustained load still need client or load tests. Use the [documented skill test tiles](GAMEPLAY.md) for the next playthrough.

## Landscape repair follow-up

`development-04-landscapes` passed 13 native cache reads and region builds using the proposed keys. The test matched each decoded landscape's SHA-256 and counted 35,826 native object placements. The staged key manifest differs in exactly 13 region rows, and all 24 cache files match the earlier development stage. The isolated game and cache again passed their handshakes and shut down normally. Startup took 58.19 seconds with zero player sessions and a sampled host memory peak of 76.03%.

The stage contains `LANDSCAPE-NATIVE.log`, `LANDSCAPE-SCOPE.json`, and `BOOT-CHECK.json` with those checks. Scene display and movement through the recovered squares still require a client test.

## World research build

The isolated `development-06-world-research` package compiled 15 Java sources into 30 overlay classes. It uses the installed owner cache and a generated regular account. The package and fixture saves are separate from the owner account and runtime.

| Check | Result |
| --- | --- |
| Python regression suite | 33 passed across map decoding, typed world data, duplicate reconciliation and the PvE fixes. |
| Quest state and save compatibility | 212 assertions passed under JDK 8. Tests cover state transitions, stage replay, missing data and reloads. |
| Native area and quest fixture | Passed minigame interface selection, PvP guard, Lumbridge arrival, original River Watch progression, stale choice rejection, an incorrect diagnosis, missing hammer, full inventory, committed payout, reload and native Home return. |
| Native shop fixture | All four real shop records opened through their staged handlers. The fixture bought an item in each and checked stock, item delivery and the right currency debit. |
| Native calf fixture | Both variants spawned with the authored combat profile. Both world configurations produced the three guaranteed drops. |
| Native landscape and resource checks | All 13 scenery builds passed with 35,826 native placements. All 88 resource checks passed. |
| Cache, home and compatibility | All 24 cache files match the owner build. Seven copied data files differ, including the 13 targeted region-key rows. Compiled Player methods match the existing owner class, with one added nullable quest field. |
| Full candidate boot | The full startup check refused to launch a JVM because host memory use exceeded the existing 60% preflight limit. Earlier builds passed the full startup test as recorded above. |

The detached checks execute game server code against the staged cache and use an isolated in-memory network channel. They cover action and save behavior. Desktop, Android and multiplayer sessions, complete world coverage, field combat animations and sustained performance still need separate acceptance runs. Follow the [quest playthrough](QUESTS.md#playing-river-watch) and [combat and shop checks](PVE_REPAIR.md#verification) on a staged server after a full boot passes.
