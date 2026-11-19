package io.ruin.model.activities.dungeoneering;

import io.ruin.Server;
import io.ruin.model.activities.raids.auto.RaidAutoAccess;
import io.ruin.model.activities.raids.auto.RaidAutoActions;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.map.Position;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.routes.ProjectileRoute;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/** Read-only encounter observations; every mechanic is resolved by a normal owner action. */
final class IDungeonBossPlanner {
    final Player p;final IDungeonSettings settings;final IDungeonActions a;final IDungeonCombatPlanner combat;
    private DungeoneeringFloor.RoomState room;
    IDungeonBossPlanner(Player p,IDungeonSettings settings,IDungeonActions a,IDungeonCombatPlanner combat){this.p=p;this.settings=settings;this.a=a;this.combat=combat;}
    void enter(DungeoneeringFloor.RoomState r){room=r;}
    @SuppressWarnings("unchecked") private Map<NPC,DungeoneeringCombat.Ctx> contexts()throws Exception{return (Map<NPC,DungeoneeringCombat.Ctx>)RaidAutoAccess.get(DungeoneeringCombat.class,"ACTIVE");}
    private static Object get(Object o,String name)throws Exception{return RaidAutoAccess.get(o,name);}
    private static int number(Object o,String name)throws Exception{return ((Number)get(o,name)).intValue();}
    private static boolean flag(Object o,String name)throws Exception{return Boolean.TRUE.equals(get(o,name));}
    private static List<?> list(Object o,String name)throws Exception{return (List<?>)get(o,name);}
    private GameObject source(int... ids){for(GameObject o:room.objects())for(int id:ids)if(room.assets().sourceObject(o.id)==id)return o;return null;}
    private boolean object(GameObject o){if(o==null)throw new IllegalStateException("Required boss mechanism is missing");if(!a.object(room,o,1))throw new IllegalStateException("No native route to "+o.getDef().name);return true;}
    private boolean move(Position at){if(p.getPosition().equals(at))return true;if(!a.move(room,at))throw new IllegalStateException("No native route to the required boss position");return true;}
    private boolean offPrayer(){boolean changed=false;for(Prayer prayer:new Prayer[]{Prayer.PROTECT_FROM_MELEE,Prayer.PROTECT_FROM_MISSILES,Prayer.PROTECT_FROM_MAGIC})if(p.getPrayer().isActive(prayer))changed|=RaidAutoActions.button(p,541,prayer.ordinal()+5,-1,1,-1);return changed;}
    boolean pulse(DungeoneeringFloor.RoomState r)throws Exception{
        Map<NPC,DungeoneeringCombat.Ctx> all=contexts();DungeoneeringCombat.Ctx boss=null;
        for(NPC n:r.bosses){DungeoneeringCombat.Ctx c=all.get(n);if(c!=null&&c.live()){boss=c;break;}}
        if(boss==null)return true;
        int kind=DungeoneeringCombat.bossKind(boss.source);Object mechanic=boss.behavior;String style=null;
        Set<Long> hazards=hazards(boss);
        if(kind==2&&boss.invulnerable){return keepMoving(hazards);}
        if(kind==4){
            // Keep the single-player food pile blocked while firing normally.
            Position block=r.tile(2,11);if(!p.getPosition().equals(block))return move(block);style=available("RANGED","MAGIC");
        }
        if(kind==5){int protection=number(mechanic,"prayer");style=protection==0?available("RANGED","MAGIC"):protection==1?available("MELEE","RANGED"):available("MELEE","MAGIC");}
        if(kind==6){
            int armour=number(mechanic,"armour");List<?> pools=list(mechanic,"pools");
            if(armour>=35&&!pools.isEmpty()){
                Position pool=(Position)get(pools.get(0),"at");int[] at=r.local(pool),b=r.local(boss.npc.getPosition());int dx=Integer.signum(at[0]-b[0]),dy=Integer.signum(at[1]-b[1]);
                Position lure=r.tile(Math.max(1,Math.min(14,at[0]+dx*(boss.npc.getSize()+1))),Math.max(1,Math.min(14,at[1]+dy*(boss.npc.getSize()+1))));
                if(!p.getPosition().equals(lure))return move(lure);
            }
        }
        if(kind==7&&((Set<?>)get(mechanic,"usedShadow")).contains(p)&&!boss.pending.isEmpty()&&ProjectileRoute.allow(boss.npc,p)){
            Position cover=findCover(boss);if(cover!=null)return move(cover);
        }
        if(kind==8&&((Map<?,?>)get(mechanic,"rot")).containsKey(p)&&((Number)((Map<?,?>)get(mechanic,"rot")).get(p)).intValue()>=6){
            boolean drained=p.getStats().get(StatType.Attack).currentLevel<p.getStats().get(StatType.Attack).fixedLevel*7/10;
            if(drained||p.getHp()<p.getMaxHp()/2)return object(source(49297));
        }
        if(kind==9&&number(mechanic,"shield")>0){if(combat.item(16295)==null&&!hasPickaxe())style=available("MAGIC");else style="MELEE";}
        if(kind==11)return skinweaver(boss,all);
        if(kind==12){
            if(!flag(mechanic,"second")){GameObject lamp=source(49265);if(lamp!=null){if(combat.item(17678)==null)throw new IllegalStateException("The night-gazer needs a carried tinderbox; use a complexity with native tool supplies");return object(lamp);}}
            if(flag(mechanic,"special")&&boss.near(p,2))return escape(boss,3,hazards);
            style=available("RANGED","MAGIC");
        }
        if(kind==13)style=available("MELEE","MAGIC");
        if(kind==14)style=available("RANGED","MAGIC");
        if(kind==16&&boss.invulnerable)return stomp(boss);
        if(kind==17&&get(mechanic,"charging")==p)return escape(boss,5,hazards);
        if(kind==18){
            long released=((Number)get(mechanic,"released")).longValue();
            if(released<0){for(Object value:list(mechanic,"mages")){NPC mage=(NPC)value;if(IDungeonCombatPlanner.live(mage))return combat.attack(r,mage,null);}return true;}
            Position safe=nearest(new int[][]{{1,6},{1,9},{14,9},{14,6}});if(!p.getPosition().equals(safe))return move(safe);style=available("RANGED","MAGIC");
        }
        if(kind==20)style=available("RANGED","MAGIC");
        if(kind==22){
            long[] until=(long[])get(mechanic,"artifactUntil");String selected=combat.choose(null);int index=selected.equals("MELEE")?0:selected.equals("RANGED")?1:2;
            if(until[index]<=boss.tick)return object(source(index==0?53977:index==1?53979:53978));
            if(until[index]-boss.tick<=25){
                for(int i=0;i<3;i++)if(until[i]-boss.tick>25&&combat.hasStyle(new String[]{"MELEE","RANGED","MAGIC"}[i])){selected=new String[]{"MELEE","RANGED","MAGIC"}[i];index=i;break;}
                if(until[index]-boss.tick<=25){RaidAutoActions.stopAttack(p);return true;}
            }style=selected;
        }
        if(kind==23){int protection=number(mechanic,"prayer");style=protection==0?available("RANGED","MAGIC"):protection==1?available("MELEE","MAGIC"):available("MELEE","RANGED");}
        if(kind==25){
            for(NPC child:boss.children){DungeoneeringCombat.Ctx eye=all.get(child);if(eye!=null&&eye.live()&&eye.behavior.getClass().getSimpleName().equals("Eye")&&!flag(eye.behavior,"sleeping")){
                int weakness=number(eye.behavior,"weakness");String eyeStyle=new String[]{"MELEE","RANGED","MAGIC"}[weakness];if(!combat.hasStyle(eyeStyle))eyeStyle=combat.choose(null);return combat.attack(r,child,eyeStyle);
            }}
            style=combat.choose(null);for(Object eye:(Object[])get(mechanic,"eyes"))if(flag(eye,"sleeping")){
                int protectedStyle=number(eye,"protectedStyle");String blocked=new String[]{"MELEE","RANGED","MAGIC"}[protectedStyle];
                if(style.equals(blocked))for(String candidate:new String[]{"MELEE","RANGED","MAGIC"})if(!candidate.equals(blocked)&&combat.hasStyle(candidate)){style=candidate;break;}
            }
        }
        boolean hopeRoar=kind==26&&pendingMechanic(boss,"HopeDevourer");
        if(hopeRoar){offPrayer();}
        if(kind==27&&number(mechanic,"rush")>=0){if(p.getCombat().getTarget()!=null)RaidAutoActions.stopAttack(p);return blink(boss);}
        if(hazards.contains(IDungeonPath.key(p.getPosition())))return dodge(hazards,boss);
        if(boss.under(p))return escape(boss,1,hazards);
        // Kill active offensive children first, excluding scenery/immune encounter actors.
        for(NPC child:boss.children){DungeoneeringCombat.Ctx c=all.get(child);if(c!=null&&c.live()&&!c.invulnerable&&c.behavior.attackable(c,p))return combat.attack(r,child,null);}
        if(boss.invulnerable||!boss.behavior.attackable(boss,p)){if(p.getCombat().getTarget()!=null)RaidAutoActions.stopAttack(p);return true;}
        boolean result=combat.attack(r,boss.npc,style);
        if(hopeRoar)offPrayer();
        if(kind==24){int phase=number(mechanic,"phase");if(settings.prayer)RaidAutoActions.prayer(p,phase==3?Prayer.PROTECT_FROM_MAGIC:phase==5?Prayer.PROTECT_FROM_MISSILES:Prayer.PROTECT_FROM_MELEE);}
        return result;
    }
    private String available(String... choices)throws Exception{for(String style:choices)if(combat.hasStyle(style))return style;throw new IllegalStateException("This boss needs usable "+Arrays.toString(choices)+" equipment and ammunition/runes");}
    private boolean pendingMechanic(DungeoneeringCombat.Ctx c,String name)throws Exception{for(Object due:c.pending)if(get(due,"action").getClass().getName().contains("$"+name+"$$Lambda$"))return true;return false;}
    private boolean hasPickaxe(){for(Item i:p.getInventory().getItems())if(i!=null&&i.getDef().name.toLowerCase(Locale.ROOT).contains("pickaxe"))return true;for(Item i:p.getEquipment().getItems())if(i!=null&&i.getDef().name.toLowerCase(Locale.ROOT).contains("pickaxe"))return true;return false;}
    private boolean skinweaver(DungeoneeringCombat.Ctx boss,Map<NPC,DungeoneeringCombat.Ctx> all)throws Exception{
        Object m=boss.behavior;
        if(flag(m,"requested"))return object(source(49286,49287,49288));
        for(Object n:(Set<?>)get(m,"skeletons"))if(IDungeonCombatPlanner.live((NPC)n))return combat.attack(room,(NPC)n,null);
        if(((Set<?>)get(m,"sealed")).size()==5)return a.npc(room,boss.npc,1);
        if(p.getHp()<p.getMaxHp()&&!boss.near(p,3))return a.approach(room,boss.npc.getPosition());return true;
    }
    private boolean stomp(DungeoneeringCombat.Ctx boss)throws Exception{
        Object m=boss.behavior;int colour=number(m,"colour"),source=new int[]{15752,15751,15750}[colour];Item crystal=combat.item(source);
        if(crystal==null){for(GroundItem g:IDungeonActions.ground(room))if(room.assets().sourceItem(g.id)==source)return a.pickup(room,g);return true;}
        boolean[] charged=(boolean[])get(m,"charged");for(int i=0;i<2;i++)if(!charged[i])return object(source(49274+colour*2+i));return true;
    }
    private boolean blink(DungeoneeringCombat.Ctx boss)throws Exception{
        Object m=boss.behavior;int rush=number(m,"rush");if(rush<8)return true;
        int[] path=(int[])get(m,"path");boolean reverse=flag(m,"reverse");int[] at=room.local(boss.npc.getPosition());int dx=Integer.signum(path[reverse?0:2]-at[0]),dy=Integer.signum(path[reverse?1:3]-at[1]);
        GameObject best=null;int distance=Integer.MAX_VALUE;
        for(GameObject o:room.objects())if(room.assets().sourceObject(o.id)==32195){int[] pos=room.local(o.getPosition());int along=(pos[0]-at[0])*dx+(pos[1]-at[1])*dy;
            if(along<=0||dx==0&&pos[0]!=at[0]||dy==0&&pos[1]!=at[1])continue;int d=IDungeon.distance(p.getPosition(),o.getPosition());if(d<distance){best=o;distance=d;}}
        if(best==null)return true;Position pos=best.getPosition();if(p.getPosition().equals(pos))return keepMoving(Collections.singleton(IDungeonPath.key(pos)));
        return object(best);
    }
    private Position nearest(int[][] positions){Position best=null;int distance=Integer.MAX_VALUE;for(int[] at:positions){Position p=room.tile(at[0],at[1]);int d=IDungeon.distance(this.p.getPosition(),p);if(d<distance){best=p;distance=d;}}return best;}
    private Position findCover(DungeoneeringCombat.Ctx boss){
        Position best=null;int distance=Integer.MAX_VALUE;
        for(int x=1;x<15;x++)for(int y=1;y<15;y++){Position at=room.tile(x,y);int d=IDungeon.distance(p.getPosition(),at);if(d>=distance||IDungeonPath.next(room,p.getPosition(),at,0,Collections.<Long>emptySet())==null)continue;
            if(!ProjectileRoute.allow(boss.npc.getAbsX(),boss.npc.getAbsY(),boss.npc.getHeight(),boss.npc.getSize(),at.getX(),at.getY(),1)){best=at;distance=d;}}
        return best;
    }
    private boolean keepMoving(Set<Long> hazards){for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(dx!=0||dy!=0){Position to=p.getPosition().relative(dx,dy);if(room.dungeon().room(to)==room&&!hazards.contains(IDungeonPath.key(to))&&IDungeonPath.adjacent(p.getPosition(),to))return a.walk(to);}return true;}
    private boolean escape(DungeoneeringCombat.Ctx boss,int distance,Set<Long> hazards){
        Position best=null;int closest=Integer.MAX_VALUE;for(int x=1;x<15;x++)for(int y=1;y<15;y++){
            Position at=room.tile(x,y);int d=IDungeon.distance(p.getPosition(),at);if(d>=closest||hazards.contains(IDungeonPath.key(at)))continue;
            if(io.ruin.model.map.route.routes.TargetRoute.inRange(boss.npc.getAbsX(),boss.npc.getAbsY(),boss.npc.getSize(),at.getX(),at.getY(),1,distance-1))continue;
            if(IDungeonPath.next(room,p.getPosition(),at,0,hazards)==null)continue;best=at;closest=d;
        }return best==null?keepMoving(hazards):a.move(room,best,hazards);
    }
    private boolean dodge(Set<Long> hazards,DungeoneeringCombat.Ctx boss){return escape(boss,1,hazards);}
    private Set<Long> hazards(DungeoneeringCombat.Ctx boss)throws Exception{
        Set<Long> cells=new HashSet<>();Object m=boss.behavior;String name=m.getClass().getSimpleName();
        if(name.equals("BalLak"))for(Object pool:list(m,"pools"))area(cells,(Position)get(pool,"at"),1);
        if(name.equals("RiftSplitter"))for(Object cluster:list(m,"clusters"))for(Object at:list(cluster,"tiles"))area(cells,(Position)at,0);
        if(name.equals("Gravecreeper"))for(Object burn:list(m,"burns"))area(cells,(Position)get(burn,"centre"),1);
        if(name.equals("Dreadnaut"))for(Object puddle:list(m,"puddles"))area(cells,(Position)get(puddle,"tile"),0);
        if(name.equals("Stomp"))for(Object at:list(m,"shadows"))area(cells,(Position)at,0);
        if(name.equals("Warmonger")){Object at=get(m,"previousSphere");if(at instanceof Position)area(cells,(Position)at,0);}
        // Delayed marked ground attacks capture immutable positions/lists. Ignore
        // actors, contexts and arbitrary object graphs; never execute a callback.
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
        for(Object due:boss.pending){Object action=get(due,"action");captures(action,cells,seen,0);}
        return cells;
    }
    private void captures(Object value,Set<Long> cells,Set<Object> seen,int depth)throws Exception{
        if(value==null||depth>3||seen.size()>256||!seen.add(value))return;
        if(value instanceof Position){Position at=(Position)value;if(room.dungeon().room(at)==room)area(cells,at,1);return;}
        if(value instanceof Iterable){for(Object entry:(Iterable<?>)value)if(entry instanceof Position)captures(entry,cells,seen,depth+1);return;}
        String type=value.getClass().getName();if(!type.startsWith("io.ruin.model.activities.dungeoneering.")||!type.contains("$$Lambda$"))return;
        for(Field field:value.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())){field.setAccessible(true);Object member=field.get(value);if(member instanceof Position||member instanceof Iterable)captures(member,cells,seen,depth+1);}
    }
    private static void area(Set<Long> cells,Position at,int radius){for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)cells.add(IDungeonPath.key(at.getX()+x,at.getY()+y));}
}
