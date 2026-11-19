package io.ruin.model.activities.dungeoneering;

import com.google.gson.*;
import io.ruin.cache.NPCDef;
import io.ruin.data.impl.npcs.npc_combat;
import io.ruin.model.combat.*;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.*;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.HitListener;
import io.ruin.model.entity.shared.listeners.DeathListener;
import io.ruin.model.map.*;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.route.routes.DumbRoute;
import io.ruin.model.map.route.routes.TargetRoute;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.stat.StatType;
import java.util.*;
import java.util.function.*;

/**
 * Encounter-owned native combat adapter for the pinned Darkan Dungeoneering
 * scripts. Only imported NPC definitions are registered; existing bosses and
 * combat handlers are untouched. See docs/dungeoneering/ATTRIBUTION.txt.
 */
public final class DungeoneeringCombat extends NPCCombat {
    public interface Arena {
        boolean active(); boolean contains(Player player); List<Player> players();
        DungeoneeringAssets assets(); java.util.Random random();
        Position tile(int localX,int localY); int[] local(Position position);
        NPC spawn(int sourceNpc,int localX,int localY); void npcDefeated(NPC npc);
        void object(int sourceObject,int shape,int rotation,int localX,int localY);
        GameObject getObject(int localX,int localY,int shape);
        void removeObject(int localX,int localY,int shape);
        int floor(); int complexity();
    }
    private static final Map<NPC,Ctx> ACTIVE=new IdentityHashMap<>();
    private static DungeoneeringAssets assets;
    private static final Map<String,Integer> BOSS_NAMES=new HashMap<>();
    private static final int[][] BOSS_FAMILIES={
        {10024,10039,1},{9929,9947,1},{9912,9928,1},{10040,10057,1},{9948,9964,1},{9965,10021,3},
        {10128,10141,1},{10143,10156,1},{10111,10127,1},{10073,10106,2},{10059,10072,1},{10058,10058,1},
        {9725,9738,1},{9753,9766,1},{9842,9855,1},{9898,9911,1},{9782,9796,1},{9767,9780,1},
        {11872,11886,1},{11925,11939,1},{11737,11751,1},{11708,11720,1},{11812,11826,1},{11940,11984,3},
        {12752,12766,1},{12478,12492,1},{12886,12900,1},{12865,12878,1},{12848,12862,1},{12737,12751,1}};
    static {
        String[] names={"to'kash the bloodchiller","plane-freezer lakhrahnaz","luminescent icefiend","icy bones","gluttonous behemoth","astea frostweb",
            "bal'lak the pummeller","shadow-forger ihlakhizan","unholy cursebearer","bulwark beast","hobgoblin geomancer","divine skinweaver",
            "night-gazer khighorahk","sagittare","lexicus runewright","har'lakk the riftsplitter","stomp","rammernaut",
            "yk'lagor the thunderous","flesh-spoiler haasghenahk","necrolord","gravecreeper","runebound behemoth","skeletal warrior",
            "kal'ger the warmonger","world-gorger shukarhazh","hope devourer","blink","dreadnaut","warped gulega"};
        for(int i=0;i<names.length;i++)BOSS_NAMES.put(names[i],i);
        BOSS_NAMES.put("skeletal archer",23);BOSS_NAMES.put("skeletal sorcerer",23);
    }
    public static int bossKind(int source) {
        if(assets==null)return -1;JsonObject row=assets.npcInfo(source);
        return row==null||!row.has("name")?-1:BOSS_NAMES.getOrDefault(row.get("name").getAsString().trim().toLowerCase(Locale.ROOT),-1);
    }
    public static boolean supportedBoss(int source) { return mechanic(bossKind(source))!=null; }
    static int[] selectableBossNpcs(){Set<Integer> ids=new TreeSet<>();for(int[] family:BOSS_FAMILIES)for(int source=family[0];source<=family[1];source+=family[2])ids.add(source);for(int[] family:new int[][]{{11999,12043,3},{12044,12088,3}})for(int source=family[0];source<=family[1];source+=family[2])ids.add(source);ids.add(532);ids.add(533);return ids.stream().mapToInt(Integer::intValue).toArray();}
    static boolean visualOnlyNpc(int source){boolean morph=false;for(int id:DungeoneeringScriptAssets.morphNpcs())if(id==source){morph=true;break;}if(!morph)return false;for(int id:DungeoneeringScriptAssets.spawnNpcs())if(id==source)return false;return true;}
    /** Select only authored base-form families; appearance phases are not difficulty variants. */
    public static int selectNpc(Arena arena,int requested,boolean mainBoss) {
        if(!mainBoss)return requested;
        int kind=bossKind(requested);if(kind<0||kind==11)return requested;
        Collection<Player> party=arena instanceof DungeoneeringFloor.RoomState?((DungeoneeringFloor.RoomState)arena).dungeon().party.members:arena.players();
        int sum=0;for(Player p:party)sum+=p.getCombat().getLevel();
        int target=Math.max(1,(int)((party.isEmpty()?126:sum/(double)party.size())*1.5));
        if(kind==8)target=(int)Math.ceil(target*.85);
        int[] family=BOSS_FAMILIES[kind];
        if(kind==23)family=requested>=12044?new int[]{12044,12088,3}:requested>=11999?new int[]{11999,12043,3}:family;
        int best=-1,difference=Integer.MAX_VALUE;
        for(int source=family[0];source<=family[1];source+=family[2]) {
            JsonObject row=arena.assets().npcInfo(source);
            if(row==null||!arena.assets().npcMappings.containsKey(Integer.toString(source))||metadata(row)==null)throw new IllegalStateException("Missing authored selectable boss variant "+source);
            int delta=Math.abs(number(row,"combatLevel",-10000)-target);
            if(delta<difference){best=source;difference=delta;}
        }
        if(kind==21)for(int source:new int[]{532,533}) {
            JsonObject row=arena.assets().npcInfo(source);if(row==null||!arena.assets().npcMappings.containsKey(Integer.toString(source)))continue;
            int delta=Math.abs(number(row,"combatLevel",-10000)-target);if(delta<difference){best=source;difference=delta;}
        }
        if(best<0)throw new IllegalStateException("No authored boss variants for "+requested);
        return best;
    }
    /** Authored encounter barriers only; ordinary world routing stays unchanged. */
    public static boolean allowReach(Entity from,Entity to,int distance) {
        if(from==null||to==null||distance<1||distance>15||from.getHeight()!=to.getHeight())return false;
        NPC actor=to.npc!=null&&from.player!=null?to.npc:from.npc!=null&&to.player!=null?from.npc:null;
        Player player=from.player!=null?from.player:to.player;
        Ctx c=actor==null?null:ACTIVE.get(actor);
        if(c==null||!c.live()||!c.valid(player)||!c.behavior.attackable(c,player))return false;
        int kind=c.boss?bossKind(c.source):-1;boolean melee=distance==1;
        boolean authored=kind==16||kind==20&&!melee||kind==7&&melee
                ||c.source>=12436&&c.source<=12477;
        if(!authored)return false;
        if(!io.ruin.model.map.route.routes.TargetRoute.inRange(from.getAbsX(),from.getAbsY(),from.getSize(),to.getAbsX(),to.getAbsY(),to.getSize(),distance))return false;
        int overlapX=Math.min(from.getAbsX()+from.getSize(),to.getAbsX()+to.getSize())-Math.max(from.getAbsX(),to.getAbsX());
        int overlapY=Math.min(from.getAbsY()+from.getSize(),to.getAbsY()+to.getSize())-Math.max(from.getAbsY(),to.getAbsY());
        return overlapX<=0||overlapY<=0;
    }
    private static Mechanic mechanic(int kind) {
        Mechanic found=DungeoneeringFrozen.create(kind);
        if(found!=null)return found;
        found=DungeoneeringAbandoned.create(kind);
        if(found!=null)return found;
        found=DungeoneeringFurnished.create(kind);
        if(found!=null)return found;
        found=DungeoneeringOccult.create(kind);
        return found!=null?found:DungeoneeringWarped.create(kind);
    }
    private static int number(JsonObject row,String key,int fallback) {return row!=null&&row.has(key)?row.get(key).getAsInt():fallback;}
    private static String string(JsonObject row,String key,String fallback) {return row!=null&&row.has(key)?row.get(key).getAsString():fallback;}
    private static JsonObject metadata(JsonObject row) {return row!=null&&row.has("combat")&&row.get("combat").isJsonObject()?row.getAsJsonObject("combat"):null;}

    public static void register(DungeoneeringAssets data) {
        assets=data;
        for(Map.Entry<String,Integer> mapping:data.npcMappings.entrySet()) {
            int source=Integer.parseInt(mapping.getKey());NPCDef def=NPCDef.get(mapping.getValue());
            // Imported model/chathead dependencies and appearance phases are not
            // independently spawned combat actors. Morphs retain their base handler/stats.
            if(source==1||source==3||source==5||source==8||source==13||visualOnlyNpc(source))continue;
            if(def==null)throw new IllegalStateException("Missing imported NPC "+source);
            JsonObject row=data.npcInfo(source),combat=metadata(row);
            boolean attack=false;for(String option:def.options)if("Attack".equalsIgnoreCase(option))attack=true;
            if(!attack && bossKind(source)!=11)continue;
            if(combat==null && bossKind(source)!=11)throw new IllegalStateException("Missing original combat metadata for "+source);
            npc_combat.Info info=new npc_combat.Info();info.ids=new int[]{def.id};
            info.hitpoints=Math.max(1,(number(combat,"hitpoints",10)+9)/10);
            int level=Math.max(1,number(row,"combatLevel",def.combatLevel));
            JsonObject levels=combat!=null&&combat.has("combatLevels")?combat.getAsJsonObject("combatLevels"):null;
            info.attack=number(levels,"ATTACK",level);info.strength=number(levels,"STRENGTH",level);info.defence=number(levels,"DEFENSE",level);
            info.ranged=number(levels,"RANGE",level);info.magic=number(levels,"MAGE",level);
            info.max_damage=Math.max(1,(number(combat,"maxHit",10)+9)/10);
            String style=string(combat,"attackStyle","MELEE");
            info.attack_style=style.equals("MAGIC")?AttackStyle.MAGIC:style.equals("RANGE")?AttackStyle.RANGED:AttackStyle.CRUSH;
            info.attack_ticks=number(row,"attackSpeed",5);info.death_ticks=Math.max(1,number(combat,"deathDelay",3));info.respawn_ticks=-1;
            info.aggressive_level=bossKind(source)==11?0:Integer.MAX_VALUE;
            info.spawn_animation=data.animation(number(combat,"respawnAnim",-1));info.attack_animation=data.animation(number(combat,"attackAnim",-1));
            info.defend_animation=data.animation(number(combat,"defenceAnim",-1));info.death_animation=data.animation(number(combat,"deathAnim",-1));
            // Pinned Darkan Poison.makePoisoned guards player antipoison effects only;
            // NPC/encounter scripts have no blanket poison immunity. Phase guards remain below.
            info.poison_immunity=false;info.venom_immunity=true;
            def.combatInfo=info;def.combatHandlerClass=DungeoneeringCombat.class;def.ignoreMultiCheck=true;
        }
    }
    public static void attach(NPC npc,Arena arena,int source,boolean boss) {
        if(ACTIVE.containsKey(npc))return;
        if(!(npc.getCombat() instanceof DungeoneeringCombat)) {
            if(boss)throw new IllegalStateException("Boss has no native dungeon combat handler: "+source);
            return;
        }
        Mechanic behavior=boss?mechanic(bossKind(source)):new Guardian();
        if(behavior==null)throw new IllegalStateException("Boss mechanics not implemented: "+source);
        DungeoneeringCombat owner=(DungeoneeringCombat)npc.getCombat();
        Ctx c=new Ctx(owner,npc,arena,source,boss,behavior);ACTIVE.put(npc,c);
        DeathListener previousDeath=npc.deathEndListener;
        npc.deathEndListener=(actor,killer,hit)->{if(previousDeath!=null)previousDeath.handle(actor,killer,hit);if(arena.active())arena.npcDefeated(npc);if(!npc.isRemoved())npc.remove();};
        owner.setAllowRespawn(false);c.scale();
        if(npc.hitListener==null)npc.hitListener=new HitListener();
        Consumer<Hit> oldPre=npc.hitListener.preDamage;
        npc.hitListener.preDamage(hit->{if(oldPre!=null)oldPre.accept(hit);c.incoming(hit);});
        BiConsumer<Hit,Entity> oldBefore=npc.hitListener.preTargetDefend,oldAfter=npc.hitListener.postTargetDefend;
        npc.hitListener.preTargetDefend((hit,victim)->{
            if(oldBefore!=null)oldBefore.accept(hit,victim);
            if(victim.player!=null&&c.valid(victim.player)&&!hit.prayerIgnored&&c.protectedBy(victim.player,hit.attackStyle)) {
                c.protectedHits.put(hit,c.behavior.protection(c,hit.attackStyle));hit.ignorePrayer();
            }
        });
        npc.hitListener.postTargetDefend((hit,victim)->{
            if(oldAfter!=null)oldAfter.accept(hit,victim);Double multiplier=c.protectedHits.remove(hit);
            if(multiplier!=null)hit.damage=(int)(hit.damage*multiplier);
            c.behavior.outgoing(c,hit,victim);
        });
        BiConsumer<Hit,Entity> oldOutgoing=npc.hitListener.preTargetDamage;
        npc.hitListener.preTargetDamage((hit,victim)->{if(oldOutgoing!=null)oldOutgoing.accept(hit,victim);if(!c.live()||victim.player==null||!c.valid(victim.player))reject(hit);});
        npc.attackNpcListener=(player,actor,message)->c.valid(player)&&c.behavior.attackable(c,player);
        behavior.begin(c);
        if(behavior.aggressive(c)&&npc.getCombat().getTarget()==null)for(Player p:c.players()){npc.getCombat().setTarget(p);break;}
    }
    public static void pulse(Arena arena) {
        for(Ctx c:new ArrayList<>(ACTIVE.values()))if(c.arena==arena) {
            if(!c.live()) {if(c.npc.isRemoved()||!arena.active())removed(c.npc);continue;}
            c.tick++;List<Due> due=new ArrayList<>();
            for(Due event:c.pending)if(event.tick<=c.tick)due.add(event);
            c.pending.removeAll(due);
            for(Due event:due)if(c.live())event.action.run();
            if(c.live())c.behavior.tick(c);
        }
    }
    public static void removed(NPC npc) {Ctx c=ACTIVE.remove(npc);if(c!=null)c.close();}
    public static void close(Arena arena) {for(Ctx c:new ArrayList<>(ACTIVE.values()))if(c.arena==arena)removed(c.npc);}
    public static boolean clickObject(Arena arena,Player p,GameObject object,int source,int option) {
        for(Ctx c:new ArrayList<>(ACTIVE.values()))if(c.arena==arena&&c.live()&&c.valid(p)&&c.behavior.object(c,p,object,source,option))return true;
        return false;
    }
    public static boolean clickNpc(Arena arena,Player p,NPC actor,int source,int option) {
        for(Ctx c:new ArrayList<>(ACTIVE.values()))if(c.arena==arena&&c.live()&&c.valid(p)&&c.behavior.npc(c,p,actor,source,option))return true;
        return false;
    }
    @Override public void init() {setAllowRespawn(false);}
    @Override public boolean canAttack(Entity victim) {Ctx c=ACTIVE.get(npc);return c!=null&&c.live()&&victim!=null&&c.valid(victim.player)&&super.canAttack(victim);}
    @Override protected boolean canAggro(Player p) {Ctx c=ACTIVE.get(npc);return c!=null&&c.behavior.aggressive(c)&&canAttack(p);}
    @Override public int getAggressionRange() {return 22;}
    @Override public int getAttackBoundsRange() {return 24;}
    @Override public void follow() {Ctx c=ACTIVE.get(npc);if(c!=null&&c.live()&&target!=null&&c.behavior.followDistance(c)>=0)follow(c.behavior.followDistance(c));}
    @Override public boolean attack() {
        Ctx c=ACTIVE.get(npc);if(c==null||!c.live()||target==null||!c.valid(target.player))return false;
        int delay=c.behavior.attack(c,target.player);if(delay>=0)updateLastAttack(Math.max(1,delay));return false;
    }
    @Override public void startDeath(Hit hit) {
        Ctx c=ACTIVE.get(npc);
        if(c!=null){
            io.ruin.model.combat.Killer credit=getKiller();
            if(credit!=null&&c.valid(credit.player))
                io.ruin.content.pets.UniversalPets.encounterKilled(npc,credit.player,c.arena.assets().npc(c.source));
            c.behavior.death(c);c.close();
        }
        super.startDeath(hit);
    }
    /** Dungeon drops/rewards are owned by the party floor, not ordinary overworld loot. */
    @Override public void dropItems(Killer killer) { }
    static void reject(Hit hit) {hit.block().nullify().setResetActions(false);}

    static abstract class Mechanic {
        void begin(Ctx c) {} void tick(Ctx c) {} void close(Ctx c) {} void death(Ctx c) {}
        boolean aggressive(Ctx c){return true;}
        void incoming(Ctx c,Hit hit) {} boolean attackable(Ctx c,Player p) {return true;}
        int followDistance(Ctx c) {return 1;}
        boolean object(Ctx c,Player p,GameObject object,int source,int option) {return false;}
        boolean npc(Ctx c,Player p,NPC npc,int source,int option) {return false;}
        abstract int attack(Ctx c,Player p);
        void outgoing(Ctx c,Hit hit,Entity victim) {}
        double protection(Ctx c,AttackStyle style) {return 0.;}
    }
    private static final class Due {final long tick;final Runnable action;Due(long tick,Runnable action){this.tick=tick;this.action=action;}}
    static final class Ctx {
        final DungeoneeringCombat owner;final NPC npc;final Arena arena;final int source;final boolean boss;Mechanic behavior;
        final List<Due> pending=new ArrayList<>();final List<NPC> children=new ArrayList<>();final List<GameObject> scenery=new ArrayList<>();
        final List<GroundItem> drops=new ArrayList<>();final Map<Hit,Double> protectedHits=new WeakHashMap<>();
        long tick;boolean closed,invulnerable;int maxHit;
        Ctx(DungeoneeringCombat owner,NPC npc,Arena arena,int source,boolean boss,Mechanic behavior){this.owner=owner;this.npc=npc;this.arena=arena;this.source=source;this.boss=boss;this.behavior=behavior;}
        boolean live(){return !closed&&arena.active()&&!npc.isRemoved()&&!owner.isDead()&&npc.getHp()>0;}
        boolean valid(Player p){return p!=null&&p.isOnline()&&p.getHp()>0&&arena.active()&&arena.contains(p);}
        List<Player> players(){List<Player> result=new ArrayList<>();for(Player p:arena.players())if(valid(p))result.add(p);return result;}
        int roll(int bound){return arena.random().nextInt(Math.max(1,bound));}
        boolean chance(int oneIn){return roll(oneIn)==0;}
        Position tile(int x,int y){return arena.tile(x,y);}
        int[] local(Position p){return arena.local(p);}
        boolean near(Player p,int distance){return npc.getHeight()==p.getHeight()&&TargetRoute.inRange(npc.getAbsX(),npc.getAbsY(),npc.getSize(),p.getAbsX(),p.getAbsY(),p.getSize(),distance);}
        boolean under(Player p){return near(p,0);}
        boolean free(Position at){Tile tile=Tile.get(at.getX(),at.getY(),at.getZ(),false);return tile!=null&&tile.isFloorFree();}
        void after(int ticks,Runnable action){pending.add(new Due(tick+Math.max(1,ticks),action));}
        void anim(int source){npc.animate(arena.assets().animation(source));}
        void gfx(int source){npc.graphics(arena.assets().spotanim(source));}
        void gfx(Player p,int source){if(valid(p))p.graphics(arena.assets().spotanim(source));}
        void tileGfx(int source,Position at,int delay){int target=arena.assets().spotanim(source);for(Player p:players())p.getPacketSender().sendGraphics(target,0,delay,at.getX(),at.getY(),at.getZ());}
        void projectile(int source,Player p,int delay){if(valid(p))new Projectile(arena.assets().spotanim(source),40,16,0,Math.max(1,delay)*30,0,16,0).send(npc,p);}
        void hit(Player p,AttackStyle style,int max,int delay){after(delay,()->{if(valid(p)){Hit hit=new Hit(npc,style,AttackType.ACCURATE).randDamage(Math.max(0,max)).delay(0);hit.endgameOrdinaryNpc=true;p.hit(hit);}});}
        void flat(Player p,int min,int max,int delay){after(delay,()->{if(valid(p))p.hit(new Hit(npc).randDamage(Math.max(0,min),Math.max(min,max)).ignoreDefence().ignorePrayer().delay(0));});}
        void volley(AttackStyle style,int sourceAnim,int projectile,int max,int delay){anim(sourceAnim);for(Player p:players()){if(projectile>=0)projectile(projectile,p,delay);hit(p,style,max,delay);}}
        void heal(int amount){if(live())npc.hit(new Hit(HitType.HEAL).fixedDamage(Math.max(0,amount)).delay(0));}
        void say(String text){npc.forceText(text);}
        void message(String text){for(Player p:players())p.sendMessage(text);}
        void move(int x,int y){Position at=tile(x,y);if(free(at))DumbRoute.route(npc,at.getX(),at.getY());}
        void movePlayer(Player p,int x,int y){Position at=tile(x,y);if(valid(p)&&free(at)){p.getMovement().reset();p.getMovement().teleport(at);}}
        NPC spawn(int source,int x,int y){NPC child=arena.spawn(source,x,y);children.add(child);return child;}
        GroundItem drop(int source,int amount,int x,int y){GroundItem item=new GroundItem(arena.assets().item(source),amount).position(tile(x,y)).spawn(0);drops.add(item);return item;}
        void clearDrops(){for(GroundItem item:drops)item.remove();drops.clear();}
        Ctx useMechanic(NPC child,Mechanic replacement){Ctx next=ACTIVE.get(child);if(next==null||next.arena!=arena)throw new IllegalStateException("Child has no dungeon combat context");next.behavior.close(next);next.pending.clear();next.behavior=replacement;if(!replacement.aggressive(next))next.owner.reset();replacement.begin(next);return next;}
        void object(int source,int shape,int rotation,int x,int y){arena.object(source,shape,rotation,x,y);GameObject object=arena.getObject(x,y,shape);if(object!=null)scenery.add(object);}
        void finishObjective(){if(live()){arena.npcDefeated(npc);npc.remove();}}
        void drainPrayer(Player p,int amount){if(valid(p))p.getStats().get(StatType.Prayer).drain(Math.max(0,amount));}
        boolean protectedBy(Player p,AttackStyle style){return style!=null&&p.getPrayer().isActive(style.isMelee()||style.isMagicalMelee()?Prayer.PROTECT_FROM_MELEE:style.isRanged()||style.isMagicalRanged()?Prayer.PROTECT_FROM_MISSILES:Prayer.PROTECT_FROM_MAGIC);}
        void incoming(Hit hit) {
            if(hit.type==HitType.HEAL&&hit.attacker==null)return;
            if(hit.attacker==null&&(hit.type==HitType.POISON||hit.type==HitType.VENOM)){
                if(!live()||invulnerable||players().isEmpty()){reject(hit);return;}
                behavior.incoming(this,hit);return;
            }
            Player attackingPlayer=hit.attacker==null?null:hit.attacker.player;
            if(attackingPlayer==null&&hit.attacker!=null&&hit.attacker.npc!=null)attackingPlayer=DungeoneeringSummoning.owner(hit.attacker.npc);
            if(!live()||!valid(attackingPlayer)||invulnerable){reject(hit);return;}
            behavior.incoming(this,hit);
        }
        void scale() {
            npc_combat.Info copy=new Gson().fromJson(new Gson().toJson(owner.info),npc_combat.Info.class);
            JsonObject row=arena.assets().npcInfo(source);int combat=Math.max(1,number(row,"combatLevel",npc.getDef().combatLevel));
            int hp=Math.max(1,(combat*(boss?25:8)+10)/10);
            if(bossKind(source)==11)hp=1;
            int defence=(int)(((combat/1.32+1)/.25)-hp);int remaining=combat-(int)((defence+hp)*.25)-1;
            int offence=(int)((remaining/.325)/1.5);int avg=Math.max(1,Math.min(boss?120:100,(defence+offence)/2));
            copy.attack=copy.strength=copy.defence=copy.ranged=copy.magic=avg;copy.hitpoints=hp;
            maxHit=Math.max(1,(combat+9)/10);copy.max_damage=maxHit;
            owner.updateInfo(copy);npc.setHp(hp);
        }
        void morph(int source){npc.transformVisual(arena.assets().npc(source));}
        void maximumHp(int hp,boolean refill){int old=npc.getHp();npc_combat.Info copy=new Gson().fromJson(new Gson().toJson(owner.info),npc_combat.Info.class);copy.hitpoints=Math.max(1,hp);owner.updateInfo(copy);npc.setHp(refill?copy.hitpoints:Math.min(old,copy.hitpoints));}
        void reduceCombat(double fraction){npc_combat.Info copy=new Gson().fromJson(new Gson().toJson(owner.info),npc_combat.Info.class);copy.hitpoints=Math.max(1,(int)(copy.hitpoints*fraction));copy.attack=Math.max(1,(int)(copy.attack*fraction));copy.strength=Math.max(1,(int)(copy.strength*fraction));copy.defence=Math.max(1,(int)(copy.defence*fraction));copy.ranged=Math.max(1,(int)(copy.ranged*fraction));copy.magic=Math.max(1,(int)(copy.magic*fraction));owner.updateInfo(copy);npc.setHp(copy.hitpoints);maxHit=Math.max(1,(int)(maxHit*fraction));}
        void close(){if(closed)return;closed=true;pending.clear();protectedHits.clear();behavior.close(this);for(NPC n:new ArrayList<>(children))if(!n.isRemoved())n.remove();children.clear();for(GameObject o:scenery)o.remove();scenery.clear();clearDrops();}
    }
    static class Guardian extends Mechanic {
        @Override int followDistance(Ctx c){return c.owner.info.attack_style==AttackStyle.CRUSH?1:7;}
        @Override int attack(Ctx c,Player p){
            AttackStyle style=c.owner.info.attack_style;if(style==AttackStyle.CRUSH&&!c.near(p,1))return 1;
            if(c.owner.info.attack_animation>=0)c.npc.animate(c.owner.info.attack_animation);
            c.hit(p,style,c.maxHit,style==AttackStyle.CRUSH?0:2);return c.owner.info.attack_ticks;
        }
    }
}
