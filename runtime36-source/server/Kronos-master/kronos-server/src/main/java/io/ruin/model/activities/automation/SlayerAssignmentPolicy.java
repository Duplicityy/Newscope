package io.ruin.model.activities.automation;

import io.ruin.Server;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.skills.slayer.SlayerTask;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Task types with a supported combat profile and a route recorded by the v42 coverage check. */
public final class SlayerAssignmentPolicy {
    private static final Set<String> VERIFIED_TASKS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "Aberrant Spectres", "Abyssal Demons", "Ankou", "Aviansies", "Bandits", "Banshees", "Basilisks", "Bats", "Birds",
            "Black Demons", "Black Dragons", "Bloodveld", "Blue Dragons", "Brine Rats", "Bronze Dragons", "Cave Bugs",
            "Catablepon", "Cave Crawlers", "Cave Horrors", "Cave Kraken", "Cave Slimes", "Cockatrice", "Cows",
            "Crawling Hands", "Dagannoth", "Dark Beasts", "Drakes", "Dust Devils", "Dwarves", "Earth Warriors",
            "Fire Giants", "Fleshcrawlers", "Gargoyles", "Ghosts",
            "Goblins", "Greater Demons", "Green Dragons", "Hellhounds", "Hill Giants", "Hobgoblins",
            "Ice Giants", "Ice Warriors", "Icefiends", "Infernal Mages", "Iron Dragons", "Jellies", "Kalphite",
            "Kurask", "Lesser Demons", "Magic Axes", "Minotaurs", "Mithril Dragons", "Monkeys", "Moss Giants",
            "Nechryael", "Pyrefiends", "Rats", "Rockslugs", "Scorpions", "Shades", "Skeletal Wyverns",
            "Skeletons", "Smoke Devils", "Spiders", "Spiritual Creatures", "Steel Dragons", "Trolls", "Turoth",
            "TzHaar", "Waterfiends", "Wyrms", "Zombies"
    )));

    private SlayerAssignmentPolicy() {}

    public static boolean restricted(Player player) {
        String name = player.getName();
        if (name == null || name.isEmpty()) return false;
        for (String account : System.getProperty("newscape.slayer.allowedAccounts", "").split(",")) {
            if (name.equalsIgnoreCase(account.trim())) return true;
        }
        return false;
    }

    static boolean verified(SlayerTask task) {
        return task != null && VERIFIED_TASKS.contains(task.name);
    }

    public static boolean allows(Player player, SlayerTask.Type tier, boolean wilderness, SlayerTask task) {
        if (!restricted(player)) return true;
        if (wilderness || tier == SlayerTask.Type.BOSS || !verified(task)) return false;
        if ("Black Dragons".equals(task.name) && !SlayerDragonRoutes.blackDragonsReady(player)) return false;
        if ("Earth Warriors".equals(task.name) && !SlayerAdditionalRoutes.earthWarriorsReady(player)) return false;
        for (NPC npc : SlayerWorld.current(Server.currentTick()).monsters(task)) {
            if (SlayerWorld.present(npc) && SlayerLoadout.forNpc(npc) != null && npc.getDef().attackOption > 0
                    && SlayerAdditionalRoutes.safeFor(player, npc)
                    && CombatAutomation.manageable(SlayerLoadout.combatRisk(npc), player.getMaxHp()))
                return true;
        }
        return false;
    }
}
