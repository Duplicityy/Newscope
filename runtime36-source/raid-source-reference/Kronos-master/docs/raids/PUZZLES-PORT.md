# Tombs path puzzles: native implementation

Source: `kurdowns/RSPS-NEAR-REALITY` at `f577de7d929513a46661d4cc7f4f036f87e0395c`.
Implementation: `RootToaPuzzles.java`; authored tables: `RootToaPuzzleData.java`.
All interactions and object changes belong to the allocated raid instance. No shared world scenery or existing item definition is overwritten.

## Crondis

- Actual water-container, waterfall, palm, crocodile, statue, acid and spear assets.
- Two hundred water completes the solo palm. Filling gives100 water; hazards spill half. Four waterfalls have the donor128-tick refill delay.
- Four authored spear columns, both sides, all ten donor delay values per column, with native object animations.
- All four acid origins emit two or three random lanes every five ticks, with travelling warnings and delayed damage.
- Crocodiles pursue nearby water carriers or drain the palm. Native protection prayer and prayer drain are used.
- The container is issued when filling a waterfall instead of leaving an unowned, transferable supply on the floor. Check, Empty and Destroy operate on that run's tagged container.

## Scabaras

- Four randomized donor quadrants and prerequisite gates: ring lights, addition tiles, hit-order obelisks, and five-step visual memory.
- West-side lobby entry uses the authored normal spawn. The two actual entrance barriers retain collision and expose native Pass/Quick-Pass traversal; their scoped handlers approach the exact bank and perform two native steps across the barrier. All four progression fires remain solid until their own puzzle completes.
- The actual Passage and Platform objects use the donor north/south endpoints, crawl/jump animations and durations. Traversal is confined to the owned instance and does not mark a puzzle complete.
- Exact25 addition tiles, eight light offsets, nine memory offsets and six obelisk positions.
- Normal light steps toggle the selected light and its two neighbours. The source's single-tile flip costs20 HP.
- Obelisks use the native **Hit** action with normal combat range and ownership checks. Order validation/transformation occurs when the real hit is scheduled; the module completes when a queued hit actually arrives. The five-tick sequence deadline and reset/rockfall remain, and a fully scheduled fifth projectile can finish its flight.
- The owned obelisk launch clears only that attack's delay. Its native weapon caller keeps a valid target through post-hit graphics and processing, then a scoped finish hook consumes the launch marker and stops repeat combat. Exact hit/target identity preserves an accepted zero-damage powered-staff projectile through the host's miss policy; ordinary magic misses keep their existing behavior.
- Two randomized symbol grids complete the final match puzzle. The three donor solo pairs begin revealed; matching stays locked until the four preliminary puzzles are complete.
- Ordinary native running checks every crossed puzzle tile, including intermediate steps.

## Het

- All five authored wall/mirror layouts and42 orb origin/direction pairs are extracted from the pinned source, without substitute scenery.
- Fixed and movable mirrors retain their source options. Movable mirrors support independent pickup, native inventory Place, rotation and pushing. Picking up several mirrors preserves every piece.
- Cardinal beams respect mirror faces, walls, the protected central seal and shield statue. A bounded state/loop guard prevents infinite beams.
- Striking the shield exposes the seal for24 ticks; players mine it using their real eligible pickaxe. Native damage reduces the source130 HP, awards host-scaled Mining XP and contributes real raid points.
- Appearance changes preserve live combat state. Moving hazards use actual NPC11708 and source paths.

## Apmeken

- All11 authored baboon waves and six spawn positions, including the source's carry-over thresholds and final all-enemies-dead gate.
- Melee/ranged/magic variants, stronger variants, shaman thralls, volatile explosions and cursed-baboon pools use their actual definitions and combat metadata.
- Every existing hammer/potion crate is bound within the instance; the original map has three of each.
- Solo Apmeken's Sight alternates vent and roof jobs with a30-tick response window and22-damage failure. Potions and hammers are real tagged supplies.
- The donor's explicitly disabled multiplayer corruption effect is not silently enabled.

## Integrity and checks

- Puzzle equipment is allocated through22 append-only raid supply aliases in cache candidate NR05. Existing IDs/payloads and the frozen Dungeoneering registry remain unchanged.
- Item IDs32874 and above exercise the existing unsigned inventory packet correction; no protocol or packet transformation was changed.
- Bank/trade guards cover both raids. Leaving or recovering after login removes only the run's tagged loans.
- Readiness checks cover definitions, model payloads, active wall variants, native inventory actions, and required combat metadata.
- `Extract-RaidPuzzleTables.py` records source/output hashes. `Check-RaidPuzzleTables.py` passed636 offline assertions, including a valid beam route for each layout and a solution for all256 ring-light states. These are rule/geometry checks, not a claim that a player completed the entire raid in game.
- `ScabarasTraversalChecks.java` passed859 source-bound checks with actual native Region/Tile/ClipUtils and an initialized-world clipping capture: all24 quadrant permutations retain reachable prerequisite puzzles across all four legitimate fire-gate progression stages. The upstream path selector skips the puzzle; its challenge crystal coordinate is an east-side rejoin point, so that coordinate alone does not establish a working normal puzzle route.
- `ScabarasPillarLaunchChecks.java` passed77 native checks, including actual NPCActionHandler dispatch, Entity.hit/Entity.processHits, ordinary and scoped attack delays, consumed identity markers, all five public PlayerCombat.attack trident casts and their real queued impact, and an unchanged ordinary magic miss. Transport/player fixtures isolate the test from accounts and live gameplay; a full native raid clear remains separate evidence.
- Desktop and Android native readers decode the actual appended models, definitions and supply action slots. Physical Android combat and group play are separate testing requirements.

This ToA route is a solo base/Entry raid. Invocations, multiplayer ToA scaling, expert cosmetics and full official UI parity are not claimed. See the ToA and Wardens coverage reports for the remaining encounter differences.
