package io.ruin.model.entity.player.autonomous;

import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.inter.Interface;
import io.ruin.model.item.Item;
import io.ruin.model.map.Tile;
import io.ruin.model.stat.StatType;
import io.ruin.network.incoming.handlers.ActionButtonHandler;
import rsps.sim.BrainScheduler;
import rsps.sim.ProgressionBrain;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

/** Isolated actor-24 fixtures in the staging world only; never loaded in production. */
public final class ActivitySupplyIntegrationChecks {
    private static Adventurer fixture;
    private static String processing;
    private static boolean pendingStart;
    private static double xpBefore;
    private static int inputBefore, bankBefore;
    private static long bankTripsBefore;

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @SuppressWarnings("unchecked") private static Adventurer reset() throws Exception {
        check(Boolean.getBoolean("kronos.adventurers.test"), "Staging only");
        Field actors = Adventurers.class.getDeclaredField("actors"); actors.setAccessible(true);
        Adventurer p = ((Map<String,Adventurer>)actors.get(null)).get("24");
        check(p != null && p.isOnline() && !p.isLocked() && p.quarantineReason==null, "Need healthy unlocked staging actor 24");
        long now=Server.currentTick();
        Properties checkpoint=p.brain.checkpoint(now);
        checkpoint.setProperty("state","PLANNING"); checkpoint.setProperty("after","PLAN");
        checkpoint.setProperty("rest","0"); checkpoint.setProperty("focus","0"); checkpoint.setProperty("focusSkill","");
        Field scheduler = Adventurers.class.getDeclaredField("scheduler"); scheduler.setAccessible(true);
        ((BrainScheduler)scheduler.get(null)).remove(p.actorId);
        p.resetActions(true,true,true); TrainingSites.release(p);
        p.getInventory().clear(); p.getBank().clear(); p.getEquipment().clear();
        p.getStats().get(StatType.Hitpoints).currentLevel = p.getStats().get(StatType.Hitpoints).fixedLevel;
        p.port = new KronosActivityPort(p); p.safety = new AdventurerSafety(p);
        ProgressionBrain brain=new ProgressionBrain(p.actorId,p.port,ProgressionBrain.Config.humanDefaults(600),p.policy,p.seed);
        brain.restore(checkpoint,now); // Initialized before registry autosaves can see the fixture.
        p.brain=brain;
        fixture = p; processing = null; pendingStart = false;
        return p;
    }
    private static ProgressionBrain.Activity activity(String skill) {
        return new ProgressionBrain.Activity("check_"+skill,skill,Collections.singleton(skill),1,400);
    }
    private static StatType stat(String skill) { return StatType.valueOf(Character.toUpperCase(skill.charAt(0))+skill.substring(1)); }

    public static void run(String command) throws Exception {
        check(Boolean.getBoolean("kronos.adventurers.test"), "Staging only");
        long tick = Server.currentTick();
        if (command.equals("bank")) {
            Adventurer p = reset();
            p.getInventory().add(995,1000); p.getInventory().add(1323,1); p.getInventory().add(1349,1);
            p.getInventory().add(361,10); p.getInventory().add(1511,15);
            check(p.getInventory().getFreeSlots()==0,"bank fixture must be full");
            p.getBank().open(); check(p.port.bankSurplus(tick,-1),"bank action incomplete");
            check(p.getInventory().getAmount(1323)==1 && p.getInventory().getAmount(1349)==1,"owned weapon/tool banked");
            check(p.getInventory().getAmount(361)==4 && p.getBank().getAmount(361)==6,"bounded recovery reserve");
            check(p.getInventory().getAmount(1511)==0 && p.getBank().getAmount(1511)==15,"gathered log conservation");
            p.getBank().open();
            ActionButtonHandler.handleAction(p,1,(Interface.BANK<<16)|24,-1,-1,false); // User had selected notes.
            check(p.port.withdrawOwned(1511,3,tick),"normal bank withdrawal failed");
            check(p.getInventory().getAmount(1511)==5 && p.getInventory().getAmount(1512)==0,"withdrew notes instead of logs");
            check(p.getBank().getAmount(1511)==10 && p.getInventory().getAmount(995)==1000,"withdrawal duplicated/lost supplies");
            for(Item item:p.getInventory().getItems().clone()) if(item!=null && (item.getId()==1323 || item.getId()==361)) p.getBank().deposit(item,item.getAmount(),false);
            java.lang.reflect.Method prepare=p.port.getClass().getDeclaredMethod("prepareCombatSupplies",long.class); prepare.setAccessible(true);
            p.getBank().open(); prepare.invoke(p.port,tick);
            check(p.getInventory().getAmount(1323)==1,"owned banked scimitar not retrieved");
            p.getBank().open(); prepare.invoke(p.port,tick);
            check(p.getEquipment().getId(io.ruin.model.item.containers.Equipment.SLOT_WEAPON)==1323,"retrieved weapon not equipped");
            check(p.getInventory().getAmount(361)==5 && p.getBank().getAmount(361)==5,"owned recovery withdrawal/conservation");
            check((Boolean)prepare.invoke(p.port,tick),"combat not ready after real resupply");
            System.out.println("CHECK PASS supply bank: full inventory, bounded food, weapon/tools retained, item-mode withdrawal, owned combat resupply, conservation");
        } else if (command.equals("eligibility")) {
            Adventurer p = reset();
            p.getInventory().add(1511,1); p.getInventory().add(946,1);
            check(p.port.candidates(p.port.observe()).stream().anyMatch(a->a.primarySkill.equals("fletching")),"fletching depends on axe/coins");
            p.getInventory().clear(); p.getBank().add(1511,5); p.getInventory().add(590,1);
            check(p.port.candidates(p.port.observe()).stream().anyMatch(a->a.primarySkill.equals("firemaking")),"banked logs not eligible for firemaking");
            check(!AdventurerSafety.isRecoveryItem(new Item(712,1)),"unsupported tea accepted as recovery");
            int rejected = 0;
            for (NPC n : World.npcs) {
                if (n.getDef().name.equals("Rat") && KronosActivityPort.option(n.getDef().options,"attack")<1) {
                    check(!p.port.eligibleCombatTarget(n),"nonattackable special rat selected"); rejected++;
                }
            }
            System.out.println("CHECK PASS supply eligibility: independent tools, banked fuel, real consumables; special rats rejected="+rejected);
        } else if (command.endsWith("-start")) {
            String skill = command.substring(0,command.length()-6);
            check(skill.equals("firemaking") || skill.equals("fletching") || skill.equals("prayer"),"unknown processing fixture");
            Adventurer p = reset(); processing = skill;
            int input = skill.equals("prayer") ? 526 : 1511;
            int tool = skill.equals("firemaking") ? 590 : skill.equals("fletching") ? 946 : -1;
            if (tool>0) p.getInventory().add(tool,1);
            p.getInventory().add(input,tool>0?27:28);
            check(p.getInventory().getFreeSlots()==0,"processing fixture must be full");
            if (skill.equals("firemaking")) {
                boolean positioned = false;
                for (int x=2025;x<=2040 && !positioned;x++) for(int y=3571;y<=3585;y++) {
                    Tile tile=Tile.get(x,y,0);
                    if ((tile==null || tile.isFloorFree()) && Tile.allowObjectPlacement(x,y,0) && TrainingSites.safeToWork(p,x,y,0)) {
                        p.getMovement().teleport(x,y,0); positioned=true; break;
                    }
                }
                check(positioned,"no safe clear firemaking fixture tile");
            }
            xpBefore=p.getStats().get(stat(skill)).experience; inputBefore=p.getInventory().getAmount(input);
            bankBefore=p.getBank().getAmount(input); bankTripsBefore=p.port.bankTrips;
            pendingStart=true;
            System.out.println("CHECK processing fixture ready: "+skill+" full inventory; call pulse after normal teleport unlock, then check after real world ticks");
        } else if (command.equals("pulse")) {
            check(fixture!=null && processing!=null,"missing processing fixture");
            if (pendingStart) {
                if (fixture.isLocked()) {
                    System.out.println("CHECK processing waiting for ordinary teleport/animation unlock; retry pulse");
                    return;
                }
                check(fixture.port.start(activity(processing),tick),"processing start rejected after unlock");
                pendingStart=false;
            }
            System.out.println("CHECK processing poll: "+fixture.port.poll(tick));
        } else if (command.equals("check")) {
            check(fixture!=null && processing!=null,"missing processing fixture");
            check(!pendingStart,"processing has not started; pulse after ordinary teleport unlock");
            Adventurer p=fixture; int input=processing.equals("prayer")?526:1511;
            check(p.quarantineReason==null,"processing quarantined: "+p.quarantineReason);
            check(p.getStats().get(stat(processing)).experience>xpBefore,"normal processing handler has not awarded XP yet");
            check(p.getInventory().getAmount(input)==inputBefore-1,"one processing action must consume exactly one input");
            check(p.getBank().getAmount(input)==bankBefore && p.port.bankTrips==bankTripsBefore,"full processing inventory was banked");
            if(processing.equals("fletching")) check(p.getInventory().getAmount(52)==15,"normal shaft output missing");
            System.out.println("CHECK PASS supply "+processing+": actual handler XP/input consumption from a full inventory, no deposit loop");
        } else throw new IllegalArgumentException(command);
    }
}
