# Theatre of Blood native adapter

Prepared as source/drop-in work only. No public deployment or in-game verification is claimed.

## Source and attribution

Adapted from `kurdowns/RSPS-NEAR-REALITY`, pinned commit `f577de7d929513a46661d4cc7f4f036f87e0395c`, primarily `plugins/excluded/theatreofblood/src/main/kotlin/com/zenyte/game/content/theatreofblood` (original encounter authors Jire and Tommeh; reward generator Stan van der Bend). Original Kotlin engine classes are not mixed into Kronos. The native adapter uses Java8, Kilim, NPC.installCombat, native Item/ItemContainer, existing DynamicMap chunks, native projectile/hit paths and existing OptionScroll actions. The donor's core `SpecialAttack.java` and `DawnbringerCombat.java` supply the Dawnbringer behavior. Keep upstream author notices and the pinned donor with the handoff.

Additional public source was inspected before implementation. OpenRune/c1e2007 ToB and Xeros218 both spawn the Nylocas boss directly and do not supply the missing original wave implementation. Their code was not incorporated. Detailed negative-source findings are in D:/Newscape-Raids-Work/tob-research/TOB-SOURCE-AUDIT.md.

## Implemented native core

- One-to-five-player parties, join requests accepted by the leader, solo start, owned dynamic room leases and all six boss-room progression plus the reward vault.
- The actual nine original ToB cache regions are validated by NearRealityRaidAssets. Every main/helper NPC uses a detached donor Info copy; no shared NPC combat definition or cache asset is overwritten.
- Maiden normal storms/blood splats,70/50/30percent transformations and walking/healing Matomenos; Bloat pillar-circuit, LOS flies, initial35tick shutdown,33tick down/stomp cycle, later falling flesh and direction reversals; Nylocas three styles, correct-style damage/reflection and10tick transforms.
- Sotetseg melee/magic/ranged ricochets,11th ranged-cycle bomb sharing,66/33percent maze transitions, bounded donor14x15 monotone path and wrong-tile damage. The native adaptation exposes that path to all players in the main room and waits for every survivor to cross. It does not claim the original selected-runner shadow-realm presentation.
- Xarpus party-scaled exhumed-healing phase, real exhumed/acid-pool objects,25percent screech and quadrant counter phase.
- Verzik shield/P2/P3 sequence with explicit authored VerzikConfigs HP1500/3250/3250 and combat-stat overrides. Donor JSON1HP placeholders are not used as live phase HP. Supporting pillars, normal lamp/electric/blood attacks,35percent red crabs/shield, poison-sensitive purple crab, correct-style chase crabs, web/dot/meteor passive rotation and20percent tornadoes use the supplied script rules and native assets.
- Supporting-pillar8379HP350 and unnamed tornado8386HP100/tick1 are extracted from explicit `VerzikConfigs.kt` script definitions with their original zero-stat defaults. The genuine unnamed tornado uses an explicit verified native asset binding; its cached name/payload are preserved.
- Red Matomenos8385 uses the explicit VerzikConfigs combat levels100 and poison/venom immunity on its detached per-run combat definition. Its200baseHP follows the donor TheatreNPC roster scaling:150for one-to-three players,175for four,200for five.
- Dawnbringer is obtained explicitly through the native raid menu, without replacing existing equipment. Missing host equipment metadata is filled from the pinned VerzikConfigs item block using a detached powered-staff type, native Wield action and existing item/model IDs. Its normal cast uses the donor's three-tick timing, six/eight-tile range, current-Magic damage formula and capped equipment magic-damage bonus through native accuracy and hit handling. Its original35percent75..150 special remains a separate energy-consuming attack. Both casts are restricted to this run's active P1 shield; a weak hit-identity marker exempts only genuine Dawnbringer hits from the shield cap. Loaned items carry the run UUID and only exact loan attributes are removed during phase2, exit/logout or crash recovery.
- Original normal donor reward weights4/2/2/2/2/2/1; configured23base chance with the donor inclusive roll and contribution scaling. Eight initial points,three participation points per room,four-point death penalty and room/Verzik phase MVP rewards feed the selection. Three common rolls use the original table and1.4normal multiplier. Rewards remain pending in the native player save until explicitly claimed.

## Safety and lifecycle

`TheatreOfBloodRaid.init/open/isInRaid/onLogin` are the root-owned integration hooks. No events start during static class initialization. Feature-disabled login still recovers a saved run marker. The enabled self-pulse cleans idle offline parties and join requests, owns every delayed action by room generation and closes a failed raid safely.

No inventory or equipment preset is installed. Native Item.copy preserves all attributes. Lethal incoming hits use an owned downed/spectator guard, with item-safe party failure. Previous player hit/teleport/logout listeners are restored only if the adapter still owns its installed listener. Existing generic listeners are retained.

Player entry markers are durably saved before map movement. An uncertain save quarantines the account instead of guessing whether a retry is safe. Pending reward delivery snapshots every native inventory slot with attributes, restores it on pre-save insertion failure, clears pending items together with the actual added inventory in the durable save, and quarantines uncertain saves to prevent repeated claims.

Room actors are stopped immediately on transition, but their DynamicMap lease is retired rather than destroyed while a player is still registered in any old region. The global enabled pulse releases a retired map only after all its Region.players lists are empty. Root-owned trade/bank guards prevent hiding a loaned raid item outside the instance before cleanup.

## Important remaining differences

This is a playable normal-mode core adaptation, not verified full OSRS parity. The supplied donor's Nylo room directly spawns Vasilias; its preceding small-Nylo waves/pillar-defense encounter is absent and has not been fabricated. Hard/entry/bypass modes, supply-chest economy, original scoreboard/party-widget presentation, combat achievements and cosmetic kits are not enabled by this adapter.

The main-room visible Sotetseg maze is an explicit adaptation; selected-runner shadow-realm routing/storm and exact original split presentation are not complete. Some secondary visual/animation details and edge behavior remain for testing: Maiden wandering blood-spawn trails/prayer drain, Bloat exact meat polygon and forced stun, Xarpus secondary poison splashes/central rocks, Verzik exact forced movements/web freezing and line-interception electric routing. Dawnbringer's normal cast and equipment repair still require the full native Theatre run before a gameplay completion claim. Rare tertiary pet/clue rewards are not included in the initial native reward adapter. Do not advertise these omitted behaviors as implemented.

## Verification

Focused native Java8 compilation passed. `content-tools/raids/TheatreOfflineChecks.java` passed200338checks:5000generated maze paths were bounded, cardinally connected and entrance-to-exit complete; actual native Item.copy did not alias attributes; Gson@Expose saved/reloaded high unsigned item IDs, exact attributes, pending rewards and claim UUID; feature-disabled init created no party or run. Logs are under D:/Newscape-Raids-Work/near-reality-20261115-0240/tob-checks.

The final private03 native Windows smoke used the currently selected full-cache client and an isolated NR05 managed cache/profile. Normal login, the real Bosses teleporter menu and solo-party entry, a populated Maiden room, native walking toward Maiden, one matching server/client8360NPC, actual incoming combat damage, normal raid exit and exact inventory/equipment slot/amount/attribute fingerprints passed. After the normal combat logout delay, guarded native `Player.attemptLogout` produced the actual client `LOGIN_SCREEN` and closed its JVM. The screenshot comes from a completed native DrawManager frame; Maiden's large model is partly cropped by the entry camera. Evidence: `D:/Newscape-Raids-Work/near-reality-20261115-0240/raid-client-smoke/TOB-NATIVE-SMOKE.json` and its named PNG/diagnostic files.

Earlier harness failures used an empty legacy profile cache or a directory outside the existing managed-cache guard. Those attempts are retained separately and are not represented as successful raid tests. The final successful test obeyed the guard without changing it.

Root coordinates the final shared compilation, initialized asset preflight and drop-in pack validation. No full boss kill, complete raid, reward claim or physical Android gameplay has been tested by this subtask. Public services were not started.
