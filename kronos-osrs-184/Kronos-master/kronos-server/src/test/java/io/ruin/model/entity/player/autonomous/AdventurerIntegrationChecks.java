package io.ruin.model.entity.player.autonomous;
import io.ruin.Server;
import io.ruin.model.entity.player.*;
import io.ruin.model.stat.*;
import io.ruin.model.combat.Hit;
import io.ruin.model.map.object.GameObject;
import rsps.sim.*;
import java.lang.reflect.*;
import java.util.*;

/** Explicit staging-only integration checks. Not packaged in the production server. */
public final class AdventurerIntegrationChecks {
 private static double[] restingXp;
 private static int restingInventory;
 private static long restStarted;
 @SuppressWarnings("unchecked") private static Map<String,Adventurer> actors() throws Exception {
  Field f=Adventurers.class.getDeclaredField("actors");f.setAccessible(true);return (Map<String,Adventurer>)f.get(null);
 }
 private static BrainScheduler scheduler() throws Exception {Field f=Adventurers.class.getDeclaredField("scheduler");f.setAccessible(true);return (BrainScheduler)f.get(null);}
 private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void run(String command) throws Exception {
  check(Boolean.getBoolean("kronos.adventurers.test"),"Staging only");
  Map<String,Adventurer> actors=actors();long now=Server.currentTick();
  if(command.equals("prayer")) {
   PrayerMemoryChecks.run();
   Adventurer p=actors.values().stream().filter(a->a.isOnline() && !a.isLocked() && !a.isStunned()
           && !a.getCombat().isDead() && !a.getCombat().isDefending(17) && a.getCombat().getTarget()==null
           && a.quarantineReason==null && a.archetype!=BuildPolicy.Archetype.SKILLER)
           .findFirst().orElseThrow(()->new AssertionError("No unlocked normal-build actor available for prayer checks"));
   p.resetActions(true,true,true);
   Stat stat=p.getStats().get(StatType.Prayer);int fixed=stat.fixedLevel,current=stat.currentLevel;double xp=stat.experience;
   long slashEnd=now+p.getPrayer().slashDelay.remaining();
   List<io.ruin.model.skills.prayer.Prayer> originalProtection=new ArrayList<>();
   for(io.ruin.model.skills.prayer.Prayer q:ProtectionPrayerPolicy.PROTECTION_PRAYERS)if(p.getPrayer().isActive(q))originalProtection.add(q);
   p.getPrayer().slashDelay.reset();
   try {
    for(io.ruin.model.combat.AttackStyle style:io.ruin.model.combat.AttackStyle.values()) {
     io.ruin.model.skills.prayer.Prayer expected=ProtectionPrayerPolicy.forStyle(style);
     stat.fixedLevel=99;stat.currentLevel=99;ProtectionPrayerPolicy.apply(p,style,true);
     if(expected!=null) {
      check(p.getPrayer().isActive(expected),"wrong protection for "+style);
      ProtectionPrayerPolicy.apply(p,style,true);check(p.getPrayer().isActive(expected),"repeated tick toggled prayer off");
      stat.fixedLevel=expected.level-1;ProtectionPrayerPolicy.apply(p,style,true);check(!p.getPrayer().isActive(expected),"level bypass");
      stat.fixedLevel=expected.level;stat.currentLevel=1;ProtectionPrayerPolicy.apply(p,style,true);check(p.getPrayer().isActive(expected),"exact level rejected");
      stat.currentLevel=0;ProtectionPrayerPolicy.apply(p,style,true);check(!p.getPrayer().isActive(expected),"empty points bypass");
     } else for(io.ruin.model.skills.prayer.Prayer q:ProtectionPrayerPolicy.PROTECTION_PRAYERS)check(!p.getPrayer().isActive(q),"unsupported attack guessed");
    }
    stat.fixedLevel=99;stat.currentLevel=99;p.getPrayer().slashDelay.delay(5);
    ProtectionPrayerPolicy.apply(p,io.ruin.model.combat.AttackStyle.CRUSH,true);
    check(!p.getPrayer().isActive(io.ruin.model.skills.prayer.Prayer.PROTECT_FROM_MELEE),"slash restriction bypass");p.getPrayer().slashDelay.reset();
    ProtectionPrayerPolicy.apply(p,io.ruin.model.combat.AttackStyle.CRUSH,true);ProtectionPrayerPolicy.apply(p,null,false);
    check(!p.getPrayer().isActive(io.ruin.model.skills.prayer.Prayer.PROTECT_FROM_MELEE),"prayer left on outside combat");
        stat.fixedLevel=99;stat.currentLevel=99;
    io.ruin.model.entity.npc.NPC source=null;
    for(io.ruin.model.entity.npc.NPC n:io.ruin.model.World.npcs)if(n.getCombat()!=null && n.getCombat().getClass()==io.ruin.model.activities.miscpvm.BasicCombat.class){source=n;break;}
    check(source!=null,"no basic enemy for hit test");
    for(io.ruin.model.combat.AttackStyle style:new io.ruin.model.combat.AttackStyle[]{io.ruin.model.combat.AttackStyle.CRUSH,io.ruin.model.combat.AttackStyle.RANGED,io.ruin.model.combat.AttackStyle.MAGIC}) {
     ProtectionPrayerPolicy.apply(p,style,true);int hp=p.getHp();
     Hit hit=new Hit(source,style).fixedDamage(3).ignoreDefence().delay(0);check(hit.defend(p),"hit defence rejected");check(hit.finish(p),"hit not delivered");
     check(p.getHp()==hp,"matching prayer failed to block actual NPC hit");
     check(p.memory.recentStyle("npc:"+source.getId())==style,"blocked attack was not observed");
    }
    check(stat.experience==xp,"prayer logic granted XP");
    System.out.println("CHECK PASS prayer: all supported styles, exact/insufficient levels, points, repeated calls, slash restrictions, safe shutdown, no XP");
   } finally {p.getPrayer().slashDelay.reset();ProtectionPrayerPolicy.apply(p,null,false);stat.fixedLevel=fixed;stat.currentLevel=current;stat.experience=xp;
    for(io.ruin.model.skills.prayer.Prayer q:originalProtection)if(!p.getPrayer().isActive(q))p.getPrayer().toggle(q);
    p.getPrayer().slashDelay.setEnd(slashEnd);}
  } else if(command.equals("shop")) {
   Adventurer p=actors.get("2");
   for(io.ruin.model.entity.npc.NPC n:io.ruin.model.World.npcs)if(n.getId()==2820){
    System.out.println("SHOP DIAG actor="+p.getPosition()+" npc="+n.getPosition()+" size="+n.getSize()+" LOS="+io.ruin.model.map.route.routes.ProjectileRoute.allow(p,n)+" skip="+n.skipMovementCheck+" mode="+p.getGameMode());
    for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(dx!=0||dy!=0){int x=n.getAbsX()+dx,y=n.getAbsY()+dy;io.ruin.model.map.Tile t=io.ruin.model.map.Tile.get(x,y,n.getHeight());System.out.println("SIDE "+x+","+y+" free="+(t==null||t.isFloorFree())+" LOS="+io.ruin.model.map.route.routes.ProjectileRoute.allow(x,y,n.getHeight(),n.getAbsX(),n.getAbsY()));}
    if(p.getPosition().isWithinDistance(n.getPosition(),1)){n.actions[2].handle(p,n);System.out.println("SHOP opened="+(p.viewingShop!=null));}
   }
  } else if(command.equals("basic")) {
   check(actors.size()>0 && actors.size()<=25,"population");
   Set<String> names=new HashSet<>();Set<String> times=new HashSet<>();
   for(Adventurer p:actors.values()) {
    check(names.add(p.getName()),"duplicate name");check(p.isOnline(),"actor offline");
    check(p.quarantineReason==null,"quarantined actor: "+p.displayName);check(!p.isAdmin(),"simulated administrator");check(p.xpMode==XpMode.HARD,"XP not lowest");
    Properties snapshot=p.brain.checkpoint(now);times.add(snapshot.getProperty("micro")+":"+snapshot.getProperty("work"));
    if(p.archetype==BuildPolicy.Archetype.SKILLER) {
     check(!p.policy.targets.containsKey("slayer"),"skiller Slayer policy");
     for(StatType t:StatType.values())if(t.isCombat()) check(p.getStats().get(t).experience==(t==StatType.Hitpoints?1154:0),"skiller combat XP changed");
    }
   }
   check(actors.size()==1 || times.size()>1,"synchronized break schedules");
   boolean denied=false;try{Adventurers.assertLegacyCapacity();}catch(IllegalStateException e){denied=true;}check(denied,"legacy cap bypass");
   System.out.println("CHECK PASS basic: population="+actors.size()+", distinct schedules="+times.size()+", Hard XP, no admin, skiller restrictions");
  } else if(command.equals("rest-start")) {
   Adventurer p=actors.get("1");
   check(p.port.pauseSafely(now)==ProgressionBrain.PauseStatus.SAFE,"Actor not safe yet; retry rest-start");
   Properties data=p.brain.checkpoint(now);data.setProperty("state","RESTING");data.setProperty("after","REST");data.setProperty("rest","180");data.setProperty("restKind","MICRO");
   scheduler().remove(p.actorId);
   p.brain=new ProgressionBrain(p.actorId,p.port,ProgressionBrain.Config.humanDefaults(600),p.policy,p.seed);
   p.brain.restore(data,now);scheduler().add(p.brain,now+1);
   restingXp=xp(p);restingInventory=itemCount(p);restStarted=now;
   Adventurers.save(p);
   System.out.println("CHECK rest baseline captured at "+now);
  } else if(command.equals("rest-check")) {
   Adventurer p=actors.get("1");check(restingXp!=null,"missing rest baseline");check(now-restStarted>=30,"need at least 30 ticks");
   check(p.isOnline() && p.brain.state()==ProgressionBrain.State.RESTING,"not resting online");
   check(Arrays.equals(restingXp,xp(p)),"XP during rest");check(restingInventory==itemCount(p),"items during rest");check(!p.hasActiveAction() && p.getMovement().isAtDestination(),"resting activity");
   System.out.println("CHECK PASS rest: no XP/items/actions across "+(now-restStarted)+" ticks");
  } else if(command.equals("xp-guard")) {
   Adventurer p=actors.values().stream().filter(a->a.archetype==BuildPolicy.Archetype.SKILLER).findFirst().get();
   double[] before=xp(p);for(StatType t:StatType.values())if(t.isCombat())p.getStats().addXp(t,100,true);
   check(Arrays.equals(before,xp(p)),"authoritative reward bypass");check(p.quarantineReason!=null,"no quarantine");
   System.out.println("CHECK PASS XP reward boundary and quarantine");
  } else if(command.equals("death")) {
   Adventurer p=actors.get("3");check(p!=null,"need three actors");p.resetActions(true,true,true);p.hit(new Hit().fixedDamage(1000));
   System.out.println("CHECK staged lethal environmental hit queued");
  } else if(command.equals("death-check")) {
   Adventurer p=actors.get("3");AdventurerMemory.Encounter e=p.memory.encounters.get("unknown");
   check(e!=null && e.deaths>=1 && e.cooldownTicks>0,"death not retained");check(!p.memory.mayAttempt("unknown"),"no retry cooldown");
   check(e.observation.contains("Mechanic cause not established"),"fabricated death cause");
   check(p.memory.healingThreshold("unknown")>.5,"healing policy did not adapt");
   System.out.println("CHECK PASS death capture, honest unknown cause, cooldown, adaptive healing threshold");
  } else if(command.equals("spacing")) {
   Field f=TrainingSites.class.getDeclaredField("claims");f.setAccessible(true);
   Map<?,?> claims=(Map<?,?>)f.get(null);List<GameObject> spots=new ArrayList<>();
   for(Object value:claims.values()){GameObject a=(GameObject)value;for(GameObject b:spots)check(a.z!=b.z || Math.max(Math.abs(a.x-b.x),Math.abs(a.y-b.y))>=12,"overlapping training claims");spots.add(a);}
   System.out.println("CHECK PASS spacing: "+spots.size()+" exclusive training reservations");
  } else throw new IllegalArgumentException(command);
 }
 private static double[] xp(Adventurer p){double[] v=new double[StatType.values().length];for(StatType t:StatType.values())v[t.ordinal()]=p.getStats().get(t).experience;return v;}
 private static int itemCount(Adventurer p){int n=0;for(io.ruin.model.item.Item i:p.getInventory().getItems())if(i!=null)n+=i.getAmount();return n;}
}
