package io.ruin.content.content242.bosses;

import com.google.gson.*;
import io.ruin.Server;
import io.ruin.cache.*;
import io.ruin.content.content242.*;
import io.ruin.model.World;
import io.ruin.model.combat.*;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.item.ItemContainer;
import io.ruin.model.item.actions.ItemItemAction;
import io.ruin.model.item.containers.Equipment;
import io.ruin.model.map.*;
import io.ruin.model.stat.StatType;
import io.ruin.model.skills.prayer.Prayer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Disposable c242qa fixtures, real world executor and actual loaded184 cache/combat.
 * Gear uses ordinary PlayerCombat targets. Boss abilities/HP thresholds are controlled fixtures.
 * This is functional verification, not an organic encounter duration or balance measurement. */
public final class Content242NativeProbe {
    static Path stage,output;static String mode;static int checks;static Player player;static NPC dummy;
    static final JsonObject report=new JsonObject();static final JsonArray evidence=new JsonArray();
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    static <T>T world(Callable<T> action)throws Exception{return Server.worker.getExecutor().submit(action).get(15,TimeUnit.SECONDS);}
    static void check(boolean condition,String text){checks++;if(!condition)throw new IllegalStateException(text);}
    static void note(String value){evidence.add(value);checkpoint(value);}
    static void checkpoint(String text){try{JsonObject status=new JsonObject();status.addProperty("mode",mode);status.addProperty("tick",Server.currentTick());status.addProperty("checkpoint",text);Files.write(stage.resolve("native242-checkpoint.json"),JSON.toJson(status).getBytes("UTF-8"));}catch(Exception failure){throw new RuntimeException(failure);}}
    static void ticks(int count)throws Exception{long target=world(()->Server.currentTick())+count;long timeout=System.currentTimeMillis()+count*1500L+15000;
        while(world(()->Server.currentTick())<target){if(System.currentTimeMillis()>timeout)throw new IllegalStateException("World tick stalled");Thread.sleep(100);}}
    static void waitFor(Callable<Boolean> condition,int count,String label)throws Exception{for(int i=0;i<count;i++){if(world(condition)){world(()->{check(true,label);return null;});return;}ticks(1);}throw new IllegalStateException("Timed out: "+label);}
    static ScheduledFuture<?> healing(){return Server.worker.getExecutor().scheduleAtFixedRate(()->{if(player!=null&&player.isOnline()&&!player.getCombat().isDead())player.setHp(player.getMaxHp());},0,100,TimeUnit.MILLISECONDS);}
    static void capture()throws Exception{
        if(!Files.exists(stage.resolve("native242-capture.enabled")))return;
        Path captures=stage.resolve("captures");Files.createDirectories(captures);Path acknowledged=captures.resolve(mode+".captured");Files.deleteIfExists(acknowledged);
        ScheduledFuture<?> temporaryHealing=healing();
        try{Files.write(captures.resolve(mode+".ready"),("tick="+world(()->Server.currentTick())+"\nmode="+mode+"\nfixtureHealing=true\n").getBytes("UTF-8"));
            note("Waiting for actual client capture: "+mode);long deadline=System.currentTimeMillis()+60000;
            while(!Files.exists(acknowledged)){if(System.currentTimeMillis()>deadline)throw new IllegalStateException("Actual client capture not acknowledged within60seconds: "+mode);Thread.sleep(200);}
            note("Actual client capture acknowledged: "+mode);
        }finally{temporaryHealing.cancel(false);}
    }
    static int id(String kind,int source){int result=Content242Registry.current().resolve(kind,source);check(result>=0,"Mapped "+kind+" source="+source);return result;}
    static String sha(Path path)throws Exception{MessageDigest hash=MessageDigest.getInstance("SHA-256");try(java.io.InputStream input=Files.newInputStream(path)){byte[] bytes=new byte[8192];int length;while((length=input.read(bytes))!=-1)hash.update(bytes,0,length);}StringBuilder value=new StringBuilder();for(byte b:hash.digest())value.append(String.format("%02x",b&255));return value.toString();}
    static void identity()throws Exception{
        URL resource=Server.class.getResource("Server.class");if(!(resource.openConnection() instanceof JarURLConnection))throw new IllegalStateException("Running server jar resource required");
        Path jar=Paths.get(((JarURLConnection)resource.openConnection()).getJarFileURL().toURI());report.addProperty("serverSha256",sha(jar));
        report.addProperty("manifestSha256",sha(Server.dataFolder.toPath().resolve("content242.json")));report.addProperty("stage",stage.getFileName().toString());report.addProperty("mode",mode);
        report.addProperty("scope","Actual loaded cache and native world; disposable fixture account. Controlled equipment/HP/ability triggers; not organic balance proof.");
    }
    static void readiness(){
        for(String feature:Arrays.asList("fang","voidwaker","inquisitor","shadow","bowfa","crystal_armour","soulreaper","virtus","ancient_sceptre"))check(ModernGear.feature(feature),"Enabled feature "+feature);
        for(int source:new int[]{26219,27690,24419,24420,24421,27275,25865,23971,23975,23979,28338,26241,26243,26245,27624}){
            ItemDef def=ItemDef.get(id("items",source));check(def!=null&&def.equipSlot>=0&&def.equipOption>=1,"Loaded wearable item "+source);
            check(def.getOption("wield","wear","equip","hold")==def.equipOption,"Actual inventory wear option "+source);}
        for(int source:new int[]{26219,27690,28338})check(ItemDef.get(id("items",source)).special!=null,"Registered special "+source);
        check(ItemDef.get(id("items",27275)).weaponType!=null&&ItemDef.get(id("items",25865)).weaponType!=null,"Charged weapon attack profiles");
        for(int source:new int[]{27275,25865})boundOption(source,"check");boundOption(27275,"uncharge");
        bossReadiness();
        if(ModernGear.feature("burning_claws"))check(ItemDef.get(id("items",29577)).special!=null,"Burning claws special registered");
        if(ModernGear.feature("scorching_bow"))check(ItemDef.get(id("items",29591)).special!=null,"Scorching bow special registered");
        note("Loaded manifest IDs, wearables and native specials verified.");
    }
    static void bossReadiness(){
        check(Scurrius242.available(),"Scurrius assets/region/handlers available");check(Amoxliatl242.available(),"Amoxliatl assets/region/handlers available");
        int[] sources={7222,7223,13685,13688};Class<?>[] classes={Scurrius242.class,GiantRat242.class,Amoxliatl242.class,UnstableIce242.class};
        for(int i=0;i<sources.length;i++){NPCDef def=NPCDef.get(id("npcs",sources[i]));check(def.combatInfo!=null&&def.combatHandlerClass==classes[i],"Native boss handler "+sources[i]);}
        if(ModernGear.feature("phantom_muspah"))check(PhantomMuspah242.available(),"Muspah forms/spikes/planes assets available");
        note("Boss asset and native handler bundles verified independently of equipment.");
    }
    static void inventoryAudit(){
        int[] sources={26219,27246,27690,24419,24420,24421,27275,27277,25865,25862,25867,23971,23973,23975,23977,23979,23981,28338,33335,26241,26243,26245,27624,29577,29591};
        JsonArray rows=new JsonArray();int bad=0;
        for(int source:sources){JsonObject row=new JsonObject();row.addProperty("source",source);int target=Content242Registry.current().resolve("items",source);row.addProperty("target",target);
            JsonArray issues=new JsonArray();ItemDef def=target<0?null:ItemDef.get(target);
            if(def==null)issues.add("missing mapped definition");else{
                row.addProperty("name",def.name);row.addProperty("equipSlot",def.equipSlot);row.addProperty("equipOption",def.equipOption);row.add("options",JSON.toJsonTree(def.inventoryOptions));row.add("bonuses",JSON.toJsonTree(def.equipBonuses));
                int wear=def.getOption("wield","wear","equip","hold");if(def.equipSlot<0)issues.add("no equipment slot");if(wear<1)issues.add("no visible wear option");if(def.equipOption<1||wear!=def.equipOption)issues.add("wear/equipOption mismatch");if(def.equipBonuses==null)issues.add("no equipment bonuses");
                JsonArray bound=new JsonArray();for(int n=0;n<5;n++)bound.add(def.inventoryActions!=null&&def.inventoryActions[n]!=null);row.add("boundActions",bound);
                for(String option:new String[]{"check","uncharge"}){int index=def.getOption(option);if(index>0&&(def.inventoryActions==null||def.inventoryActions[index-1]==null))issues.add("visible unbound "+option);}
            }
            row.add("issues",issues);if(issues.size()>0)bad++;rows.add(row);checks++;
        }
        report.add("inventory",rows);report.addProperty("inventoryIssueCount",bad);note("Read-only inventory audit completed; definitions with issues="+bad+" of"+sources.length);
    }
    static void boundOption(int source,String name){ItemDef def=ItemDef.get(id("items",source));int option=def.getOption(name);check(option>=1&&def.inventoryActions!=null&&def.inventoryActions[option-1]!=null,"Visible and bound inventory "+name+" source="+source);}
    static void weaponReadiness(){
        for(String feature:Arrays.asList("fang","voidwaker","shadow","bowfa","soulreaper","burning_claws","scorching_bow"))check(ModernGear.feature(feature),"Enabled weapon feature "+feature);
        for(int source:new int[]{26219,27690,27275,27277,25865,25862,25867,28338,29577,29591}){ItemDef def=ItemDef.get(id("items",source));check(def!=null&&def.equipSlot==3&&def.equipOption>0&&def.getOption("wield")==def.equipOption,"Native weapon Wield readiness "+source);}
        for(int source:new int[]{26219,27690,28338,29577,29591})check(ItemDef.get(id("items",source)).special!=null,"Registered weapon special "+source);
        for(int source:new int[]{27275,25865})boundOption(source,"check");boundOption(27275,"uncharge");
        check(ItemDef.get(id("items",27275)).weaponType!=null&&ItemDef.get(id("items",25865)).weaponType!=null,"Charged weapon attack profiles");
        note("Seven weapon combat dependencies ready; crystal armour remains separately audited.");
    }
    static Player account(){Player found=null;for(Player p:World.players.entityList)if(p!=null&&p.isOnline()&&p.getChannel()!=null&&p.getChannel().isActive()){
        check("c242qa".equalsIgnoreCase(p.getName()),"Only disposable c242qa may be online");check(found==null,"Unique connected fixture account");found=p;}
        check(found!=null,"Connected c242qa required");check(!found.isLocked()&&!found.getCombat().isDead(),"Fixture account alive and unlocked");return found;}
    static Item[] snapshot(ItemContainer container){Item[] source=container.getItems(),result=new Item[source.length];for(int i=0;i<source.length;i++)if(source[i]!=null)result[i]=new Item(source[i].getId(),source[i].getAmount(),source[i].copyOfAttributes());return result;}
    static Item[] equipment(){return snapshot(player.getEquipment());}
    static void clearInventory(){for(int i=0;i<player.getInventory().getItems().length;i++)player.getInventory().set(i,null);}
    static void restore(Item[] old){player.getCombat().reset();for(int i=0;i<old.length;i++)player.getEquipment().set(i,old[i]);player.getEquipment().sendUpdates();}
    static void wield(int source){player.getCombat().reset();player.getCombat().queuedSpell=null;player.getCombat().autocastSpell=null;
        for(int i=0;i<player.getEquipment().getItems().length;i++)player.getEquipment().set(i,null);
        clearInventory();
        Item weapon=new Item(id("items",source));if(source==27275||source==25865)ModernCharges.set(weapon,20);
        player.getInventory().set(0,weapon);player.getEquipment().equip(weapon);
        check(player.getEquipment().getId(Equipment.SLOT_WEAPON)==id("items",source),"Ordinary native inventory equip source="+source);
        if(source==29591){Item ammo=new Item(ItemID.RUNE_ARROW,100);check(ammo.getDef().rangedAmmo==RangedAmmo.RUNE_ARROW,"Actual native rune arrow metadata");player.getInventory().set(1,ammo);player.getEquipment().equip(ammo);check(player.getEquipment().getId(Equipment.SLOT_AMMO)==ItemID.RUNE_ARROW,"Native ammo equipped");}
        player.getEquipment().sendUpdates();player.getCombat().restoreSpecial(100);
        if(player.getCombat().specialActive()!=null)player.getCombat().toggleSpecial();
    }
    static Position freeAdjacent(){Position at=player.getPosition();for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){Position p=new Position(at.getX()+d[0],at.getY()+d[1],at.getZ());Tile tile=Tile.get(p,true);if(tile.isFloorFree()&&tile.npcCount==0&&tile.playerCount==0)return p;}throw new IllegalStateException("No free native combat fixture tile");}
    static void weapon(int source,boolean special)throws Exception{
        final List<Hit> landed=new ArrayList<>();final int[] start={0};
        world(()->{wield(source);dummy=new NPC(3078).spawn(freeAdjacent());dummy.getCombat().setAllowRespawn(false);dummy.getCombat().setAllowRetaliate(false);dummy.setMaxHp(99);dummy.setHp(99);
            dummy.hitListener=new HitListener().postDamage(hit->{if(hit.attacker==player){landed.add(hit);player.getCombat().reset();}});
            check(player.getCombat().canAttack(dummy,false),"Native combat accepts fixture target");start[0]=Config.SPECIAL_ENERGY.get(player);
            if(special)player.getCombat().toggleSpecial();player.getCombat().setTarget(dummy);return null;});
        try{waitFor(()->!landed.isEmpty(),30,"Native weapon impact source="+source+" special="+special);
            if(source==29577)ticks(5);
            world(()->{Hit hit=landed.get(0);check(hit.attackWeapon!=null&&hit.attackWeapon.id==id("items",source),"Impact attributed to mapped weapon");
                check(hit.type==HitType.DAMAGE||hit.type==HitType.BLOCKED,"Native impact damage/block type");
                if(source==27690&&special)check(hit.attackStyle==AttackStyle.MAGIC&&hit.damage>0,"Voidwaker guaranteed magic special");
                if(source==27275||source==25865)check(ModernCharges.get(player.getEquipment().get(3),0)<20,"Charged weapon consumed a real shot");
                if(source==29591&&!special)check(player.getEquipment().get(Equipment.SLOT_AMMO).getAmount()<100,"Scorching ordinary projectile consumes native ammo");
                if(source==29577){check(landed.size()>=3,"Burning claws native three-hit callbacks");note("Burning claws stochastic burn remaining="+ModernBurn.remaining(dummy)+"; no proc forced");}
                if(special)check(Config.SPECIAL_ENERGY.get(player)<start[0],"Native special energy drained");
                note("Weapon source="+source+" special="+special+" impact="+hit.damage+" style="+hit.attackStyle+" targetHP="+dummy.getHp());return null;});
        }finally{world(()->{player.getCombat().reset();if(dummy!=null){ModernBurn.clear(dummy);if(!dummy.isRemoved())dummy.remove();}dummy=null;return null;});ticks(2);}
    }
    static void scorchingSpecial()throws Exception{
        final List<Hit> queued=new ArrayList<>();final int[] energy={0};
        world(()->{wield(29591);NPCDef imp=null;for(NPCDef def:NPCDef.cached.values())if(def.demon&&"Imp".equals(def.name)&&def.size==1&&def.combatInfo!=null&&(imp==null||def.id<imp.id))imp=def;
            check(imp!=null,"Loaded native one-tile demon fixture");dummy=new NPC(imp.id).spawn(freeAdjacent());dummy.getCombat().setAllowRespawn(false);dummy.getCombat().setAllowRetaliate(false);dummy.setMaxHp(200);dummy.setHp(200);
            dummy.hitListener=new HitListener().postDefend(hit->{if(hit instanceof ModernBurn.BurnHit)queued.add(hit);});
            check(ModernBurn.demon(dummy),"Scorching target is native demon");check(player.getCombat().canAttack(dummy,false),"Native Scorching combat accepts demon");energy[0]=Config.SPECIAL_ENERGY.get(player);player.getCombat().toggleSpecial();player.getCombat().setTarget(dummy);return null;});
        try{waitFor(()->!queued.isEmpty()&&dummy.getHp()<200,30,"Scorching native burn queue completed HP damage");
            world(()->{player.getCombat().reset();check(Config.SPECIAL_ENERGY.get(player)==energy[0]-250,"Scorching special drains25percent");check(player.getEquipment().get(Equipment.SLOT_AMMO).getAmount()==100,"Scorching shackles spend no ordinary arrow");check(dummy.isFrozen(),"Scorching native demon bind active");check(ModernBurn.remaining(dummy)>0,"Scorching residual burn remains queued");note("Scorching demon="+dummy.getId()+" immediate native burnHP="+dummy.getHp()+" burnQueueCallbacks="+queued.size()+" remaining="+ModernBurn.remaining(dummy)+" ammo=100 unchanged by special");return null;});
        }finally{world(()->{player.getCombat().reset();if(dummy!=null){ModernBurn.clear(dummy);if(!dummy.isRemoved())dummy.remove();}dummy=null;return null;});ticks(2);}
    }
    static void charges(){
        clearInventory();Item staff=new Item(id("items",27277)),soul=new Item(id("items",566),20),chaos=new Item(id("items",562),50);
        player.getInventory().set(0,staff);player.getInventory().set(1,soul);player.getInventory().set(2,chaos);ItemItemAction.handleAction(player,soul,staff);
        check(staff.getId()==id("items",27275)&&ModernCharges.get(staff,0)==10,"Native rune-on-staff action charges10");
        check(ModernCharges.plainAmount(player.getInventory(),id("items",566))==0&&ModernCharges.plainAmount(player.getInventory(),id("items",562))==0,"Native charge consumes exact rune amounts");
        ItemDef active=staff.getDef();active.inventoryActions[active.getOption("check")-1].handle(player,staff);active.inventoryActions[active.getOption("uncharge")-1].handle(player,staff);
        check(staff.getId()==id("items",27277)&&ModernCharges.get(staff,0)==0,"Native Uncharge action returns empty staff");
        check(ModernCharges.plainAmount(player.getInventory(),id("items",566))==20&&ModernCharges.plainAmount(player.getInventory(),id("items",562))==50,"Native Uncharge refunds exact runes");
        clearInventory();Item bow=new Item(id("items",25862)),shards=new Item(id("items",23962),2);player.getInventory().set(0,bow);player.getInventory().set(1,shards);ItemItemAction.handleAction(player,shards,bow);
        check(bow.getId()==id("items",25865)&&ModernCharges.get(bow,0)==200,"Native shard-on-bow action charges200");
        bow.getDef().inventoryActions[bow.getDef().getOption("check")-1].handle(player,bow);note("Normal inventory charge/check/ShadowUncharge handlers and exact payments/refunds verified");
    }
    static void gear()throws Exception{Item[][] old=world(()->{player=account();for(StatType stat:new StatType[]{StatType.Attack,StatType.Strength,StatType.Defence,StatType.Hitpoints,StatType.Magic,StatType.Ranged})player.getStats().get(stat).set(99);return new Item[][]{equipment(),snapshot(player.getInventory())};});
        try{world(()->{charges();return null;});weapon(26219,false);capture();weapon(26219,true);weapon(27690,true);weapon(27275,false);weapon(25865,false);weapon(28338,false);weapon(29577,true);weapon(29591,false);scorchingSpecial();}
        finally{world(()->{restore(old[0]);for(int i=0;i<old[1].length;i++)player.getInventory().set(i,old[1][i]);player.getInventory().sendUpdates();return null;});}
    }
    static NPC encounter(){for(NPC npc:World.npcs.entityList)if(npc!=null&&!npc.isRemoved()&&npc.getPosition().getRegion()==player.getPosition().getRegion()&&npc.getCombat() instanceof Boss242Combat)return npc;return null;}
    static void invoke(Object target,String method,Class<?>[] types,Object... arguments)throws Exception{Method m=target.getClass().getDeclaredMethod(method,types);m.setAccessible(true);m.invoke(target,arguments);}
    static void enter(int region,int npc,int bx,int by,int px,int py)throws Exception{
        world(()->{player=account();check(player.getAbsX()<6400,"Entry begins outside private maps");
            io.ruin.model.entity.shared.listeners.TeleportListener before=player.teleportListener;
            player.teleportListener=p->false;
            try{Boss242Instances.enter(player,region,npc,bx,by,px,py);
                check(player.content242BossReturn==null,"Denied native teleport allocates no encounter/recovery marker");}
            finally{player.teleportListener=before;}
            Boss242Instances.enter(player,region,npc,bx,by,px,py);return null;});
        waitFor(()->player.getPosition().getRegion().dynamicIndex!=-1&&encounter()!=null,15,"Actual native private encounter entry");
        world(()->{check(player.content242BossReturn!=null,"Recovery marker set");check(((Boss242Combat)encounter().getCombat()).valid(player),"Owner admitted to own encounter");note("VISIBLE PRIVATE ENTRY "+mode+" tile="+player.getAbsX()+","+player.getAbsY()+" lease="+player.getPosition().getRegion().dynamicIndex);return null;});
        ticks(6);if(Files.exists(stage.resolve("native242-capture.enabled"))){world(()->{capturePosition();return null;});ticks(2);}capture();
    }
    static void capturePosition(){
        NPC boss=encounter();check(boss!=null,"Capture owns a live boss");Boss242Combat combat=(Boss242Combat)boss.getCombat();int bx=boss.getAbsX(),by=boss.getAbsY(),size=boss.getSize();Position selected=null;
        for(int distance:new int[]{4,5,3}){int[][] offsets={{-distance,size/2},{size+distance-1,size/2},{size/2,-distance},{size/2,size+distance-1}};
            for(int[] offset:offsets){Position tile=new Position(bx+offset[0],by+offset[1],boss.getHeight());if(!combat.free(tile))continue;boolean occupied=false;
                for(NPC actor:World.npcs.entityList)if(actor!=null&&!actor.isRemoved()&&actor.getHeight()==tile.getZ()&&tile.getX()>=actor.getAbsX()&&tile.getX()<actor.getAbsX()+actor.getSize()&&tile.getY()>=actor.getAbsY()&&tile.getY()<actor.getAbsY()+actor.getSize()){occupied=true;break;}
                if(!occupied){selected=tile;break;}}
            if(selected!=null)break;}
        check(selected!=null,"Walkable unoccupied close-boss capture tile");player.getMovement().teleport(selected);player.face(boss);note("Capture-only fixture position="+selected.getX()+","+selected.getY()+" boss="+bx+","+by+" size="+size);
    }
    static void preview()throws Exception{
        try{if(mode.equals("preview-scurrius"))enter(13210,7222,3301,9867,3290,9868);else if(mode.equals("preview-amoxliatl"))enter(5446,13685,1362,4510,1376,4511);else enter(11330,12077,2844,4258,2859,4259);}
        finally{if(player!=null){world(()->{Boss242Instances.leave(player);return null;});waitFor(()->player.getAbsX()<6400&&player.content242BossReturn==null,15,"Visual preview exit clears recovery marker");}}
    }
    static void bosses(boolean rat)throws Exception{
        enter(rat?13210:5446,rat?7222:13685,rat?3301:1362,rat?9867:4510,rat?3290:1376,rat?9868:4511);final NPC boss=world(()->encounter());final Boss242Combat combat=(Boss242Combat)boss.getCombat();
        final int[] naturalHits={0};final HitListener original=world(()->player.hitListener);
        final java.util.function.Consumer<Hit> originalPost=original.postDamage;
        ScheduledFuture<?> healing=healing();
        world(()->{original.postDamage(hit->{if(originalPost!=null)originalPost.accept(hit);if(hit.attacker!=null&&hit.attacker.npc!=null&&hit.attacker.npc.getCombat() instanceof Boss242Combat)naturalHits[0]++;});return null;});
        try{
            if(rat){
                world(()->{boss.setHp(boss.getMaxHp()*4/5);return null;});
                waitFor(()->{Field f=Scurrius242.class.getDeclaredField("phase");f.setAccessible(true);return f.get(combat).toString().equals("FEEDING");},80,"Scurrius routes to actual feeding tile at80percent");
                world(()->{boss.setHp(boss.getMaxHp()*3/10);return null;});
                waitFor(()->{Field f=Scurrius242.class.getDeclaredField("phase");f.setAccessible(true);return f.get(combat).toString().equals("ENRAGED");},70,"Scurrius routes to centre and enrages at30percent");
                world(()->{invoke(combat,"summon",new Class<?>[]{boolean.class},false);return null;});ticks(4);
                world(()->{check(!combat.children.isEmpty(),"Live rat wave spawned");NPC minion=combat.children.get(0);check(minion.getCombat() instanceof GiantRat242,"Rat native handler");int hp=minion.getHp();
                    Hit strike=new Hit(player,AttackStyle.STAB,AttackType.ACCURATE).fixedDamage(1).delay(0);minion.hit(strike);check(strike.damage==hp,"Rat one-hit native defend");note("Scurrius feeding/enrage routing and native rat summon/one-hit checked");return null;});ticks(2);
                world(()->{invoke(combat,"rocks",new Class<?>[]{boolean.class},false);return null;});ticks(11);
            }else{
                world(()->{player.getMovement().teleport(combat.tile(1368,4511));boss.setHp(Math.max(1,boss.getMaxHp()-100));return null;});ticks(2);
                world(()->{invoke(combat,"unstable",new Class<?>[]{Player.class},player);return null;});ticks(4);
                world(()->{check(!combat.children.isEmpty(),"Live unstable ice spawned");NPC ice=combat.children.get(0);check(ice.getCombat() instanceof UnstableIce242,"Ice native handler");
                    Hit ranged=new Hit(player,AttackStyle.RANGED,AttackType.ACCURATE).fixedDamage(10);ice.hit(ranged);check(ranged.isBlocked(),"Ice blocks ranged");
                    Hit melee=new Hit(player,AttackStyle.STAB,AttackType.ACCURATE).fixedDamage(1).delay(0);ice.hit(melee);check(melee.damage==ice.getHp(),"Ice break uses native melee one-hit");return null;});ticks(3);
                world(()->{check(!combat.scenery.isEmpty(),"Broken ice created persistent pool");invoke(combat,"icicle",new Class<?>[]{Player.class},player);note("Amox unstable ice native break/pool and delayed icicle triggered");return null;});ticks(7);
            }
            world(()->{check(naturalHits[0]>0,"Actual native boss attacks reached owner");note("Native boss impacts="+naturalHits[0]+"; fixture healing enabled for observation");return null;});
        }finally{
            healing.cancel(false);world(()->{original.postDamage=originalPost;Boss242Instances.leave(player);return null;});
            waitFor(()->player.getAbsX()<6400&&player.content242BossReturn==null,15,"Exit returns owner and clears recovery marker");
            ticks(2);world(()->{check(boss.isRemoved(),"Owned boss removed after map exit");check(combat.children.isEmpty()&&combat.scenery.isEmpty(),"Owned minions/hazards cleaned");note("Private map exit and actor/hazard cleanup verified");return null;});
        }
    }
    static void muspah()throws Exception{
        world(()->{player=account();player.getStats().get(StatType.Hitpoints).set(99);player.getStats().get(StatType.Prayer).set(99);return null;});
        enter(11330,12077,2844,4258,2859,4259);final NPC boss=world(()->encounter());final PhantomMuspah242 combat=(PhantomMuspah242)boss.getCombat();ScheduledFuture<?> healing=healing();
        try{
            world(()->{check(boss instanceof PhantomMuspah242.Actor,"Encounter uses actor-specific freeze resistance");check(combat.spikes.spikes.size()>=2,"Initial native spike wave exists");player.getCombat().reset();boss.setHp(637);return null;});
            waitFor(()->combat.state.specials>=1,20,"First Muspah threshold special");waitFor(()->!combat.state.held,35,"First special completes its native tick sequence");
            world(()->{boss.setHp(425);return null;});waitFor(()->combat.state.specials==2,20,"Second Muspah threshold special");waitFor(()->!combat.state.held,35,"Second special completes its native tick sequence");
            world(()->{check(!combat.spikes.spikes.isEmpty(),"Moving/standing spike field persists");boss.setHp(126);note("Muspah both darkness specials completed; beginning native shockwave");return null;});
            waitFor(()->combat.state.form==MuspahState242.Form.SHIELDED,25,"Native shockwave reaches prayer shield");
            world(()->{check(combat.state.shield>0&&combat.state.shield<=75,"Shield points initialized");if(!player.getPrayer().isActive(Prayer.SMITE))player.getPrayer().toggle(Prayer.SMITE);return null;});ticks(1);
            world(()->{check(player.getPrayer().isActive(Prayer.SMITE),"Actual native Smite activated");return null;});
            for(int i=0;i<4&&world(()->combat.state.form==MuspahState242.Form.SHIELDED);i++){
                final int before=world(()->combat.state.shield);
                world(()->{check(player.getPrayer().isActive(Prayer.SMITE),"Smite remains active before controlled shield hit");boss.hit(new Hit(player,AttackStyle.RANGED,AttackType.ACCURATE).fixedDamage(100).ignoreDefence().delay(0));return null;});ticks(2);
                world(()->{note("Controlled accurate Smite shield delta="+before+"->"+combat.state.shield);check(combat.state.shield<=Math.max(0,before-25),"Native Smite drains at least25 per accurate100 damage roll");return null;});
            }
            waitFor(()->combat.state.form==MuspahState242.Form.FINAL&&combat.state.shieldDone,8,"Native Smite hits break shield");
            world(()->{check(combat.state.shield==0,"Shield cannot become negative");boss.hit(new Hit(player).fixedDamage(boss.getHp()).delay(0));note("Muspah shield depleted; final native kill queued");return null;});
            waitFor(()->boss.getCombat().isDead(),6,"Final phase permits native death");ticks(8);
            world(()->{check(combat.children.isEmpty()&&combat.scenery.isEmpty()&&combat.spikes.originalMasks.isEmpty(),"Death clears clouds,spikes andsaved clipping");note("Muspah native specials/shield/death completed using controlledHP/hit fixtures");return null;});
        }finally{healing.cancel(false);world(()->{player.getPrayer().deactivate(Prayer.SMITE);Boss242Instances.leave(player);return null;});waitFor(()->player.getAbsX()<6400&&player.content242BossReturn==null,15,"Muspah exit clears recovery marker");}
    }
    public static void agentmain(String arguments,Instrumentation ignored)throws Exception{
        String[] split=arguments.split("\\|",2);stage=Paths.get(split[0]);mode=split[1];output=stage.resolve("native242-"+mode+".json");
        identity();report.addProperty("startedUtc",java.time.Instant.now().toString());
        try{world(()->{if(mode.equals("inventory-audit"))inventoryAudit();else if(mode.equals("gear"))weaponReadiness();else if(mode.startsWith("preview-")||mode.equals("boss-readiness")||mode.equals("scurrius")||mode.equals("amoxliatl")||mode.equals("muspah"))bossReadiness();else readiness();return null;});if(mode.equals("gear"))gear();else if(mode.equals("scurrius"))bosses(true);else if(mode.equals("amoxliatl"))bosses(false);else if(mode.equals("muspah"))muspah();else if(mode.startsWith("preview-"))preview();else check(mode.equals("readiness")||mode.equals("inventory-audit")||mode.equals("boss-readiness"),"Known probe mode");report.addProperty("result","PASS");}
        catch(Throwable failure){report.addProperty("result","FAIL");report.addProperty("error",failure.toString());if(player!=null&&!mode.equals("gear"))try{world(()->{Boss242Instances.leave(player);return null;});}catch(Exception ignoredCleanup){}throw failure;}
        finally{report.addProperty("assertions",checks);report.addProperty("finishedUtc",java.time.Instant.now().toString());report.add("evidence",evidence);Files.write(output,JSON.toJson(report).getBytes("UTF-8"));}
    }
}
