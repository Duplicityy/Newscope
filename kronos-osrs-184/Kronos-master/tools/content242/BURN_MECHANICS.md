# Burning claws and Scorching bow

These implementations are restricted to explicitly mapped items in the isolated242
worktree. They do not modify `Hit.finish`, any existing weapon, the184 damage
packet, player saves or production. Merge `mechanics-burn-assets.json` into the
staged registry requirements; absent feature flags or visual bindings prevent
special registration.

The pinned reference is TSPS commit `1ccb548be725c70900b604968354dd3147b839ea`:

* `server/plugins/combat/specials/BurningClaws.SpecialAttack.js`
* `server/plugins/combat/specials/ScorchingBow.SpecialAttack.js`
* `server/plugins/combat/Burns.plugin.js`
* `server/plugins/combat/effects/Demonbane.EquipmentEffects.js`
* `server/src/main/typescript/elvarg/game/content/combat/hit/PendingHit.ts`

Burning barrage costs35%, rolls accuracy up to three times against slash defence,
retains the selected attack style's accuracy, and distributes damage according to
the first successful branch. Each of its three impacts independently rolls15%,
30% or45% to add a10-damage burn; a full miss cannot burn. The demonbane passive
adds5% accuracy and damage.

Scorching shackles costs25% against demons, binds for20 ticks subject to native
freeze immunity, and applies one immediate plus four periodic burn damage. It
does not fire a normal arrow, consume ammunition or roll attack damage during
that special. The existing ranged entry checks still require suitable equipped
ammunition, as the donor's ranged attack eligibility does. A non-demon refusal
returns to the ordinary shot without spending special energy. Its ordinary bow
uses range10, speed5 (rapid4) and a30% demonbane bonus; ranged slayer-helm damage
combines additively to45%.

The shared burn clock deals one damage per stack every four ticks, with independent
expiry, at most five stacks per target and4,096 active targets. It clears stale
entries each world tick and validates actor identity, online/alive state, plane
and dynamic-map lease again when a queued hit completes. It retains no saved
burns. `BurnHit` uses the native entity queue and184 damage splat, credits each
valid source before lethal damage, and calls native HP/death methods once. Status
completion deliberately skips ordinary attack/XP/Smite/recoil/equipment callbacks.
NPC defence-time reductions remain active. Status burns do not wait behind
invincibility or full damage-delay locks.

New encounter combat classes can implement `ModernBurn.StatusTarget` to validate
the source owner and reduce/block damage at shield or phase boundaries. The
`BurnHit.validForOwner` helper validates captured actor/lease identity without
setting the native attacker field. Completion applies `beforeBurn` before kill
credit and HP, clamps its return to the incoming damage and revalidates ownership.

`Test-ModernBurn.ps1` compiles small fixtures against a candidate server library.
The detached regression passes65,649 assertions: donor split examples, all damage
bounds through max-hit200, strict burn chances, stack phase/expiry/caps, lifecycle
invalidation, and actual `Entity.hit`/`processHits` delivery with native kill credit
and a184 damage splat. It also checks encounter ownership, shield blocks and lethal
phase caps, callback amplification rejection and queued ownership revocation.
It starts no server and creates no account. Actual staged
weapon attacks, animation/projectile display and energy/ammunition state still
need a staged client/gameplay test.

Explicit boundaries: modern sound IDs and burn splat74 are not reused in184;
ordinary184 demon classification is retained. Boss-specific demonbane vulnerability
multipliers and custom burn-immunity listeners are not inferred from names.
The normal-burn named immunities in the donor are retained. Restoration-pool
cures can call `ModernBurn.clear(entity)`; existing pool code is not changed here.
