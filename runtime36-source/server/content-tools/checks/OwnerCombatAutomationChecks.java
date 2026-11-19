package io.ruin.model.activities.automation;

import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.process.ProcessWorker;
import io.ruin.api.protocol.world.WorldStage;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.InterfaceDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.cache.Varpbit;
import io.ruin.data.impl.items.weapon_types;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.World;
import io.ruin.model.activities.bosses.slayer.ThermonuclearSmokeDevil;
import io.ruin.model.activities.duelarena.Duel;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.combat.AttackType;
import io.ruin.model.combat.WeaponType;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerCombat;
import io.ruin.model.entity.player.PlayerMovement;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.containers.Inventory;
import io.ruin.model.map.MapListener;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.skills.slayer.SlayerTask;
import io.ruin.model.stat.StatList;
import io.ruin.model.stat.StatType;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;

public final class OwnerCombatAutomationChecks {
    private static int checks;

    private OwnerCombatAutomationChecks() {}

    public static void main(String[] args) {
        try {
            Path fixture = Paths.get(args[0]);
            configure(fixture);
            policyChecks();
            nativeCombatChecks();
            smokeDevilChecks();
            System.out.println("PASS " + checks + " owner combat automation policy and detached native checks.");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void policyChecks() {
        verify(CombatAutomation.priority(StatType.Attack, false, AttackStyle.SLASH, AttackType.ACCURATE) == 2, "accurate attack preferred");
        verify(CombatAutomation.priority(StatType.Strength, false, AttackStyle.CRUSH, AttackType.CONTROLLED) == 1, "controlled style fallback");
        verify(CombatAutomation.priority(StatType.Defence, false, AttackStyle.RANGED, AttackType.LONG_RANGED) > 0, "defensive ranged fallback");
        verify(CombatAutomation.priority(StatType.Magic, false, AttackStyle.CRUSH, AttackType.ACCURATE) == 0, "melee will not substitute for Magic");
        verify(CombatAutomation.priority(StatType.Magic, true, AttackStyle.MAGIC, AttackType.ACCURATE) == 0, "Thermy prefers melee damage");
        verify(CombatAutomation.manageable(3, 12) && !CombatAutomation.manageable(4, 12), "scaled routine threat cap");
        verify(CombatAutomation.needsFood(55, 99, 8) && !CombatAutomation.needsFood(99, 99, 8), "food needs missing health");
        verify(CombatAutomation.needsFood(15, 25, 8) && !CombatAutomation.needsFood(15, 25, 0), "native threat triggers food urgency");
        verify(CombatAutomation.supports(StatType.Slayer) && !CombatAutomation.supports(StatType.Mining), "scope accurately advertised");
    }

    private static void configure(Path fixture) throws Exception {
        Thread.currentThread().setName("server-worker #1");
        Server.dataFolder = fixture.resolve("data").toFile();
        Server.fileStore = new FileStore(fixture.resolve("Cache").toString(), 8 * 1024 * 1024);
        World.type = WorldType.PVP;
        World.stage = WorldStage.DEV;
        Varpbit.load();
        InterfaceDef.load();
        ObjectDef.load();
        ItemDef.load();
        NPCDef.load();
        Class.forName("io.ruin.model.item.actions.impl.Consumable");
        new weapon_types().fromJson("weapon_types", text(fixture.resolve("data/items/weapon_types.json")));
        npc_combat npc = new npc_combat();
        npc.fromJson("Chicken", text(fixture.resolve("data/npcs/combat/Chicken.json")));
        npc.fromJson("Thermonuclear_smoke_devil", text(fixture.resolve("data/npcs/combat/Thermonuclear_smoke_devil.json")));
        MapListener.LISTENERS = new MapListener[0];
        for (int region = 0; region < Region.LOADED.length; region++) {
            Region.LOADED[region] = new Region(region);
            Region.LOADED[region].empty = true;
        }
        Field ticks = ProcessWorker.class.getDeclaredField("executions");
        ticks.setAccessible(true);
        ticks.setLong(Server.worker, 100);
    }

    private static void nativeCombatChecks() {
        Probe player = new Probe(new Position(3229, 3147, 0));
        NPC chicken = new NPC(1173).spawn(3230, 3147, 0);
        player.visible.add(chicken);
        CombatAutomation strength = new CombatAutomation(player, StatType.Strength, 15, 100);
        verify(strength.pulse(100) == null, "start continues");
        verify(player.combat.getTarget() == chicken, "native NPC action acquired real target at first world slot");
        verify(player.combat.getAttackType() == AttackType.AGGRESSIVE, "native attack-set change selected Strength style");
        strength.stop();
        verify(player.combat.getTarget() == null, "stop released only owned target");

        CombatAutomation noTask = new CombatAutomation(player, StatType.Slayer, 15, 101);
        verify(noTask.pulse(101).contains("Slayer assignment"), "Slayer requires a real assigned task");
        noTask.stop();

        SlayerTask birds = new SlayerTask();
        birds.name = "Birds";
        player.slayerTask = birds;
        player.slayerTaskRemaining = 2;
        chicken.getCombat().getInfo().slayerTasks = new SlayerTask[]{birds};
        CombatAutomation assigned = new CombatAutomation(player, StatType.Slayer, 15, 102);
        verify(assigned.pulse(102) == null && player.combat.getTarget() == chicken, "native Slayer assignment filters and targets bird");
        assigned.stop();

        player.stats.set(StatType.Hitpoints, 50, 0);
        player.setHp(10);
        player.inventory.add(379, 1);
        CombatAutomation recover = new CombatAutomation(player, StatType.Hitpoints, 60, 103);
        verify(recover.pulse(103) == null, "injured combat waits for recovery");
        verify(player.combat.getTarget() == null && player.getHp() == 22 && !player.inventory.hasId(379),
                "native consumable healed and consumed lobster before engaging");
        recover.stop();

        Probe fighting = new Probe(new Position(3229, 3147, 0));
        fighting.visible.add(chicken);
        fighting.stats.set(StatType.Hitpoints, 50, 0);
        fighting.inventory.add(379, 1);
        CombatAutomation interrupted = new CombatAutomation(fighting, StatType.Hitpoints, 60, 104);
        verify(interrupted.pulse(104) == null && fighting.combat.getTarget() == chicken, "combat starts while healthy");
        fighting.setHp(20);
        verify(interrupted.pulse(105) == null && fighting.combat.getTarget() == chicken
                && fighting.getHp() == 32 && !fighting.inventory.hasId(379), "native eating reclaims interrupted owned target");
        interrupted.stop();
        chicken.remove();
    }

    private static void smokeDevilChecks() {
        Probe player = new Probe(new Position(2376, 9452, 0));
        NPC boss = new NPC(499).spawn(2374, 9452, 0);
        verify(boss.getCombat() instanceof ThermonuclearSmokeDevil, "native boss handler loaded from selected JSON");
        player.visible.add(boss);
        CombatAutomation low = CombatAutomation.smokeDevil(player, 2, 110);
        verify(low.pulse(110).contains("93 Slayer") && player.combat.getTarget() == null, "requires native Slayer level");
        low.stop();
        player.stats.set(StatType.Slayer, 93, 0);
        CombatAutomation unmasked = CombatAutomation.smokeDevil(player, 2, 110);
        verify(unmasked.pulse(110).contains("facemask") && player.combat.getTarget() == null, "wear required smoke protection");
        unmasked.stop();
        player.equipment.set(Equipment.SLOT_HAT, new Item(4164));
        CombatAutomation noTask = CombatAutomation.smokeDevil(player, 2, 110);
        verify(noTask.pulse(110).contains("assignment") && player.combat.getTarget() == null, "requires eligible assignment");
        noTask.stop();

        SlayerTask smoke = new SlayerTask();
        smoke.name = "Smoke Devils";
        player.slayerTask = smoke;
        player.slayerTaskRemaining = 4;
        boss.getCombat().getInfo().slayerTasks = new SlayerTask[]{smoke};
        player.stats.set(StatType.Hitpoints, 90, 0);
        player.setHp(90);
        player.inventory.add(385, 2);
        CombatAutomation run = CombatAutomation.smokeDevil(player, 2, 110);
        verify(run.pulse(110) == null && player.combat.getTarget() == boss, "eligible boss mode uses native attack action");
        player.thermonuclearSmokeDevilKills.increment(player);
        verify(run.remainingBossKills() == 1, "counts credited native counter change");
        player.thermonuclearSmokeDevilKills.increment(player);
        verify(run.pulse(111).contains("goal reached"), "native kill goal completes");
        run.stop();
        verify(player.combat.getTarget() == null, "boss stop clears owned attack");
        boss.remove();
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void verify(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }

    private static final class Probe extends Player {
        final PlayerMovement movement = new PlayerMovement(this);
        final Inventory inventory = new Inventory();
        final Equipment equipment = new Equipment();
        final StatList stats = new StatList();
        final PlayerCombat combat = new PlayerCombat();
        final Duel duel = new Duel(this);
        final Collection<NPC> visible = new ArrayList<>();

        Probe(Position position) {
            this.position = position.copy();
            inventory.init(28, false);
            equipment.init(14, false);
            try {
                Field statsField = Player.class.getDeclaredField("stats");
                statsField.setAccessible(true);
                statsField.set(this, stats);
                Field combatField = Player.class.getDeclaredField("combat");
                combatField.setAccessible(true);
                combatField.set(this, combat);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            stats.init(this);
            combat.init(this);
            combat.weaponType = WeaponType.UNARMED;
            combat.changeAttackSet(0);
        }

        @Override public PlayerMovement getMovement() { return movement; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public Equipment getEquipment() { return equipment; }
        @Override public StatList getStats() { return stats; }
        @Override public PlayerCombat getCombat() { return combat; }
        @Override public Duel getDuel() { return duel; }
        @Override public Collection<NPC> localNpcs() { return visible; }
        @Override public void face(Entity target) {}
        @Override public void faceNone(boolean delay) {}
        @Override public int animate(int id, int delay) { return 0; }
        @Override public void privateSound(int id, int type, int delay) {}
        @Override public void updateVarp(int id) {}
        @Override public void sendMessage(String message) {}
        @Override public void sendFilteredMessage(String message) {}
    }
}
