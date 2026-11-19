# Boss and quest placement audit

The staged build has 10,644 configured static NPC placements across 545 map squares. These include ordinary world NPCs and 3,225 records from the existing revision 242 backport. Inherited scripts create additional NPCs as required. The [population audit](NPC_STATUS.md) lists unresolved duplicate-looking rows and placements with incomplete combat evidence.

## Boss entry points

The current boss progression registry names 30 families, including three enabled revision 242 encounter modules. The cache definitions and configured spawn files provide 31 matching static rows for 23 of those families. This is a check of placement records, with separate evidence needed for playable fights, access requirements, loot, and multiplayer isolation.

| Remaining family | Placement evidence |
| --- | --- |
| Kraken | The Kraken Cove file places NPC 496, the whirlpool. The combat script handles its transition to NPC 494. |
| Skotizo | The Catacombs altar calls the encounter script, which creates Skotizo in a dynamic map. |
| Scurrius, Amoxliatl, Phantom Muspah | The existing New lands menu opens the inherited private encounter scripts, which spawn the bosses after entry. |
| Obor | A combat definition and kill counter exist. The installed spawn data and server sources supplied no verified placement or entry handler. |
| Deranged Archaeologist | A kill-counter entry and cache definition exist. The installed spawn data and server sources supplied no verified placement or entry handler. |

The registry is a named subset of the world. ToB and ToA use their own encounter registries. Bosses outside these sources need individual coverage checks.

## Quests

The server source audit found **zero complete OSRS quest chains**. The quest tab opens the server noticeboard, and the existing quest-point config supplies a default value. NPC models, quest-named objects, and scripted activities such as Mage Arena are available as separate world content. A complete quest requires an entry, advancing stages, player-specific saved progress, and completion and reward handling.
