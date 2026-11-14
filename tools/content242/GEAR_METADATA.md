# Revision 242 equipment metadata adapter

`gear_metadata.build_metadata(item_map, item_fields, item_metadata,
existing_weapon_types, gameplay=None, weapon_stats=None)` returns three values
in a dictionary: `item_info` rows, `weapon_types` additions, and `report` rows.
The last two arguments are keyword-only. All item maps use donor IDs as keys;
`item_map` maps donor IDs to free target IDs. `item_fields` and `item_metadata`
are the outputs of `legacy_assets.read_item`. The donor gameplay input can be
an array or ID-keyed dictionary. `load_json(Path(...))` handles the target's
commented JSON without removing comment markers inside strings.

The function never edits the original JSON or cache. Append its results to
staging copies only, after checking the IDs are genuinely free. Before encoding
each item, remove the field opcodes in the matching report row's
`remove_inventory_options`. This suppresses unavailable Check, Charge, Uncharge,
Transform and other interactions, plus equipment actions on inventory-only
items. The caller is responsible for preserving an appropriate Drop action or
other intentional inventory policy. Notes/placeholders remain inventory-only.

## Verified target schema

`io.ruin.data.impl.items.item_info.Temp` reads integer equipment bonuses and
skill levels, `equip_slot`, body hiding booleans, `two_handed`, string weapon and
ammo IDs, examine, kilograms and protect value. It resolves weapon profiles
through `weapon_types.MAP`, and ranged entries through Java enums. Its equipment
action is discovered from the cache's Wield/Equip/Wear/Ride/Hold option.

The conversion uses these revision 242 raw fields:

| Source | Target |
| --- | --- |
| Opcode 13 | Primary equipment slot |
| Opcodes 14 and 27 | Additional covered body slots: 8 hair, 11 beard, 6 arms; weapon plus slot 5 is two-handed |
| Signed opcode 75 | Grams divided by 1,000 for weight |
| Param 0–10 | Five attack, five defence and melee strength bonuses |
| Param 12 + 189 | Ranged strength (ammo plus equipment) |
| Param 11 | Prayer bonus |
| Param 299 / 10 | Magic damage percent |
| Param 14 | Attack speed in ticks |
| Param 13 | Attack range; zero uses the donor weapon-range fallback |
| Params 434/436, 435/437, 191/613 | Up to three skill-index/required-level pairs |
| Param 23 | Existing ranged weapon/ammunition level gate |
| Opcode 65 | Tradeable flag |
| Opcode 12 | Cache shop value, used as the deterministic protect value |

Raw bonuses take precedence over donor JSON, whose old gameplay export includes
stale bonuses. Missing raw stats mean zero, so broken/inactive pieces cannot
recover obsolete stats. Gameplay skill requirements are only a fallback when
there are no raw requirement pairs. Quest, combat-level and boss-unlock gates
need gameplay code and are not claimed to be implemented.

The target stores magic damage as an integer. Fractional values are rounded
down and marked `basic-equipment-rounded-magic`, with the exact source percent
and conversion in the report. All other accepted items with equipment metadata
are `basic-equipment`; this means base stats and the legacy combat behavior,
not complete OSRS behavior. Inventory-only items have no `equip_slot`.

## Supported behavior and boundaries

Armor and accessories use their raw slots, level requirements and base stats.
Torva, Masori, Virtus, the DT2 rings, Moon armor and Oathplate can therefore be
represented without fabricating their effects. Existing mechanics that dispatch
by old item IDs do not automatically apply to remapped versions. Set effects,
passives, special attacks, charge consumption, degradation, repair/crafting and
new unlock gates are not added by this adapter.

Known melee families start with existing legacy profiles, keeping their proven
animations and action types while setting source speed/range. The optional
animation importer described below can then replace those animations with
validated donor sequences. This includes
the basic attacks of Fang, Voidwaker, Burning claws, Soulreaper axe and similar
items when donor family data is available. It does not implement Fang accuracy,
Soulreaper stacks, multiple hits or new specials. Some newer staff, partisan,
banner and scythe style layouts are approximated by a named legacy profile and
explicitly recorded. Ordinary staffs retain legacy melee/manual-spell behavior;
new powered staffs and powered books remain inventory-only.

Ranged dispatch is enabled only for the explicit ordinary arrow-bow family
allowlist, using `RangedWeapon.NORMAL_BOW`. Bone shortbow and Scorching bow can
shoot existing arrows, with no new passives or special attacks. Allowed ammo is
the existing enum's bronze, iron, steel, broad, mithril, adamant, rune, amethyst
and dragon arrows, additionally gated by the legacy ranged-level check. The
adapter recognizes exact existing plain arrow, bolt and javelin names for
`RangedAmmo`; it does not fabricate projectiles or poison behavior.

Unrecognized bows, crystal/Faerdhinen/Craw/Webweaver bows, atlatls, crossbows,
thrown weapons, blowpipes, salamanders and powered weapons remain inventory-only
until their distinct ammo, charging, projectile or combat behavior is provided.
Their assets can still be inspected. Extra inventory actions are removed, not
left as misleading interactions.

Target item IDs must be at most 65,534 for packet sentinels, and equipment IDs
must be at most 65,023 because `Appearance` encodes `itemId + 512` as a short.
The adapter rejects duplicate destination IDs and conflicting generated weapon
profile names. It does not discover collisions with existing item definitions;
that is the importer's responsibility.

Run the focused checks with Python 3:

```text
python -m unittest discover -s tools/content242 -p test_gear_metadata.py
```

The checks cover stale donor data, skill/slot/weight conversion, magic rounding,
weapon cloning without source mutation, ranged ammo dispatch, disabled powered
weapons, broken-item defaults, templates, packet bounds and commented JSON.

## Genuine animation import

`AnimationImporter(baseStore, donorStore, outStore, item_map=verified_mapping)`
stages ordinary donor sequence/frame/skeleton data in memory. Call
`upgrade_weapon_profiles(importer, additions, item_map, gameplay,
existing_weapon_types=existing_types)` before writing generated metadata. It
updates only new item rows and gives every upgraded weapon its own copied
profile. Integer or per-style `attackAnim`, `blockAnim` and the seven stance,
walk, turn and run fields are supported. Unsupported individual animations keep
the existing profile's fallback, with reasons in the returned report.

`import_sequence(id)` returns its target ID or `None` with an exclusion reason.
`finish()` writes appended groups and returns mapping/exclusion counts. It reads
the current output sequence dictionary and index tables, so item/map additions
written by the surrounding importer remain present. Invoke `finish()` once.
The preservation verifier must treat index 2/group 12 as an additive definition
group, checking each original definition byte-for-byte.

Deduplication hashes the content after skeleton/frame remapping. Existing groups
are never overwritten. Each frame is checked against the legacy transform and
short-smart decoder. Sparse frame file IDs are supported: the old archive's
file array is sized by the maximum file ID. The source sequence's frame
references must exist. The known optional skeletal-matrix trailer is validated
and removed from otherwise usable legacy skeletons. Weighted-only sequences
remain unsupported; no Maya animation is presented as a working conversion.

Sequence hand-item overrides are encoded as item ID plus 512. Only an explicit
verified item mapping permits these overrides; an absent mapping excludes the
sequence instead of rendering an unrelated item at a reused ID. Zero hand-item
overrides, which hide the held item, are retained.

Animation tests use an in-memory store to verify append-only writes, preservation
of other current staged groups, identical-content reuse, sparse frames,
weighted exclusions, held-item mappings, skeleton trailers and per-item profile
isolation. Run both test modules with `-p test_*.py`.

## Runtime verification limits

The isolated revision 184 client completed login, home, normal equipment and
imported-map captures. The actual client decoder also processed 131 appended
sequences, 127 frame groups and 1,677 frames, then animated the male/female
Torva + Fang assemblies across 132 frames. These checks establish equipment,
asset decoding and animation compatibility.

**Live damage from the new Fang was not confirmed.** The staging combat probe
rejected its selected character at its presence guard before spawning any NPC
or setting a combat target. Its proof file correctly recorded `passed: false`.
The staging server was then shut down; no further attachment was attempted.
The evidence does not establish hit damage, timing, special attacks or passives.

`Run-Content242-CombatProbe.ps1` and `Content242CombatProbe.java` are a future,
guarded verification tool, not a successful test result. Candidate selection was
subsequently corrected to scan actual world slots, require exactly one connected
online c242qa, skip stale disconnected entries, and report candidate counts.
That correction compiled but was not exercised on a live staging character.
The original cause of the rejected selection is therefore not conclusively
established. The probe's process guards were separately checked to reject an
unflagged JVM before accessing a request or server state.

When deliberately run in a fresh isolated stage with c242qa online and wearing
the imported Fang, the probe creates only one temporary standard Man, raises
that NPC's HP to survive a normal hit, disables its retaliation/respawn, and
starts ordinary PlayerCombat targeting. It observes actual HP loss attributed
to c242qa for at most 20 world ticks; it never writes damage. On success or
failure it resets its own combat target and removes its temporary NPC. Output
is `combat-proof.json` in the selected test stage. A passing file is required
before reporting live combat as verified.
