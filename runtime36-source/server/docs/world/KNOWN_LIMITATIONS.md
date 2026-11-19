# Remaining world coverage

The current work is a development integration for Newscape's installed world. It establishes the map and NPC baselines, adds the world entry, and repairs specific gameplay paths. The shipped legacy and custom mechanics remain the starting point for further work.

| Area | Status and next evidence needed |
| --- | --- |
| Geography | One revision 242 reference map pair is absent. Nineteen installed landscapes still require valid keys or compatible archive data after 13 verified repairs. The complete published OpenRS2 catalogue yielded no further matches. The [landscape research](LANDSCAPE_RESEARCH.md) records the remaining dependencies. |
| NPCs | The two reconciled dungeon files have verified counterparts. Review the 134 other repeated NPC and tile identities individually. The staged audit flags 315 Attack-labelled placements without static combat records for script and encounter review. The calf repair covers 18 previously flagged placements. |
| Combat and loot | The population report records static combat and loot coverage. Each unresolved NPC still needs verified attack handling, eligibility, respawn, and reward behavior before it can be marked complete. |
| Shops and services | The existing world registry loads its shop bindings. Eighteen raw extended options require runtime review. The Fishing Guild, Piscatoris, Port Piscarilius and Clan Wars shop changes have focused checks. Other shops and services still need independent coverage. |
| Skilling | The verified aliases connect mining, woodcutting, and cooking to the existing engines. Further resource families and locations require compatible definitions and action tests. |
| Transportation | The native Lumbridge arrival and Home return passed detached tests. A native survey verified 43 authored bindings and identified 12 actionless definitions. Individual travel requirements and region crossings need a client pass. |
| Quests, bosses, activities | The source audit found zero complete OSRS quest chains. [River Watch](QUESTS.md) is a separate original Newscape quest verified in a detached playthrough. The named boss registry has 23 families with static boss records and five with compiled spawn or transformation handling. Obor and Deranged Archaeologist still need verified entry and placement paths. The [boss and quest placement audit](BOSSES_AND_QUESTS.md) describes the scope. |
| Client and capacity | Startup and handshakes can be verified in the isolated build. Playing through the client, multiplayer, and sustained tick latency under load remain separate acceptance checks. |

The [map manifest methodology](COVERAGE.md) and [NPC report](NPC_STATUS.md) distinguish configured data from playable content. The [gameplay checks](GAMEPLAY.md) list concrete test locations.
