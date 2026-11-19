package io.ruin.model.activities.raids.toa.port;

import io.ruin.content.content242.ModernGear;
import io.ruin.model.World;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.player.Player;
import io.ruin.model.combat.Hit;
import io.ruin.model.combat.HitType;
import io.ruin.model.entity.shared.listeners.DeathListener;
import io.ruin.model.inter.dialogue.OptionsDialogue;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.map.*;
import io.ruin.model.map.dynamic.*;
import com.google.gson.annotations.Expose;
import io.ruin.model.item.Item;
import java.util.*;

/** Native, additive ToA encounter adapter. No Zenyte packets, player replacement or cache writes. */
public final class TombsOfAmascutRaid {
    private static final Map<Player, Session> SESSIONS = new IdentityHashMap<>();
    private static final Map<ToaRoom, Factory> CONTROLLERS = new EnumMap<>(ToaRoom.class);
    /** Durable pending claims are recorded on the player's ordinary native save. */
    public static final class Progress {
        @Expose public long completions;
        @Expose public Item[] pendingRewards;
        @Expose public String claimToken;
        /** Crash recovery for raid-only boosts, including when every loan item was consumed. */
        @Expose public int supplyEffects;
    }
    public interface Factory {
        List<String> missingAssets();
        ToaEncounter create(Session session);
    }
    public static void register(ToaRoom room,Factory factory) {
        Factory old=CONTROLLERS.putIfAbsent(room,Objects.requireNonNull(factory));
        if(old!=null&&old.getClass()!=factory.getClass())
            throw new IllegalStateException("Duplicate ToA room controller: "+room);
    }
    private TombsOfAmascutRaid() {}

    public static void init() {
        ModernGear.bindTombsContext(TombsOfAmascutRaid::isInRaid);
        ToaRestRooms.install();
        ToaSupplies.install();
        if(!CONTROLLERS.containsKey(ToaRoom.ZEBAK))register(ToaRoom.ZEBAK,new Factory(){
            public List<String> missingAssets(){return ZebakEncounter.missingAssets();}
            public ToaEncounter create(Session s){return new ZebakEncounter(s);}
        });
        // Optional native modules are fixed local class names, not donor scripts or downloaded bytecode.
        for(String name:new String[]{"KephriEncounter","BabaEncounter","AkkhaEncounter","WardensEncounter","RootToaPuzzles"}){
            try{Class.forName("io.ruin.model.activities.raids.toa.port."+name).getMethod("install").invoke(null);}
            catch(ClassNotFoundException absent){} // An unfinished module remains absent from the real entry menu.
            catch(ReflectiveOperationException invalid){throw new IllegalStateException("Invalid ToA module "+name,invalid);}
        }
    }
    public static boolean isInRaid(Player p) {
        Session s = SESSIONS.get(p);
        return s != null && !s.closed && s.inside(p);
    }
    /** Package-scoped observation for the owner-controlled normal-action adapter. */
    static Session currentSession(Player p) { return SESSIONS.get(p); }
    /** Donor projectile exceptions; native callers still enforce weapon range and physical reach. */
    public static boolean ignoreProjectileClip(Entity from, Entity target) {
        if (RootToaPuzzles.ignoreScabarasPillarProjectileClip(from, target)) return true;
        if (from == null || from.player == null || target == null || target.npc == null) return false;
        Session s = SESSIONS.get(from.player);
        return s != null && s.room == ToaRoom.KEPHRI && s.encounter instanceof KephriEncounter
                && ((KephriEncounter) s.encounter).ignoreProjectileClip(from.player, target.npc);
    }
    public static void leave(Player p){Session s=SESSIONS.get(p);if(s!=null&&!ToaRewardClaims.busy(p))s.close(true);}
    /** Existing Boss242Instances.recoverLogin restores the shared persisted return marker first. */
    public static void onLogin(Player p) {
        Session old = SESSIONS.get(p);
        if (old != null) old.close(true);
        RootToaPuzzles.recoverLoans(p);
        ToaSupplies.recoverLoans(p);
        if(ToaRewardClaims.hasRewards(p))p.sendMessage("You have unclaimed Tombs rewards. Open Tombs of Amascut in the Bosses teleporter to collect them.");
    }
    public static void open(Player p) {
        Session active = SESSIONS.get(p);
        if (active != null) {
            active.menu();
            return;
        }
        if (!Boolean.getBoolean("newscape.raids.enabled")) {
            p.sendMessage("The new raid pack is not enabled on this world."); return;
        }
        if(ToaRewardClaims.hasRewards(p)){ToaRewardClaims.open(p);return;}
        List<String> missing=missingAssets();
        p.dialogue(new OptionsDialogue("Tombs of Amascut - solo beta",
                new Option("Complete base raid (level 0)",()->{
                    if(!missing.isEmpty()){p.sendMessage("The full route is unavailable: "+missing.get(0));return;}
                    enter(p,ToaRoom.MAIN_HALL,true);
                }),
                new Option("Choose an authored boss encounter", () -> choose(p,0)),
                new Option("Current encounter coverage", () -> p.sendScroll("Tombs of Amascut beta",
                        "The complete solo route includes four puzzles, four path bosses and both Wardens rooms.",
                        "Base level 0: no invocations, expert cosmetics or party matchmaking are claimed.",
                        "Only a completed full route awards rewards; isolated boss tests never do.",
                        "Near Reality reward table: configurable 2x unique modifier; low-level eligibility applies.",
                        "Use the Bosses teleporter entry again to leave safely.")),
                new Option("Cancel")));
    }
    public static List<String> missingAssets(){
        List<String> result=new ArrayList<>();
        for(ToaRoom room:ToaRoom.values()){
            Factory f=CONTROLLERS.get(room);
            if(f==null)result.add("controller "+room.title);
            else for(String missing:f.missingAssets())result.add(room.title+": "+missing);
        }
        result.addAll(NativeToaRewards.missingAssets());result.addAll(ToaSupplies.missingAssets());return result;
    }
    private static void choose(Player p,int offset){
        List<ToaRoom> rooms=new ArrayList<>();for(ToaRoom r:new ToaRoom[]{ToaRoom.ZEBAK,ToaRoom.KEPHRI,ToaRoom.AKKHA,ToaRoom.BABA,ToaRoom.WARDENS})if(CONTROLLERS.containsKey(r))rooms.add(r);
        List<Option> options=new ArrayList<>();
        for(int i=offset;i<Math.min(offset+3,rooms.size());i++){ToaRoom r=rooms.get(i);options.add(new Option(r.title+" (solo beta)",()->enter(p,r,false)));}
        if(offset+3<rooms.size())options.add(new Option("Next encounters",()->choose(p,offset+3)));
        options.add(new Option("Cancel"));p.dialogue(new OptionsDialogue("Choose an encounter; no full-raid loot",options));
    }
    private static boolean eligible(Player p) {
        return p.isOnline() && p.getHp() > 0 && !p.isLocked() && !p.getCombat().isDead()
                && p.getAbsX() < 6400 && p.wildernessLevel == 0 && !p.pvpAttackZone
                && p.content242BossReturn == null && p.deathStartListener == null && p.deathEndListener == null
                && !SESSIONS.containsKey(p);
    }
    private static void enter(Player p,ToaRoom room,boolean fullRaid) {
        if (!eligible(p)) { p.sendMessage("Finish your current activity before entering the Tombs."); return; }
        Factory factory=CONTROLLERS.get(room);if(factory==null)return;
        List<String> missing = factory.missingAssets();
        if (!missing.isEmpty()) { p.sendMessage(room.title+" is unavailable: " + missing.get(0)); return; }
        // Native permissions check teleblock, teleport listeners, locks and activity guards before allocation.
        p.getMovement().startTeleport(0, event -> {
            if (SESSIONS.containsKey(p) || p.content242BossReturn != null) return;
            Session s = null;
            try {
                s = new Session(p,room,fullRaid); s.build(); SESSIONS.put(p, s); s.enter();
            } catch (RuntimeException failure) {
                if (s != null) s.close(true);
                p.sendMessage("This encounter could not be created safely. No equipment was changed.");
                io.ruin.Server.logError("ToA encounter creation", failure);
            }
        });
    }
    public static final class Session {
        public final Player player;
        final Position back;
        public DynamicMap map;
        public ToaRoom room;
        public final boolean fullRaid;
        public final ToaSupplies supplies;
        private final EnumSet<ToaRoom> cleared=EnumSet.noneOf(ToaRoom.class);
        private final EnumMap<ToaRoom,Double> roomPoints=new EnumMap<>(ToaRoom.class);
        private double points=5000;
        private int deaths;
        private boolean roomComplete,completionRecorded;
        private Item[] earned;
        private final List<DynamicMap> maps=new ArrayList<>();
        final DeathListener oldDeath;
        DeathListener ownedDeath;
        ToaEncounter encounter;
        int lease;
        boolean closed;
        Session(Player p,ToaRoom room,boolean fullRaid) { player=p; back=p.getPosition().copy(); oldDeath=p.deathEndListener;this.room=room;this.fullRaid=fullRaid;supplies=new ToaSupplies(this); }
        void build() {
            // Source room anchors are occupied; neighbouring cells may intentionally be void.
            int source=NearRealityRaidAssets.region(room.sourceRegion);
            if(source<0)throw new IllegalStateException("Missing ToA room "+room);
            map=new DynamicMap();maps.add(map);
            if(room==ToaRoom.WARDENS_FINAL){
                // NR SecondWardenEncounter.copyPlanesMap(...,0,1): authored floor0 is fought on plane1.
                map.build(source,0);List<DynamicChunk> raised=new ArrayList<>();Region original=Region.LOADED[source];
                for(int cx=0;cx<8;cx++)for(int cy=0;cy<8;cy++)raised.add(new DynamicChunk((original.baseX>>3)+cx,(original.baseY>>3)+cy,0).pos(cx,cy,1));
                map.build(raised);
            }else map.build(source,room.plane);
            lease=map.swRegion.dynamicIndex;
            // Only instance-owned copies of entry/exit controls are changed.
            for (Region region:map.getRegions()) if(region!=null) MultiZone.add(region.bounds);
            encounter=CONTROLLERS.get(room).create(this);encounter.spawn();
        }
        public Position tile(int x,int y) { return new Position(map.swRegion.baseX+x-room.baseX,map.swRegion.baseY+y-room.baseY,room.plane); }
        public int sourceX(Position p){return p.getX()-map.swRegion.baseX+room.baseX;}
        public int sourceY(Position p){return p.getY()-map.swRegion.baseY+room.baseY;}
        boolean inside(Player p) {
            if(closed)return false;
            int region=p.getPosition().getRegion().id;
            for(DynamicMap owned:maps)for(Region r:owned.getRegions())if(r!=null&&r.id==region)return true;
            return false;
        }
        public boolean valid() { return !closed && player.isOnline() && player.getHp()>0 && map!=null && map.swRegion!=null
                && player.getPosition().getRegion().dynamicIndex==lease && player.getHeight()==room.plane; }
        void enter() {
            player.content242BossReturn=new int[]{back.getX(),back.getY(),back.getZ()};
            ownedDeath=(entity,killer,hit)->{
                supplies.died();
                if(!fullRaid||completionRecorded){close(true);return;}
                deaths++;points=Math.max(0,points-Math.max(1000,points*.20));
                player.sendMessage("The room resets. Deaths: "+deaths+". Raid points: "+points()+".");
                transition(room);
            }; player.deathEndListener=ownedDeath;
            player.addActiveMapListener(new MapListener(this::inside).onExit((p,logout)->close(logout)));
            player.getMovement().teleport(tile(room.entryX,room.entryY));
            player.sendMessage(fullRaid?"Tombs solo base beta: complete all four paths and Wardens. Your equipment remains yours.":room.title+" isolated encounter beta: no raid loot. Your equipment remains yours.");
            World.startEvent(event->{
                event.delay(3);
                while(!closed && player.isOnline() && inside(player)) {
                    if(valid()){supplies.tick();encounter.tick();}
                    event.delay(1);
                }
                if(!closed)close(!player.isOnline());
            });
        }
        public void completed() {
            if(closed||roomComplete)return;roomComplete=true;final ToaRoom finished=room;
            // Native startDeath runs before the lethal hit's postDamage callback; score it first.
            World.startEvent(event->{event.delay(1);if(closed||room!=finished)return;routeCompletion(finished);});
        }
        private void routeCompletion(ToaRoom finished){
            if(!fullRaid){player.sendMessage(finished.title+" defeated. This isolated encounter does not award full-raid loot.");menu();return;}
            switch(finished){
                case CRONDIS:transition(ToaRoom.ZEBAK);return;
                case SCABARAS:transition(ToaRoom.KEPHRI);return;
                case HET:transition(ToaRoom.AKKHA);return;
                case APMEKEN:transition(ToaRoom.BABA);return;
                case ZEBAK:case KEPHRI:case AKKHA:case BABA:
                    cleared.add(finished);player.sendMessage(finished.title+" complete. Paths: "+cleared.size()+"/4. Points: "+points()+".");
                    transition(ToaRoom.MAIN_HALL);return;
                case WARDENS_FINAL:
                    if(cleared.size()==4){award();return;}break;
                default:break;
            }
            player.sendMessage("This completion cannot advance the raid. Leave safely and report the room to staff.");menu();
        }
        public int points(){return (int)Math.max(0,Math.min(64000,points));}
        public int deaths(){return deaths;}
        public boolean cleared(ToaRoom boss){return cleared.contains(boss);}
        public int pathsCompleted(){return cleared.size();}
        /** Only real positive damage by this instance's participant produces capped points. */
        public void recordDamage(Hit hit,double multiplier){
            if(!fullRaid||closed||completionRecorded||hit==null||hit.isNullified()||hit.damage<=0||hit.type==HitType.HEAL
                    ||hit.attacker==null||hit.attacker.player!=player||!valid()||!Double.isFinite(multiplier)||multiplier<=0)return;
            double previous=roomPoints.getOrDefault(room,0D);
            double gained=Math.min(Math.max(0,20000-previous),Math.min(64000-points,hit.damage*multiplier));
            if(gained>0){roomPoints.put(room,previous+gained);points+=gained;}
        }
        public void choosePath(ToaRoom puzzle){
            if(!valid()||room!=ToaRoom.MAIN_HALL||!fullRaid)return;
            ToaRoom boss=puzzle==ToaRoom.CRONDIS?ToaRoom.ZEBAK:puzzle==ToaRoom.SCABARAS?ToaRoom.KEPHRI:puzzle==ToaRoom.HET?ToaRoom.AKKHA:puzzle==ToaRoom.APMEKEN?ToaRoom.BABA:null;
            if(boss==null)return;if(cleared.contains(boss)){player.sendMessage("That path is already complete.");return;}transition(puzzle);
        }
        public void enterWardens(){
            if(!valid()||room!=ToaRoom.MAIN_HALL||!fullRaid)return;
            if(cleared.size()!=4){player.sendMessage("Complete all four paths before entering the lower level.");return;}transition(ToaRoom.WARDENS);
        }
        private void award(){
            if(closed||completionRecorded||ToaRewardClaims.busy(player))return;
            if(earned==null)earned=NativeToaRewards.roll(points(),0);
            if(encounter!=null)encounter.close();
            ToaRewardClaims.record(player,earned,()->{
                completionRecorded=true;
                player.sendMessage("Tombs of Amascut complete! "+points()+" points, "+deaths+" deaths. Rewards safely recorded.");
                if(!closed&&player.isOnline())transition(ToaRoom.REWARDS);
            });
        }
        public void menu(){
            if(closed||ToaRewardClaims.busy(player))return;
            if(fullRaid&&room==ToaRoom.REWARDS){ToaRewardClaims.open(player);return;}
            if(fullRaid&&room==ToaRoom.WARDENS_FINAL&&earned!=null&&!completionRecorded){
                player.dialogue(new OptionsDialogue("Record your completed raid",new Option("Retry saving earned rewards",this::award),new Option("Leave safely",()->close(true))));return;
            }
            List<Option> options=new ArrayList<>();
            if(fullRaid&&room==ToaRoom.MAIN_HALL){
                if(cleared.size()==4)options.add(new Option("Enter the Wardens' lower level",this::enterWardens));
                else for(ToaRoom puzzle:new ToaRoom[]{ToaRoom.CRONDIS,ToaRoom.SCABARAS,ToaRoom.HET,ToaRoom.APMEKEN}){
                    ToaRoom boss=puzzle==ToaRoom.CRONDIS?ToaRoom.ZEBAK:puzzle==ToaRoom.SCABARAS?ToaRoom.KEPHRI:puzzle==ToaRoom.HET?ToaRoom.AKKHA:ToaRoom.BABA;
                    if(!cleared.contains(boss))options.add(new Option(puzzle.title.replace(" puzzle",""),()->choosePath(puzzle)));
                }
                if(supplies.available())options.add(new Option("Claim the helpful spirit's supplies",supplies::openSelection));
            }
            options.add(new Option("Leave safely",()->close(true)));
            if(options.size()<5)options.add(new Option("Stay"));
            player.dialogue(new OptionsDialogue(fullRaid?"Tombs: "+cleared.size()+"/4 paths, "+points()+" points":"Leave this isolated encounter?",options));
        }
        /** Internal authored phase/room transition, never exposed as an arbitrary teleport command. */
        public void transition(ToaRoom next) {
            if(closed)return;
            Factory factory=CONTROLLERS.get(next);
            if(factory==null || !factory.missingAssets().isEmpty()){
                player.sendMessage("The next authored room is not ready; you can leave safely.");return;
            }
            DynamicMap previous=map;
            if(encounter!=null)encounter.close();
            room=next;roomComplete=false;
            try{build();player.getMovement().teleport(tile(room.entryX,room.entryY));}
            catch(RuntimeException failure){close(true);io.ruin.Server.logError("ToA room transition",failure);return;}
            World.startEvent(event->{
                event.delay(2);
                while(player.isOnline() && previous.swRegion!=null
                        && player.getPosition().getRegion().dynamicIndex==previous.swRegion.dynamicIndex)event.delay(1);
                if(maps.remove(previous)){for(Region r:previous.getRegions())if(r!=null)MultiZone.remove(r.bounds);previous.destroy();}
            });
        }
        public void close(boolean returnPlayer) {
            if(closed)return; closed=true;
            if(encounter!=null)encounter.close();
            supplies.close();
            if(player.deathEndListener==ownedDeath)player.deathEndListener=oldDeath;
            SESSIONS.remove(player);
            if(returnPlayer)player.getMovement().teleport(back);
            // Native teleport is queued. Do not release a leased map beneath a departing player.
            World.startEvent(event->{
                event.delay(2);
                boolean stillInside;
                do{stillInside=false;int region=player.getPosition().getRegion().id;
                    for(DynamicMap owned:maps)for(Region r:owned.getRegions())if(r!=null&&r.id==region)stillInside=true;
                    if(player.isOnline()&&stillInside)event.delay(1);
                }while(player.isOnline()&&stillInside);
                if(player.isOnline() && player.getAbsX()<6400)player.content242BossReturn=null;
                for(DynamicMap owned:maps){for(Region region:owned.getRegions())if(region!=null)MultiZone.remove(region.bounds);owned.destroy();}
                maps.clear();
            });
        }
    }
}
