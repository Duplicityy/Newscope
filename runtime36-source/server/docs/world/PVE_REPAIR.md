# NPC combat and shop repairs

These changes apply to the isolated world development build.

## Repaired

| Area | Evidence and repair |
| --- | --- |
| Ardougne and Lumbridge cow calves | Eighteen existing placements exposed Attack without a server combat record. Installed NPC 2794 is byte-identical to working 2792. NPC 2801 shares its model, movement animations, action and combat level with a one-tile footprint. Both variants now use the existing Newscape calf combat record and its cowhide, raw beef and bones drops on the PVP and ECO configurations. |
| Fishing Guild Shop | The authored shop row at (2596, 3401, 0) pointed to NPC 1299, which decodes to a sheep with no options in this cache. The existing Roachey dialogue targeted the same stale ID and its purchase callback used an empty shop identifier. NPC 8684 is the installed Roachey definition with native Talk-to and Trade actions. The existing placement now uses 8684, and both interaction paths reach the existing Newscape stock and currency configuration. |
| Piscatoris and Port Piscarilius shops | Arnold Lydspor and Tynan offered dialogue choices that passed empty shop IDs. Their existing dialogue now opens the same authored shops as direct Trade, with the original stock and prices. |
| Clan Wars reward shop | Lisa's existing spawn already declared a Trade binding to the LMS Reward Shop. The server now exposes Trade in slot four, matching the same ID-specific menu override verified in the packaged Windows client and signed Android code29. The existing Survival Tokens currency, prices, and Talk-to and Join action slots are preserved. |

NPC placements at home, game assets, cache definitions, drop quantities, shop stock, prices and currency were preserved. The fishing edit keeps the original tile, plane, facing and wander radius. The stale 1299 reference was unique to that placement and the old dialogue handler in the audited server sources and staged spawn data.

## Remaining findings

The pre-repair audit counted 333 placement rows across 57 NPC IDs whose raw Attack option had no static combat record. The merged development data retains 10,644 rows, with this audit flag reduced to 315 rows across 55 IDs. At least nine of those rows are the intentional runtime suppression of Attack on Man 3080. Other rows include Hunter creatures, encounter helpers, quest NPCs and ordinary monsters that still need per-ID gameplay evidence.

- Scorpia has a static spawn, a configured specialised handler and scripted guardian summons. Her 27 authored offspring placements use ID 6616 with no static combat record. The existing `ScorpiasOffspring` projectile class is unbound. A compatible authored profile for HP, ranged damage, aggression and respawn is still needed before activating these attacks.
- Obor 7416 has server combat stats and authored tables in both world configurations. Its handler is blank and its attack, defend and death animation IDs are unset. The audited build has no verified Newscape entry or spawn binding for this fight.
- Deranged Archaeologist 7806 exists in the installed cache and kill counters. The audited build has no verified encounter handler, static placement or reward binding.

## Provenance

The repaired IDs, spawn rows, dialogue and loot records were checked against the branch and the installed development-04 cache. The original three calf files and the Fishing Guild spawn file were SHA-256 identical to their staged development-04 counterparts before editing.

The comparison sources describe why cache identity, server placement and gameplay binding need separate evidence. Reviewed TSPS at `d36cc7ebd89b96ed2caa0e2dfc7fa3fc6c0ad494` under BSD 2-Clause and OpenRune Server at `c072fd273ed0c6149dcdb3017d6167082e328f23` under ISC. No external code, spawns, encounters, drops or assets were imported. [TSPS spawn documentation](https://github.com/RSPSApp/tsps/blob/d36cc7ebd89b96ed2caa0e2dfc7fa3fc6c0ad494/docs/npc-spawns.md), [TSPS licence](https://github.com/RSPSApp/tsps/blob/d36cc7ebd89b96ed2caa0e2dfc7fa3fc6c0ad494/LICENSE), [OpenRune drop documentation](https://github.com/OpenRune/OpenRune-Server/blob/c072fd273ed0c6149dcdb3017d6167082e328f23/docs/drops.md), [OpenRune licence](https://github.com/OpenRune/OpenRune-Server/blob/c072fd273ed0c6149dcdb3017d6167082e328f23/LICENSE.md).

## Verification

Eight detached tests passed against branch data and the installed development-04 cache, then again against the merged development-05 data and cache. They cover the installed NPC identities, models, movement animations, action slots and referenced combat animations and items, unique combat and loot bindings in both data configurations, all 18 intended calf placements, and both shop identities, placements and currency bindings. The packaged clients' matching slot override was checked directly in their `NPCDefinition.class` bytes. The initial development-05 build predates the server-side Lisa override.

From the repository root, run the checks in PowerShell with an installed cache path. Optionally set `NEWSCAPE_WORLD242_TEST_DATA` to the merged data folder of a staged development build.

```powershell
$env:NEWSCAPE_WORLD242_TEST_CACHE = 'D:/Newscape-World242/development-04-landscapes/Cache'
python -B -m unittest discover -s content-tools -p test_world242_pve.py -v
```

The detached server fixture opened all four shops through the packaged actions and bought one item from each using its configured currency. It loaded and spawned both calf variants with their combat records, then verified the three guaranteed drops under both world configurations. A staged client session is needed to check movement, animations and client menus. Attack one of each calf variant, confirm retaliation and death animation, and pick up the three guaranteed drops. At the Fishing Guild, verify right-click Trade and Talk-to → Yes please both open the same stocked shop, then buy a small item with coins. At Piscatoris and Port Piscarilius, verify Arnold and Tynan open the same stock from Dialogue and Trade. At Clan Wars, choose Trade on Lisa and verify the LMS shop purchase debits Survival Tokens.
