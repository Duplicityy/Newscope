package io.ruin.model.activities.dungeoneering;

import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.model.activities.raids.auto.RaidAutoAccess;
import io.ruin.model.activities.raids.auto.RaidAutoActions;
import io.ruin.model.combat.AttackSet;
import io.ruin.model.combat.AttackStyle;
import io.ruin.model.combat.AttackType;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.item.Item;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.skills.magic.rune.RuneRemoval;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/** Plans from carried native equipment, runes and real consumables. */
final class IDungeonCombatPlanner {
    final Player p;final IDungeonSettings c;final IDungeonActions a;
    private DungeoneeringFloor.RoomState room;private long craftUntil,shopAt=-100;private int shopFailures;
    IDungeonCombatPlanner(Player p,IDungeonSettings c,IDungeonActions a){this.p=p;this.c=c;this.a=a;}
    void enter(DungeoneeringFloor.RoomState r){room=r;shopFailures=0;}
    static boolean live(NPC n){return n!=null&&!n.isRemoved()&&n.getHp()>0&&n.getCombat()!=null&&!n.getCombat().isDead();}
    Item item(int source){Integer id=room.assets().itemMappings.get(Integer.toString(source));if(id==null)return null;for(Item i:p.getInventory().getItems())if(i!=null&&i.getId()==id)return i;return null;}
    private List<Item> residents(){List<Item> all=new ArrayList<>();for(Item i:p.getEquipment().getItems())if(i!=null)all.add(i);for(Item i:p.getInventory().getItems())if(i!=null)all.add(i);return all;}
    static boolean usable(Player p,ItemDef d){if(d.equipSlot<0||d.equipOption<1)return false;if(d.equipReqs!=null)for(int r:d.equipReqs)if(p.getStats().get(r>>8).fixedLevel<(r&255))return false;return true;}
    private static int bonus(ItemDef d,int i){return d.equipBonuses==null||d.equipBonuses.length<=i?0:d.equipBonuses[i];}
    static int score(ItemDef d,String style){return (style.equals("MAGIC")?bonus(d,3):style.equals("RANGED")?bonus(d,4):Math.max(bonus(d,0),Math.max(bonus(d,1),bonus(d,2))))*4+12*bonus(d,style.equals("MAGIC")?12:style.equals("RANGED")?11:10)+bonus(d,5)+bonus(d,6)+bonus(d,7)+bonus(d,8)+bonus(d,9);}
    private static boolean matches(ItemDef d,String style){if(style.equals("MAGIC"))return d.name.toLowerCase(Locale.ROOT).contains("staff");if(d.weaponType==null||d.weaponType.attackSets==null)return false;for(AttackSet s:d.weaponType.attackSets)if(s!=null&&s.style!=null&&(style.equals("RANGED")?s.style.isRanged():s.style.isMelee()))return true;return false;}
    private Item ammo(Item weapon){if(weapon.getDef().rangedWeapon==null)return null;Item best=null;for(Item i:residents())if(i.getDef().equipSlot==Equipment.SLOT_AMMO&&i.getDef().rangedAmmo!=null&&usable(p,i.getDef())&&weapon.getDef().rangedWeapon.allowAmmo(i.getDef().rangedAmmo)&&weapon.getDef().rangedLevel>=i.getDef().rangedLevel&&(best==null||score(i.getDef(),"RANGED")>score(best.getDef(),"RANGED")))best=i;return best;}
    private Item best(String style,int slot){Item best=null;for(Item i:residents())if(i.getDef().equipSlot==slot&&usable(p,i.getDef())&&(slot!=Equipment.SLOT_WEAPON||matches(i.getDef(),style)&&(!style.equals("RANGED")||ammo(i)!=null))&&(best==null||score(i.getDef(),style)>score(best.getDef(),style)))best=i;return best;}
    boolean hasStyle(String style)throws Exception{return style.equals("MELEE")||best(style,Equipment.SLOT_WEAPON)!=null&&(!style.equals("MAGIC")||spell()!=null);}
    String choose(String required)throws Exception{
        String preferred=c.combat.equals("AUTO")?"MELEE":c.combat;
        if(required!=null){if(!c.switchStyles&&!required.equals(preferred))throw new IllegalStateException("Enable combat style changes for this boss mechanic");if(!hasStyle(required))throw new IllegalStateException("Carry/bind a usable "+required.toLowerCase(Locale.ROOT)+" weapon and its ammunition/runes");return required;}
        if(hasStyle(preferred))return preferred;return "MELEE";
    }
    boolean gear(String style)throws Exception{
        Item weapon=best(style,Equipment.SLOT_WEAPON);
        if(!style.equals("MAGIC")&&DungeoneeringSpells.isDungeonSpell(p.getCombat().autocastSpell)){
            DungeoneeringSpells.open(p);IDungeonActions.select(p,"Combat spells / autocast");IDungeonActions.select(p,"Stop dungeon autocast");p.closeInterface(InterfaceType.MAIN);return false;
        }
        boolean changed=false;Item arrows=weapon==null?null:ammo(weapon);
        for(int slot:new int[]{3,13,0,4,7,1,2,9,10,12,5}){
            if(slot==5&&weapon!=null&&weapon.getDef().twoHanded)continue;
            Item candidate=slot==3?weapon:slot==13?arrows:best(style,slot);
            if(candidate!=null&&candidate.getContainer()==p.getInventory()&&(c.upgradeGear||slot==3||slot==13)){
                if(!RaidAutoActions.equip(p,candidate))return false;changed=true;
            }
        }
        if(style.equals("MAGIC")){
            Object spell=spell();if(spell==null)throw new IllegalStateException("No affordable dungeon combat spell remains");
            String name=(String)RaidAutoAccess.get(spell,"name");Object current=p.getCombat().autocastSpell;
            if(current==null||!DungeoneeringSpells.isDungeonSpell(p.getCombat().autocastSpell)||RaidAutoAccess.get(current,"source")!=spell){
                DungeoneeringSpells.open(p);IDungeonActions.select(p,"Combat spells / autocast");
                if(!IDungeonActions.select(p,label(name)+" -"))throw new IllegalStateException("Native dungeon spell choice unavailable");p.closeInterface(InterfaceType.MAIN);return false;
            }
        }else if(changed&&weapon!=null&&weapon.getDef().weaponType!=null){
            AttackSet chosen=null;for(AttackSet s:weapon.getDef().weaponType.attackSets)if(s!=null&&s.style!=null&&(style.equals("RANGED")?s.style.isRanged():s.style.isMelee())){if(chosen==null||s.type==AttackType.RAPID_RANGED)chosen=s;}
            if(chosen!=null)RaidAutoActions.button(p,593,chosen.child,0,1,0);
        }
        return !changed;
    }
    private Object spell()throws Exception{
        Object best=null;int maximum=0;
        for(Object s:(Object[])RaidAutoAccess.get(DungeoneeringSpells.class,"COMBAT")){
            int level=((Number)RaidAutoAccess.get(s,"level")).intValue(),damage=((Number)RaidAutoAccess.get(s,"max")).intValue();String name=(String)RaidAutoAccess.get(s,"name");
            if(level>p.getStats().get(StatType.Magic).currentLevel||damage<=maximum||name.equals("BIND")||name.equals("SNARE")||name.equals("ENTANGLE"))continue;
            int[] runes=(int[])RaidAutoAccess.get(s,"runes");Item[] cost=new Item[runes.length/2];for(int i=0;i<cost.length;i++)cost[i]=new Item(room.assets().item(runes[i*2]),runes[i*2+1]);
            if(RuneRemoval.get(p,cost)!=null){maximum=damage;best=s;}
        }return best;
    }
    boolean attack(DungeoneeringFloor.RoomState r,NPC target,String required)throws Exception{
        if(!live(target))return true;String style=choose(required);if(!gear(style))return true;
        if(c.prayer){AttackStyle incoming=incoming(target);Prayer protection=incoming!=null&&incoming.isMagic()?Prayer.PROTECT_FROM_MAGIC:incoming!=null&&incoming.isRanged()?Prayer.PROTECT_FROM_MISSILES:Prayer.PROTECT_FROM_MELEE;RaidAutoActions.prayer(p,protection);}
        return a.attack(target);
    }
    private AttackStyle incoming(NPC target)throws Exception{
        Object state=((Map<?,?>)RaidAutoAccess.get(DungeoneeringCombat.class,"ACTIVE")).get(target);
        AttackStyle style=target.getCombat().getInfo().attack_style;long first=Long.MAX_VALUE;
        if(state!=null)for(Object due:(List<?>)RaidAutoAccess.get(state,"pending")){
            long tick=((Number)RaidAutoAccess.get(due,"tick")).longValue();if(tick>=first)continue;Object action=RaidAutoAccess.get(due,"action");
            for(Field field:action.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&field.getType()==AttackStyle.class){field.setAccessible(true);AttackStyle marked=(AttackStyle)field.get(action);if(marked!=null){style=marked;first=tick;}}
        }return style;
    }
    boolean sustain(){
        if(p.isStunned()||p.potDelay.isDelayed()||p.eatDelay.isDelayed()||p.karamDelay.isDelayed())return false;
        if(c.autoEat&&p.getHp()*100<=p.getMaxHp()*c.eatPercent){Item best=null;double heal=-1;for(Item i:p.getInventory().getItems())if(i!=null){DungeoneeringSkills.Food food=DungeoneeringSkills.data.foods.get(Integer.toString(room.assets().sourceItem(i.getId())));if(food!=null&&i.getDef().getOption("Eat")>0&&food.heal>heal){best=i;heal=food.heal;}}if(best!=null)return RaidAutoActions.inventory(p,best,"Eat");}
        boolean prayer=c.prayer&&p.getStats().get(StatType.Prayer).currentLevel<Math.max(1,p.getStats().get(StatType.Prayer).fixedLevel/4),drained=false;
        for(StatType t:new StatType[]{StatType.Attack,StatType.Strength,StatType.Defence,StatType.Magic,StatType.Ranged})if(p.getStats().get(t).currentLevel<p.getStats().get(t).fixedLevel*85/100)drained=true;
        for(Item i:p.getInventory().getItems())if(i!=null){String n=i.getDef().name.toLowerCase(Locale.ROOT);if(i.getDef().getOption("Drink")>0&&(prayer&&n.contains("prayer")||drained&&n.contains("restore")))return RaidAutoActions.inventory(p,i,"Drink");}
        if(c.buryBones)for(Item i:p.getInventory().getItems())if(i!=null&&i.getDef().getOption("Bury")>0)return RaidAutoActions.inventory(p,i,"Bury");return false;
    }
    int foodCount(){int count=0;for(Item i:p.getInventory().getItems())if(i!=null&&DungeoneeringSkills.data.foods.containsKey(Integer.toString(room.assets().sourceItem(i.getId()))))count+=i.getAmount();return count;}
    boolean want(int id){
        ItemDef d=ItemDef.get(id);int src=room.assets().sourceItem(id);String n=d.name.toLowerCase(Locale.ROOT);
        if(src<0)return false;
        if(Arrays.asList(16295,17678,17754,17883).contains(src))return item(src)==null&&p.getEquipment().getId(3)!=id;
        if(c.cookFood&&(src==17794||src==17796))return item(src)==null;
        if(src==18201)return c.lootCoins;
        if(src>=17780&&src<=17793||src==17776)return c.lootRunes;
        if(DungeoneeringSkills.data.foods.containsKey(Integer.toString(src)))return c.lootFood&&foodCount()<10;
        if(d.rangedAmmo!=null)return c.lootAmmo;
        if(c.buryBones&&d.getOption("Bury")>0)return true;
        if(!c.upgradeGear||!usable(p,d))return false;
        for(String style:new String[]{"MELEE","RANGED","MAGIC"}){
            if(d.equipSlot==Equipment.SLOT_WEAPON&&!matches(d,style))continue;
            Item existing=best(style,d.equipSlot);if(existing==null||score(d,style)>score(existing.getDef(),style))return true;
        }return false;
    }
    boolean prepare(DungeoneeringFloor.RoomState r)throws Exception{
        if(r.node.id!=r.dungeon().graph.startId)return false;
        int needed=-1;
        if(best("RANGED",Equipment.SLOT_WEAPON)==null&&item(16867)==null)needed=16867;
        else if(item(16427)==null&&p.getEquipment().get(Equipment.SLOT_AMMO)==null)needed=16427;
        else if(c.combat.equals("MAGIC")&&best("MAGIC",Equipment.SLOT_WEAPON)==null)needed=16977;
        if(needed>=0&&r.complexity()>=3&&item(18201)!=null&&p.getInventory().getFreeSlots()>2){
            if(Server.currentTick()-shopAt<3)return true;if(shopFailures>=3)throw new IllegalStateException("Smuggler could not supply required equipment; check rusty coins and inventory space");
            String name=ItemDef.get(r.assets().item(needed)).name;
            if(p.optionScroll!=null&&"Smuggler supplies".equals(p.optionScroll.title)){int before=p.getInventory().count(r.assets().item(needed));if(IDungeonActions.select(p,name+" -")){if(p.getInventory().count(r.assets().item(needed))==before)shopFailures++;shopAt=Server.currentTick();return true;}}
            if(IDungeonActions.select(p,"Buy supplies"))return true;
            for(NPC n:r.npcs)if(r.assets().sourceNpc(n.getId())==11226)return a.npc(r,n,1);
        }
        return c.combat.equals("MAGIC")&&prepareRunes(r);
    }
    boolean prepareRunes(DungeoneeringFloor.RoomState r){
        if(Server.currentTick()<craftUntil)return true;
        int[] sources={17780,17784};for(int src:sources)if(p.getInventory().count(r.assets().item(src))<20&&item(17776)!=null){
            for(DungeoneeringSkills.Recipe recipe:DungeoneeringSkills.data.recipes)if(recipe.category.equals("DUNG_RUNECRAFTING")&&recipe.output.id==src&&canMake(recipe))return recipe(r,recipe);
        }return false;
    }
    private boolean canMake(DungeoneeringSkills.Recipe recipe){if(DungeoneeringSkills.effectiveLevel(p,DungeoneeringSkills.skill(recipe.skill))<recipe.level)return false;if(recipe.tool>=0&&item(recipe.tool)==null)return false;for(DungeoneeringSkills.Ingredient i:recipe.inputs)if(p.getInventory().count(room.assets().item(i.id))<i.amount)return false;return true;}
    private boolean recipe(DungeoneeringFloor.RoomState r,DungeoneeringSkills.Recipe recipe){
        String category=recipe.category;
        String[] words=category.equals("DUNG_RUNECRAFTING")?new String[]{"runecrafting","altar"}:new String[]{"fire","range"};GameObject fixture=null;
        for(GameObject o:r.objects())for(String w:words)if(o.getDef().name.toLowerCase(Locale.ROOT).contains(w)){fixture=o;break;}
        if(fixture==null)return false;if(!p.getPosition().isWithinDistance(fixture.getPosition(),4))return a.approach(r,fixture.getPosition());
        if(IDungeonActions.select(p,"Make 5")){craftUntil=Server.currentTick()+16;return true;}
        String name=ItemDef.get(r.assets().item(recipe.output.id)).name+" x";
        if(IDungeonActions.select(p,name))return true;
        if(IDungeonActions.select(p,category.replace("DUNG_","").replace('_',' ')))return true;
        DungeoneeringSkills.menu(p);return true;
    }
    boolean replaceFood(DungeoneeringFloor.RoomState r){
        if(foodCount()>=3)return false;if(Server.currentTick()<craftUntil)return true;
        for(DungeoneeringSkills.Recipe recipe:DungeoneeringSkills.data.recipes)if(recipe.category.equals("DUNG_COOKING")&&canMake(recipe)&&recipe(r,recipe))return true;
        if(item(17794)!=null&&item(17796)!=null&&p.getInventory().getFreeSlots()>2)for(NPC n:r.npcs)if(live(n)==false&&!n.isRemoved()&&r.assets().sourceNpc(n.getId())==2859){craftUntil=Server.currentTick()+17;return a.npc(r,n,1);}return false;
    }
    private static String label(String name){StringBuilder s=new StringBuilder();for(String word:name.toLowerCase(Locale.ROOT).split("_")){if(s.length()>0)s.append(' ');s.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));}return s.toString();}
}
