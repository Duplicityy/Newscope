# Owner raid automation

Enter a Tombs of Amascut session or Theatre of Blood run normally, then use:

```text
::raid auto
::raid auto status
::raid auto stop
::raid auto leave
```

`::raid auto start` also starts the controller. Only the exact `OWNER` player group may use these commands. Other staff ranks receive a refusal. The existing `::raid` teleport command remains available.

The controller plays the current raid using the owner's carried equipment, food, potions, ammunition, charges and earned raid supplies. It switches combat styles, handles supported puzzle and boss mechanics, and uses the ordinary reward and exit interactions. ToB advancement respects the party leader and controls only the invoking owner.

Bring usable melee and ranged weapons, compatible ammunition, a charged powered magic weapon, a pickaxe where required, food, and restoration supplies. Missing equipment, refused consumables or insufficient inventory space stop the controller with a message. Equipped items move through normal Wear/Wield actions, retaining their attributes.

Starting automation turns Auto Retaliate off through the normal Combat tab action. Camera movement, chat and status checks can be used while observing. Manual gameplay actions stop automation before the original action is dispatched.

After `stop`, control returns to the owner in the current encounter. `leave` requests the normal raid exit after any native transition lock settles. Death, logout, a changed raid session, loss of owner permissions and the duration limit stop automation. It does not resume automatically on login.

The source has passed a combined Java 8 compile and independent code review of owner authorization, native packet/action paths, resource handling and lifecycle controls. The owner explicitly reserved gameplay testing. Full raid completion and reward claims remain unverified; compilation is not represented as a gameplay pass.

The feature remains part of the private release candidate while the existing public-access hold is in effect.
