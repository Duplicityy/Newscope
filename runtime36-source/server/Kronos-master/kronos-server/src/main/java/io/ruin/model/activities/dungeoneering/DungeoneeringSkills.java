package io.ruin.model.activities.dungeoneering;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.model.combat.Hit;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.*;
import io.ruin.model.item.actions.impl.Consumable;
import io.ruin.model.skills.magic.rune.Rune;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.map.Position;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.stat.StatType;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Uses actual donor recipes/levels/ingredients, presented with the client's existing native widgets. */
public final class DungeoneeringSkills {
    private static DungeoneeringAssets assets;
    static Data data;
    private static final Map<GameObject,Integer> harvests=new IdentityHashMap<>();
    private static final Map<Integer,Integer> poisonTiers=new HashMap<>();
    public static final class Ingredient {public int id,amount;}
    public static final class Recipe {public String name,category,skill;public int level,tool,animation;public double xp;public Ingredient output;public Ingredient[] inputs;public int[] multipliers;}
    public static final class Food {public String name;public double heal;}
    public static final class Familiar {public String name,style;public int npc,combatNpc,specialAnimation,specialAnimation2,level,minutes,pointCost,scroll,specialCost,storage;public double callXp,infuseXp;}
    static final class Poisonable {int base;int[] poisoned;}
    static final class Data {int schemaVersion;Recipe[] recipes;Map<String,Food> foods;Map<String,Familiar> familiars;List<List<Integer>> stock;List<DungeoneeringGathering.Fire> fires;DungeoneeringRanged.Arrow[] arrows;Map<String,JsonObject> forage;Poisonable[] poisonable;}
    private static final double[] MINE_XP={15,27.5,41,56.5,71,85,100.5,117,131,148},WOOD_XP={35,60,85,115,145,175,210,245,285,330},FARM_XP={6.1,9.2,12.8,17.4,23.5,31.6,42.2,55.8,72.9,94};
    private static final int[] MINES={49806,49786,49766,53771,55514},TREES={49745,49725,49705,53751,55494},FLOWERS={49866,49846,49826,53791,55534},CHESTS={49345,49346,49347,54407,32462};
    private static final String[] NATIVE_SKILLS={"Attack","Defence","Strength","Hitpoints","Ranged","Prayer","Magic","Cooking","Woodcutting","Fletching","Fishing","Firemaking","Crafting","Smithing","Mining","Herblore","Agility","Thieving","Slayer","Farming","Runecrafting","Hunter","Construction"};
    private DungeoneeringSkills(){}
    static StatType skill(String name){for(StatType type:StatType.values())if(type.name().equalsIgnoreCase(name)||name.equals("DEFENSE")&&type==StatType.Defence||name.equals("RANGE")&&type==StatType.Ranged)return type;throw new IllegalArgumentException("Not a native skill: "+name);}
    static Data readData()throws Exception{Path path=Server.dataFolder.toPath().resolve("dungeoneering/skill-recipes.json");if(!Files.isRegularFile(path)||Files.size(path)>4*1024*1024)throw new IllegalStateException("Missing donor skill recipes");Data result=new Gson().fromJson(new String(Files.readAllBytes(path),StandardCharsets.UTF_8),Data.class);if(result==null||result.schemaVersion!=1||result.recipes==null||result.recipes.length<500||result.foods==null)throw new IllegalStateException("Incomplete donor recipe set");return result;}
    static void collectRuntimeAssets(DungeoneeringRuntimeAssets.Required r,DungeoneeringAssets candidate){
        final Data authored;try{authored=readData();}catch(Exception e){throw new IllegalStateException("Cannot preflight donor recipe data",e);}
        for(Recipe recipe:authored.recipes){r.add("items",recipe.output.id,recipe.tool);r.add("animations",recipe.animation);for(Ingredient input:recipe.inputs)r.add("items",input.id);}for(String item:authored.foods.keySet())r.add("items",Integer.parseInt(item));for(List<Integer> stock:authored.stock)for(int item:stock)r.add("items",item);for(DungeoneeringGathering.Fire fire:authored.fires){r.add("items",fire.item);r.add("objects",fire.object);}for(DungeoneeringRanged.Arrow arrow:authored.arrows){r.add("items",arrow.items);r.add("spotanims",arrow.drawback,arrow.projectile);}for(Poisonable weapon:authored.poisonable){r.add("items",weapon.base);r.add("items",weapon.poisoned);}
        r.add("items",16295,16933,18201,17678,16361,17794,17754,17883,17446,16427,17780,17776,17796,17490,17752,17447,526,532,536);r.range("items",17780,17793);r.range("items",18017,18020);r.range("items",19653,19659);
        for(int tier=0;tier<11;tier++)r.add("items",16295+tier*2,16361+tier*2);for(int tier=0;tier<8;tier++)for(int base:new int[]{17239,16669,17217,16845,17173,17319,16935,15753,16977,16867,16383,18159})r.add("items",base+tier*2);for(int tier=0;tier<8;tier++)r.add("items",16427+tier*5);
        for(int tier=0;tier<10;tier++){r.add("items",17995+tier*2,17797+tier*2);for(int theme=0;theme<5;theme++)for(int base:new int[]{MINES[theme],TREES[theme],FLOWERS[theme]})r.add("objects",base+tier*2,base+tier*2+1);}
        for(int tier=0;tier<5;tier++)r.add("items",16757+tier*2,17630+tier*2,17682+tier*2,17448+tier*2);for(int tier=0;tier<3;tier++)r.add("items",17494+tier*2,17823+tier);
        r.add("objects",CHESTS);r.add("objects",49348,49349,49350,54408,32679);r.add("npcs",2859);r.add("animations",13074,13118);
        for(Map.Entry<String,Integer> npc:candidate.npcMappings.entrySet()){int id=Integer.parseInt(npc.getKey());if(id>=10320&&id<=10459||id>=10480&&id<=10486||id>=10560&&id<=10603||id>=10157&&id<=10187||id>=10225&&id<=10235||id>=10364&&id<=10374||id>=10629&&id<=10669||id>=10726&&id<=10769||id>=10797&&id<=10830||id>=12903&&id<=12913||id>=12941&&id<=12951)r.add("npcs",id);}
    }
    public static void register(DungeoneeringAssets imported)throws Exception{
        assets=imported;data=readData();
        if(data==null||data.schemaVersion!=1||data.recipes==null||data.recipes.length<500||data.foods==null)throw new IllegalStateException("Incomplete donor recipe set");
        for(Recipe recipe:data.recipes){assets.item(recipe.output.id);for(Ingredient i:recipe.inputs)assets.item(i.id);if(recipe.tool>=0)assets.item(recipe.tool);}
        for(Map.Entry<String,Food> entry:data.foods.entrySet()){
            int source=Integer.parseInt(entry.getKey()),target=assets.item(source);Food food=entry.getValue();ItemDef.get(target).consumable=true;ItemAction.registerInventory(target,"Eat",(p,item)->{if(!Dungeoneering.inRun(p)){p.sendMessage("Dungeon food can only be eaten inside a dungeon.");return;}if(p.isLocked()||p.eatDelay.isDelayed()||p.karamDelay.isDelayed()||p.potDelay.isDelayed())return;item.remove(1);Consumable.animEat(p);p.incrementHp((int)Math.floor(food.heal));p.eatDelay.delay(3);p.getCombat().delayAttack(3);});
            ItemPlayerAction.register(target,(p,item,other)->{DungeoneeringFloor f=Dungeoneering.floor(p);if(f==null||f!=Dungeoneering.floor(other)||!f.contains(p)||!f.contains(other)||p.isLocked()||other.isLocked()||p.eatDelay.isDelayed())return;if(other.getHp()>=other.getMaxHp()){p.sendMessage("They are already at full health.");return;}item.remove(1);Consumable.animEat(p);other.incrementHp(DungeoneeringKinship.medicHeal(p,(int)Math.floor(food.heal)));p.eatDelay.delay(3);p.getCombat().delayAttack(3);});
        }
        for(int source=19653;source<=19658;source++){Integer target=assets.itemMappings.get(Integer.toString(source));if(target!=null)ItemAction.registerInventory(target,1,(p,item)->{DungeoneeringFloor floor=Dungeoneering.floor(p);if(floor==null)return;DungeoneeringFloor.RoomState room=floor.room(p.getPosition());if(room!=null&&room.puzzle instanceof DungeoneeringPuzzles.Poltergeist)((DungeoneeringPuzzles.Poltergeist)room.puzzle).consecrate(p,item);});}
        for(int target:assets.objectMappings.values())ItemObjectAction.register(target,(p,item,obj)->{DungeoneeringFloor floor=Dungeoneering.floor(p);if(floor!=null&&floor.itemObject(p,item,obj))return;p.sendMessage("Nothing interesting happens.");});
        for(int target:assets.itemMappings.values())ItemItemAction.register(target,(p,from,to)->{if(!Dungeoneering.inRun(p))return;int a=assets.sourceItem(from.getId()),b=assets.sourceItem(to.getId());List<Recipe> choices=new ArrayList<>();for(Recipe recipe:data.recipes)if(uses(recipe,a)&&uses(recipe,b)||recipe.tool==a&&uses(recipe,b)||recipe.tool==b&&uses(recipe,a))choices.add(recipe);showRecipes(p,choices,0);});
        for(int source=17556;source<=17628;source+=2){Integer target=assets.itemMappings.get(Integer.toString(source));if(target!=null){final int id=source;ItemAction.registerInventory(target,"Drink",(p,item)->drink(p,item,id));}}
        for(Recipe recipe:data.recipes)if(recipe.category.equals("DUNG_HERB_CLEANING")){int target=assets.item(recipe.inputs[0].id);ItemAction.registerInventory(target,"Clean",(p,item)->craft(p,recipe,1));}
        for(int source:new int[]{526,532,536}){int target=assets.item(source);final double prayerXp=source==526?4.5:source==532?15:72;ItemAction.registerInventory(target,"Bury",(p,item)->{if(!Dungeoneering.inRun(p)||p.isLocked()||p.eatDelay.isDelayed())return;item.remove(1);p.animate(827);xp(p,StatType.Prayer,prayerXp);p.eatDelay.delay(2);});}
        if(data.poisonable==null||data.poisonable.length!=33)throw new IllegalStateException("All original dungeon poison families are required");
        for(Poisonable row:data.poisonable)for(int tier=0;tier<3;tier++){int target=assets.item(row.poisoned[tier]);poisonTiers.put(target,tier);final int result=target,poisonSource=new int[]{17572,17596,17620}[tier];ItemItemAction.register(assets.item(poisonSource),assets.item(row.base),(p,poison,item)->applyPoison(p,poison,item,result));}
        Rune[] runes={Rune.AIR,Rune.WATER,Rune.EARTH,Rune.FIRE,Rune.MIND,Rune.CHAOS,Rune.DEATH,Rune.BLOOD,Rune.BODY,Rune.COSMIC,Rune.ASTRAL,Rune.NATURE,Rune.LAW,Rune.SOUL};for(int i=0;i<runes.length;i++)ItemDef.get(assets.item(17780+i)).rune=runes[i];
        for(int id:assets.itemMappings.values()){ItemDef def=ItemDef.get(id);String name=def.name.toLowerCase(Locale.ROOT);if(name.contains("staff"))for(Rune rune:new Rune[]{Rune.AIR,Rune.WATER,Rune.EARTH,Rune.FIRE})if(name.contains(rune.name().toLowerCase(Locale.ROOT)))def.staffRune=rune;}
        DungeoneeringFloor.register(assets);DungeoneeringSummoning.register(imported,data.familiars);DungeoneeringRanged.register(imported,data.arrows);DungeoneeringGathering.register(imported,data.fires);
    }
    private static boolean uses(Recipe recipe,int source){for(Ingredient i:recipe.inputs)if(i.id==source)return true;return false;}
    public static void xp(Player p,StatType skill,double amount){if(amount>0)p.getStats().addXp(skill,amount,true);}
    private static void applyPoison(Player p,Item poison,Item item,int output){if(!Dungeoneering.inRun(p)||p.isLocked())return;int amount=Math.min(15,item.getAmount());if(amount<item.getAmount()&&p.getInventory().getFreeSlots()==0){p.sendMessage("You need inventory space to split this stack.");return;}Item[] before=Dungeoneering.copy(p.getInventory().getItems());poison.setId(assets.item(17490));if(amount==item.getAmount())item.setId(output);else{Item result=item.copy();result.setId(output);result.setAmount(amount);item.remove(amount);if(p.getInventory().add(result)!=amount){p.getInventory().clear();for(int slot=0;slot<before.length;slot++)if(before[slot]!=null)p.getInventory().set(slot,before[slot]);return;}}p.sendMessage("You coat your "+item.getDef().name.toLowerCase(Locale.ROOT)+" with weapon poison.");}
    public static void poisonedHit(Hit hit,Entity target){if(hit==null||target==null||target.npc==null||hit.damage<=0||hit.attackStyle==null||hit.attacker==null||hit.attacker.player==null)return;Player p=hit.attacker.player;DungeoneeringFloor f=Dungeoneering.floor(p);if(f==null||!Dungeoneering.inRun(p)||!f.owns(target.npc)||!hit.attackStyle.isMelee()&&!hit.attackStyle.isRanged())return;ItemDef used=hit.attackStyle.isRanged()?hit.rangedAmmo:hit.attackWeapon;Integer tier=used==null?null:poisonTiers.get(used.id);if(tier!=null&&f.random.nextInt(9)==0)target.poison(new int[]{5,6,7}[tier]);}
    static int effectiveLevel(Player p,StatType skill){return p.getStats().get(skill).currentLevel+DungeoneeringSummoning.aptitude(p);}
    static void clear(DungeoneeringFloor.RoomState room){Position sw=room.unrotatedTile(0,0);harvests.keySet().removeIf(obj->obj.x>=sw.getX()&&obj.x<sw.getX()+16&&obj.y>=sw.getY()&&obj.y<sw.getY()+16);DungeoneeringGathering.clear(room);}
    private static int levelForTier(int tier){return Math.max(1,tier*10);}
    static boolean tool(Player p,boolean mining){if(DungeoneeringExpeditionSupplies.beltTool(p,mining))return true;int base=mining?16295:16361;StatType skill=mining?StatType.Mining:StatType.Woodcutting;for(int tier=10;tier>=0;tier--){Integer id=assets.itemMappings.get(Integer.toString(base+tier*2));if(id!=null&&(p.getInventory().hasId(id)||p.getEquipment().getId(3)==id)&&p.getStats().get(skill).currentLevel>=(tier==10?99:levelForTier(tier)))return true;}return false;}
    static void starter(Player p,DungeoneeringFloor run){
        int defence=Math.min(8,1+p.getStats().get(StatType.Defence).fixedLevel/10),magic=Math.min(defence,Math.min(8,1+p.getStats().get(StatType.Magic).fixedLevel/10)),range=Math.min(defence,Math.min(8,1+p.getStats().get(StatType.Ranged).fixedLevel/10));
        if(run.party.complexity<=3){for(int id:new int[]{17239+(defence-1)*2,16669+(defence-1)*2,17217+(magic-1)*2,16845+(magic-1)*2,17173+(range-1)*2,17319+(range-1)*2})p.getInventory().add(assets.item(id),1);if(run.party.complexity<=2){for(int id:new int[]{16935+(defence-1)*2,15753+(defence-1)*2,16977+(magic-1)*2,16867+(range-1)*2})p.getInventory().add(assets.item(id),1);p.getInventory().add(assets.item(16427+(range-1)*5),100);p.getInventory().add(assets.item(17780),100);p.getInventory().add(assets.item(17784),100);}}
        for(Item bound:Dungeoneering.progress(p).boundItems)if(bound!=null)p.getInventory().add(bound.copy());
    }
    static void startTable(DungeoneeringFloor.RoomState r){
        List<Item> items=new ArrayList<>();for(int source:new int[]{16295,16933})items.add(new Item(source));
        if(r.complexity()>=2){
            if(r.supplyKit()){items.add(new Item(17796,50));items.add(new Item(17776,25));}
            else items.add(new Item(18201,5000+r.random().nextInt(10000)));
            for(int id:new int[]{17678,16361,17794,16933})items.add(new Item(id));}
        if(r.complexity()>=3){for(int id:new int[]{17754,17883,17446})items.add(new Item(id));items.add(new Item(16427,100));items.add(new Item(17780,100));items.add(new Item(17776,100));}
        for(Player p:r.dungeon().party.members){for(int i=0;i<9;i++)items.add(new Item(18159+r.random().nextInt(8)*2));for(int i=0;i<3;i++){int tier=r.random().nextInt(8);items.add(new Item(new int[]{16383+tier*2,16867+tier*2,16977+tier*2,17239+tier*2}[r.random().nextInt(4)]));}}
        for(Item item:items){int slot=r.random().nextInt(10),x=slot<6?9+r.random().nextInt(3):slot<8?10+r.random().nextInt(2):14,y=slot<6?10+r.random().nextInt(2):slot<8?14:10+r.random().nextInt(2);r.item(item.getId(),item.getAmount(),x,y);}
    }
    static void populate(DungeoneeringFloor.RoomState r){
        String puzzle=r.template.puzzle;
        if(!"FISHING_FERRET".equals(puzzle)&&!"TOXIN_MAZE".equals(puzzle)&&!"FREMENNIK_CAMP".equals(puzzle)){
            List<Integer> pool=guardians(r);int count=1+r.random().nextInt(3);
            for(int n=0;n<count&&!pool.isEmpty();n++){int source=pool.get(r.random().nextInt(pool.size()));for(int attempt=0;attempt<64;attempt++){int x=2+r.random().nextInt(11),y=2+r.random().nextInt(11);Position pos=r.tile(x,y);Tile tile=Tile.get(pos);if(tile!=null&&tile.isFloorFree()){r.guardians.add(r.spawn(source,x,y));break;}}}
        }
        if(r.puzzle!=null)return;
        if(r.complexity()>=3&&r.random().nextInt(3)==0)resource(r,MINES[r.theme()],StatType.Mining);
        if(r.complexity()>=2&&r.random().nextInt(3)==0)resource(r,TREES[r.theme()],StatType.Woodcutting);
        if(r.complexity()>=4&&r.random().nextInt(3)==0)resource(r,FLOWERS[r.theme()],StatType.Farming);
        if(r.complexity()>=2&&r.random().nextInt(4)==0)r.spawn(2859,3,3);
        if(r.complexity()>=5&&r.random().nextInt(3)==0)r.object(CHESTS[r.theme()],10,0,2,2);
        DungeoneeringGathering.populate(r);
    }
    private static void resource(DungeoneeringFloor.RoomState r,int base,StatType skill){int level=1;for(Player p:r.dungeon().party.members)level=Math.max(level,p.getStats().get(skill).fixedLevel);int tier=Math.min(9,Math.max(0,level/10-1));for(int attempt=0;attempt<64;attempt++){int x=1+r.random().nextInt(13),y=1+r.random().nextInt(13);Position pos=r.tile(x,y);Tile tile=Tile.get(pos);if(tile!=null&&tile.isTileFree()){r.object(base+tier*2,10,0,x,y);return;}}}
    private static List<Integer> guardians(DungeoneeringFloor.RoomState r){
        int max=1;for(Player p:r.dungeon().party.members)max=Math.max(max,p.getCombat().getLevel());final int maximum=max*2;
        List<Integer> pool=new ArrayList<>();int[][] ranges={{10397,10459},{10320,10363},{10560,10603},{10480,10486}};
        List<int[]> groups=new ArrayList<>(Arrays.asList(ranges));switch(r.theme()){case 0:groups.add(new int[]{10225,10235});groups.add(new int[]{10157,10167});groups.add(new int[]{10762,10769});break;case 1:groups.add(new int[]{10178,10187});groups.add(new int[]{10629,10669});groups.add(new int[]{10364,10374});break;case 2:groups.add(new int[]{10726,10735});groups.add(new int[]{10797,10814});break;case 3:groups.add(new int[]{10744,10753});groups.add(new int[]{10821,10830});groups.add(new int[]{10754,10761});break;case 4:groups.add(new int[]{12903,12913});groups.add(new int[]{12941,12951});groups.add(new int[]{10736,10743});break;}
        for(int[] range:groups)for(int id=range[0];id<=range[1];id++){Integer target=assets.npcMappings.get(Integer.toString(id));if(target==null)continue;NPCDef def=NPCDef.get(target);if(def.combatInfo!=null&&def.combatLevel<=maximum)pool.add(id);}return pool;
    }
    static void monsterDrop(DungeoneeringFloor.RoomState r,NPC npc){
        int[] local=r.local(npc.getPosition());int source=assets.sourceNpc(npc.getId()),tier=Math.max(1,Math.min(11,npc.getDef().combatLevel/11));java.util.Random random=r.random();
        if(source!=10831&&source!=10821)r.item(npc.getDef().name.toLowerCase(Locale.ROOT).contains("dragon")?536:npc.getSize()>1?532:526,1,local[0],local[1]);
        for(int i=0,count=1+random.nextInt(10);i<count;i++)r.item(18159+(Math.min(1+random.nextInt(tier),8)-1)*2,1,local[0],local[1]);
        if(random.nextInt(10)==0)r.item(16757+(Math.min(1+random.nextInt(tier),5)-1)*2,1,local[0],local[1]);
        if(random.nextInt(5)==0)r.item(17780+random.nextInt(14),90+random.nextInt(30),local[0],local[1]);
        if(r.complexity()>=5&&random.nextInt(5)==0)r.item(17995+(Math.min(1+random.nextInt(tier),10)-1)*2,1,local[0],local[1]);
        if(r.complexity()>=3&&random.nextInt(5)==0)r.item(17630+(Math.min(1+random.nextInt(tier),5)-1)*2,1+random.nextInt(10),local[0],local[1]);
        if(r.complexity()>=2&&random.nextInt(5)==0)r.item(17682+(Math.min(1+random.nextInt(tier),5)-1)*2,1+random.nextInt(10),local[0],local[1]);
        if(r.complexity()>=4&&random.nextInt(5)==0)r.item(17448+(Math.min(1+random.nextInt(tier),5)-1)*2,1+random.nextInt(10),local[0],local[1]);
        if(r.complexity()>=5&&random.nextInt(5)==0)r.item(17494+(Math.min(1+random.nextInt(tier),3)-1)*2,1+random.nextInt(10),local[0],local[1]);
        if(r.complexity()>=5&&random.nextInt(5)==0)r.item(17823+Math.min(1+random.nextInt(tier),3)-1,1+random.nextInt(10),local[0],local[1]);
        if(r.complexity()>=5&&random.nextInt(3)==0)r.item(18017+random.nextInt(4),npc.getSize(),local[0],local[1]);
        if(r.complexity()>=2)r.item(18201,1000+random.nextInt(10001),local[0],local[1]);
        if(r.complexity()>=3&&random.nextInt(5)==0)r.item(17776,10+random.nextInt(300),local[0],local[1]);
        if(r.complexity()>=2&&random.nextInt(5)==0)r.item(17796,10+random.nextInt(300),local[0],local[1]);
        if(r.complexity()>=5&&random.nextInt(10)==0)r.item(17490,1,local[0],local[1]);
        if(random.nextInt(10)==0)r.item(16933,1,local[0],local[1]);
        if(r.complexity()>=4&&random.nextInt(10)==0)r.item(17752,1,local[0],local[1]);
        if(r.complexity()>=2&&random.nextInt(10)==0)r.item(17794,1,local[0],local[1]);
        if(r.complexity()>=4&&random.nextInt(5)==0)r.item(17447,10+random.nextInt(300),local[0],local[1]);
    }
    public static boolean object(DungeoneeringFloor.RoomState r,Player p,GameObject obj,int source,int option){
        if(DungeoneeringGathering.object(r,p,obj,source,option))return true;
        String name=obj.getDef().name.toLowerCase(Locale.ROOT);
        for(int tier=0;tier<10;tier++){
            if(source==MINES[r.theme()]+tier*2){gather(r,p,obj,StatType.Mining,tier,17630+tier*2,MINE_XP[tier],13074,true);return true;}
            if(source==TREES[r.theme()]+tier*2){gather(r,p,obj,StatType.Woodcutting,tier,17682+tier*2,WOOD_XP[tier],13118,true);return true;}
            if(source==FLOWERS[r.theme()]+tier*2){gather(r,p,obj,StatType.Farming,tier,17448+tier*2,FARM_XP[tier],3659,false);return true;}
        }
        if(source==CHESTS[r.theme()]){int level=Math.max(1,Math.min(90,r.floor()/6*10));if(p.getStats().get(StatType.Thieving).currentLevel<level){p.sendMessage("You need Thieving level "+level+".");return true;}r.replace(obj,new int[]{49348,49349,49350,54408,32679}[r.theme()]);p.getInventory().add(assets.item(18201),1+r.random().nextInt((level/10+1)*1000));xp(p,StatType.Thieving,new double[]{25.5,57,115,209,331.5,485,661.5,876,1118,1410.5}[level/10]);return true;}
        if(name.contains("anvil")){menu(p,"DUNG_SMITHING");return true;}if(name.contains("furnace")){menu(p,"DUNG_SMELTING");return true;}if(name.contains("runecrafting")||name.equals("altar")){menu(p,"DUNG_RUNECRAFTING");return true;}if(name.contains("summoning")||name.contains("obelisk")){menu(p,"DUNG_SUMMONING");return true;}
        if(name.equals("water trough")){int from=assets.item(17490);int n=p.getInventory().count(from);if(n>0){p.getInventory().remove(from,n);p.getInventory().add(assets.item(17492),n);}return true;}return false;
    }
    private static void gather(DungeoneeringFloor.RoomState r,Player p,GameObject object,StatType skill,int tier,int output,double experience,int animation,boolean tool){
        if(effectiveLevel(p,skill)<levelForTier(tier)){p.sendMessage("You need "+skill.name()+" level "+levelForTier(tier)+".");return;}
        if(tool&&!tool(p,skill==StatType.Mining)){p.sendMessage("You need a usable "+(skill==StatType.Mining?"pickaxe":"hatchet")+".");return;}
        p.startEvent(event->{while(r.active()&&r.contains(p)&&object.id>=0&&p.getInventory().getFreeSlots()>0){Integer nativeAnimation=assets.animationMappings.get(Integer.toString(animation));if(nativeAnimation!=null)p.animate(nativeAnimation);event.delay(3);if(!r.active()||!r.contains(p)||object.id<0)return;if(p.getInventory().add(assets.item(output),1)>0){xp(p,skill,experience);int left=harvests.getOrDefault(object,3+r.random().nextInt(4))-1;harvests.put(object,left);if(left<=0){int src=assets.sourceObject(object.id);r.replace(object,src+1);harvests.remove(object);break;}}}p.resetAnimation();});
    }
    static boolean npc(DungeoneeringFloor.RoomState r,Player p,NPC npc,int source,int option){if(DungeoneeringGathering.npc(r,p,npc,source,option))return true;if(source==2859){int tier=Math.min(9,Math.max(0,p.getStats().get(StatType.Fishing).currentLevel/10));if(!p.getInventory().hasId(assets.item(17794))||!p.getInventory().hasId(assets.item(17796))){p.sendMessage("You need a fishing rod and feathers.");return true;}p.startEvent(event->{for(int i=0;i<5&&r.active()&&r.contains(p);i++){event.delay(3);if(!r.contains(p)||p.getInventory().getFreeSlots()==0||!p.getInventory().hasId(assets.item(17796)))return;p.getInventory().remove(assets.item(17796),1);p.getInventory().add(assets.item(17797+tier*2),1);xp(p,StatType.Fishing,9+tier*18);}});return true;}return false;}
    static boolean itemObject(DungeoneeringFloor.RoomState r,Player p,Item item,GameObject object,int source){if(DungeoneeringGathering.itemObject(r,p,item,object,source))return true;String name=object.getDef().name.toLowerCase(Locale.ROOT);int original=assets.sourceItem(item.getId());List<Recipe> choices=new ArrayList<>();for(Recipe recipe:data.recipes)if(uses(recipe,original)&&((name.contains("anvil")&&recipe.category.equals("DUNG_SMITHING"))||(name.contains("furnace")&&recipe.category.equals("DUNG_SMELTING"))||((name.equals("fire")||name.contains("range"))&&recipe.category.equals("DUNG_COOKING"))))choices.add(recipe);if(!choices.isEmpty()){showRecipes(p,choices,0);return true;}return false;}
    public static void menu(Player p){if(!Dungeoneering.inRun(p))return;SortedSet<String> groups=new TreeSet<>();for(Recipe recipe:data.recipes)groups.add(recipe.category);List<Option> choices=new ArrayList<>();for(String category:groups)choices.add(new Option(category.replace("DUNG_","").replace('_',' '),()->menu(p,category)));OptionScroll.open(p,"Dungeon crafting",false,choices);}
    static void menu(Player p,String category){List<Recipe> choices=new ArrayList<>();for(Recipe recipe:data.recipes)if(recipe.category.equals(category))choices.add(recipe);showRecipes(p,choices,0);}
    static void showRecipes(Player p,List<Recipe> recipes,int page){if(!Dungeoneering.inRun(p))return;List<Option> choices=new ArrayList<>();int end=Math.min(recipes.size(),page+90);for(int i=page;i<end;i++){Recipe recipe=recipes.get(i);choices.add(new Option(ItemDef.get(assets.item(recipe.output.id)).name+" x"+recipe.output.amount+" ("+recipe.skill+" "+recipe.level+")",()->amount(p,recipe)));}if(end<recipes.size())choices.add(new Option("Next page",()->showRecipes(p,recipes,end)));if(page>0)choices.add(new Option("Previous page",()->showRecipes(p,recipes,Math.max(0,page-90))));choices.add(new Option("All crafting categories",()->menu(p)));OptionScroll.open(p,"Dungeon recipes",false,choices);}
    private static void amount(Player p,Recipe recipe){OptionScroll.open(p,"Make "+ItemDef.get(assets.item(recipe.output.id)).name,false,new Option("Make 1",()->craft(p,recipe,1)),new Option("Make 5",()->craft(p,recipe,5)),new Option("Make all available",()->craft(p,recipe,100)));}
    private static void craft(Player p,Recipe recipe,int amount){if(!Dungeoneering.inRun(p))return;DungeoneeringFloor floor=Dungeoneering.floor(p);if(floor==null)return;p.startEvent(event->{for(int n=0;n<amount;n++){if(!Dungeoneering.inRun(p)||Dungeoneering.floor(p)!=floor||!make(p,recipe))break;event.delay(3);}p.resetAnimation();});}
    static boolean make(Player p,Recipe recipe){
        int level=recipe.skill.equals("SUMMONING")?DungeoneeringSummoning.level(p):effectiveLevel(p,skill(recipe.skill));if(level<recipe.level){p.sendMessage("You need "+recipe.skill+" level "+recipe.level+".");return false;}
        if(recipe.tool>=0&&!p.getInventory().hasId(assets.item(recipe.tool))){p.sendMessage("You need "+ItemDef.get(assets.item(recipe.tool)).name+".");return false;}
        if(!fixture(p,recipe.category))return false;
        for(Ingredient item:recipe.inputs)if(p.getInventory().count(assets.item(item.id))<item.amount){p.sendMessage("You need "+item.amount+" "+ItemDef.get(assets.item(item.id)).name+".");return false;}
        Item[] before=Dungeoneering.copy(p.getInventory().getItems());for(Ingredient item:recipe.inputs)p.getInventory().remove(assets.item(item.id),item.amount);
        int count=recipe.output.amount;if(recipe.multipliers!=null)for(int i=0;i+1<recipe.multipliers.length;i+=2)if(level>=recipe.multipliers[i])count=recipe.output.amount*recipe.multipliers[i+1];
        if(p.getInventory().add(assets.item(recipe.output.id),count)!=count){p.getInventory().clear();for(int i=0;i<before.length;i++)if(before[i]!=null)p.getInventory().set(i,before[i]);p.sendMessage("You do not have enough inventory space.");return false;}
        Integer animation=assets.animationMappings.get(Integer.toString(recipe.animation));if(animation!=null)p.animate(animation);
        if(recipe.skill.equals("SUMMONING"))DungeoneeringSummoning.addXp(p,recipe.xp);else xp(p,skill(recipe.skill),recipe.xp);return true;
    }
    private static boolean fixture(Player p,String category){String[] names=category.equals("DUNG_SMITHING")?new String[]{"anvil"}:category.equals("DUNG_SMELTING")?new String[]{"furnace"}:category.equals("DUNG_COOKING")?new String[]{"fire","range"}:category.equals("DUNG_RUNECRAFTING")?new String[]{"runecrafting","altar"}:category.equals("DUNG_SUMMONING")?new String[]{"summoning","obelisk"}:null;if(names==null)return true;DungeoneeringFloor floor=Dungeoneering.floor(p);DungeoneeringFloor.RoomState room=floor==null?null:floor.room(p.getPosition());if(room!=null)for(GameObject object:room.objects())for(String word:names)if(object.getDef().name.toLowerCase(Locale.ROOT).contains(word)&&p.getPosition().isWithinDistance(object.getPosition(),5))return true;p.sendMessage("You need to be near a suitable "+names[0]+".");return false;}
    static void imbue(Player p) {
        DungeoneeringFloor run = Dungeoneering.floor(p);
        if (run == null || !Dungeoneering.inRun(p)) return;
        List<Option> options = new ArrayList<>();
        int spinebeam = assets.item(16985);
        int catalytic = assets.item(16967);
        for (Item item : p.getInventory().getItems()) {
            if (item == null || item.getId() != spinebeam || item.getAmount() != 1 || item.hasAttributes()) continue;
            int slot = item.getSlot();
            options.add(new Option("Imbue spinebeam staff into catalytic staff / Runecrafting 50",
                () -> imbue(p, run, item, slot, catalytic)));
        }
        if (options.isEmpty()) options.add(new Option("Bring an unbound spinebeam staff to a runecrafting altar",
            () -> Dungeoneering.open(p)));
        options.add(new Option("Back to expedition", () -> Dungeoneering.open(p)));
        OptionScroll.open(p, "Dungeon weapon imbuement", false, options);
    }

    private static void imbue(Player p, DungeoneeringFloor run, Item item, int slot, int output) {
        if (!Dungeoneering.inRun(p) || Dungeoneering.floor(p) != run || !run.entered.contains(p) ||
            p.isLocked() || io.ruin.model.entity.player.PlayerFile.isSaveQuarantined(p) ||
            p.getInventory().get(slot, item.getId()) != item || item.getAmount() != 1 || item.hasAttributes()) return;
        for (Item bound : Dungeoneering.progress(p).boundItems) {
            if (bound != null && bound.getId() == item.getId()) {
                p.sendMessage("Unbind that staff before imbuing it.");
                return;
            }
        }
        if (p.getStats().get(StatType.Runecrafting).currentLevel < 50) {
            p.sendMessage("You need level 50 Runecrafting to imbue this staff.");
            return;
        }
        DungeoneeringFloor.RoomState room = run.room(p.getPosition());
        boolean nearAltar = false;
        if (room != null) for (GameObject object : room.objects()) {
            String name = object.getDef().name.toLowerCase(Locale.ROOT);
            if ((name.contains("runecrafting") || name.contains("rune crafting")) &&
                name.contains("altar") && p.getPosition().isWithinDistance(object.getPosition(), 5)) {
                nearAltar = true;
                break;
            }
        }
        if (!nearAltar) {
            p.sendMessage("Stand near a dungeon runecrafting altar to imbue a staff.");
            return;
        }
        p.getInventory().set(slot, new Item(output, 1));
        xp(p, StatType.Runecrafting, 39.5);
        xp(p, StatType.Magic, 39.5);
        p.sendMessage("You imbue the spinebeam staff into a catalytic staff.");
        imbue(p);
    }
    static void smuggler(Player p,DungeoneeringFloor floor){List<Option> choices=new ArrayList<>();choices.add(new Option("Buy supplies",()->shop(p,floor,false)));choices.add(new Option("Sell dungeon supplies",()->shop(p,floor,true)));choices.add(new Option("Crafting recipes",()->menu(p)));choices.add(new Option("Dungeon progress",()->floor.status(p)));OptionScroll.open(p,"Smuggler",false,choices);}
    private static void shop(Player p,DungeoneeringFloor floor,boolean sell){if(Dungeoneering.floor(p)!=floor||!Dungeoneering.inRun(p))return;if(floor.party.complexity<=1){p.sendMessage("The smuggler has no stock at complexity 1.");return;}List<Option> choices=new ArrayList<>();if(sell){for(Item item:p.getInventory().getItems())if(item!=null){int source=assets.sourceItem(item.getId());String name=item.getDef().name.toLowerCase(Locale.ROOT);if(source<0||source==18201||name.contains("key")||name.contains("(b)")||name.contains("kinship")||name.contains("gatestone"))continue;int price=Math.max(1,(int)(cost(source)*.3));final int slot=item.getSlot(),id=item.getId();choices.add(new Option("Sell "+item.getDef().name+" - "+price+" coins",()->{if(Dungeoneering.floor(p)!=floor)return;Item current=p.getInventory().get(slot,id);if(current==null)return;current.remove(1);p.getInventory().add(assets.item(18201),price);shop(p,floor,true);}));}}else{
            if(data.stock==null||data.stock.size()!=4){p.sendMessage("The source shop stock is not available in this build.");return;}for(int source:data.stock.get(Math.min(3,floor.party.complexity-2))){int price=cost(source),target=assets.item(source);choices.add(new Option(ItemDef.get(target).name+" - "+price+" coins",()->{if(Dungeoneering.floor(p)!=floor)return;if(p.getInventory().count(assets.item(18201))<price){p.sendMessage("You do not have enough rusty coins.");return;}if(p.getInventory().getFreeSlots()==0&&!ItemDef.get(target).stackable){p.sendMessage("You need inventory space.");return;}p.getInventory().remove(assets.item(18201),price);if(p.getInventory().add(target,1)!=1)p.getInventory().add(assets.item(18201),price);shop(p,floor,false);}));}
        }OptionScroll.open(p,sell?"Sell to the smuggler":"Smuggler supplies",false,choices);}
    private static int cost(int source){JsonObject def=assets.itemDefinitions==null?null:assets.itemDefinitions.get(Integer.toString(source));if(def==null)return 1;int cost=def.has("cost")?def.get("cost").getAsInt():1;JsonObject params=def.has("parameters")&&def.get("parameters").isJsonObject()?def.getAsJsonObject("parameters"):def.has("params")&&def.get("params").isJsonObject()?def.getAsJsonObject("params"):null;double mult=params!=null&&params.has("1046")?params.get("1046").getAsInt()/100.:1.;return Math.max(1,(int)Math.min(Integer.MAX_VALUE,cost*mult));}
    private static void drink(Player p,Item item,int source){
        if(!Dungeoneering.inRun(p)||p.isLocked()||p.potDelay.isDelayed()||p.eatDelay.isDelayed())return;
        int tier=source>=17606?2:source>=17582?1:0;int base=tier==2?17606:tier==1?17582:17556;int offset=source-base,groupOffset=offset+(tier>0&&offset>=16?2:0);int flat=tier+4;double percent=new double[]{.10,.14,.20}[tier];
        StatType[] affected=offset==0?new StatType[]{StatType.Magic}:offset==2?new StatType[]{StatType.Ranged}:offset==4?new StatType[]{StatType.Attack,StatType.Strength}:offset==6?new StatType[]{StatType.Defence}:groupOffset==18?new StatType[]{StatType.Woodcutting,StatType.Mining,StatType.Fishing}:groupOffset==20?new StatType[]{StatType.Smithing,StatType.Crafting,StatType.Fletching,StatType.Construction,StatType.Firemaking}:groupOffset==22?new StatType[]{StatType.Cooking,StatType.Farming,StatType.Herblore,StatType.Runecrafting}:groupOffset==24?new StatType[]{StatType.Agility,StatType.Hunter,StatType.Thieving,StatType.Slayer}:null;
        if(affected!=null){if(groupOffset>=18){flat=new int[]{3,4,6}[tier];percent=new double[]{.02,.04,.06}[tier];}for(StatType type:affected)p.getStats().get(type).boost(flat,percent);}
        else if(offset==8){for(StatType type:StatType.values())if(type!=StatType.Prayer)p.getStats().get(type).restore(new int[]{5,7,10}[tier],new double[]{.12,.17,.24}[tier]);}
        else if((tier==0&&offset==12)||(tier>0&&offset==10)){p.curePoison(new int[]{500,1000,2000}[tier]);p.cureVenom(new int[]{500,1000,2000}[tier]);if(tier==2)p.superAntifireTicks=Math.max(p.superAntifireTicks,2000);else p.antifireTicks=Math.max(p.antifireTicks,new int[]{500,1000}[tier]);}
        else if((tier==0&&offset==14)||(tier>0&&offset==12)){p.getStats().get(StatType.Prayer).restore(new int[]{5,7,10}[tier],new double[]{.10,.15,.22}[tier]);DungeoneeringSummoning.restore(p,new int[]{5,7,10}[tier],new double[]{.10,.15,.22}[tier]);}
        else{return;}
        item.setId(assets.item(17490));p.animate(829);p.potDelay.delay(3);p.getCombat().delayAttack(1);
    }
}
