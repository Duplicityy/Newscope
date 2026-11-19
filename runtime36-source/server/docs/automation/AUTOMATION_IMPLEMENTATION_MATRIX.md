# Newscape automation implementation matrix

This matrix records the verified server baseline and the first private automation candidate. **Native** means an action or encounter implementation was found in the selected source or overlay. **Partial** means availability or progression behavior remains unverified. **Existing owner** means a controller ships separately in the private overlay. **Actor reference** means the autonomous Adventurer system controls simulated players with their own profiles and safety policy. Automated and live phone evidence are tracked independently. Package references use the selected Kronos-master/kronos-server/src/main/java/io/ruin server tree. See the [server audit](AUTOMATION_SERVER_AUDIT.md) for runtime evidence.

The expanded candidate registers all 23 standard skills. The entries below describe the current routes and recipes. Scope and test depth vary.

## Standard skills

| Skill | Native evidence | Existing automation and next requirement |
| --- | --- | --- |
| Attack | Melee XP, combat styles and PlayerCombat | Local ordinary PvE, native accurate style. |
| Strength | Combat power/XP. Bound owner has an account-specific exception | Local ordinary PvE, native aggressive style. |
| Defence | Defensive style and XP | Local ordinary PvE, eligible defensive style. |
| Hitpoints | Combat XP and current/maximum health | Local ordinary PvE with equipped weapon or valid existing spell mode. |
| Ranged | Attack styles, projectiles and equipment rules | Local ordinary PvE with native ranged gear/ammunition. |
| Magic | Spell, SpellBook and combat/utility spell actions | Local ordinary PvE with configured autocast and supplies. |
| Prayer | Bone, altars, active prayers and drain | Native bone burial with carried bones. |
| Runecraft | Altars, Essence, DenseEssence, Abyss and OuraniaAltar | Native eligible reachable altar creation with carried essence. Altar bindings checked. |
| Agility | Shortcuts, authored course actions and Mark of Grace | Original Gnome course using real ordered obstacles. Bindings and resume order checked. |
| Herblore | Herb, Potion, PestleAndMortar and HerbTar | Native cleaning and carried ingredients for supported potion recipes. |
| Thieving | PickPocket, Stall, WallSafe and Pyramid Plunder | Local native warehouse of defined stalls, level and fullness checked. Native stall binding verified. |
| Crafting | Gem, Glass, Pottery, Armour, Amulet and moulds | Native gem cutting with carried ingredients. |
| Fletching | LogCutting, StringBow, Arrow, Bolt and Dart | Native log cutting and bow stringing with carried materials. |
| Slayer | Slayer, SlayerTask, SlayerUnlock and slayer_tasks.json | Existing supported assignment, native eligible local targets and task counters. |
| Hunter | Hunter, Impling, Puro-Puro and dungeon trapping | One local bird snare or eligible chinchompa trap at a time. |
| Mining | Mining, Rock, Pickaxe, MiningGuild, RuneEssence and verified 242 aliases | Locally reachable ordinary rocks, pickaxe, nearby bank and ore provenance. |
| Smithing | SmeltBar, SmithBar, SmithItem and RingOfForging | Native furnace smelting with carried ore. |
| Fishing | FishingSpot, FishingCatch, FishingTool, area and guild scripts | Native conventional fishing spots and eligible methods, with local bank handling. |
| Cooking | Cooking, Food, JugOfWine and verified 242 aliases | Native nearby cooking range, with carried raw food. |
| Firemaking | Burning and Wintertodt | Native logs and tinderbox on permitted tiles. |
| Woodcutting | Woodcutting, Tree, Hatchet, Woodcutting Guild and verified 242 aliases | Native local trees and best usable axe, with local bank handling. |
| Farming | Farming, ToolStorage and native patch handling | Reachable native crop patch interactions, with carried supplies. Stops during real growth. |
| Construction | Construction, House, Hotspot, Buildable and Flatpack | Native flatpack chair production at the owner's workbench, one paid action at a time. |
| Sailing | No StatType or authoritative training implementation located | Unavailable in this baseline. Transport and boats are separate mechanics. |
| Dungeoneering | Native 727-derived maps, party/floor lifecycle, escrow and XP | Existing owner iDungeon. Preserve it. Earlier owner deployment plus pure and native-boundary checks. |
| Custom Athletics | Six-system Athletics measures qualifying travel, rounds and gathering | Separate progression mechanic. Repetitive walking can be uncredited. No dedicated owner controller located. |
| Dungeon gathering, Summoning and prestige | Dungeon tools, recipes, Summoning and normal floor prestige | Existing iDungeon planner within its instance scope. |
| Post-99 mastery | Separate exact XP/mastery service and ordinary skill caps | Base levels initially. Mastery goals require explicit settings and progress semantics. |

## Encounters and systems

| Activity | Server evidence | Automation baseline |
| --- | --- | --- |
| Zulrah | Zulrah.java, ZulrahSnakeling, forms, rotations, venom and native attack styles | Strong follow-up candidate. Validate live phase and safe-lane observations. |
| Vorkath | Native Vorkath.java with attacks and acid/freeze specials | Requires combat shell, protection/consumable checks and special reactions. |
| God Wars Dungeon | Native boss-wing scripts and access checks including GodwarsBossEntrance | Requires faction items, kill count, attack timings and retreat methods. |
| Obor | Combat data and counters present. No verified playable placement/entry in the audited source and installed spawn data | Blocked on key, door and instance evidence. |
| The Gauntlet and Corrupted Gauntlet | Client helper and item/asset records found. No authoritative server entry, preparation and boss lifecycle found | Keep unavailable pending playable server evidence. |
| Nex | No authoritative encounter implementation located | Keep unavailable pending playable server evidence. |
| Araxxor | No authoritative encounter implementation located | Keep unavailable pending playable server evidence. |
| Chambers of Xeric | Native layouts, bosses and team instances | Candidate for scouting or later encounter tests. No verified owner CoX execution path. |
| Theatre of Blood | Native theatre phases, rewards, lobbies and boss options | Existing RaidAuto and TheatreRaidAuto. Individual live clears need evidence. |
| Tombs of Amascut | Native puzzle and boss encounters, phases, lobby and boss options | Existing RaidAuto and ToaRaidAuto. Previous owner gameplay feedback exists. Full run and mechanic coverage are separate tests. |
| Phantom Muspah | 242 instance, asset gate, attacks, shield, spikes and clouds | Candidate after exact availability and region entry validation. |
| Scurrius and Amoxliatl | 242 encounter entry and combat modules | Candidate after exact runtime bind/availability and entry checks. |
| Banking, shops and inventory | Native validated action interfaces and stock/currency handling. Latest startup loaded 33 shop bindings | Shared services. Enforce PIN, location, mode, item attributes and slot identity. |
| Grand Exchange | Widget definitions and travel references found. No complete private transaction flow verified | Keep automatic Exchange trades unavailable. The actor report has market disabled. |
| Navigation | Native clipping, footprint routes and Map Walk overlay | Use local routes and verified transport edges. Bound search and retries. |
| Account progression | Adventurer planner selects available skill actions using owned supplies and safety | Use planning patterns. |
| Owner controls | iDungeon and raid controls exist. The v37 Android game opens in landscape | Shared panel needs its own lifecycle. Portrait needs Android host support and device checks. |

## Expanded boss controllers

| Encounter | Current automation | Evidence |
|---|---|---|
| Thermonuclear Smoke Devil | Existing task and local eligible combat | Detached target, food, style, gates and kill-counter checks |
| Vorkath | Entered private instance, wake, real fireball and acid observations, frozen-spawn handling, supplies and retreat | Native detached hazard and spell-queue checks. Full clears pending. |
| Zulrah | Entered shrine, native form and Jad decisions, fume avoidance, consumables and owned loot | Detached across all native phases. Full clears pending. |
| ToA, ToB, iDungeon | Existing native controllers and owner controls | Existing checks rerun. Global entry and scripted playthrough remain scope of the existing controllers. |
