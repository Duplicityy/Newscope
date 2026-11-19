# Wardens native encounter port

Source provenance: Near Reality commit `f577de7d929513a46661d4cc7f4f036f87e0395c`, Savions' `WardenEncounter`, `SecondWardenEncounter`, `WardensObelisk`, `StaticWardenNPC`, `MovingWardenNPC`, `WardenCoreNPC`, `FlyingWardenNPC`, `EnergySiphonNPC`, `WardenChargingOrb`, and `WardenPhantom` under `core-restricted/src/main/java/com/zenyte/game/content/tombsofamascut`.

This is an additive native Java8/Kilim controller. It does not load donor engine code or modify the original player items, native NPC definitions, live cache, server connection or packet protocol. `WardensPatternData.java` retains exact source-coordinate tables, including all floor groups and all32 floor replacement steps. Shared asset validation gates either room if its genuine model, animation, graphic, object or stat record is unavailable.

Implemented normal, solo encounter mechanics:

- Obelisk260HP, two charging Wardens, exact three charging-orb paths per side; standing on an orb intercepts it for3damage. Charge totals select the later Warden. Authored alternating rotating-trap grids and energy projectiles are retained.
- A moving Warden's140HP shield accepts only its displayed magic or ranged style. Its core has4500 real HP and receives five times core damage. Core exposure lengths21/29/37/45/53ticks follow actual remaining health; returning the core alternates magic/ranged shields. Normal mage/range/melee, reactive divine prayer attacks and7tick stone imprisonment are present. Obelisk wheel, bomb and isolation patterns use actual source tables and damage.
- A first-room victory transitions through the shared safe instance lease to the actual final room. Both distinct Warden appearances are retained; a directly selected final-room encounter defaults to Elidinis.
- Flying Warden880HP uses the three authored floor rotations. Incoming damage is clamped at80/60/40/20% for the actual four solo skull patterns (4/5/6/7skulls). Siphons take one damage per successful melee attack. Success returns5% maxHP damage; timeout uses the original full-floor attack and11damage per missing skull.
- At5% the Warden restores20% maxHP and enters its final phase. Actual floor replacement objects progressively remove eight rows; impending lightning marks15% of available tiles with the authored delay and9–13damage. A player on a removed tile is moved onto the surviving floor.
- Base phantom attacks appear at the donor skull phases: Zebak/Akkha followed by Ba-ba/Kephri according to the opposite Warden. Prayer multipliers and telegraphed tile impacts follow the donor. Phantoms without donor combat-stat records credit damage through the instance controller's live boss rather than inventing NPC stats.
- Native actor death, instance-only objects, generation-checked delayed actions and cleanup apply throughout. No normal NPC loot, respawns, global action bindings or foreign reward rolls occur.

Remaining differences and limits:

- This controller supplies Wardens combat within the shared ToA encounter/session adapter. It does not itself claim that four path puzzles, party scaling, invocations, a full raid reward calculation, raid widgets, music, sound and cinematic camera scripts are finished. The shared ToA adapter determines final raid availability and rewards.
- No invocation modifiers are enabled; the normal solo values above are intentional. The donor's own multiplayer skull table duplicates the solo patterns with a TODO, so unsupported party behavior is not claimed.
- Moving Warden pathing uses native follow-at-eight. Its first awakening is shortened, so the original21tick awakening/cinematic is not reproduced.
- Native forced movement is represented by safe tile movement for orb stepping, dislodging a player and floor erosion. Ordinary source animations/gfx remain; the cinematic floor-removal projectiles and hidden background Wardens are omitted.
- Energy siphons are native attackable1HP NPCs; the donor's invisible supplemental blocking object and repeated cosmetic siphon projectile are not installed. No attacks/actions were globally accelerated for this mechanic.
- Visual HUD colour changes, protection-prayer icon9, stone mid/end presentation and original room widgets require desktop/Android visual confirmation. Native instance coordinates and source-plane copying are provided by the shared session builder.
- These files have been compiled and their donor coordinate tables checked offline. No claim of a completed gameplay run, multiplayer balance, phone performance or bug-free release is made. The public server remains offline and this work is prepared only as a drop-in candidate.
