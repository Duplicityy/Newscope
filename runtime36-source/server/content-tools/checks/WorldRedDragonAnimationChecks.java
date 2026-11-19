import io.ruin.Server;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.protocol.world.WorldType;
import io.ruin.cache.AnimDef;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.World;
import io.ruin.model.activities.miscpvm.BasicCombat;
import io.ruin.model.activities.miscpvm.dragons.ChromaticDragon;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.combat.Hit;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.route.types.RouteEntity;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Exercises configured melee and scripted dragonfire with detached actors. */
public final class WorldRedDragonAnimationChecks {
    private static final class RecordingNpc extends NPC {
        int animation = -1;
        int graphic = -1;
        Hit lastHit;

        RecordingNpc(int id) {
            super(id);
        }

        void placeForAttack(int east, int north) {
            position = new Position(east, north, 0);
        }

        @Override
        public int animate(int sequence) {
            animation = sequence;
            return 0;
        }

        @Override
        public void graphics(int sequence, int height, int delay) {
            graphic = sequence;
        }

        @Override
        public int hit(Hit... hits) {
            require(hits.length == 1, "Exactly one hit per action");
            lastHit = hits[0];
            return 0;
        }
    }

    public static void main(String[] args) {
        try {
            verify(args);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void verify(String[] args) throws Exception {
        World.type = WorldType.PVP;
        Server.fileStore = new FileStore(args[0]);
        AnimDef.load();
        NPCDef.load();
        ItemDef.load();
        npc_combat loader = new npc_combat();
        loader.fromJson("installed red dragons", read(args[1]));
        for (int id = 247; id <= 251; id++) {
            NPCDef def = NPCDef.get(id);
            require(def.combatHandlerClass.equals(ChromaticDragon.class), "Installed red dragon combat handler");
            require(def.combatInfo.attack_animation == -1, "Reproduced missing red dragon profile attack");
        }
        if (args.length == 4) {
            loader.fromJson("installed regional combat", read(args[3]));
            require(NPCDef.get(13100).combatInfo.attack_animation == -1, "Reproduced missing guard attack");
            require(NPCDef.get(14933).combatInfo.attack_animation == -1, "Reproduced missing pirate attack");
        }
        loader.fromJson("staged world animation profiles", read(args[2]));
        Method setCombat = NPC.class.getDeclaredMethod("setCombat");
        setCombat.setAccessible(true);
        Method dragonfire = ChromaticDragon.class.getDeclaredMethod("meleeDragonfire");
        dragonfire.setAccessible(true);
        for (int id = 247; id <= 251; id++) {
            RecordingNpc attacker = new RecordingNpc(id);
            RecordingNpc victim = new RecordingNpc(id);
            require((Boolean) setCombat.invoke(attacker), "Native combat loaded");
            require(attacker.getCombat().getClass() == ChromaticDragon.class, "Retained chromatic handler");
            ChromaticDragon combat = (ChromaticDragon) attacker.getCombat();
            combat.setTarget(victim);
            combat.basicAttack();
            require(attacker.animation == 80 && attacker.graphic == -1, "Ordinary melee played the configured sequence");
            require(victim.lastHit.attackStyle == AttackStyle.SLASH, "Ordinary melee retained its slash hit");
            dragonfire.invoke(combat);
            require(attacker.animation == 81 && attacker.graphic == 1, "Scripted dragonfire retained its sequence and graphic");
            require(victim.lastHit.attackStyle == AttackStyle.DRAGONFIRE, "Dragonfire retained its hit style");
            require(combat.getInfo().defend_animation == 89 && combat.getInfo().death_animation == 92,
                    "Native block and death sequences linked");
        }
        System.out.println("PASS all five red dragons play the configured melee sequence and retained scripted dragonfire.");
        if (args.length == 4) {
            int region = Region.getId(3200, 3200);
            Region.LOADED[region] = new Region(region);
            verifyRegionalAttack(setCombat, 13100, 9033, AttackStyle.STAB);
            verifyRegionalAttack(setCombat, 14933, 9030, AttackStyle.SLASH);
            System.out.println("PASS native BasicCombat emits guard 9033 with STAB and pirate 9030 with SLASH.");
        }
    }

    private static void verifyRegionalAttack(Method setCombat, int id, int sequence, AttackStyle style) throws Exception {
        RecordingNpc attacker = new RecordingNpc(id);
        RecordingNpc victim = new RecordingNpc(id);
        attacker.placeForAttack(3200, 3200);
        victim.placeForAttack(3201, 3200);
        RouteEntity direction = new RouteEntity();
        direction.finishX = 3200;
        direction.finishY = 3200;
        attacker.getRouteFinder().routeEntity = direction;
        require((Boolean) setCombat.invoke(attacker), "Native regional combat loaded");
        require(attacker.getCombat().getClass() == BasicCombat.class, "Existing regional BasicCombat retained");
        attacker.getCombat().setTarget(victim);
        require(attacker.getCombat().attack(), "Adjacent target receives a real attack");
        require(attacker.animation == sequence && victim.lastHit.attackStyle == style, "Regional animation and hit style");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void require(boolean valid, String description) {
        if (!valid) throw new AssertionError(description);
    }
}
