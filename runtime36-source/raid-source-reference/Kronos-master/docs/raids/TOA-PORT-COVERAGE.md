# Near Reality ToA native port coverage

Donor: `kurdowns/RSPS-NEAR-REALITY`, commit `f577de7d929513a46661d4cc7f4f036f87e0395c`, `near-reality-server-main/core-restricted/src/main/java/com/zenyte/game/content/tombsofamascut`. Original encounter author: Savions. The host remains native Kronos/Java8/Kilim with its existing protocol. No donor server process or Zenyte packets replace the working client/server.

This is a prepared **solo base/Entry raid beta**, not verified production ToA or invocation/party parity. Public deployment is not authorized at this checkpoint.

## Route and entry

`io.ruin.model.activities.raids.toa.port.TombsOfAmascutRaid` exposes `init`, `open(Player)`, `isInRaid(Player)`, `onLogin(Player)`, `missingAssets` and `leave(Player)`. Initialization and the Bosses entry are guarded by `newscape.raids.enabled`. Actual membership binds the existing `ModernGear` Fang ToA context. The complete route opens only when all twelve controllers and required cache/reward/supply assets pass verification. Isolated boss tests remain separate and never roll full-raid loot.

| Stage | Actual source region | Progression |
| --- | --- | --- |
| Main hall | 14160 | Choose any unfinished path; scoped native entrance actions. |
| Crondis / Zebak | 15698 / 15700 | Resourcefulness puzzle, then Zebak; return to hall. |
| Scabaras / Kephri | 14162 / 14164 | Isolation puzzles, then Kephri; return to hall. |
| Het / Akkha | 14674 / 14676 | Mirror/obelisk puzzle, then Akkha; return to hall. |
| Apmeken / Ba-ba | 15186 / 15188 | Baboon-wave room, then Ba-ba; return to hall. |
| Wardens | 15184 / 15696 | Both actual rooms and authored phases. Final terrain is copied source plane0 to active plane1, as in the donor. |
| Burial tomb | 14672 | Durable saved rewards and safe exit. |

All four path bosses must be completed before the lower level opens. Only that complete route followed by final Wardens completion may record raid rewards. Four puzzle adapters, Kephri and Wardens have their own companion coverage notes.

## Encounter mechanics

- **Zebak:** actual head/tail and split ranged/magic projectiles, delayed impacts, melee/bleeding,85/70/55/40% specials, jug/roar and wave sequences, real safe gaps, poison and boulder shields,25% enrage. Jugs use native combat/range with authentic5HP; distant target selection cannot instantly break one. Moving jugs use actual11736 visuals.
- **Ba-ba:** melee/prayer chip, shadow-slam warnings/footprint, rubble and thrown-rock cover, both66/33% boulder sequences, cracked/normal rolling boulders, ranged cracking, collision knockback and baboon adds.
- **Akkha:** three visual/combat styles and weaknesses, shadow vulnerabilities/quadrant pressure, four-symbol memory, movement trails, final melee stand with real/fake shadow swaps and unstable orbs. All188 orb spawn/direction rows match the pinned donor exactly.
- **Kephri:** native controller with shield/downed phases, swarms, scarab helpers, dung and source-adapted attacks; see separate notes.
- **Wardens:** obelisk, moving Wardens/core, flying/final room, original trap/orb/skull/floor-erosion patterns and helpers; see `WARDENS-PORT.md`.

All assets resolve through the scoped `NearRealityRaidAssets` adapter. Per-spawn combat statistics are detached donor copies, never shared definition replacements. Raid actors are rewardless for ordinary/six-system drops; raid loot has one explicit claim path.

## Actual helpful-spirit supplies

The real helpful spirit11694 offers one source-authored Life/Chaos/Power choice after two paths and after four paths. Native supply bag27314 stores up to28 physical units and supports selected/all withdrawals, carrying supplies back into the bag, and content inspection through existing native dialogues. It does not require a new client interface.

Source item groups are Nectar27315/17/19/21, silk27323/25, tears27327/29/31/33, scarab27335/37, adrenaline27339/41, salts27343/45 and ambrosia27347/49. These source IDs resolve to verified scoped aliases. Original native242 item definitions were preserved: genuine source inventory actions were restored only on additive unused-ID clones. Real actions are Drink, Apply, Crack and Crush as appropriate; no fake Drop-to-drink path exists.

Native effects reproduce the donor's consumables: nectar heals/overheals and drains combat levels; tears restore drained stats/prayer; ambrosia restores run energy, cures toxins and boosts HP/prayer; salts refresh combat boosts for800ticks every25; adrenaline halves existing native special costs for250ticks; silk heals5 every5ticks for100ticks; scarab restores8prayer every4ticks for36ticks. The cost hook runs after the existing host cost calculation and only in an owned active ToA run. Party-adjacent tears healing is irrelevant to this solo beta and is not advertised.

Supply items/bag carry an exact run-token attribute. They cannot be used by a different/finished run, and bank/trade guards remain active. Exit/login recovery removes tagged loans, not a player's existing equal-ID items or equipment. Stored supply contents and temporary timers end with the run; this beta does not resume a run after logout/restart. Authentic dose transformations retain loan attributes; final doses disappear rather than producing a fabricated item.

## Safety and saved rewards

Original native inventory, equipment and attributes remain in place. Only explicit run-tagged loan supplies are cleaned up. Native teleport permissions run before allocation. Maps have owned leases, MultiZone entries and scoped actions; retiring maps are released only after departure. Delayed damage rechecks participant/map ownership. Closing/resetting removes owned actors, objects and callbacks. Disconnect/restart recovery reuses persisted `content242BossReturn`.

Full-run deaths reset the current room, lose20% of points with a1000 minimum, retain completed paths and never delete gear. Isolated deaths leave safely. Actual participant damage earns room-capped20000/run-capped64000 points from a5000 start; outsider/nullified/healing hits do not count. Completion waits until the lethal hit's post-damage callback runs.

`Player.toaProgress` stores completion count, claim token and remaining earned items in the ordinary player save. `PlayerFile.saveDurably` is the commit barrier for completion and withdrawal. Partial claims retain leftovers; noteable plain rewards use verified native note pairs. Attributed rewards retain attributes and are not converted to notes. A definitive save failure restores exact inventory slots/attributes and progress. A timeout never rolls back a possibly committed write: the account remains quarantined until its exact future resolves; an unknown outcome needs operator verification. Outstanding rewards survive logout/restart and are offered from the Bosses entry.

## Explicit reward policy

The real donor loot implementation is `encounter/RewardEncounter.java`; its party-level obsolete TODO does not mean rewards are absent. Native loot reuses all26 common item IDs/divisors, the source small/hard-purple branches/weights, thread1/15, jewel1/20 and elite clue1/26. No additional host drop-rate bonus is applied.

The full route is explicitly **raid level0** because invocation selection/raid-level scaling is not ported. Donor low-level1/50 unique eligibility gates therefore remain active; do not advertise level150/expert rates. The donor2x unique modifier is default, configurable with `-Dnewscape.toa.uniqueMultiplier=2.0`. Expert kits/remnants/phalanx and a pet roll are not fabricated for this route; the pinned method has no pet roll to transplant.

Two deliberate numerical corrections preserve fractional probability and remove the donor `random(99) <= 0` extra chance when calculated chance is zero. Inputs are bounded and probability caps at55%. Hard-purple weights remain the donor3/2/2/2/2 rather than a silently substituted official table.

## Known limits and testing

- Solo only: no party matchmaking/shared progression, invocations, path levels or expert cosmetics.
- Modern donor HUDs/tints/sounds/custom hit bars/stopwatch bars are not recreated through new client packets. Existing desktop/Android controls remain.
- Zebak poison spread/swimming/knockback are native collision adaptations; blood/upset-stomach invocations stay off. Actors use queued native movement.
- Ba-ba sarcophagus interactions and exact minion tactics are reduced. Both boulder thresholds execute instead of retaining the donor's debug first-boulder skip.
- Akkha elemental/debuff/status extras and exact sub-tick blast animation wave are not ported; native quadrant warnings/hits exist.
- Full human playthrough, physical Android rendering/touch, collision/animation edge cases, inventory during phases, and injected durable-save failures remain beta verification tasks. Compilation and numerical checks do not establish those behaviors.

Verification: full native Gradle `:kronos-server:classes` passed on JDK8 with all12 modules, then passed again with supplies/shared hooks in44seconds using768MB. Final all-ToA focused compilation passed after the small retention/cleanup/puzzle fixes; source/class SHA256 receipts identify the exact freeze. `ZebakRulesChecks` passed54 cases; `ToaRewardMathChecks` passed72,194 cases; `VerifyToaSourceTables.js` matched all188 orb vectors and26 common reward pairs exactly; `ToaSavedRewardsChecks` passed12 actual native Gson/attribute/custom-ID/deep-copy checks. Logs are under `D:/Newscape-Raids-Work/near-reality-20261115-0240/{build-logs,toa-compile}`. These checks did not start a public world or mutate accounts. Parent-owned private/cache/model receipts are separate.

Official numerical reference: https://secure.runescape.com/m=news/tombs-of-amascut-drop-mechanics--osmumtens-fang?oldschool=1 (28October2022). Later client formulas have different breakpoints and were not silently mixed. Supplementary pinned BSD-2-Clause client math/puzzle references and SHA receipts are at `D:/Newscape-Raids-Work/research/toa-primary`; no code from that repository was copied into these encounter/reward files.
