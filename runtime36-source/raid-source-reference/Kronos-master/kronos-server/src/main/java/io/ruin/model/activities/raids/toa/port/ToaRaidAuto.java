package io.ruin.model.activities.raids.toa.port;

import io.ruin.cache.ItemDef;
import io.ruin.content.content242.ModernChargedWeapons;
import io.ruin.content.content242.ModernCharges;
import io.ruin.content.content242.ModernGear;
import io.ruin.model.activities.raids.auto.RaidAuto;
import io.ruin.model.activities.raids.auto.RaidAutoAccess;
import io.ruin.model.activities.raids.auto.RaidAutoActions;
import io.ruin.model.combat.AttackSet;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.combat.AttackType;
import io.ruin.model.combat.RangedWeapon;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.item.attributes.AttributeExtensions;
import io.ruin.model.item.actions.impl.chargable.Blowpipe;
import io.ruin.model.item.actions.impl.chargable.CorruptedStaff;
import io.ruin.model.item.actions.impl.chargable.SanguinestiStaff;
import io.ruin.model.item.actions.impl.chargable.TridentOfTheSeas;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.Stat;
import io.ruin.model.stat.StatType;
import java.util.*;

/** Owner automation for an already-entered native raid; all gameplay uses normal player actions. */
public final class ToaRaidAuto {
    private ToaRaidAuto() {}
    public static RaidAuto.Controller create(Player player) {
        if(!RaidAuto.isOwner(player))return null;
        TombsOfAmascutRaid.Session session=TombsOfAmascutRaid.currentSession(player);
        return session==null||session.closed?null:new Controller(player,session);
    }
    private static Object get(Object target,String field)throws Exception{return RaidAutoAccess.get(target,field);}
    private static int number(Object target,String field)throws Exception{return ((Number)get(target,field)).intValue();}
    private static boolean option(Player p,String prefix){return RaidAutoActions.hasOption(p,prefix);}
    private static void select(Player p,String prefix){if(!RaidAutoActions.select(p,prefix))throw new IllegalStateException("Expected Tombs menu option is unavailable: "+prefix);}
    private static String pathLabel(ToaRoom puzzle){return puzzle.title.replace(" puzzle","");}
    private static List<Item> residents(Player p){List<Item> items=new ArrayList<>();for(Item item:p.getEquipment().getItems())if(item!=null)items.add(item);for(Item item:p.getInventory().getItems())if(item!=null)items.add(item);return items;}
    private static boolean equippable(Player p,Item item){ItemDef def=item.getDef();if(def.equipSlot<0||def.equipOption<1)return false;if(def.equipReqs!=null)for(int requirement:def.equipReqs)if(p.getStats().get(requirement>>8).fixedLevel<(requirement&255))return false;return true;}
    private static boolean matches(AttackStyle actual,String requested){return requested.equals("MAGIC")?actual.isMagic():requested.equals("RANGED")||requested.equals("DARTS")?actual.isRanged():actual.isMelee();}
    private static boolean weaponMatches(Item item,String style){if(item.getDef().weaponType==null||item.getDef().weaponType.attackSets==null)return false;for(AttackSet set:item.getDef().weaponType.attackSets)if(set!=null&&set.style!=null&&matches(set.style,style))return true;return false;}
    private static boolean magicReady(Item item){int id=item.getId();if(id==TridentOfTheSeas.FULLY_CHARGED)return true;if(id==TridentOfTheSeas.CHARGED||id==12899||id==SanguinestiStaff.CHARGED||id==CorruptedStaff.CHARGED)return AttributeExtensions.getCharges(item)>0;return ModernGear.feature("shadow")&&id==ModernCharges.item(ModernChargedWeapons.SHADOW)&&ModernCharges.get(item,0)>0;}
    private static Item ammo(Player p,Item weapon){RangedWeapon ranged=weapon.getDef().rangedWeapon;if(ranged==null||ranged.allowedAmmo==null)return null;Item best=null;for(Item item:residents(p))if(item.getDef().equipSlot==Equipment.SLOT_AMMO&&equippable(p,item)&&item.getDef().rangedAmmo!=null&&ranged.allowAmmo(item.getDef().rangedAmmo)&&weapon.getDef().rangedLevel>=item.getDef().rangedLevel&&(best==null||bonus(item.getDef(),11)>bonus(best.getDef(),11)))best=item;return best;}
    private static boolean rangedReady(Player p,Item item){if(ModernChargedWeapons.bowfa(item.getId()))return item.getId()==ModernCharges.item(ModernChargedWeapons.BOWFA_CORRUPT)||item.getId()==ModernCharges.item(ModernChargedWeapons.BOWFA)&&ModernCharges.get(item,10000)>0;RangedWeapon ranged=item.getDef().rangedWeapon;if(ranged==null)return false;if(ranged==RangedWeapon.TOXIC_BLOWPIPE)return Blowpipe.getDart(item)!=null&&Blowpipe.getDartAmount(item)>0&&Blowpipe.getScalesAmount(item)>0;if(ranged==RangedWeapon.CORRUPTED_JAVELIN)return AttributeExtensions.getCharges(item)>0;return ranged.allowedAmmo==null?ranged.data!=null:ammo(p,item)!=null;}
    private static int bonus(ItemDef def,int slot){return def.equipBonuses==null||def.equipBonuses.length<=slot?0:def.equipBonuses[slot];}
    private static int score(Item item,String style){ItemDef def=item.getDef();int attack=style.equals("MAGIC")?bonus(def,3):style.equals("RANGED")||style.equals("DARTS")?bonus(def,4):Math.max(bonus(def,0),Math.max(bonus(def,1),bonus(def,2)));int damage=bonus(def,style.equals("MAGIC")?12:style.equals("RANGED")||style.equals("DARTS")?11:10);int value=attack*4+damage*12+bonus(def,13);if(style.equals("FAST_MELEE")&&def.equipSlot==Equipment.SLOT_WEAPON)value-=def.weaponType.attackTicks*10000;return value;}
    private static Item best(Player p,String style,int slot){Item best=null;int value=Integer.MIN_VALUE;for(Item item:residents(p))if(item.getDef().equipSlot==slot&&equippable(p,item)&&(slot!=Equipment.SLOT_WEAPON||weaponMatches(item,style)&&(style.equals("MAGIC")?magicReady(item):style.equals("RANGED")||style.equals("DARTS")?rangedReady(p,item):true))){int candidate=score(item,style);if(candidate>value){value=candidate;best=item;}}return best;}
    private static boolean gear(Player p,String style){Item weapon=best(p,style,Equipment.SLOT_WEAPON);if(weapon==null)throw new IllegalStateException(style.equals("MAGIC")?"Carry a charged trident, Sanguinesti/Corrupted staff, or Tumeken's shadow for raid auto.":"Carry an equippable "+style.toLowerCase(Locale.ROOT)+" weapon with its compatible ammunition/charges before using raid auto.");boolean twoHanded=weapon.getDef().twoHanded;Item ammunition=ammo(p,weapon);boolean changed=false;for(int slot:new int[]{3,13,0,4,7,5,1,2,9,10,12}){if(slot==5&&twoHanded)continue;Item selected=slot==3?weapon:slot==13?ammunition:best(p,style,slot);if(selected!=null&&selected.getContainer()==p.getInventory()){if(!RaidAutoActions.equip(p,selected))return false;changed=true;}}
        Item held=p.getEquipment().get(Equipment.SLOT_WEAPON);if(held==null||!weaponMatches(held,style))return false;if(changed){AttackSet chosen=null;for(AttackSet set:held.getDef().weaponType.attackSets)if(set!=null&&set.style!=null&&matches(set.style,style)){if(chosen==null||set.type==AttackType.RAPID_RANGED)chosen=set;}if(chosen!=null)RaidAutoActions.button(p,593,chosen.child,0,1,0);}return true;}
    private static boolean drained(Player p){for(StatType type:new StatType[]{StatType.Attack,StatType.Ranged,StatType.Magic}){Stat stat=p.getStats().get(type);if(stat.currentLevel<Math.max(1,(stat.fixedLevel*85+99)/100))return true;}return false;}
    private static boolean food(Item item){return item.getDef().consumable&&item.getDef().getOption("Eat")>0;}
    private static boolean sustain(Player p){if(p.isStunned())return false;int hp=p.getHp(),critical=Math.max(1,p.getMaxHp()*30/100),heal=Math.max(1,p.getMaxHp()/2);boolean restore=drained(p)||p.getStats().get(StatType.Prayer).currentLevel<Math.max(1,p.getStats().get(StatType.Prayer).fixedLevel/4);for(int priority=0;priority<3;priority++)for(Item item:p.getInventory().getItems())if(item!=null){String name=item.getDef().name.toLowerCase(Locale.ROOT);boolean healing=food(item)||name.contains("ambrosia")||name.contains("nectar")||name.contains("saradomin brew"),restoring=name.contains("super restore")||name.contains("tears of elidinis");int rank=hp<critical&&healing?0:restore&&restoring?1:hp<heal&&healing?2:99;if(rank!=priority)continue;String action=food(item)?"Eat":"Drink";if(p.potDelay.isDelayed()||p.karamDelay.isDelayed()||action.equals("Eat")&&p.eatDelay.isDelayed())continue;int id=item.getId(),amount=item.getAmount();Map<String,String> attributes=item.copyOfAttributes();if(RaidAutoActions.inventory(p,item,action)){if(item.getContainer()==p.getInventory()&&item.getId()==id&&item.getAmount()==amount&&p.getHp()==hp&&attributes.equals(item.copyOfAttributes()))throw new IllegalStateException("The native "+action.toLowerCase(Locale.ROOT)+" action refused "+item.getDef().name+"; check its activity/resource requirements.");return true;}}return false;}
    private static int source(Item item){String value=item.copyOfAttributes().get("toa.supply.source");return value==null?0:Integer.parseInt(value);}
    private static boolean tears(int src){return src>=27327&&src<=27333&&(src&1)==1;}
    private static boolean nectar(int src){return src>=27315&&src<=27321&&(src&1)==1;}
    private static int doses(int src){return nectar(src)?4-(src-27315)/2:tears(src)?4-(src-27327)/2:src==27347?2:src==27349?1:0;}
    private static int ordinaryDoses(int id,int first){return id>=first&&id<=first+6&&(id-first)%2==0?4-(id-first)/2:0;}

    private static final class Controller implements RaidAuto.Controller {
        final Player p;final TombsOfAmascutRaid.Session session;final int initialDeaths;
        Object encounter;ToaRoom room;String reason="Ready",lastAction="",supplyChoice;int supplyStage;long supplyStarted,lastActionTick=-100,lastProgress;String signature="";boolean chestRequested,claimed;
        Object scabarasEncounter,kephriEncounter;int completedDevices,matchedPairs,phase,minBossHp;boolean shield;long scabarasProgress,kephriProgress;
        Controller(Player p,TombsOfAmascutRaid.Session session){this.p=p;this.session=session;try{initialDeaths=number(session,"deaths");}catch(Exception failure){throw new IllegalStateException("Cannot observe current Tombs session",failure);}}
        public String status(){return "ToA "+session.room.title+", "+session.pathsCompleted()+"/4 paths, HP "+p.getHp()+": "+reason;}
        public void stopped(String why){ToaAutoPuzzlePlanner.reset(p,encounter);ToaAutoBossPlanner.reset(p);supplyChoice=null;}
        public boolean pulse(long tick)throws Exception{
            if(TombsOfAmascutRaid.currentSession(p)!=session||session.closed){reason="Native raid session ended";return false;}
            if(number(session,"deaths")>initialDeaths){reason="Stopped after a genuine raid death";return false;}
            if(!session.valid())return true;
            if(room!=session.room||encounter!=session.encounter){ToaAutoPuzzlePlanner.reset(p,encounter);ToaAutoBossPlanner.reset(p);encounter=session.encounter;room=session.room;signature="";lastProgress=tick;lastAction="";supplyChoice=null;}
            watchdog(tick);
            if(sustain(p)){reason="Use a carried consumable through its normal inventory action";return true;}
            if(room!=ToaRoom.REWARDS&&supplies(tick))return true;
            if(room==ToaRoom.MAIN_HALL){mainHall();return true;}
            if(room==ToaRoom.REWARDS)return rewards();
            Map<String,Object> plan=EnumSet.of(ToaRoom.CRONDIS,ToaRoom.SCABARAS,ToaRoom.HET,ToaRoom.APMEKEN).contains(room)?ToaAutoPuzzlePlanner.plan(p,session,encounter,tick):ToaAutoBossPlanner.plan(p,session,encounter,tick);
            execute(plan,tick);return true;
        }
        void watchdog(long now)throws Exception{
            String observed=room+":"+session.pathsCompleted()+":"+ToaAutoPuzzlePlanner.signature(encounter);if(!observed.equals(signature)){signature=observed;lastProgress=now;}if(now-lastProgress>=400)throw new IllegalStateException("No native encounter progress for400 ticks; take manual control.");
            if(room==ToaRoom.SCABARAS){int devices=count((boolean[])get(encounter,"done")),pairs=count((boolean[])get(encounter,"matched"));if(scabarasEncounter!=encounter){scabarasEncounter=encounter;completedDevices=devices;matchedPairs=pairs;scabarasProgress=now;}if(devices>completedDevices||pairs>matchedPairs){completedDevices=Math.max(devices,completedDevices);matchedPairs=Math.max(pairs,matchedPairs);scabarasProgress=now;}if(now-scabarasProgress>=400)throw new IllegalStateException("Scabaras device progression stalled; take manual control.");}
            if(room==ToaRoom.KEPHRI){Object rules=get(encounter,"rules");NPC boss=(NPC)get(encounter,"boss");int nextPhase=number(rules,"phase");boolean nextShield=number(rules,"shieldTicks")>0;if(kephriEncounter!=encounter||phase!=nextPhase||shield!=nextShield){kephriEncounter=encounter;phase=nextPhase;shield=nextShield;minBossHp=boss.getHp();kephriProgress=now;}else if(!shield&&boss.getHp()<minBossHp){minBossHp=boss.getHp();kephriProgress=now;}if(now-kephriProgress>=400)throw new IllegalStateException("Kephri native phase/health progression stalled; take manual control.");}
        }
        int count(boolean[] values){int result=0;for(boolean value:values)if(value)result++;return result;}
        void mainHall()throws Exception{Object supplies=session.supplies;Set<?> claimed=(Set<?>)get(supplies,"claimed");int completed=session.pathsCompleted(),checkpoint=completed>=4?4:completed>=2?2:0;if(checkpoint>0&&!claimed.contains(checkpoint)){TombsOfAmascutRaid.open(p);select(p,"Claim the helpful spirit's supplies");select(p,checkpoint==4?"Power:":"Life:");reason="Claim earned helpful-spirit supplies";return;}TombsOfAmascutRaid.open(p);if(completed==4){select(p,"Enter the Wardens'");return;}Set<?> cleared=(Set<?>)get(session,"cleared");ToaRoom[] puzzles={ToaRoom.CRONDIS,ToaRoom.SCABARAS,ToaRoom.HET,ToaRoom.APMEKEN},bosses={ToaRoom.ZEBAK,ToaRoom.KEPHRI,ToaRoom.AKKHA,ToaRoom.BABA};for(int i=0;i<puzzles.length;i++)if(!cleared.contains(bosses[i])){select(p,pathLabel(puzzles[i]));return;}}
        void execute(Map<String,Object> plan,long tick)throws Exception{if(plan==null)return;reason=String.valueOf(plan.get("reason"));for(String key:new String[]{"prayer","boost"})if(plan.get(key)!=null)RaidAutoActions.prayer(p,Prayer.valueOf(String.valueOf(plan.get(key))));if(plan.get("style")!=null&&!gear(p,String.valueOf(plan.get("style"))))return;String kind=String.valueOf(plan.get("kind"));NPC npc=(NPC)plan.get("npc");GameObject object=(GameObject)plan.get("object");String action=kind+":"+(npc==null?"":npc.getIndex())+":"+(object==null?"":object.id+":"+object.x+":"+object.y)+":"+plan.get("x")+":"+plan.get("y");if((kind.equals("object")||kind.equals("npc"))&&action.equals(lastAction)&&tick-lastActionTick<12)return;
            if(Boolean.TRUE.equals(plan.get("stopCombat"))&&!kind.equals("move")&&(Boolean.TRUE.equals(plan.get("stopAlways"))||p.getCombat().getTarget()!=null))RaidAutoActions.walk(p,p.getAbsX(),p.getAbsY());
            if(kind.equals("move")){io.ruin.model.map.Position tile=session.tile(((Number)plan.get("x")).intValue(),((Number)plan.get("y")).intValue());RaidAutoActions.walk(p,tile.getX(),tile.getY());}
            else if(kind.equals("attack")){if(p.getCombat().getTarget()!=npc){int option=npc.getDef().attackOption;if(option<1)option=npc.getDef().getOption("Hit");if(option<1)throw new IllegalStateException("Native NPC attack option is unavailable");RaidAutoActions.npc(p,npc,option,Boolean.TRUE.equals(plan.get("ctrlRun")));}}
            else if(kind.equals("npc"))RaidAutoActions.npc(p,npc,((Number)plan.get("option")).intValue());
            else if(kind.equals("object"))RaidAutoActions.object(p,object,((Number)plan.get("option")).intValue());
            else if(kind.equals("inventory"))RaidAutoActions.inventory(p,((Number)plan.get("id")).intValue(),String.valueOf(plan.get("action")));
            else if(!kind.equals("wait"))throw new IllegalArgumentException("Unknown native raid action: "+kind);lastAction=action;lastActionTick=tick;
        }
        @SuppressWarnings("unchecked") boolean supplies(long tick)throws Exception{
            List<Item> stored=(List<Item>)get(session.supplies,"stored");Item bag=null;int heal=0,drain=0,restore=0,loans=0,carriedTears=0;for(Item item:p.getInventory().getItems())if(item!=null){int src=source(item);if(src==27314)bag=item;else if(item.copyOfAttributes().containsKey("toa.supply.loan"))loans++;int d=doses(src);if(tears(src)){restore+=d;carriedTears++;}else if(nectar(src)){heal+=d;drain+=d;}else if(src==27347||src==27349)heal+=d;int brew=ordinaryDoses(item.getId(),6685);heal+=brew+(food(item)?item.getAmount():0);drain+=brew;restore+=ordinaryDoses(item.getId(),3024);}if(bag==null){supplyChoice=null;return false;}int free=p.getInventory().getFreeSlots(),tears=0,nectar=0,ambrosia=0;for(Item item:stored){int src=source(item);if(tears(src))tears++;if(nectar(src))nectar++;if(src==27347||src==27349)ambrosia++;}
            if(supplyChoice!=null){if(tick-supplyStarted>=12)throw new IllegalStateException("Native supply menu did not complete within12 ticks");if(supplyStage==1&&option(p,"Choose supplies to withdraw")){if(supplyChoice.equals("STORE")){select(p,"Store carried Tombs supplies");supplyChoice=null;}else{select(p,"Choose supplies to withdraw");supplyStage=2;}return true;}if(supplyStage==2){String prefix=supplyChoice.equals("TEARS")?"Tears of Elidinis (":supplyChoice.equals("NECTAR")?"Nectar (":"Ambrosia (";int batch=supplyChoice.equals("TEARS")?tears:supplyChoice.equals("NECTAR")?nectar:ambrosia;if(option(p,prefix)){if(batch>0&&batch<=free-3)select(p,prefix);supplyChoice=null;return true;}if(supplyChoice.equals("AMBROSIA")&&option(p,"Next supplies")){select(p,"Next supplies");return true;}}RaidAutoActions.inventory(p,bag,"Open");supplyStage=1;return true;}
            if(stored.isEmpty())return false;int budget=free-3,first=source(stored.get(0));String choice=null;
            if(tears>0&&restore<Math.max(4,(drain+1)/2)){if(tears<=budget)choice="TEARS";else if(loans>0&&tears+carriedTears<=budget+loans)choice="STORE";else throw new IllegalStateException("Free "+Math.max(0,tears-budget)+" inventory slots for the earned restoration batch before continuing.");}
            if(choice==null&&heal==0&&nectar+ambrosia>0&&budget<=0)throw new IllegalStateException("Free inventory space to withdraw earned healing supplies.");
            if(choice==null&&heal<6&&budget>0){if(nectar(first)||first==27347||first==27349){RaidAutoActions.inventory(p,bag,"Withdraw 1");reason="Withdraw one earned healing supply";return true;}if(nectar>0&&nectar<=budget)choice="NECTAR";else if(ambrosia>0&&ambrosia<=budget)choice="AMBROSIA";else if(heal==0&&nectar+ambrosia>0)throw new IllegalStateException("Free inventory space to withdraw earned healing supplies.");}
            if(choice==null&&room==ToaRoom.MAIN_HALL&&budget>0){RaidAutoActions.inventory(p,bag,"Withdraw 1");reason="Withdraw earned supplies";return true;}
            if(choice==null)return false;supplyChoice=choice;supplyStage=1;supplyStarted=tick;RaidAutoActions.inventory(p,bag,"Open");reason="Balance earned healing and restoration through the native bag menu";return true;
        }
        boolean rewards()throws Exception{
            if(ToaRewardClaims.busy(p)){reason="Await native durable reward save";return true;}
            TombsOfAmascutRaid.Progress progress=p.toaProgress;boolean pending=false;if(progress!=null&&progress.pendingRewards!=null)for(Item reward:progress.pendingRewards)if(reward!=null)pending=true;
            if(pending){if(p.getInventory().getFreeSlots()<6){boolean carried=false;for(Item item:p.getInventory().getItems())if(item!=null&&source(item)!=27314&&item.copyOfAttributes().containsKey("toa.supply.loan"))carried=true;if(carried)for(Item item:p.getInventory().getItems())if(item!=null&&source(item)==27314){RaidAutoActions.inventory(p,item,"Open");select(p,"Store carried Tombs supplies");reason="Store carried Tombs supplies for reward space";return true;}throw new IllegalStateException("Free inventory space before collecting native Tombs rewards.");}if(!chestRequested){RaidAutoActions.object(p,(GameObject)ToaAutoPuzzlePlanner.rewardChest(session),1);chestRequested=true;reason="Approach the real burial chest";return true;}if(option(p,"Collect rewards")){select(p,"Collect rewards");claimed=true;chestRequested=false;reason="Collect genuinely earned native rewards";}return true;}
            TombsOfAmascutRaid.open(p);select(p,"Leave safely");reason=claimed?"Native rewards collected; normal raid exit":"Native burial rewards are empty; normal raid exit";return false;
        }
    }
}
