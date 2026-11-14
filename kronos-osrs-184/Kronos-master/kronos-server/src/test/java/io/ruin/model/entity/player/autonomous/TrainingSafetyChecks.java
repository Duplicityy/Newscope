package io.ruin.model.entity.player.autonomous;

import java.lang.reflect.Field;
import java.util.Map;

/** Exercises real NPC spawn data and persistent hazard memory in an isolated world. */
public final class TrainingSafetyChecks {
    private static void check(boolean value,String detail) {if(!value)throw new AssertionError(detail);}
    @SuppressWarnings("unchecked")
    public static void run() throws Exception {
        check(Boolean.getBoolean("kronos.adventurers.test"),"staging only");
        Field field=Adventurers.class.getDeclaredField("actors");field.setAccessible(true);
        Map<String,Adventurer> roster=(Map<String,Adventurer>)field.get(null);
        Adventurer player=roster.get("2");
        check(player!=null && player.getCombat().getLevel()==3,"requires unchanged level-three skiller");
        AdventurerMemory previous=player.memory;
        try {
            player.memory=new AdventurerMemory();
            check(!TrainingSites.safeToWork(player,3126,3252,0),"jail guard training spot accepted");
            check(!TrainingSites.safeToWork(player,3086,3230,0),"dark wizard fishing spot accepted");
            check(TrainingSites.safeToWork(player,2030,3577,0),"normal home rejected");
            check(!TrainingSites.safeToWork(player,2030,3577,1),"unsupported plane accepted");
            // Real regression: a Rellekka rock anchored inside radius 55 routes
            // the player to 2688,3698, two tiles outside its catalogue boundary.
            check(TrainingSites.at(2688,3698,0)==null,"boundary fixture unexpectedly inside catalogue");
            check(TrainingSites.allowsPresence(2688,3698,0),"normal mining adjacency rejected");
            check(!TrainingSites.safeToWork(player,2688,3698,0),"movement margin expanded resource destinations");
            check(TrainingSites.allowsPresence(2694,3698,0),"exact bounded movement margin rejected");
            check(!TrainingSites.allowsPresence(2695,3698,0),"movement margin unbounded");
            check(!TrainingSites.allowsPresence(2688,3698,1),"movement margin permits another plane");
            io.ruin.model.map.Position position=player.getPosition().copy();
            try {
                player.getPosition().set(2688,3698,0);
                boolean actualRecovery=player.getCombat().isDead() || player.safety.needsRecovery();
                check(player.port.observe().needsRecovery==actualRecovery,"legal adjacent tile falsely requests recovery/quarantine");
            } finally {player.getPosition().copy(position);}
            boolean edgeHome=player.edgeHome;
            try {
                player.edgeHome=false;
                player.getPosition().copy(io.ruin.model.World.HOME);
                check(TrainingSites.safeToWork(player,2909,3364,0),"Falador mine fixture itself must be safe");
                io.ruin.model.map.Position arrival=TrainingSites.landingFor(player,TrainingSites.at(2909,3364,0));
                check(arrival!=null && arrival.getX()==2965 && arrival.getY()==3379,"test must use actual free Falador menu landing");
                check(!TrainingSites.safeToWork(player,arrival.getX(),arrival.getY(),arrival.getZ()),"known Imp arrival hazard missed");
                check(!TrainingSites.canTravelTo(player,2909,3364,0),"safe remote resource admitted through unsafe city landing");
                boolean queued=player.getMovement().isTeleportQueued(),locked=player.isLocked();
                check(TrainingSites.travel(player,2909,3364,0),"refused remote travel incorrectly permits an overland route");
                check(player.getMovement().isTeleportQueued()==queued && player.isLocked()==locked
                        && player.getPosition().equals(io.ruin.model.World.HOME),"blocked arrival initiated a teleport");
                player.getPosition().set(2909,3364,0);
                check(TrainingSites.canTravelTo(player,2910,3364,0),"local resource incorrectly requires unsafe city teleport");
                check(TrainingSites.canTravelTo(player,2030,3577,0),"ordinary safe Home arrival rejected");
                io.ruin.model.map.Position home=TrainingSites.landingFor(player,TrainingSites.at(2030,3577,0));
                player.memory.avoidThreat("test-arrival",home.getX(),home.getY(),home.getZ());
                check(!TrainingSites.canTravelTo(player,2030,3577,0),"learned arrival hazard ignored for a different remote target");
                player.memory.avoidedThreats.clear();
                player.edgeHome=true;
                check(!TrainingSites.canTravelTo(player,2030,3577,0),"Home override allowed arrival outside configured areas");
            } finally {player.edgeHome=edgeHome;player.getPosition().copy(position);player.memory.avoidedThreats.clear();}
            AdventurerMemory.Encounter encounter=new AdventurerMemory.Encounter();
            encounter.deaths=1;encounter.cooldownTicks=2;
            AdventurerMemory.DamageSample sample=new AdventurerMemory.DamageSample();
            sample.x=2030;sample.y=3577;sample.z=0;encounter.finalHits.add(sample);
            player.memory.encounters.put("test-danger",encounter);
            check(!TrainingSites.safeToWork(player,2031,3577,0),"recorded dangerous place immediately retried");
            check(!player.memory.avoidsLocation(2030,3577,1),"memory confused planes");
            player.memory.tick();check(player.memory.avoidsLocation(2030,3577,0),"cooldown expired early");
            player.memory.tick();check(TrainingSites.safeToWork(player,2030,3577,0),"expired cooldown permanently blocks ordinary site");
            encounter.deaths=3;check(!TrainingSites.safeToWork(player,2030,3577,0),"repeated-failure location not parked");
            System.out.println("CHECK PASS terrain: guard/wizard danger, bounded Rellekka adjacency, actual Falador arrival gating without teleport/overland fallback, local alternatives, learned arrival avoidance, Home override, plane boundaries, death cooldown");
        } finally {player.memory=previous;}
    }
}
