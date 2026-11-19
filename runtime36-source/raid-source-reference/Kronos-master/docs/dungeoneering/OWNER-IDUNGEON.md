# Owner iDungeon

Open `::idungopen` for the native configuration window. It uses the existing client widgets on Windows and Android and needs no protocol or cache update. Only the exact OWNER group can open or run it.

The configuration has floor range, complexity, small/medium/large size, repeat and prestige controls, early-floor rushing, bonus-room exploration, preferred combat style, mechanic-driven style changes, protection prayers, food threshold, supply collection, equipment upgrades, cooking, and death/time/floor/progress limits. Start and Stop are explicit. Opening configuration stops automation. Saved preferences never save an active controller or resume it on login.

Commands:

- `::idung start` starts from a safe normal location or an existing solo dungeon.
- `::idung stop` returns control while leaving the dungeon available for manual play.
- `::idung status` reports the current step and completed floor count.
- `::idung leave` uses the ordinary abandon confirmation and equipment restoration.

The controller creates/selects a normal solo party, respects the native unlocked floor list, enters through the existing durable equipment escrow, gathers shared keys and native supplies, clears guardians, solves the ten implemented room puzzles, and uses normal actions for the current boss family. Rewards and prestige are claimed through native menus. The bot does not grant items, edit stats, reduce boss health, skip puzzle state, install fixtures, discard inventory, or replace the player's original equipment outside the dungeon.

Bind preferred equipment through the normal dungeon menu. A usable ranged weapon with compatible arrows is particularly useful for encounter mechanics. The bot collects supplies and can buy an available basic bow/ammunition from the smuggler with rusty coins. Magic requires owned dungeon runes; it uses the strongest affordable native damage spell and can craft basic runes near an existing altar. Fishing/cooking uses available native tools, resources and fixtures. Unsupported/missing supplies, unreachable interactions or stalled progress stop the controller for manual control. Low-complexity floors can lack supplies needed by a particular boss; the bot does not fabricate them.

Manual movement, inventory, object/NPC actions, item/spell interactions, interface changes, and other manual commands stop automation before the original action is dispatched. Chat, camera movement and status commands remain usable. Logout, permission loss, party changes, activity changes, death limits and timeouts also stop it. All existing dungeon equipment restoration and account saves remain native.

## Validation and scope

Compiled with the existing Java 8 runtime and overlay classpath. Offline checks exercise the native flip-tile effects, the joint statue-bank planner, saved-preference round trips, bounds, snapshot isolation, and manual-control packet-family classification. No game server, Player or gameplay controller is started by these checks. Gameplay acceptance testing is reserved for the owner; a full 60-floor/boss playthrough is not claimed.

This is a fresh server-native implementation informed by the retained iDungeon Pro source at Stevemagegod/My-Favorite-Runescape-Scripts commit `885e17b0d197023fe1719baa2117882936c336e2`, `src/idungeonPro.java`. The obsolete Rarebot runtime, its authentication/update hooks, and its external networking are not executed or bundled. Existing Darkan dungeon content attribution and licenses remain authoritative for that content.
