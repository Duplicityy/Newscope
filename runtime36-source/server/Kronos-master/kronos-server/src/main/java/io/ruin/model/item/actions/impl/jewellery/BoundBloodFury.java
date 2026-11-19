package io.ruin.model.item.actions.impl.jewellery;

import io.ruin.cache.ItemDef;
import io.ruin.model.combat.Hit;
import io.ruin.model.combat.Killer;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPCCombat;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.listeners.DeathListener;
import io.ruin.model.item.Item;
import io.ruin.model.item.ItemContainerG;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.bank.Bank;
import io.ruin.model.item.containers.bank.BankItem;
import io.ruin.model.map.ground.GroundItem;

import java.util.Collections;
import java.util.Map;

/** Owned combat loot can go straight to Bound owner bank while this particular amulet is worn. */
public final class BoundBloodFury {
    public static final int ITEM_ID = 53200;
    private static final int ACCOUNT_ID = Integer.getInteger("newscape.boundBloodFury.accountId", -1);
    private static final String BINDING = "newscape.boundBloodFury";
    private static final ThreadLocal<Player> KILL_REWARDS = new ThreadLocal<>();

    private BoundBloodFury() {}

    static boolean accountMatches(int userId, String name) {
        return ACCOUNT_ID > 0 && userId == ACCOUNT_ID && name != null
                && name.equalsIgnoreCase(System.getProperty("newscape.boundBloodFury.accountName", ""));
    }

    private static boolean owner(Player p) {
        return p != null && !Boolean.getBoolean("kronos.public") && accountMatches(p.getUserId(), p.getName());
    }

    private static boolean authentic(Item item) {
        return item != null && item.getId() == ITEM_ID
                && Integer.toString(ACCOUNT_ID).equals(item.getAttributeString(BINDING, ""));
    }

    public static boolean equipAllowed(Player p, Item item) {
        if (item == null || item.getId() != ITEM_ID || owner(p) && authentic(item)) return true;
        p.sendMessage("This amulet is bound to Bound owner.");
        return false;
    }

    public static boolean transferAllowed(Player p, Item item) {
        if (item == null || item.getId() != ITEM_ID) return true;
        p.sendMessage("Bound owner Blood Fury cannot be sold or traded.");
        return false;
    }

    public static boolean pickupAllowed(Player p, int id, int originalOwner) {
        return id != ITEM_ID || originalOwner == ACCOUNT_ID && owner(p);
    }

    private static boolean wearing(Player p) {
        return owner(p) && authentic(p.getEquipment().get(Equipment.SLOT_AMULET));
    }

    public static void dropItems(NPCCombat combat, Killer killer) {
        Player player = killer == null ? null : killer.player;
        Player previous = KILL_REWARDS.get();
        if (!wearing(player) && previous == null) {
            combat.dropItems(killer);
            return;
        }
        enter(player);
        try {
            combat.dropItems(killer);
        } finally {
            restore(previous);
        }
    }

    public static void deathListener(DeathListener listener, Entity npc, Killer killer, Hit hit) {
        Player player = killer == null ? null : killer.player;
        Player previous = KILL_REWARDS.get();
        if (!wearing(player) && previous == null) {
            listener.handle(npc, killer, hit);
            return;
        }
        enter(player);
        try {
            listener.handle(npc, killer, hit);
        } finally {
            restore(previous);
        }
    }

    private static void enter(Player p) {
        restore(wearing(p) ? p : null);
    }

    private static void restore(Player p) {
        if (p == null) KILL_REWARDS.remove();
        else KILL_REWARDS.set(p);
    }

    public static boolean bankGround(GroundItem drop, int appearMinutes, Map<String, String> attributes) {
        Player p = KILL_REWARDS.get();
        return p != null && appearMinutes != 0 && drop.originalOwner == p.getUserId()
                && drop.activeOwner == p.getUserId() && drop.getTimeDropped() == 0
                && bank(p, drop.id, drop.amount, attributes);
    }

    public static boolean bankInventory(ItemContainerG<?> inventory, int id, int amount, Map<String, String> attributes) {
        Player p = KILL_REWARDS.get();
        return p != null && inventory == p.getInventory() && bank(p, id, amount, attributes);
    }

    /** The guaranteed head has an implicit recipient and a public legacy spawn. */
    public static GroundItem spawnVorkathHead(GroundItem drop, Player p) {
        if (KILL_REWARDS.get() == p && wearing(p) && bank(p, drop.id, drop.amount, Collections.emptyMap())) return drop;
        return drop.spawn();
    }

    static boolean stackFits(int amount, int existing) {
        return amount > 0 && existing >= 0 && (long) amount + existing <= Integer.MAX_VALUE;
    }

    private static boolean bank(Player p, int id, int amount, Map<String, String> attributes) {
        if (id == ITEM_ID || amount <= 0 || !p.isOnline() || p.logoutStage != 0 || p.joinedTournament
                || p.getDuel().stage >= 4 || !p.getBankPin().allowsPassiveDeposit()
                || PlayerFile.isSaveQuarantined(p)) return false;
        ItemDef def = ItemDef.get(id);
        if (def == null || def.isPlaceholder()) return false;
        int bankId = def.isNote() ? def.notedId : id;
        ItemDef bankDef = ItemDef.get(bankId);
        if (bankDef == null || bankDef.isNote() || bankDef.isPlaceholder()) return false;

        Bank bank = p.getBank();
        if (attributes == null || attributes.isEmpty()) {
            // Bank.add normally clamps a full int stack. Leave the whole drop in its usual location in that case.
            for (BankItem item : bank.getItems()) {
                if (item == null || item.getId() != bankId || item.hasAttributes()) continue;
                if (!stackFits(amount, item.getAmount())) return false;
                break;
            }
        }
        return bank.add(bankId, amount, attributes) == amount;
    }
}
