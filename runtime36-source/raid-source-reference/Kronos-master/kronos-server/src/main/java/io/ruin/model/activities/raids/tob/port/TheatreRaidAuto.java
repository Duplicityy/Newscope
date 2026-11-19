package io.ruin.model.activities.raids.tob.port;

import io.ruin.cache.ItemDef;
import io.ruin.content.content242.ModernCharges;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.activities.raids.auto.RaidAuto;
import io.ruin.model.activities.raids.auto.RaidAutoAccess;
import io.ruin.model.activities.raids.auto.RaidAutoActions;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.Position;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.routes.DumbRoute;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/** Owner-only normal player actions in an already active Theatre run. No entry or fixture setup. */
public final class TheatreRaidAuto implements RaidAuto.Controller {
    private final Player player;
    private final TheatreOfBloodRaid.Run run;
    private final int startingDeaths;
    private long now,lastAttack=-100,lastRoute=-100,lastProgress;
    private int stage=-1,phase=-1,lowWater=Integer.MAX_VALUE,routeX=Integer.MIN_VALUE,routeY,p1CoverUntil=-1;
    private boolean finished,claimRequested;
    private long claimTick;
    private String state="Ready";
    private static final Map<Class<?>,String[]> CAPTURE_FIELDS=new HashMap<>();

    /** Inspection only: shared RaidAuto starts the owner-validated event after this factory returns. */
    public static RaidAuto.Controller create(Player player) {
        if(!RaidAuto.isOwner(player))throw new IllegalStateException("Raid auto is available only to OWNER.");
        TheatreOfBloodRaid.Run run=TheatreOfBloodRaid.RUNS.get(player);
        if(run==null)return null;
        if(run.closed||run.spectators.contains(player)||player.theatreProgress==null)
            throw new IllegalStateException("Join a live Theatre room as an active participant first.");
        return new TheatreRaidAuto(player,run);
    }
    private TheatreRaidAuto(Player player,TheatreOfBloodRaid.Run run){this.player=player;this.run=run;startingDeaths=player.theatreProgress.deaths;state="Attached to "+run.room().name;}
    @Override public String status(){return state;}
    @Override public void stopped(String reason){finished=true;state=reason;}
    private boolean stop(String reason){finished=true;state=reason;return false;}
    private void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    @Override public boolean pulse(long worldTick)throws Exception {
        if(finished)return false;now=worldTick;
        if(!RaidAuto.isOwner(player)||!player.isOnline())return stop("Owner session ended.");
        if(player.theatreProgress==null||player.theatreProgress.deaths!=startingDeaths||run.spectators.contains(player))return stop("You were downed. Raid auto stopped; no spectator actions were taken.");
        if(TheatreOfBloodRaid.RUNS.get(player)!=run||run.closed||!run.party.members.contains(player))return stop("The attached Theatre run ended or changed.");
        if(PlayerFile.isSaveQuarantined(player))return stop("Native save requires attention; raid auto stopped.");
        if(player.getHp()<=0||player.getCombat().isDead())return stop("You are no longer an active fighter.");
        if(player.isLocked()||run.transition||run.tick<=run.arrivalUntil){state="Waiting for the normal room transition";return true;}
        if(!run.contains(player))return stop("You left the attached Theatre room.");
        if(stage!=run.stage){stage=run.stage;phase=-1;lowWater=Integer.MAX_VALUE;lastProgress=now;routeX=Integer.MIN_VALUE;p1CoverUntil=-1;claimRequested=false;}
        if(run.cleared){RaidAutoActions.stopAttack(player);if(stage==6)return vault();if(run.party.leader!=player){state="Room cleared; waiting for the party leader";return true;}openMenu();require(RaidAutoActions.select(player,"Continue to next room"),"The normal continue option is unavailable.");state="Continuing through the normal Theatre menu";return true;}
        if(sustain())return true;
        TheatreCombat encounter=run.encounter;
        if(encounter==null||encounter.boss==null||encounter.boss.isRemoved()){state="Waiting for the native encounter";return true;}
        NPC boss=encounter.boss;TheatreCombat.Actor actor=encounter.actors.get(boss);
        require(actor!=null,"The native boss controller is unavailable.");
        int nextPhase=actor.behavior instanceof TheatreVerzik?((TheatreVerzik)actor.behavior).phase:actor.behavior instanceof TheatreCombat.Xarpus?((TheatreCombat.Xarpus)actor.behavior).phase:actor.behavior instanceof TheatreCombat.Sotetseg?((TheatreCombat.Sotetseg)actor.behavior).phase:0;
        if(nextPhase!=phase){phase=nextPhase;lowWater=boss.getHp();lastProgress=now;}else if(boss.getHp()<lowWater){lowWater=boss.getHp();lastProgress=now;}
        if(now-lastProgress>400)return stop("No new boss phase or lower boss HP for400 ticks. Check gear and mechanics, then restart manually.");
        state=run.room().name+" — normal combat";
        switch(stage){
            case 0:maiden(boss,(TheatreCombat.Maiden)actor.behavior);break;
            case 1:bloat(boss,(TheatreCombat.Bloat)actor.behavior);break;
            case 2:nylo(boss,(TheatreCombat.Nylo)actor.behavior,actor.ticks);break;
            case 3:sotetseg(boss,(TheatreCombat.Sotetseg)actor.behavior);break;
            case 4:xarpus(boss,(TheatreCombat.Xarpus)actor.behavior,actor.ticks);break;
            case 5:verzik(boss,(TheatreVerzik)actor.behavior,actor.ticks);break;
            default:return stop("Unsupported Theatre room; use the normal raid menu.");
        }
        return true;
    }
    private void openMenu(){TheatreOfBloodRaid.open(player);}
    private boolean vault()throws Exception {
        List<Item> pending=player.theatreProgress.pendingRewards;
        if(pending!=null&&!pending.isEmpty()){
            if(claimRequested){require(now-claimTick<=20,"The native claim did not finish; check inventory space and save status.");state="Waiting for the native reward claim";return true;}
            int needed=0;for(Item item:pending){ItemDef d=item.getDef();require(d!=null&&item.getAmount()>0,"Invalid native pending reward; stop and contact staff.");needed+=d.stackable?1:item.getAmount();}
            require(player.getInventory().getFreeSlots()>=needed,"Free "+needed+" inventory slots, then restart raid auto to claim your earned rewards. Nothing was dropped.");
            openMenu();require(RaidAutoActions.select(player,"Claim pending rewards"),"The native claim option is unavailable.");claimRequested=true;claimTick=now;state="Requested the normal earned-reward claim";return true;
        }
        if(!claimRequested)return stop("Theatre complete; no pending rewards remain. Use the normal menu to leave.");
        openMenu();require(RaidAutoActions.select(player,"Leave the Theatre"),"The normal leave option is unavailable.");return stop("Native reward claim finished; left through the normal Theatre menu.");
    }
    private int fixed(StatType type){return player.getStats().get(type).fixedLevel;}
    private int current(StatType type){return player.getStats().get(type).currentLevel;}
    private List<Item> owned(){List<Item> result=new ArrayList<>();for(Item item:player.getEquipment().getItems())if(item!=null)result.add(item);for(Item item:player.getInventory().getItems())if(item!=null)result.add(item);return result;}
    private String name(Item item){return item.getDef()==null?"":item.getDef().name.toLowerCase(Locale.ROOT);}
    private boolean nativeConsumable(Item item,String action){if(item==null||item.getAmount()<1||item.getDef()==null)return false;ItemDef def=item.getDef();int option=def.getOption(action);return option>=1&&option<=5&&def.inventoryActions!=null&&def.inventoryActions.length>=option&&def.inventoryActions[option-1]!=null;}
    private Item carried(String... fragments){for(Item item:player.getInventory().getItems())if(nativeConsumable(item,"Drink")){String name=name(item);for(String fragment:fragments)if(name.contains(fragment))return item;}return null;}
    private boolean eatOrDrink(Item item)throws Exception {
        if(item==null)return false;String action=item.getDef().getOption("Eat")>0?"Eat":"Drink";
        if(player.karamDelay.isDelayed()||(action.equals("Eat")?player.eatDelay.isDelayed():player.potDelay.isDelayed()))return false;
        int id=item.getId(),amount=item.getAmount(),hp=player.getHp();String usedName=item.getDef().name;
        RaidAutoActions.inventory(player,item,action);
        boolean changed=item.getContainer()!=player.getInventory()||item.getId()!=id||item.getAmount()!=amount||player.getHp()!=hp;
        if(changed)state="Using owned "+usedName+" through the native inventory action";
        return changed;
    }
    private Item healingFood(){for(Item item:player.getInventory().getItems())if(nativeConsumable(item,"Eat")&&item.getDef().consumable){String name=name(item);for(String food:new String[]{"manta ray","anglerfish","dark crab","shark","sea turtle","tuna potato","monkfish","cooked karambwan","swordfish","lobster","bass","salmon","trout"})if(name.contains(food))return item;}return null;}
    private boolean sustain()throws Exception {
        int hp=player.getHp(),maximum=Math.max(1,fixed(StatType.Hitpoints));boolean critical=hp<Math.max(10,maximum*3/10),hurt=hp<Math.max(10,maximum*13/20);
        Item food=healingFood(),brew=carried("saradomin brew"),restore=carried("super restore","restore potion","sanfew serum"),prayer=carried("super restore","prayer potion","sanfew serum");
        if(critical){require(food!=null||brew!=null,"Bring healing food or Saradomin brews; raid auto has stopped at low HP.");if(eatOrDrink(food!=null?food:brew))return true;}
        if(current(StatType.Prayer)<Math.max(1,fixed(StatType.Prayer)/4)){require(prayer!=null,"Bring prayer-restoration potions; raid auto has stopped.");if(eatOrDrink(prayer))return true;}
        boolean drained=false;for(StatType type:new StatType[]{StatType.Attack,StatType.Strength,StatType.Ranged,StatType.Magic})if(current(type)<fixed(type)-Math.max(2,fixed(type)*15/100))drained=true;
        if(drained){require(restore!=null,"Combat stats are drained. Bring super restores before continuing raid auto.");if(eatOrDrink(restore))return true;}
        if(hurt){require(food!=null||brew!=null,"Healing supplies are exhausted; raid auto stopped.");if(eatOrDrink(food!=null?food:brew))return true;}
        return false;
    }
    private void protect(Prayer prayer)throws Exception {if(!player.getPrayer().isActive(prayer)){require(RaidAutoActions.prayer(player,prayer),"The required protection prayer is unavailable.");require(player.getPrayer().isActive(prayer),"Check your prayer level and points before continuing.");}}
    private boolean requirements(ItemDef def){if(def.equipReqs!=null)for(int encoded:def.equipReqs){int index=encoded>>8,level=encoded&255;if(index<0||index>=StatType.values().length||fixed(StatType.values()[index])<level)return false;}return true;}
    private boolean setMatches(AttackSet set,int style){return set!=null&&set.style!=null&&(style==0?set.style.isMelee():style==1?set.style.isRanged():set.style==AttackStyle.MAGIC);}
    private boolean powered(Item item){int id=item.getId();if(id==11905)return true;if(id==11907||id==12899||id==22323||id==30032)return item.getAttributeInt("CHARGES",0)>0;return name(item).equals("tumeken's shadow")&&ModernCharges.get(item,0)>0;}
    private boolean weaponMatches(Item item,int style){ItemDef def=item.getDef();if(def==null||def.equipSlot!=Equipment.SLOT_WEAPON||def.weaponType==null||def.weaponType.attackSets==null||item.getId()==22516||!requirements(def))return false;if(style==2&&!powered(item))return false;for(AttackSet set:def.weaponType.attackSets)if(setMatches(set,style))return true;return false;}
    private boolean usableAmmo(Item weapon,Item ammo){return ammo!=null&&ammo.getDef()!=null&&ammo.getAmount()>0&&ammo.getDef().rangedAmmo!=null&&weapon.getDef().rangedWeapon.allowAmmo(ammo.getDef().rangedAmmo)&&weapon.getDef().rangedLevel>=ammo.getDef().rangedLevel&&requirements(ammo.getDef());}
    private boolean rangedReady(Item weapon){RangedWeapon type=weapon.getDef().rangedWeapon;if(type==null)return false;if(type.data!=null)return true;if(type.allowedAmmo==null)return false;for(Item item:owned())if(usableAmmo(weapon,item))return true;return false;}
    private void equip(Item item)throws Exception {require(item!=null,"Required owned equipment is missing.");int slot=item.getDef().equipSlot,id=item.getId();if(player.getEquipment().getId(slot)==id)return;require(item.getContainer()==player.getInventory(),"Required equipment is not carried.");RaidAutoActions.equip(player,item);require(player.getEquipment().getId(slot)==id,"Native equip was refused. Check requirements and free inventory space.");}
    private void gear(int style)throws Exception {
        Item chosen=null;for(Item item:owned())if(weaponMatches(item,style)&&(style!=1||rangedReady(item))){chosen=item;break;}
        require(chosen!=null,style==2?"Carry a charged powered staff for magic phases.":style==1?"Carry a usable ranged weapon and compatible ammunition.":"Carry a usable melee weapon.");
        if(style==1&&chosen.getDef().rangedWeapon.data==null){Item ammo=player.getEquipment().get(Equipment.SLOT_AMMO);if(!usableAmmo(chosen,ammo)){for(Item item:player.getInventory().getItems())if(item!=null&&usableAmmo(chosen,item)){equip(item);break;}}}
        equip(chosen);WeaponType type=player.getEquipment().getDef(Equipment.SLOT_WEAPON).weaponType;int selected=-1;
        for(int i=0;i<type.attackSets.length;i++)if(setMatches(type.attackSets[i],style)){if(selected<0)selected=i;if(style==1&&type.attackSets[i].type==AttackType.RAPID_RANGED){selected=i;break;}}
        require(selected>=0,"The equipped weapon has no matching native attack style.");
        if(Config.ATTACK_SET.get(player)!=selected){require(RaidAutoActions.button(player,593,4+selected*4,-1,1,-1),"Native combat style selection was refused.");require(Config.ATTACK_SET.get(player)==selected,"Native combat style did not apply.");}
    }
    private void attack(NPC target)throws Exception {if(target==null||target.isRemoved()||target.getHp()<=0)return;if(player.getCombat().getTarget()==target&&now-lastAttack<10)return;int option=target.getDef().attackOption;require(option>=1&&option<=5,"The target has no native attack option.");RaidAutoActions.npc(player,target,option);lastAttack=now;}
    private NPC find(int... ids){for(NPC npc:run.actors)if(!npc.isRemoved()&&npc.getHp()>0)for(int id:ids)if(npc.getId()==NearRealityRaidAssets.npc(id))return npc;return null;}
    private int sx(NPC npc){return run.room().cx*8+npc.getAbsX()-run.map.swRegion.baseX;}
    private int sy(NPC npc){return run.room().cy*8+npc.getAbsY()-run.map.swRegion.baseY;}
    private boolean route(int x,int y)throws Exception {if(run.sourceX(player)==x&&run.sourceY(player)==y){routeX=Integer.MIN_VALUE;return false;}if(routeX==x&&routeY==y&&now-lastRoute<4)return true;Position at=run.tile(x,y);require(at.getX()>=run.map.swRegion.baseX&&at.getX()<run.map.swRegion.baseX+run.room().width*8&&at.getY()>=run.map.swRegion.baseY&&at.getY()<run.map.swRegion.baseY+run.room().height*8,"The planned route left the current room.");RaidAutoActions.walk(player,at.getX(),at.getY());routeX=x;routeY=y;lastRoute=now;return true;}
    private boolean hazardous(Collection<Position> positions,int x,int y,int radius){for(Position p:positions)if(Math.max(Math.abs(p.getX()-x),Math.abs(p.getY()-y))<=radius)return true;return false;}
    private boolean directWalkable(int dx,int dy){int x=player.getAbsX(),y=player.getAbsY();for(int step=0;step<3&&(x!=dx||y!=dy);step++){int nx=x+Integer.signum(dx-x),ny=y+Integer.signum(dy-y);if(DumbRoute.getDirection(player.getRouteFinder().getClipUtils(),x,y,player.getHeight(),player.getSize(),nx,ny)==null)return false;x=nx;y=ny;}return x==dx&&y==dy;}
    private boolean evade(Collection<Position> positions,int radius)throws Exception {int x=player.getAbsX(),y=player.getAbsY();if(!hazardous(positions,x,y,radius))return false;for(int[] d:new int[][]{{2,0},{0,2},{-2,0},{0,-2},{3,0},{0,3},{-3,0},{0,-3}})if(!hazardous(positions,x+d[0],y+d[1],radius)&&directWalkable(x+d[0],y+d[1])){route(run.sourceX(player)+d[0],run.sourceY(player)+d[1]);return true;}throw new IllegalStateException("No clear nearby hazard escape; take manual control.");}
    private void maiden(NPC boss,TheatreCombat.Maiden b)throws Exception {protect(Prayer.PROTECT_FROM_MAGIC);if(evade(b.splats.keySet(),0))return;gear(0);NPC crab=find(8366);attack(crab==null?boss:crab);}
    private void bloat(NPC boss,TheatreCombat.Bloat b)throws Exception {protect(Prayer.PROTECT_FROM_MISSILES);if(evade(b.falling.keySet(),0))return;if(b.down&&b.cycle<24){gear(0);attack(boss);return;}route(sx(boss)>3294?3288:3299,sy(boss)>4445?4440:4451);}
    private void nylo(NPC boss,TheatreCombat.Nylo b,int ticks)throws Exception {protect(b.style==0?Prayer.PROTECT_FROM_MELEE:b.style==1?Prayer.PROTECT_FROM_MISSILES:Prayer.PROTECT_FROM_MAGIC);if(ticks%10>=6){RaidAutoActions.stopAttack(player);return;}gear(b.style);attack(boss);}
    private long key(int x,int y){return ((long)x<<32)|(y&0xffffffffL);}
    private void maze(TheatreCombat.Maze maze)throws Exception {RaidAutoActions.stopAttack(player);int x=run.sourceX(player),y=run.sourceY(player);if(y>=4326)return;ArrayDeque<int[]> queue=new ArrayDeque<>();Map<Long,Long> parent=new HashMap<>();long start=key(x,y);parent.put(start,start);queue.add(new int[]{x,y});Long end=null;while(!queue.isEmpty()){int[] at=queue.remove();if(at[1]>=4326){end=key(at[0],at[1]);break;}for(int[] d:new int[][]{{0,1},{1,0},{-1,0},{0,-1}}){int nx=at[0]+d[0],ny=at[1]+d[1];long k=key(nx,ny);if(nx<3274||nx>3287||ny<4309||ny>4326||(ny>=4311&&ny<=4325&&!maze.path.contains(k))||parent.containsKey(k))continue;parent.put(k,key(at[0],at[1]));queue.add(new int[]{nx,ny});}}require(end!=null,"No visible native maze route; take manual control.");long next=end;while(parent.get(next)!=start&&parent.get(next)!=next)next=parent.get(next);route((int)(next>>32),(int)next);}
    private void sotetseg(NPC boss,TheatreCombat.Sotetseg b)throws Exception {protect(Prayer.PROTECT_FROM_MAGIC);if(b.maze!=null){maze(b.maze);return;}incomingPrayer(boss);gear(1);attack(boss);}
    private void xarpus(NPC boss,TheatreCombat.Xarpus b,int ticks)throws Exception {protect(Prayer.PROTECT_FROM_MISSILES);if(b.phase==0){RaidAutoActions.stopAttack(player);if(!b.exhumed.isEmpty()){Position at=b.exhumed.keySet().iterator().next();route(run.room().cx*8+at.getX()-run.map.swRegion.baseX,run.room().cy*8+at.getY()-run.map.swRegion.baseY);}return;}if(evade(b.poison,1))return;if(b.phase==2){int current=(run.sourceX(player)>=sx(boss)+2?1:0)+(run.sourceY(player)>=sy(boss)+2?2:0);if(current==b.quadrant){int safe=(b.quadrant+1)%4;if(route(sx(boss)+((safe&1)==0?-3:6),sy(boss)+((safe&2)==0?-3:6)))return;}if(ticks%8>=5){RaidAutoActions.stopAttack(player);return;}}else if(ticks%5==1||ticks%5==2){int x=run.sourceX(player),y=run.sourceY(player);if(route(x+(x<sx(boss)+3?2:-2),y+(ticks%2==0?1:-1)))return;}gear(1);attack(boss);}
    private void incomingPrayer(NPC boss)throws Exception {Object queue=RaidAutoAccess.get(player,"queuedHits");if(!(queue instanceof List))return;Hit selected=null;int scanned=0;for(Object entry:(List<?>)queue){if(++scanned>64)break;if(entry instanceof Hit){Hit hit=(Hit)entry;if(hit.attacker==boss&&hit.attackStyle!=null&&!hit.isNullified()&&(selected==null||hit.getTicks()<selected.getTicks()))selected=hit;}}if(selected!=null)protect(selected.attackStyle.isRanged()?Prayer.PROTECT_FROM_MISSILES:selected.attackStyle.isMelee()?Prayer.PROTECT_FROM_MELEE:Prayer.PROTECT_FROM_MAGIC);}
    private void dawn(NPC boss,TheatreVerzik b)throws Exception {int id=NearRealityRaidAssets.item(22516,"Dawnbringer");require(id>=0,"The native Dawnbringer asset is unavailable.");Item weapon=RaidAutoAccess.item(player,id);if(weapon==null&&player.getEquipment().getId(Equipment.SLOT_WEAPON)!=id){require(player.getInventory().getFreeSlots()>0,"Free one inventory slot for the native Dawnbringer loan.");openMenu();require(RaidAutoActions.select(player,"Take Dawnbringer"),"The native Dawnbringer loan option is unavailable.");weapon=RaidAutoAccess.item(player,id);}if(player.getEquipment().getId(Equipment.SLOT_WEAPON)!=id)equip(weapon);if(Config.ATTACK_SET.get(player)!=3){RaidAutoActions.button(player,593,16,-1,1,-1);require(Config.ATTACK_SET.get(player)==3,"Native Dawnbringer long-range style did not apply.");}if(Config.SPECIAL_ENERGY.get(player)>=350&&RaidAutoAccess.get(player.getCombat(),"specialActive")==null)RaidAutoActions.button(player,593,36,-1,1,-1);NPC cover=null;for(NPC n:b.pillars)if(!n.isRemoved()&&n.getHp()>0&&(cover==null||sy(n)>sy(cover)))cover=n;if(cover!=null){int cx=sx(cover),cy=sy(cover),x=cx==3161?cx-1:cx+3,y=cy+1,bx=sx(boss),by=sy(boss),size=boss.getSize();int gap=Math.max(Math.max(Math.max(bx-x,x-(bx+size-1)),Math.max(by-y,y-(by+size-1))),0);if(gap<=8){if(!route(x,y))attack(boss);return;}int interval=b.attacks<0?19:15,until=interval-b.phaseTicks%interval,travel=(Math.max(Math.abs(run.sourceX(player)-x),Math.abs(run.sourceY(player)-y))+1)/2;if(until<=travel+2)p1CoverUntil=b.phaseTicks+until;if(p1CoverUntil>=b.phaseTicks){if(!route(x,y))RaidAutoActions.stopAttack(player);return;}if(route(3165,4315))return;}attack(boss);}
    private void poison(NPC purple)throws Exception {Item poison=null;for(Item item:owned())if(weaponMatches(item,0)&&name(item).contains("(p")){poison=item;break;}require(poison!=null,"Carry a poisoned melee weapon for the purple Nylocas.");equip(poison);attack(purple);}
    private void verzik(NPC boss,TheatreVerzik b,int ticks)throws Exception {protect(Prayer.PROTECT_FROM_MAGIC);if(b.phase==1){dawn(boss,b);return;}int x=run.sourceX(player),y=run.sourceY(player);if(b.phase==2&&b.phaseTicks%4==2&&x>=3166&&x<=3170&&y>=4312&&y<=4316){route(3172,4314);return;}NPC purple=find(8384);if(purple!=null){poison(purple);return;}NPC red=find(8385);if(red!=null){gear(0);attack(red);return;}NPC chaser=find(8381,8382,8353);if(chaser!=null){gear(chaser.getId()==NearRealityRaidAssets.npc(8381)?0:chaser.getId()==NearRealityRaidAssets.npc(8382)?1:2);attack(chaser);return;}if(b.phase==2){Item boots=RaidAutoAccess.item(player,7159);if(boots==null)boots=RaidAutoAccess.item(player,22951);if(boots!=null)equip(boots);int clock=b.phaseTicks%4;if(clock==2||clock==3){RaidAutoActions.stopAttack(player);if(x>=3166&&x<=3170&&y>=4312&&y<=4316)route(3172,4314);return;}gear(0);if(clock==0){route(3170,4314);attack(boss);return;}attack(boss);return;}Set<Position> dots=visibleDots();if(dots!=null&&!dots.isEmpty()){RaidAutoActions.stopAttack(player);Position chosen=null;int distance=Integer.MAX_VALUE;for(Position dot:dots){boolean occupied=false;for(Player member:run.party.members)if(member!=player&&member.isOnline()&&member.getPosition().equals(dot))occupied=true;int d=Math.max(Math.abs(dot.getX()-player.getAbsX()),Math.abs(dot.getY()-player.getAbsY()));if(!occupied&&d<distance){chosen=dot;distance=d;}}require(chosen!=null,"All visible safe dots are occupied; take manual control.");route(run.room().cx*8+chosen.getX()-run.map.swRegion.baseX,run.room().cy*8+chosen.getY()-run.map.swRegion.baseY);return;}List<Position> hazards=new ArrayList<>();for(NPC n:run.actors)if(!n.isRemoved()&&n.getId()==NearRealityRaidAssets.npc(8386))hazards.add(n.getPosition());for(GameObject object:run.objects)if(object.id==NearRealityRaidAssets.object(32734))hazards.add(object.getPosition());if(evade(hazards,1))return;incomingPrayer(boss);gear(0);attack(boss);}
    private Set<Position> visibleDots()throws Exception {Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());int scanned=0;for(TheatreOfBloodRaid.Due due:run.pending){if(++scanned>64)break;Set<Position> found=dotCapture(due.action,0,seen);if(found!=null)return found;}return null;}
    private Set<Position> dotCapture(Object value,int depth,Set<Object> seen)throws Exception {if(value==null||depth>5||seen.size()>=128||!seen.add(value))return null;Class<?> type=value.getClass();if(!type.getName().startsWith("io.ruin.model.activities.raids.tob.port.Theatre"))return null;String[] names=CAPTURE_FIELDS.get(type);if(names==null){if(CAPTURE_FIELDS.size()>=64)return null;List<String> fields=new ArrayList<>();for(Field field:type.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&(Set.class.isAssignableFrom(field.getType())||Runnable.class.isAssignableFrom(field.getType())))fields.add(field.getName());names=fields.toArray(new String[0]);CAPTURE_FIELDS.put(type,names);}for(String name:names){Object child=RaidAutoAccess.get(value,name);if(child instanceof Set){Set<?> values=(Set<?>)child;if(values.isEmpty()||values.size()>5)continue;Set<Position> positions=new HashSet<>();boolean valid=true;for(Object item:values){if(!(item instanceof Position)){valid=false;break;}Position p=(Position)item;if(p.getZ()!=player.getHeight()||p.getX()<run.map.swRegion.baseX||p.getX()>=run.map.swRegion.baseX+run.room().width*8||p.getY()<run.map.swRegion.baseY||p.getY()>=run.map.swRegion.baseY+run.room().height*8){valid=false;break;}positions.add(p);}if(valid)return positions;}else if(child instanceof Runnable){Set<Position> found=dotCapture(child,depth+1,seen);if(found!=null)return found;}}return null;}
}
