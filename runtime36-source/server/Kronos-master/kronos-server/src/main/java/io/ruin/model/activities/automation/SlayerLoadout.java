package io.ruin.model.activities.automation;

import io.ruin.cache.ItemDef;
import io.ruin.model.activities.godwars.combat.armadyl.ArmadylSpiritualMage;
import io.ruin.model.activities.godwars.combat.armadyl.ArmadylSpiritualWarrior;
import io.ruin.model.activities.godwars.combat.armadyl.Aviansie;
import io.ruin.model.activities.miscpvm.BasicCombat;
import io.ruin.model.activities.miscpvm.dragons.BrutalDragon;
import io.ruin.model.activities.miscpvm.dragons.ChromaticDragon;
import io.ruin.model.activities.miscpvm.dragons.MetalDragon;
import io.ruin.model.activities.miscpvm.dragons.MithrilDragon;
import io.ruin.model.activities.miscpvm.slayer.*;
import io.ruin.model.combat.AttackSet;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemNPCAction;
import io.ruin.model.item.actions.ItemAction;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.bank.BankItem;
import io.ruin.model.skills.slayer.Slayer;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;
import io.ruin.model.skills.herblore.Potion;
import io.ruin.network.incoming.handlers.ActionButtonHandler;

import java.util.Map;

/** Item identities and special mechanics taken from the corresponding native NPC combat classes. */
enum SlayerLoadout {
    BASIC(BasicCombat.class), INFERNAL(InfernalMage.class), ABYSSAL(AbyssalDemon.class),
    BANSHEE(Banshee.class, 4166, Equipment.SLOT_HAT),
    SPECTRE(AberrantSpectre.class, 4168, Equipment.SLOT_HAT),
    COCKATRICE(Cockatrice.class, 4156, Equipment.SLOT_SHIELD),
    CAVE_HORROR(CaveHorror.class, 8923, Equipment.SLOT_AMULET), CRAWLER(CaveCrawler.class),
    BASILISK(Basilisk.class, 4156, Equipment.SLOT_SHIELD),
    DUST(DustDevil.class, 4164, Equipment.SLOT_HAT),
    SMOKE(SmokeDevil.class, 4164, Equipment.SLOT_HAT),
    GARGOYLE(Gargoyle.class, 0, -1, 4162, 9),
    SLUG(Rockslug.class, 0, -1, 4161, 4),
    LEAF(LeafVulnerableMonster.class, 4158, Equipment.SLOT_WEAPON),
    CHROMATIC_DRAGON(ChromaticDragon.class), METAL_DRAGON(MetalDragon.class), BRUTAL_DRAGON(BrutalDragon.class),
    MITHRIL_DRAGON(MithrilDragon.class),
    WYVERN(SkeletalWyvern.class, 21634, Equipment.SLOT_SHIELD),
    WYRM(Wyrm.class, 23037, Equipment.SLOT_FEET),
    DRAKE(Drake.class, 23037, Equipment.SLOT_FEET),
    NECHRYAEL(Nechryael.class),
    CAVE_KRAKEN(CaveKraken.class),
    AVIANSIE(Aviansie.class),
    SPIRITUAL_CREATURE(BasicCombat.class);

    private final Class<?> combatClass;
    private final int equipmentId, slot, finisher, finishingHp;
    private static final int BANK_WIDGET = Interface.BANK << 16;
    private static final int[] LEAF_WEAPONS = {11902, 20727, 4158};
    private static final int PRAYER_RESTOCK_DOSES = 16, PRAYER_REFILL_LEVEL = 9;
    private static final Potion[] CURES = {Potion.ANTIPOISON, Potion.SUPER_ANTIPOISON,
            Potion.ANTIDOTE_PLUS, Potion.ANTIDOTE_PLUS_PLUS};
    private static final Potion[] SUPER_ANTIFIRE = {Potion.EXTENDED_SUPER_ANTIFIRE, Potion.SUPER_ANTIFIRE};
    private static final Potion[] ANTIFIRE = {Potion.EXTENDED_ANTIFIRE, Potion.ANTIFIRE,
            Potion.EXTENDED_SUPER_ANTIFIRE, Potion.SUPER_ANTIFIRE};
    // Include the charged and uncharged forms recognized by the underlying damage handlers.
    private static final int[] DRAGONFIRE_SHIELDS = {1540, 11283, 11284, 21633, 21634, 22002, 22003};
    private static final int[] WYVERN_SHIELDS = {21633, 21634, 22002, 22003, 9731, 2890, 11283, 11284};
    private static final int[] KARUULM_BOOTS = {23037, 22951, 21643};
    private static final int[] SPIRITUAL_CREATURES = {2210, 2211, 2212, 2242, 2243, 2244, 3159, 3160, 3161};
    private static final int ANTIFIRE_REFRESH_TICKS = 24, ANTIFIRE_RETREAT_TICKS = 8;

    SlayerLoadout(Class<?> combatClass) { this(combatClass, 0, -1); }
    SlayerLoadout(Class<?> combatClass, int equipmentId, int slot) { this(combatClass, equipmentId, slot, 0, 0); }
    SlayerLoadout(Class<?> combatClass, int equipmentId, int slot, int finisher, int finishingHp) {
        this.combatClass = combatClass;
        this.equipmentId = equipmentId;
        this.slot = slot;
        this.finisher = finisher;
        this.finishingHp = finishingHp;
    }

    static SlayerLoadout forNpc(NPC npc) {
        if (npc.getCombat() == null) return null;
        Class<?> script = npc.getCombat().getClass();
        if (script == BasicCombat.class && accepts(npc.getId(), SPIRITUAL_CREATURES)) return SPIRITUAL_CREATURE;
        if (script == ArmadylSpiritualMage.class || script == ArmadylSpiritualWarrior.class) return AVIANSIE;
        for (SlayerLoadout rules : values()) if (script == rules.combatClass) return rules;
        return null;
    }

    static int combatRisk(NPC npc) {
        int maxDamage = npc.getCombat().getInfo().max_damage;
        // Native MithrilDragon magic and ranged attacks both cap at 18. The
        // ranged profile requires separation and keeps full dragonfire protection.
        if (npc.getCombat().getClass() == MithrilDragon.class) return 18;
        // The Nechryael spawns up to two #10 death spawns. Each deals up to two.
        return maxDamage >= 0 && npc.getCombat().getClass() == Nechryael.class ? maxDamage + 4 : maxDamage;
    }

    boolean requiresRanged() {
        return this == CAVE_KRAKEN || this == AVIANSIE || this == MITHRIL_DRAGON;
    }

    boolean requiresGodProtection() {
        return this == AVIANSIE || this == SPIRITUAL_CREATURE;
    }

    boolean protection(Player p) {
        if (requiresGodProtection()) return godProtection(p);
        if (equipmentId == 0 || p.getEquipment().getId(slot) == equipmentId) return true;
        if (this == WYVERN) return accepts(p.getEquipment().getId(slot), WYVERN_SHIELDS);
        if (this == WYRM || this == DRAKE) return accepts(p.getEquipment().getId(slot), KARUULM_BOOTS);
        if (slot == Equipment.SLOT_HAT) return Slayer.hasSlayerHelmEquipped(p);
        if (this != LEAF) return false;
        ItemDef weapon = p.getEquipment().getDef(slot);
        return weapon != null && weapon.leafBladed;
    }

    int finisher(Player p) {
        return this == SLUG && p.getEquipment().getId(Equipment.SLOT_WEAPON) == 11037 ? 0 : finisher;
    }

    boolean canFight(Player p) {
        int item = finisher(p);
        AttackStyle style = p.getCombat().getAttackStyle();
        return protection(p) && (item == 0 || p.getInventory().getAmount(item) > 0)
                && dragonfireReady(p)
                && (this != CRAWLER || p.isPoisonImmune() || cure(p) != null)
                && p.getCombat().autocastSpell == null && style != null
                && (style.isMelee() && !requiresRanged()
                || style.isRanged() && usableAmmo(p, 3) && (!requiresRanged() || usableRange(p)));
    }

    boolean finish(Player p, NPC npc) {
        int id = finisher(p);
        if (id == 0 || npc.getHp() <= 0 || npc.getHp() > finishingHp
                || npc.getCombat().lastAttacker != p || !p.getPosition().isWithinDistance(npc.getPosition(), 1)
                || npc.getDef().itemActions == null || !npc.getDef().itemActions.containsKey(id)) return false;
        Item item = p.getInventory().findItem(id);
        if (item == null || this == GARGOYLE && npc.getCombat().getTarget() != p) return false;
        ItemNPCAction.handleAction(p, item, npc);
        return true;
    }

    private boolean selectStyle(Player p, boolean ranged) {
        if (p.getCombat().autocastSpell != null) return false;
        AttackStyle style = p.getCombat().getAttackStyle();
        if (style != null && (ranged ? style.isRanged() : style.isMelee())) return true;
        if (p.getCombat().weaponType == null) return false;
        AttackSet[] sets = p.getCombat().weaponType.attackSets;
        for (int i = 0; sets != null && i < sets.length; i++) {
            if (sets[i] != null && (ranged ? sets[i].style.isRanged() : sets[i].style.isMelee())) {
                p.getCombat().changeAttackSet(i);
                return true;
            }
        }
        return false;
    }

    String prepare(Player p, SlayerSettings settings, boolean atBank) {
        if (p.getCombat().autocastSpell != null) return "Disable autocast to use the supported Slayer combat styles.";
        if (requiresGodProtection() && !protection(p))
            return "Equip protection items for all four God Wars factions before entering the shared dungeon.";
        if (!protection(p)) {
            int[] choices = this == LEAF ? LEAF_WEAPONS
                    : this == WYVERN ? WYVERN_SHIELDS
                    : this == WYRM || this == DRAKE ? KARUULM_BOOTS
                    : slot == Equipment.SLOT_HAT ? hatChoices(p, atBank) : new int[]{equipmentId};
            equipProtection(p, slot, choices, atBank);
            if (!protection(p)) return "Equip " + ItemDef.get(equipmentId).name
                    + (this == WYVERN ? " or another shield that protects against wyvern breath"
                    : this == WYRM || this == DRAKE ? ", brimstone boots or granite boots" : "")
                    + (slot == Equipment.SLOT_HAT ? " or a Slayer helmet" : "") + ". Check levels and free slots.";
        }
        if (usesDragonfire() && !prepareDragonfire(p, atBank))
            return "Bring super antifire, or an antifire potion with a dragonfire-protective shield.";
        int id = finisher(p);
        if (id != 0 && atBank) {
            int target = this == SLUG ? Math.min(8, p.slayerTaskRemaining) : 1;
            fill(p, id, target);
        }
        if (id != 0 && p.getInventory().getAmount(id) < 1)
            return "No usable " + ItemDef.get(id).name + " remains.";
        if (this == CRAWLER && !p.isPoisonImmune() && cure(p) == null) {
            if (atBank) bankPotion(p, CURES);
            if (cure(p) == null) return "Bring an unnoted antipoison potion for Cave Crawlers.";
        }
        boolean ranged = this != LEAF && finisher(p) == 0 && (settings.preferRanged
                || p.getCombat().getAttackStyle() != null && p.getCombat().getAttackStyle().isRanged());
        boolean combatReady = requiresRanged() ? prepareRangedOnly(p, atBank)
                : ranged && prepareRanged(p, atBank) && selectStyle(p, true);
        if (requiresRanged() && !combatReady)
            return "Equip a six-tile ranged weapon with compatible ammunition before hunting this assignment.";
        if (!combatReady && !prepareMelee(p, atBank))
            return "No usable melee weapon or compatible ranged ammunition. Check equipment and bank stock.";
        if (atBank) {
            for (int i = CombatAutomation.FOODS.length - 1; i >= 0 && food(p) < settings.foodTarget(); i--) {
                int food = CombatAutomation.FOODS[i];
                fill(p, food, p.getInventory().getAmount(food) + settings.foodTarget() - food(p));
            }
        }
        if (food(p) < settings.foodMinimum()) return "Need at least " + settings.foodMinimum()
                + " supported food. Check bank stock and inventory space.";
        return null;
    }

    static boolean godProtection(Player p) { return godProtection(p, null); }

    private static boolean godProtection(Player p, ItemDef replacementWeapon) {
        int factions = 0;
        for (Item item : p.getEquipment().getItems()) {
            if (item == null || replacementWeapon != null && (item.getSlot() == Equipment.SLOT_WEAPON
                    || item.getSlot() == Equipment.SLOT_SHIELD && replacementWeapon.twoHanded)) continue;
            factions |= godFlags(item.getDef());
        }
        if (replacementWeapon != null) factions |= godFlags(replacementWeapon);
        return factions == 15;
    }

    private static int godFlags(ItemDef def) {
        int flags = 0;
        for (int i = 0; i < def.godItem.length; i++) if (def.godItem[i]) flags |= 1 << i;
        return flags;
    }

    private static boolean usableRange(Player p) {
        return p.getCombat().weaponType != null
                && p.getCombat().weaponType.maxDistance >= SlayerRangedTargeting.SHOOT_RANGE;
    }

    private boolean prepareRangedOnly(Player p, boolean atBank) {
        if (usableRange(p) && prepareRanged(p, atBank) && selectStyle(p, true)) return protection(p);
        return equipRanged(p, p.getInventory().getItems(), false, atBank)
                || atBank && equipRanged(p, p.getBank().getItems(), true, true);
    }

    private boolean equipRanged(Player p, Item[] items, boolean sourceBank, boolean atBank) {
        for (Item item : items) {
            if (item == null || item.getId() < 0 || item.getAmount() <= 0) continue;
            ItemDef weapon = item.getDef();
            if (weapon == null || weapon.isNote() || weapon.equipSlot != Equipment.SLOT_WEAPON
                    || weapon.weaponType == null || weapon.weaponType.maxDistance < SlayerRangedTargeting.SHOOT_RANGE
                    || weapon.rangedWeapon == null || weapon.rangedWeapon.data != null || weapon.rangedWeapon.allowedAmmo == null
                    || !meetsLevels(p, weapon) || requiresGodProtection() && !godProtection(p, weapon)) continue;
            if (!ammoAvailable(p, weapon, atBank)) continue;
            if (sourceBank && !withdraw(p, weapon.id, 1)) continue;
            Item carried = sourceBank ? p.getInventory().findItem(weapon.id) : item;
            if (carried == null) continue;
            p.getEquipment().equip(carried);
            p.getCombat().updateWeapon(false);
            if (p.getEquipment().getId(Equipment.SLOT_WEAPON) == weapon.id
                    && prepareRanged(p, atBank) && selectStyle(p, true)) return protection(p);
        }
        return false;
    }

    private static boolean ammoAvailable(Player p, ItemDef weapon, boolean atBank) {
        Item equipped = p.getEquipment().get(Equipment.SLOT_AMMO);
        if (compatible(p, weapon, equipped) && equipped.getAmount() >= 5) return true;
        for (Item item : p.getInventory().getItems())
            if (compatible(p, weapon, item) && item.getAmount() >= 5) return true;
        if (atBank) {
            for (Item item : p.getBank().getItems())
                if (compatible(p, weapon, item) && item.getAmount() >= 10) return true;
        }
        return false;
    }

    private static void equipProtection(Player p, int slot, int[] choices, boolean atBank) {
        for (int id : choices) {
            if (!meetsLevels(p, ItemDef.get(id))) continue;
            Item carried = p.getInventory().findItem(id);
            if (carried == null && atBank && withdraw(p, id, 1))
                carried = p.getInventory().findItem(id);
            if (carried == null) continue;
            p.getEquipment().equip(carried);
            if (p.getEquipment().getId(slot) == id) {
                p.getCombat().updateWeapon(false);
                return;
            }
        }
    }

    private static boolean accepts(int itemId, int[] ids) {
        for (int id : ids) if (id == itemId) return true;
        return false;
    }

    private boolean usesDragonfire() {
        return this == CHROMATIC_DRAGON || this == METAL_DRAGON || this == BRUTAL_DRAGON
                || this == MITHRIL_DRAGON || this == DRAKE;
    }

    private static boolean hasDragonfireShield(Player p) {
        return accepts(p.getEquipment().getId(Equipment.SLOT_SHIELD), DRAGONFIRE_SHIELDS);
    }

    private static int dragonfireTicks(Player p) {
        return hasDragonfireShield(p) ? Math.max(p.antifireTicks, p.superAntifireTicks) : p.superAntifireTicks;
    }

    private static Item antifirePotion(Player p) {
        return findPotion(p, hasDragonfireShield(p) ? ANTIFIRE : SUPER_ANTIFIRE);
    }

    private boolean prepareDragonfire(Player p, boolean atBank) {
        if (!hasDragonfireShield(p) && dragonfireTicks(p) <= ANTIFIRE_REFRESH_TICKS
                && findPotion(p, SUPER_ANTIFIRE) == null) {
            if (atBank) bankPotion(p, SUPER_ANTIFIRE);
            if (findPotion(p, SUPER_ANTIFIRE) == null)
                equipProtection(p, Equipment.SLOT_SHIELD, DRAGONFIRE_SHIELDS, atBank);
        }
        if (atBank && antifirePotion(p) == null)
            bankPotion(p, hasDragonfireShield(p) ? ANTIFIRE : SUPER_ANTIFIRE);
        // Drink before setting off if necessary. The fight refreshes again at the target.
        if (!atBank) refreshProtection(p);
        return dragonfireReady(p) || antifirePotion(p) != null;
    }

    boolean refreshProtection(Player p) {
        if (!usesDragonfire() || dragonfireTicks(p) > ANTIFIRE_REFRESH_TICKS || p.isLocked() || p.isStunned()
                || p.potDelay.isDelayed() || p.karamDelay.isDelayed()) return false;
        Item item = antifirePotion(p);
        if (item == null) return false;
        drinkAction(item.getDef()).handle(p, item);
        return dragonfireTicks(p) > ANTIFIRE_REFRESH_TICKS && p.getCombat().getDragonfireResistance() >= 1.0;
    }

    boolean dragonfireReady(Player p) {
        if (!usesDragonfire()) return true;
        int ticks = dragonfireTicks(p);
        return ticks > ANTIFIRE_RETREAT_TICKS && p.getCombat().getDragonfireResistance() >= 1.0
                && (ticks > ANTIFIRE_REFRESH_TICKS || antifirePotion(p) != null);
    }

    static boolean needsPrayerSupplies(Player p) {
        return p.getStats().get(StatType.Prayer).fixedLevel >= 37
                && prayerDoses(p) == 0 && bankHasPrayer(p);
    }

    private static boolean bankHasPrayer(Player p) {
        for (int id : Potion.PRAYER.vialIds) {
            if (id > 0 && p.getBank().getAmount(id) > 0) return true;
        }
        return false;
    }

    static int prayerDoses(Player p) {
        int doses = 0;
        for (int i = 0; i < Potion.PRAYER.vialIds.length; i++) {
            int id = Potion.PRAYER.vialIds[i];
            if (id > 0) doses += (i + 1) * p.getInventory().getAmount(id);
        }
        return doses;
    }

    static void restockPrayer(Player p) {
        int doses = prayerDoses(p);
        for (int i = Potion.PRAYER.vialIds.length - 1; i >= 0 && doses < PRAYER_RESTOCK_DOSES; i--) {
            int id = Potion.PRAYER.vialIds[i];
            ItemDef potion = id > 0 ? ItemDef.get(id) : null;
            if (potion == null || potion.isNote() || drinkAction(potion) == null) continue;
            int bottleDoses = i + 1;
            while (doses + bottleDoses <= PRAYER_RESTOCK_DOSES && p.getInventory().getFreeSlots() > 2
                    && withdraw(p, id, 1)) {
                doses += bottleDoses;
            }
        }
    }

    static boolean lowPrayer(Player p) {
        return p.getStats().get(StatType.Prayer).currentLevel <= PRAYER_REFILL_LEVEL;
    }

    static void drinkPrayer(Player p) {
        Stat prayer = p.getStats().get(StatType.Prayer);
        if (prayer.currentLevel > PRAYER_REFILL_LEVEL || prayer.fixedLevel - prayer.currentLevel < 7
                || p.isLocked() || p.isStunned() || p.potDelay.isDelayed() || p.karamDelay.isDelayed()) return;
        for (int id : Potion.PRAYER.vialIds) {
            Item potion = id > 0 ? p.getInventory().findItem(id) : null;
            if (potion == null) continue;
            ItemDef def = potion.getDef();
            ItemAction drink = def == null || def.isNote() ? null : drinkAction(def);
            if (drink != null) {
                drink.handle(p, potion);
                return;
            }
        }
    }

    private static Item findPotion(Player p, Potion[] potions) {
        for (Potion potion : potions) {
            for (int id : potion.vialIds) {
                if (id <= 0) continue;
                Item item = p.getInventory().findItem(id);
                ItemDef def = item == null ? null : item.getDef();
                if (def != null && !def.isNote() && drinkAction(def) != null) return item;
            }
        }
        return null;
    }

    private static void bankPotion(Player p, Potion[] potions) {
        for (Potion potion : potions) {
            for (int i = potion.vialIds.length - 1; i >= 0; i--) {
                int id = potion.vialIds[i];
                ItemDef def = id <= 0 ? null : ItemDef.get(id);
                if (def != null && !def.isNote() && drinkAction(def) != null
                        && withdraw(p, id, 1)) return;
            }
        }
    }

    private static Item cure(Player p) {
        return findPotion(p, CURES);
    }

    static boolean drinkAntipoison(Player p) {
        if (!p.isPoisoned() || p.potDelay.isDelayed()) return false;
        Item item = cure(p);
        if (item == null) return false;
        drinkAction(item.getDef()).handle(p, item);
        return !p.isPoisoned();
    }

    private static ItemAction drinkAction(ItemDef def) {
        int slot = def.getOption("drink") - 1;
        return slot >= 0 && def.inventoryActions != null && slot < def.inventoryActions.length
                ? def.inventoryActions[slot] : null;
    }

    private int[] hatChoices(Player p, boolean atBank) {
        Item helmed = p.getInventory().findItems(item -> item.getDef().slayerHelm && meetsLevels(p, item.getDef()))
                .stream().findFirst().orElse(null);
        if (helmed == null && atBank) helmed = p.getBank().findItems(item -> item.getDef().slayerHelm && meetsLevels(p, item.getDef()))
                .stream().findFirst().orElse(null);
        return helmed == null ? new int[]{equipmentId} : new int[]{helmed.getId(), equipmentId};
    }

    private boolean prepareMelee(Player p, boolean atBank) {
        if (p.getEquipment().get(Equipment.SLOT_WEAPON) != null && selectStyle(p, false)) return true;
        if (equipMelee(p, p.getInventory().getItems(), false) || atBank && equipMelee(p, p.getBank().getItems(), true))
            return true;
        return p.getEquipment().get(Equipment.SLOT_WEAPON) == null && selectStyle(p, false);
    }

    private boolean equipMelee(Player p, Item[] items, boolean atBank) {
        for (Item item : items) {
            if (item == null || item.getId() < 0 || item.getAmount() <= 0) continue;
            ItemDef def = item.getDef();
            if (def == null || def.equipSlot != Equipment.SLOT_WEAPON || def.isNote()
                    || !meetsLevels(p, def) || def.weaponType == null || def.weaponType.attackSets == null
                    || (slot == Equipment.SLOT_SHIELD || usesDragonfire() && hasDragonfireShield(p)) && def.twoHanded
                    || requiresGodProtection() && !godProtection(p, def)
                    || this == LEAF && !def.leafBladed) continue;
            boolean melee = false;
            for (AttackSet set : def.weaponType.attackSets) if (set != null && set.style.isMelee()) melee = true;
            int id = item.getId();
            if (!melee || atBank && !withdraw(p, id, 1)) continue;
            Item carried = atBank ? p.getInventory().findItem(id) : item;
            if (carried != null) {
                p.getEquipment().equip(carried);
                p.getCombat().updateWeapon(false);
            }
            if (p.getEquipment().getId(Equipment.SLOT_WEAPON) == id && selectStyle(p, false)) return true;
        }
        return false;
    }

    private boolean prepareRanged(Player p, boolean atBank) {
        ItemDef weapon = p.getEquipment().getDef(Equipment.SLOT_WEAPON);
        if (weapon == null || weapon.rangedWeapon == null || weapon.rangedWeapon.data != null
                || weapon.rangedWeapon.allowedAmmo == null) return false;
        if (usableAmmo(p, 15)) return true;
        for (Item item : p.getInventory().getItems()) {
            if (compatible(p, weapon, item)) p.getEquipment().equip(item);
            if (usableAmmo(p, 15)) return true;
        }
        if (atBank) {
            for (BankItem item : p.getBank().getItems()) {
                if (!compatible(p, weapon, item) || item.getAmount() < 10) continue;
                int id = item.getId();
                for (int i = 0; i < 5 && withdraw(p, id, 10); i++) {
                    Item ammo = p.getInventory().findItem(id);
                    if (ammo != null) p.getEquipment().equip(ammo);
                }
                if (usableAmmo(p, 15)) return true;
            }
        }
        return usableAmmo(p, 5);
    }

    private static boolean usableAmmo(Player p, int minimum) {
        ItemDef weapon = p.getEquipment().getDef(Equipment.SLOT_WEAPON);
        Item ammo = p.getEquipment().get(Equipment.SLOT_AMMO);
        return weapon != null && weapon.rangedWeapon != null && weapon.rangedWeapon.data == null
                && weapon.rangedWeapon.allowedAmmo != null && compatible(p, weapon, ammo) && ammo.getAmount() >= minimum;
    }

    private static boolean compatible(Player p, ItemDef weapon, Item ammo) {
        if (ammo == null || ammo.getId() < 0 || ammo.getAmount() <= 0) return false;
        ItemDef def = ammo.getDef();
        return def != null && !def.isNote() && def.equipSlot == Equipment.SLOT_AMMO && def.rangedAmmo != null
                && weapon.rangedWeapon.allowAmmo(def.rangedAmmo) && weapon.rangedLevel >= def.rangedLevel
                && meetsLevels(p, def);
    }

    private static boolean meetsLevels(Player p, ItemDef def) {
        if (def == null || def.equipOption <= 0) return false;
        if (def.equipReqs != null) {
            for (int requirement : def.equipReqs)
                if (p.getStats().get(requirement >> 8).fixedLevel < (requirement & 255)) return false;
        }
        return true;
    }

    static int food(Player p) {
        int total = 0;
        for (int id : CombatAutomation.FOODS) total += p.getInventory().getAmount(id);
        return total;
    }

    private static void fill(Player p, int id, int target) {
        int need = Math.min(target - p.getInventory().getAmount(id), p.getInventory().getFreeSlots() - 2);
        while (need > 0) {
            Item stock = p.getBank().findItem(id);
            if (stock == null || stock.getAmount() <= 0) return;
            int amount = Math.min(need, stock.getAmount());
            amount = amount >= 10 ? 10 : amount >= 5 ? 5 : 1;
            if (!withdraw(p, id, amount)) return;
            need -= amount;
        }
    }

    static boolean withdraw(Player p, int id, int amount) {
        if (!p.isVisibleInterface(Interface.BANK)) return false;
        Item item = p.getBank().findItem(id);
        if (item == null || item.getAmount() < amount || ItemDef.get(id).isNote()) return false;
        int before = p.getInventory().getAmount(id);
        ActionButtonHandler.handleAction(p, 1, BANK_WIDGET | 22, -1, -1, false);
        ActionButtonHandler.handleAction(p, amount == 10 ? 4 : amount == 5 ? 3 : 2,
                BANK_WIDGET | 13, item.getSlot(), id, false);
        return p.getInventory().getAmount(id) - before == amount;
    }

    static boolean depositLoot(Player p, Map<Item, Integer> drops) {
        for (Item item : p.getInventory().getItems()) {
            if (!drops.containsKey(item)) continue;
            int amount = Math.min(drops.get(item), item.getAmount());
            int stored = p.getBank().deposit(item, amount, false);
            if (stored < amount) {
                drops.put(item, amount - stored);
                return false;
            }
            drops.remove(item);
        }
        drops.clear();
        return true;
    }
}
