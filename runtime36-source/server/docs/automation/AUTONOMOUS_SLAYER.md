# Owner Slayer

Open `::bot slayer`, choose a Slayer master, review the settings, and select **Start Slayer**. The same native menu works on desktop and Android. On Android, starting Slayer in the automation panel opens this menu if no master or assignment is set. Use **Pause**, **Resume**, or **Stop** from the Slayer menu. A reconnect requires a fresh start and reads the saved task and preferences.

The controller gets assignments exclusively at the main Home. Nieve and Turael already serve there. Mazchna, Vannaka, Chaeldar, and Krystilia have additional Home spawns, and their original world spawns remain in place. Konar gains a Home spawn for her existing Newscape assignment interaction. These masters share Newscape's existing tier pools. The installed build has no supported assignment interaction for Duradel, Spria, or Steve. The controller uses the normal Home spell and the main Home destination to reach the master without changing the player's personal Home preference.

The defaults are Easy assignments, melee with an equipped ranged fallback, food at 60% health, restocking below two food, a target of 12 food after banking, owned-drop pickup, free travel, and a preference for banking at the player's configured Home. The script uses existing items and bank stock. It pauses for manual PIN entry, missing items, unsupported mechanics, or an unavailable route. Selecting a different master applies to the next task. A single skill can start at level 99. Account progression still advances to the selected level.

The server controller reads the same Slayer task matcher and completion counter as ordinary gameplay. It uses native teleports, collision and object actions, combat, item handling, and bank withdrawals. Its assignment revision invalidates a previous plan even when the next assignment is the same monster type. The live game continues to own task counts and rewards. The general automation controller retains its existing private-world owner permissions.

The normal Home Teleport spell accepts combat on the private world for players holding the exact OWNER role, including owners whose crown is hidden. Tele Block, Wilderness level rules, activity restrictions, and other native teleport checks still apply.

## Slayer settings on Android

In owner client v43, open **Automation → Combat and Slayer → Slayer**. Choose a Slayer master and Easy, Medium or Hard from the dropdowns. **Save Slayer settings** remembers both choices. **Start** applies the selections and begins the Slayer loop. If the master field is empty, Start opens the list and asks you to choose one. Desktop players can use the native `::bot slayer` menu. Native combat requirements and the introductory Easy assignment still apply.

The controller tops up to 16 unnoted prayer-potion doses at a normal verified bank. It prefers four-dose bottles and uses the native Drink action when prayer is low during eligible fights. It uses existing stock and leaves room for loot. A required PIN is handled by the standard bank interface.

## Bound owner Blood Fury

Item 53200 is a separate, non-tradeable definition using item 24780's existing models and equipment stats. The private deposit effect requires the Bound owner account's persistent ID and name and the issued amulet in the neck slot. The normal 24780 definition keeps its original behavior.

The deposit hook handles personal monster loot delivered by native NPC deaths and their synchronous reward callbacks. Allocation, drop rates and reward consumption still run first. A full or locked bank falls through to the native delivery path. Raid reward chests and separate claims use their own normal flows.

The runtime35 staging script adds one cache definition, preserves existing definition bytes and banks Bound owner former neck item before equipping the amulet. The stopped runtime34 and its save remain the rollback baseline. The owner world loader must retain both the signed class manifest and the matching cache paths.

## Coverage and limits

The coverage TSV lists the safe task pools found in the v42 spawn data, grouped by native tier. Each entry shows qualifying NPC variants, native spawn counts, the route witness when one was found, and the restricted account pool decision. Level, unlock, equipment, survival, and block requirements still apply per character.

The native assignment picker restricts `Bound owner` and `configured owner` to task types supported by the script. This applies to new tasks from the bot and the normal master dialogue. Existing tasks, task points, block preferences, and other accounts' native pools retain their current behavior. The restricted pool requires a carried brass key for Earth Warriors and permanent and current level 80 Agility for the Black Dragons route. God Wars options require the native entrance rope and equipped protection for all four factions. The script still checks the character's actual stock and equipment before travelling.

The route catalogue includes the native Stronghold gates, the Kalphite task cave, the Slayer Tower double doors and stairs, the Fremennik dungeon corridors, the Ice Mountain approach, the Edgeville brass-key entrance, the Karuulm rock crossings and stairs, the Kraken Cove entrance, and the Ancient Cavern Mithril stairs. Some routes depend on a specific setup. Karuulm needs heat-protective boots, dragons with dragonfire need full native protection, and Cave Kraken and Mithril Dragons need a dry ranged firing position. Special bosses, Wilderness tasks, autocast, charged ranged weapons, automatic purchases and skips, and unhandled boss or monster hazards remain outside the script's coverage.

Coverage includes 23 of 23 Easy, 36 of 36 Medium, and 34 of 38 Hard entries in the v42 safe pools. These counts include conditional routes where the character meets the checks described above. Both restricted accounts leave the following Hard entries out of their new assignments.

| Assignment | Remaining blocker |
| --- | --- |
| Adamant Dragons | Native poison splashes persist and remain damaging even with ordinary antipoison. |
| Hydras | The controller lacks avoidance for the native persistent poison pools. |
| Red Dragons | The Brimhaven audit found no walkable approach or legal firing line to the lower-risk spawns. The reachable Brutal Red in the Catacombs hits 26, above the controller's 24 threshold at 99 Hitpoints. |
| Rune Dragons | Moving sparks, native self-healing, and a maximum hit of 38 are outside the supported combat profile. |

## Verification

Run the repository's `OwnerSlayerCoverage` check using the installed v42 cache and server data, the assembled candidate classes, and the normal server dependencies. Pass the cache directory, data directory, and output TSV path as arguments. Use `-Dnewscape.slayer.homeSpawns=PATH` to supply the staged Home spawn file when the base data directory predates this update. The optional `--loop` argument runs a disposable embedded-player fixture through native movement, tasks, equipment, combat, and banking. The fixture seeds an existing three-kill Banshee assignment and uses the restricted native picker for a fixture named `Bound owner` for subsequent tasks. It opens no network listeners and writes no player saves.

The final candidate passed 195 server fixture assertions and 1,722 movement assertions. A detached player acquired a native task from each of the seven Home masters. The combat fixture completed Banshees, Rockslugs, and Hill Giants, then obtained another assignment from Nieve. It restocked during Rockslugs and continued the same assignment through the native salt-finishing mechanic. Separate controller checks reached and started a qualifying native attack on ten additional Medium and Hard scenarios, including Earth Warriors, Drakes, Cave Kraken, Mithril Dragons, Spiritual Creatures, and Black Dragons.

Deploy the candidate class overlay with its supplemental `data-overlay/npcs/spawns/slayer_masters_home.json` file. The native v42 Slayer fixture and deployed Home master data are the baseline for this update. A desktop or Android client-connected playthrough remains unverified.
