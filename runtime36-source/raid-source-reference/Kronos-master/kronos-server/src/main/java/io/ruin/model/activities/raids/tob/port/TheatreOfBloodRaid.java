package io.ruin.model.activities.raids.tob.port;

import io.ruin.Server;
import io.ruin.cache.ItemDef;
import io.ruin.model.World;
import io.ruin.model.activities.raids.NearRealityRaidAssets;
import io.ruin.model.activities.dungeoneering.Dungeoneering;
import io.ruin.model.combat.Hit;
import io.ruin.model.combat.HitType;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.entity.shared.listeners.*;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.map.*;
import io.ruin.model.map.dynamic.DynamicChunk;
import io.ruin.model.map.dynamic.DynamicMap;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import java.util.*;

/**
 * Native Java8/Kilim adapter for the pinned Near Reality six-room normal raid.
 * Attribution and intentionally unported mechanics: docs/raids/TOB-PORT.md.
 * All maps, NPC handlers, delayed actions and object actions belong to one lease.
 */
public final class TheatreOfBloodRaid {
    static final Position LOBBY = new Position(3660,3219,0);
    static final Map<Player, Party> PARTIES = new IdentityHashMap<>();
    static final Map<Player, Run> RUNS = new IdentityHashMap<>();
    static final Random RANDOM = new Random();
    private static final List<DynamicMap> RETIRED = new ArrayList<>();
    private static boolean initialized;

    public static void init() {
        if(initialized || !Boolean.getBoolean("newscape.raids.enabled"))return;
        initialized=true;
        TheatreDawnbringer.install();
        World.startEvent(event->{while(initialized){
            for(Party party:new HashSet<>(PARTIES.values()))if(party.run==null){for(Player p:new ArrayList<>(party.members))if(!p.isOnline())party.remove(p);party.requests.removeIf(p->!p.isOnline()||PARTIES.containsKey(p));}
            reapMaps();
            for(Run run:new HashSet<>(RUNS.values()))try{run.pulse();}
            catch(Exception failure){Server.logError("Theatre instance ended safely",failure);run.close("The raid ended because its instance could not continue safely.");}
            event.delay(1);
        }});
    }
    public static boolean isInRaid(Player p){return RUNS.containsKey(p);}
    private static void reapMaps(){for(Iterator<DynamicMap> it=RETIRED.iterator();it.hasNext();){DynamicMap map=it.next();boolean occupied=false;for(Region r:map.getRegions())if(r!=null&&!r.players.isEmpty())occupied=true;if(!occupied){for(Region r:map.getRegions())if(r!=null)MultiZone.remove(r.bounds);map.destroy();it.remove();}}}
    public static void onLogin(Player p) {
        TheatreProgress s=p.theatreProgress;
        if(s==null)return;
        if(s.pendingRewards==null)s.pendingRewards=new ArrayList<>();
        if(s.activeRun!=null && !RUNS.containsKey(p)) {
            removeLoans(p,s.activeRun);p.getMovement().teleport(safeReturn(s));s.activeRun=null;
            p.sendMessage("Your previous Theatre instance has ended. Your carried equipment is unchanged.");
        }
        if(!s.pendingRewards.isEmpty())p.sendMessage("You have unclaimed Theatre rewards. Use the Bosses teleporter's Theatre entry to claim them.");
    }
    static TheatreProgress progress(Player p){if(p.theatreProgress==null)p.theatreProgress=new TheatreProgress();return p.theatreProgress;}
    static Position safeReturn(TheatreProgress s){return s.returnX>0&&s.returnX<6400&&s.returnY>0&&s.returnY<16384&&s.returnZ>=0&&s.returnZ<=3?new Position(s.returnX,s.returnY,s.returnZ):LOBBY.copy();}
    public static void open(Player p) {
        if(!Boolean.getBoolean("newscape.raids.enabled")){p.sendMessage("The Theatre is not enabled on this world.");return;}
        Run run=RUNS.get(p);
        List<Option> options=new ArrayList<>();
        if(run!=null) {
            options.add(new Option("Current room: "+run.room().name,()->open(p)));
            if(run.cleared && run.party.leader==p)options.add(new Option("Continue to next room",()->run.next()));
            if(run.stage==5&&!run.cleared)options.add(new Option("Take Dawnbringer",()->loanDawnbringer(p,run)));
            options.add(new Option("Leave the Theatre",()->run.leave(p,true)));
        } else {
            Party party=PARTIES.get(p);
            if(party==null) {
                options.add(new Option("Create party / play solo",()->{if(eligible(p)){Party created=new Party(p);PARTIES.put(p,created);open(p);}}));
                for(Party other:new HashSet<>(PARTIES.values()))if(other.run==null&&other.members.size()<5&&other.leader.isOnline()&&other.leader!=p)
                    options.add(new Option("Request to join "+other.leader.getName(),()->request(p,other)));
            } else {
                options.add(new Option("Party: "+party.members.size()+"/5 (leader "+party.leader.getName()+")",()->open(p)));
                if(party.leader==p) {
                    options.add(new Option("Start normal Theatre",()->start(party)));
                    for(Player applicant:new ArrayList<>(party.requests))if(applicant.isOnline())
                        options.add(new Option("Accept "+applicant.getName(),()->accept(party,applicant)));
                }
                options.add(new Option("Leave party",()->{party.remove(p);open(p);}));
            }
        }
        if(!progress(p).pendingRewards.isEmpty())options.add(new Option("Claim pending rewards",()->claim(p)));
        options.add(new Option("Close"));
        OptionScroll.open(p,"Theatre of Blood",options);
    }
    static boolean eligible(Player p) {
        boolean allowed=p.isOnline()&&!p.isLocked()&&!p.getCombat().isDefending(10)&&p.getHp()>0&&p.wildernessLevel==0
                &&p.getAbsX()<6400&&p.getDuel().stage==0&&!p.joinedTournament&&!p.supplyChestRestricted
                &&!Dungeoneering.inRun(p)&&!io.ruin.model.activities.hollowdepths.HollowDepths.inRun(p)
                &&!PlayerFile.isSaveQuarantined(p)&&!RUNS.containsKey(p)&&p.logoutListener==null&&p.teleportListener==null;
        if(!allowed)p.sendMessage("Leave your current activity and combat before joining the Theatre.");
        return allowed;
    }
    private static void request(Player p,Party party){if(!eligible(p)||PARTIES.containsKey(p)||party.run!=null||party.members.size()>=5)return;party.requests.add(p);p.sendMessage("Join request sent.");party.leader.sendMessage(p.getName()+" requested to join your Theatre party. Open the Theatre menu to accept.");}
    private static void accept(Party party,Player p){if(party.run!=null||party.members.size()>=5||!party.requests.remove(p)||PARTIES.containsKey(p)||!eligible(p))return;party.members.add(p);PARTIES.put(p,party);party.message(p.getName()+" joined the party.");open(party.leader);}
    private static void start(Party party) {
        if(party.run!=null||party.members.isEmpty())return;
        for(Player p:party.members)if(!eligible(p))return;
        if(!ready()){party.message("The Theatre's native assets are incomplete. Entry was refused before moving or changing any player.");return;}
        for(Player p:party.members)if(!progress(p).pendingRewards.isEmpty()){party.message(p.getName()+" must claim the previous raid's rewards before starting.");return;}
        Run run=new Run(party);party.run=run;
        try {
            for(Player p:party.members){TheatreProgress s=progress(p);s.activeRun=run.id;s.returnX=p.getAbsX();s.returnY=p.getAbsY();s.returnZ=p.getHeight();RUNS.put(p,run);run.attach(p);}
            for(Player p:party.members)if(!save(p))throw new IllegalStateException("Theatre entry save unresolved");
            run.load(0);
        }catch(Exception failure){Server.logError("Theatre entry blocked",failure);run.close("The raid could not start safely. Your equipment was not replaced.");}
    }
    /** Actual referenced base assets must resolve; optional sounds never substitute geometry. */
    public static boolean ready() {
        for(Room r:Room.values())for(int cx=0;cx<r.width;cx++)for(int cy=0;cy<r.height;cy++)
            if(NearRealityRaidAssets.position(r.cx*8+cx*8,r.cy*8+cy*8,r.z)==null)return false;
        for(Room r:Room.values())if(r.npc>0&&(NearRealityRaidAssets.npc(r.npc,r.npcName)<0||NearRealityRaidAssets.combatInfo(r.npc)==null))return false;
        int[] helpers={8366,8379,8381,8382,8353,8384,8385,8386,8376,8356,8357,8340,8372,8374};
        for(int id:helpers)if(NearRealityRaidAssets.npc(id)<0||NearRealityRaidAssets.combatInfo(id)==null)return false;
        for(int id:new int[]{33035,32734})if(NearRealityRaidAssets.object(id,"")<0)return false;
        return true;
    }
    static final class Party {
        Player leader;final List<Player> members=new ArrayList<>();final Set<Player> requests=Collections.newSetFromMap(new IdentityHashMap<>());Run run;
        Party(Player p){leader=p;members.add(p);}
        void message(String text){for(Player p:new ArrayList<>(members))if(p.isOnline())p.sendMessage(text);}
        void remove(Player p){members.remove(p);PARTIES.remove(p);requests.remove(p);if(leader==p&&!members.isEmpty())leader=members.get(0);}
    }
    enum Room {
        MAIDEN("Maiden of Sugadinti",392,552,12,7,0,8360,"The Maiden of Sugadinti",3162,4444,3182,4446),
        BLOAT("Pestilent Bloat",408,552,8,8,0,8359,"Pestilent Bloat",3299,4445,3302,4449),
        NYLOCAS("Nylocas Vasilias",408,528,8,8,0,8355,"Nylocas Vasilias",3294,4247,3295,4253),
        SOTETSEG("Sotetseg",408,536,8,8,0,8388,"Sotetseg",3277,4326,3279,4310),
        XARPUS("Xarpus",392,544,8,8,1,8338,"Xarpus",3169,4386,3174,4380),
        VERZIK("Verzik Vitur",392,536,8,8,0,8370,"Verzik Vitur",3166,4323,3168,4303),
        REWARDS("Verzik's vault",400,536,8,8,0,-1,"",0,0,3237,4306);
        final String name,npcName;final int cx,cy,width,height,z,npc,bx,by,ex,ey;
        Room(String n,int cx,int cy,int w,int h,int z,int npc,String nn,int bx,int by,int ex,int ey){name=n;this.cx=cx;this.cy=cy;width=w;height=h;this.z=z;this.npc=npc;npcName=nn;this.bx=bx;this.by=by;this.ex=ex;this.ey=ey;}
    }
    static final class Hooks {
        final HitListener previousHit;final LogoutListener previousLogout;final TeleportListener previousTeleport;HitListener installedHit;LogoutListener installedLogout;TeleportListener installedTeleport;
        Hooks(Player p){previousHit=p.hitListener;previousLogout=p.logoutListener;previousTeleport=p.teleportListener;}
        void restore(Player p){if(p.hitListener==installedHit)p.hitListener=previousHit;if(p.logoutListener==installedLogout)p.logoutListener=previousLogout;if(p.teleportListener==installedTeleport)p.teleportListener=previousTeleport;}
    }
    static final class Due {final int tick;final Runnable action;Due(int t,Runnable r){tick=t;action=r;}}
    static final class Run {
        final String id=UUID.randomUUID().toString();final Party party;final Map<Player,Hooks> hooks=new IdentityHashMap<>();
        final Set<Player> spectators=Collections.newSetFromMap(new IdentityHashMap<>());final Map<Player,Integer> damage=new IdentityHashMap<>();
        final Map<Player,Integer> points=new IdentityHashMap<>(),roomDamage=new IdentityHashMap<>(),phaseDamage=new IdentityHashMap<>();
        final List<Due> pending=new ArrayList<>();final List<NPC> actors=new ArrayList<>();final List<GameObject> objects=new ArrayList<>();
        DynamicMap map;TheatreCombat encounter;int stage,tick,arrivalUntil;boolean cleared,closed,transition,phaseMvpQueued;int generation;
        Run(Party p){party=p;for(Player member:p.members)points.put(member,8);}
        void recordDamage(Player p,NPC actor,int amount){damage.merge(p,amount,Integer::sum);if(encounter!=null&&actor==encounter.boss){roomDamage.merge(p,amount,Integer::sum);phaseDamage.merge(p,amount,Integer::sum);if(phaseMvpQueued)phaseMvp();}}
        void mvp(Map<Player,Integer> scores,int award){Player best=null;int top=0;for(Player p:party.members){int score=scores.getOrDefault(p,0);if(score>top){top=score;best=p;}}if(best!=null)points.merge(best,award,Integer::sum);scores.clear();}
        void phaseMvp(){mvp(phaseDamage,2);phaseMvpQueued=false;}
        Room room(){return Room.values()[stage];}
        Position tile(int x,int y){return new Position(map.swRegion.baseX+x-room().cx*8,map.swRegion.baseY+y-room().cy*8,room().z);}
        int sourceX(Player p){return room().cx*8+p.getAbsX()-map.swRegion.baseX;}int sourceY(Player p){return room().cy*8+p.getAbsY()-map.swRegion.baseY;}
        boolean contains(Player p){return !closed&&map!=null&&map.swRegion!=null&&p.getHeight()==room().z&&p.getAbsX()>=map.swRegion.baseX&&p.getAbsX()<map.swRegion.baseX+room().width*8&&p.getAbsY()>=map.swRegion.baseY&&p.getAbsY()<map.swRegion.baseY+room().height*8;}
        boolean valid(Player p){return p!=null&&p.isOnline()&&RUNS.get(p)==this&&!spectators.contains(p)&&contains(p)&&p.getHp()>0;}
        List<Player> targets(){List<Player> result=new ArrayList<>();for(Player p:party.members)if(valid(p))result.add(p);return result;}
        void attach(Player p){Hooks h=new Hooks(p);hooks.put(p,h);h.installedHit=new HitListener().preDamage(hit->{if(h.previousHit!=null&&h.previousHit.preDamage!=null)h.previousHit.preDamage.accept(hit);if(RUNS.get(p)!=this)return;if(spectators.contains(p)){hit.damage=0;hit.nullify();return;}if(hit.type!=HitType.HEAL&&hit.damage>=p.getHp()){hit.damage=0;hit.nullify();down(p);}});
            // Preserve other callbacks while adding the sole incoming-damage guard.
            if(h.previousHit!=null){h.installedHit.preDefend=h.previousHit.preDefend;h.installedHit.postDefend=h.previousHit.postDefend;h.installedHit.postDamage=h.previousHit.postDamage;h.installedHit.preTargetDefend=h.previousHit.preTargetDefend;h.installedHit.postTargetDefend=h.previousHit.postTargetDefend;h.installedHit.preTargetDamage=h.previousHit.preTargetDamage;h.installedHit.postTargetDamage=h.previousHit.postTargetDamage;}
            p.hitListener=h.installedHit;
            h.installedLogout=new LogoutListener().onLogout(q->leave(q,true));p.logoutListener=h.installedLogout;
            h.installedTeleport=q->{q.sendMessage("Use the Theatre menu to resign from this raid.");return false;};p.teleportListener=h.installedTeleport;
        }
        void down(Player p){if(!spectators.add(p))return;progress(p).deaths++;points.merge(p,-4,Integer::sum);p.resetActions(true,true,true);p.clearHits();p.setHp(p.getMaxHp());p.getMovement().teleport(tile(room().ex,room().ey));p.sendMessage("You are out for this room. Your carried items are safe; surviving teammates can finish it.");if(targets().isEmpty())close("Your party has fallen. The Theatre has ended without rewards.");}
        void load(int next){
            if(closed)return;transition=true;generation++;pending.clear();clearRoom();stage=next;cleared=false;spectators.clear();roomDamage.clear();phaseDamage.clear();if(stage<6)for(Player p:party.members)points.merge(p,3,Integer::sum);
            Room r=room();map=new DynamicMap();List<DynamicChunk> chunks=new ArrayList<>();
            for(int x=0;x<r.width;x++)for(int y=0;y<r.height;y++)for(int z=0;z<4;z++){Position source=NearRealityRaidAssets.position(r.cx*8+x*8,r.cy*8+y*8,z);if(source==null)throw new IllegalStateException("Unmapped Theatre source chunk");chunks.add(new DynamicChunk(source.getX()>>3,source.getY()>>3,source.getZ()).pos(x,y,z));}
            map.setObjectConsumer(obj->{String name=obj.getDef().name.toLowerCase(Locale.ROOT);if(name.contains("formidable passage")||name.equals("vyre orator")||name.contains("treasure chest")) {obj.actions=new ObjectAction[5];for(int i=0;i<5;i++)obj.actions[i]=(p,o)->{if(RUNS.get(p)==this)open(p);};}});
            map.build(chunks);for(Region region:map.getRegions())if(region!=null)MultiZone.add(region.bounds);
            for(Player p:new ArrayList<>(party.members)){p.resetActions(true,true,true);p.clearHits();p.getMovement().teleport(tile(r.ex,r.ey));}
            arrivalUntil=tick+5;transition=false;
            if(r.npc>0){encounter=new TheatreCombat(this,r);encounter.begin();}
            else{cleared=true;party.message("The raid is complete. Open the Theatre menu to claim rewards or leave.");}
            party.message("Theatre room "+(stage+1)+": "+r.name+". Use the Bosses teleporter entry or the room passage for the raid menu.");
        }
        NPC spawn(int source,String name,int x,int y,int hp,TheatreCombat.Behavior behavior){
            // The original8386 is unnamed. Its explicit, verified asset binding authorizes it;
            // inventing a display name or accepting arbitrary unnamed NPCs would hide a bad ID.
            int id=source==8386?NearRealityRaidAssets.npc(source):NearRealityRaidAssets.npc(source,name);
            if(id<0)throw new IllegalStateException("Missing Theatre NPC "+source+" "+name);
            io.ruin.data.impl.npcs.npc_combat.Info info=NearRealityRaidAssets.combatInfo(source);
            if(info==null)throw new IllegalStateException("Missing authored combat stats "+source);
            if(source==8385)TheatreVerzik.applyRedCrabStats(info);
            NPC npc=new NPC(id).spawn(tile(x,y));actors.add(npc);map.addNpc(npc);TheatreCombat.attach(this,npc,info,hp,behavior);return npc;
        }
        void later(int ticks,Runnable action){int lease=generation;pending.add(new Due(tick+Math.max(1,ticks),()->{if(!closed&&generation==lease)action.run();}));}
        void pulse(){if(closed)return;tick++;if(phaseMvpQueued)phaseMvp();for(Player p:new ArrayList<>(party.members))if(!p.isOnline())leave(p,true);else if(tick>arrivalUntil&&!transition&&!contains(p))leave(p,true);if(closed)return;List<Due> due=new ArrayList<>();for(Due action:pending)if(action.tick<=tick)due.add(action);pending.removeAll(due);for(Due action:due)if(!closed)action.action.run();if(!closed&&encounter!=null&&!cleared&&tick>arrivalUntil)encounter.pulse();}
        void completeRoom(){if(closed||cleared)return;cleared=true;generation++;pending.clear();for(Player p:party.members)p.clearHits();for(NPC n:actors)if(encounter!=null&&n!=encounter.boss&&!n.isRemoved())n.remove();if(stage<5)mvp(roomDamage,new int[]{2,2,1,1,2}[stage]);if(stage==5){phaseMvp();for(Player p:party.members)points.merge(p,-8,Integer::sum);award();}party.message(room().name+" defeated. The leader can continue through the Theatre menu.");}
        void next(){if(!closed&&cleared){if(stage<6)load(stage+1);else close("You leave Verzik's vault.");}}
        void award(){List<Player> contributing=new ArrayList<>();int total=0;for(Player p:party.members){int score=points.getOrDefault(p,0);if(score>0){contributing.add(p);total+=score;}}if(contributing.isEmpty())return;Player unique=null;
            // Donor normal chance23%, scaled by surviving contribution points; max18/player+14MVP.
            int chance=Math.min(23,(int)Math.round(23.0*total/(party.members.size()*18+14)));
            if(RANDOM.nextInt(101)<=chance){int roll=RANDOM.nextInt(total);for(Player p:contributing){roll-=points.get(p);if(roll<0){unique=p;break;}}}
            for(Player p:contributing){TheatreProgress s=progress(p);if(id.equals(s.rewardedRun))continue;List<Item> rewards=new ArrayList<>();if(p==unique){int[] ids={22477,22324,22481,22326,22327,22328,22486};int[] weights={4,2,2,2,2,2,1};int roll=RANDOM.nextInt(15);for(int i=0;i<ids.length;i++){roll-=weights[i];if(roll<0){rewards.add(new Item(ids[i]));break;}}}else for(int i=0;i<3;i++)rewards.add(commonReward());
                for(Item reward:rewards)if(ItemDef.get(reward.getId())==null)throw new IllegalStateException("Missing original Theatre reward "+reward.getId());
                s.pendingRewards.addAll(rewards);s.rewardedRun=id;s.completions++;save(p);p.sendMessage("Your Theatre rewards are saved. Claim them from the Theatre menu with inventory space.");}
        }
        void clearRoom(){if(encounter!=null){encounter.close();encounter=null;}for(NPC npc:actors)if(!npc.isRemoved())npc.remove();actors.clear();for(GameObject obj:objects)if(obj.id!=-1)obj.remove();objects.clear();if(map!=null){RETIRED.add(map);map=null;}}
        void leave(Player p,boolean teleport){if(RUNS.get(p)!=this)return;RUNS.remove(p);Hooks h=hooks.remove(p);if(h!=null)h.restore(p);spectators.remove(p);TheatreProgress s=progress(p);removeLoans(p,id);s.activeRun=null;p.resetActions(true,true,true);p.clearHits();if(teleport)p.getMovement().teleport(safeReturn(s));party.remove(p);if(party.members.isEmpty())close("The party has left.");}
        void close(String message){if(closed)return;closed=true;generation++;pending.clear();party.message(message);for(Player p:new ArrayList<>(party.members))leave(p,true);clearRoom();party.run=null;}
    }
    private static Item commonReward(){
        // Pinned donor common table, unnoted IDs converted to their native notes where available.
        int[][] rows={{22446,50,60,2},{560,500,600,1},{565,500,600,1},{1939,500,600,1},{453,500,600,1},{444,300,360,1},{1775,200,240,1},{449,130,156,1},{451,60,72,1},{245,50,60,1},{3138,50,60,1},{215,50,60,1},{211,40,48,1},{3049,37,44,1},{213,36,43,1},{209,34,40,1},{207,30,36,1},{3051,27,32,1},{2485,26,31,1},{217,24,28,1},{219,20,24,1},{1391,15,18,1},{1373,4,4,1},{1127,4,4,1},{1113,4,4,1},{5289,3,3,1},{5315,3,3,1},{5316,3,3,1},{21488,10,12,1}};
        int roll=RANDOM.nextInt(30);int[] row=rows[0];for(int[] choice:rows){roll-=choice[3];if(roll<0){row=choice;break;}}
        ItemDef def=ItemDef.get(row[0]);int id=def!=null&&def.notedId>0?def.notedId:row[0];int amount=(int)Math.ceil((row[1]+RANDOM.nextInt(row[2]-row[1]+1))*1.4);return new Item(id,amount);
    }
    private static boolean save(Player p){try{PlayerFile.saveDurably(p);return true;}catch(Exception failure){PlayerFile.quarantine(p,"Theatre save requires confirmation; rewards must not be repeated");p.lock(LockType.FULL_NULLIFY_DAMAGE);p.sendMessage("Your Theatre save is pending confirmation. Please contact staff before retrying.");Server.logError("Theatre durable save",failure);return false;}}
    private static void loanDawnbringer(Player p,Run run){if(RUNS.get(p)!=run||run.stage!=5||run.cleared||run.encounter==null)return;TheatreCombat.Actor actor=run.encounter.actors.get(run.encounter.boss);if(actor==null||!(actor.behavior instanceof TheatreCombat.Verzik)||((TheatreCombat.Verzik)actor.behavior).phase!=1)return;int id=NearRealityRaidAssets.item(22516,"Dawnbringer");if(id<0||p.getInventory().getFreeSlots()==0){p.sendMessage("Free an inventory slot before taking the Dawnbringer.");return;}for(Item item:p.getInventory().getItems())if(item!=null&&run.id.equals(item.copyOfAttributes().get("theatre.loan")))return;for(Item item:p.getEquipment().getItems())if(item!=null&&run.id.equals(item.copyOfAttributes().get("theatre.loan")))return;Map<String,String> attributes=new HashMap<>();attributes.put("theatre.loan",run.id);p.getInventory().add(new Item(id,1,attributes));p.sendMessage("You take the Dawnbringer. Wield it yourself; this raid loan disappears when the shield phase or raid ends.");}
    static void removeLoans(Player p,String run){if(run==null)return;for(int i=0;i<p.getInventory().getItems().length;i++){Item item=p.getInventory().get(i);if(item!=null&&run.equals(item.copyOfAttributes().get("theatre.loan")))p.getInventory().set(i,null);}for(int i=0;i<p.getEquipment().getItems().length;i++){Item item=p.getEquipment().get(i);if(item!=null&&run.equals(item.copyOfAttributes().get("theatre.loan")))p.getEquipment().set(i,null);}}
    private static void claim(Player p){
        TheatreProgress s=progress(p);if(isInRaid(p)&&!RUNS.get(p).cleared){p.sendMessage("Finish the room before claiming rewards.");return;}
        if(PlayerFile.isSaveQuarantined(p)||s.pendingRewards.isEmpty())return;
        List<Item> rewards=new ArrayList<>(s.pendingRewards);int needed=0;
        for(Item item:rewards){ItemDef def=ItemDef.get(item.getId());if(item.getAmount()<=0||def==null)throw new IllegalStateException("Invalid pending Theatre reward");needed+=def.stackable?1:item.getAmount();}
        if(p.getInventory().getFreeSlots()<needed){p.sendMessage("Free "+needed+" inventory slots before claiming.");return;}
        Item[] before=new Item[p.getInventory().getItems().length];for(int i=0;i<before.length;i++){Item item=p.getInventory().get(i);before[i]=item==null?null:item.copy();}
        try{for(Item item:rewards)if(p.getInventory().add(item.copy())!=item.getAmount())throw new IllegalStateException("Theatre reward could not fit");}
        catch(Exception failure){for(int i=0;i<before.length;i++)p.getInventory().set(i,before[i]);p.sendMessage("Your rewards could not fit. The original inventory was restored.");return;}
        s.pendingRewards.clear();if(save(p))p.sendMessage("Your Theatre rewards have been claimed and saved.");
    }
    private TheatreOfBloodRaid(){}
}
