package io.ruin.model.activities.dungeoneering;

import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.activities.raids.auto.RaidAuto;
import io.ruin.model.activities.raids.auto.RaidAutoActions;
import io.ruin.model.activities.raids.toa.port.TombsOfAmascutRaid;
import io.ruin.model.activities.raids.tob.port.TheatreOfBloodRaid;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.player.PlayerGroup;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.item.Item;
import io.ruin.model.map.Position;
import io.ruin.model.map.ground.GroundItem;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.stat.StatType;
import io.ruin.network.incoming.Incoming;
import io.ruin.network.incoming.handlers.ActionButtonHandler;
import java.util.*;

/** Owner-only native iDungeon adaptation. Controllers are world-thread-local and never saved. */
public final class IDungeon {
    private static final Map<Player,Run> ACTIVE=new IdentityHashMap<>();
    private static final Map<Player,String> LAST=new WeakHashMap<>();
    private IDungeon(){}
    public static boolean owner(Player p){return p!=null&&p.isGroup(PlayerGroup.OWNER);}
    static IDungeonSettings settings(Player p){if(!owner(p))throw new IllegalStateException("Owner required");DungeoneeringProgress s=Dungeoneering.progress(p);if(s.idungeon==null)s.idungeon=new IDungeonSettings();s.idungeon.normalize();return s.idungeon;}
    public static boolean active(Player p){return ACTIVE.containsKey(p);}
    public static String status(Player p){Run r=ACTIVE.get(p);return r==null?LAST.getOrDefault(p,"Stopped"):"Running: "+r.reason+" ("+r.completed+" floors)";}
    public static boolean command(Player p,String token,String[] args){
        if(!token.equalsIgnoreCase("idungopen")&&!token.equalsIgnoreCase("idung"))return false;
        if(!owner(p)){p.sendMessage("iDungeon is available only to the owner.");return true;}
        args=args==null?new String[0]:Arrays.stream(args).filter(s->!s.isEmpty()).toArray(String[]::new);
        try{
            if(token.equalsIgnoreCase("idungopen")){if(args.length!=0){usage(p);return true;}IDungeonInterface.open(p);return true;}
            String sub=args.length==0?"status":args[0].toLowerCase(Locale.ROOT);
            if(args.length>1){usage(p);return true;}
            switch(sub){case "open":IDungeonInterface.open(p);break;case "start":start(p);break;case "stop":stop(p,"Stopped by owner",true);break;case "leave":leave(p);break;case "status":p.sendMessage("iDungeon: "+status(p)+".");break;default:usage(p);}
        }catch(Exception e){stop(p,"Stopped: "+message(e),true);p.sendMessage("iDungeon: "+message(e));Server.logError("Owner iDungeon command",e);}return true;
    }
    private static void usage(Player p){p.sendMessage("Use ::idungopen or ::idung start|stop|status|leave.");}
    static void start(Player p){
        if(!owner(p)||!p.isOnline())return;
        if(active(p)){p.sendMessage("iDungeon: "+status(p));return;}
        if(!Dungeoneering.available())throw new IllegalStateException("Dungeoneering is not available on this world");
        if(p.isLocked()||p.getHp()<1||p.getCombat().isDead()||PlayerFile.isSaveQuarantined(p))throw new IllegalStateException("Wait until you are alive, unlocked and your save is confirmed");
        if(RaidAuto.isActive(p)||TombsOfAmascutRaid.isInRaid(p)||TheatreOfBloodRaid.isInRaid(p)||io.ruin.model.activities.hollowdepths.HollowDepths.inRun(p))throw new IllegalStateException("Leave the current activity before starting iDungeon");
        Dungeoneering.Party party=Dungeoneering.party(p);
        if(party!=null&&(party.leader!=p||party.members.size()!=1))throw new IllegalStateException("iDungeon requires your own solo dungeon party");
        Run run=new Run(p,settings(p).copy());ACTIVE.put(p,run);LAST.remove(p);
        p.sendMessage("iDungeon started. Use ::idung stop or make any manual gameplay action to take control.");schedule(run);
    }
    public static boolean stop(Player p,String reason,boolean notify){
        Run r=ACTIVE.remove(p);if(r==null)return false;
        LAST.put(p,reason);
        // Cancel the normal crafting/fishing/route event as well as its target,
        // retaining the menu that an incoming manual click is about to use.
        // Locked native saves/traversals finish under their original lifecycle.
        if(!p.isLocked())p.resetActions(false,true,true);else p.getCombat().reset();
        if(notify&&p.isOnline())p.sendMessage("iDungeon: "+reason+".");return true;
    }
    static void leave(Player p){
        stop(p,"Safe exit requested",false);
        if(!Dungeoneering.inRun(p)){p.sendMessage("You are outside a dungeon. iDungeon is stopped.");return;}
        Run r=new Run(p,settings(p).copy());r.leaving=true;ACTIVE.put(p,r);schedule(r);
    }
    public static void onLogout(Player p){stop(p,"Logged out",false);LAST.remove(p);}
    public static void manualInput(Player p,Incoming handler,int opcode){if(active(p)&&RaidAuto.isGameplayInput(handler))stop(p,"Stopped for manual control",true);}
    private static void schedule(Run r){World.startEvent(event->{event.delay(1);while(ACTIVE.get(r.p)==r){try{if(!r.pulse()){stop(r.p,r.reason,true);return;}}catch(Exception failure){stop(r.p,"Stopped: "+message(failure),true);Server.logError("Owner iDungeon stopped",failure);return;}event.delay(1);}});}
    private static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}

    static final class Run {
        final Player p;final IDungeonSettings c;final IDungeonActions a;final IDungeonCombatPlanner combat;
        final IDungeonPuzzlePlanner puzzles;final IDungeonBossPlanner bosses;
        final long started=Server.currentTick();long lastProgress=started,entrySince=started;
        int completed,initialDeaths,desiredFloor=-1;String reason="Preparing solo dungeon",signature="",claimedRun;
        DungeoneeringFloor floor;DungeoneeringFloor.RoomState room;
        boolean leaving,retaliationReady,resetPending;
        Run(Player p,IDungeonSettings c){this.p=p;this.c=c;a=new IDungeonActions(p);combat=new IDungeonCombatPlanner(p,c,a);puzzles=new IDungeonPuzzlePlanner(p,a);bosses=new IDungeonBossPlanner(p,c,a,combat);}
        boolean pulse()throws Exception{
            long tick=Server.currentTick();
            if(!owner(p)||!p.isOnline()||p.logoutStage!=0){reason="Owner logged out or permission ended";return false;}
            if(World.updating){reason="World update";return false;}
            if(RaidAuto.isActive(p)||TombsOfAmascutRaid.isInRaid(p)||TheatreOfBloodRaid.isInRaid(p)||io.ruin.model.activities.hollowdepths.HollowDepths.inRun(p)){reason="Another activity took control";return false;}
            if(p.getHp()<1||p.getCombat().isDead()){reason="Stopped on death";return false;}
            if(c.maximumMinutes>0&&tick-started>=c.maximumMinutes*100L){reason="Session time limit reached";return false;}
            if(PlayerFile.isSaveQuarantined(p)){reason="Waiting for native durable save confirmation";if(tick-lastProgress>100){reason="Save confirmation is pending; take manual control";return false;}return true;}
            if(leaving){if(tick-started>100){reason="Normal exit timed out; take manual control";return false;}if(!Dungeoneering.inRun(p)){reason="Original equipment restored by normal dungeon exit";return false;}if(p.isLocked())return true;Dungeoneering.open(p);if(IDungeonActions.select(p,"Abandon floor")){confirm(p);}reason="Normal dungeon exit pending";return true;}
            if(p.isLocked())return true;
            Dungeoneering.Party party=Dungeoneering.party(p);
            if(party!=null&&(party.leader!=p||party.members.size()!=1)){reason="Solo party changed; take manual control";return false;}
            if(!Dungeoneering.inRun(p)){
                if(floor!=null){
                    if(!floor.id.equals(Dungeoneering.progress(p).lastRewardRun)){reason="Dungeon ended without a verified native reward claim";return false;}
                    completed++;floor=null;room=null;desiredFloor=-1;claimedRun=null;entrySince=tick;lastProgress=tick;
                    if(!c.repeat||c.maximumFloors>0&&completed>=c.maximumFloors){reason="Completed floor limit reached; original equipment restored";return false;}
                }
                return entry(tick,party);
            }
            DungeoneeringFloor live=Dungeoneering.floor(p);
            if(live==null||live.destroyed){reason="Native dungeon ended";return false;}
            if(!live.contains(p)||p.getMovement().isTeleportQueued())return true;
            if(floor==null){floor=live;initialDeaths=live.deaths.getOrDefault(p.getUserId(),0);retaliationReady=false;lastProgress=tick;}
            else if(floor!=live){reason="Dungeon identity changed; take manual control";return false;}
            int deaths=live.deaths.getOrDefault(p.getUserId(),0)-initialDeaths;
            if(deaths>0&&(c.stopOnDeath||deaths>c.maximumDeaths)){reason="Stopped after a native dungeon death";return false;}
            DungeoneeringFloor.RoomState current=live.room(p.getPosition());if(current==null)return true;
            if(room!=current){room=current;puzzles.enter(room);combat.enter(room);bosses.enter(room);signature="";lastProgress=tick;}
            String observed=progress();if(!observed.equals(signature)){signature=observed;lastProgress=tick;}
            if(tick-lastProgress>=c.roomTimeout){reason="Room progress timed out; take manual control";return false;}
            if(!retaliationReady){if(Config.AUTO_RETALIATE.get(p)==0){RaidAutoActions.button(p,593,30,-1,1,-1);return true;}retaliationReady=true;}
            reason="Floor "+live.party.floor+", room "+room.node.id+" / "+live.rooms.length;
            if(combat.sustain())return true;
            if(live.complete&&(!c.explore||frontier(room)==null)){
                if(claimedRun==null){Dungeoneering.open(p);if(IDungeonActions.select(p,"Claim floor rewards and leave"))claimedRun=live.id;}
                reason="Claiming earned XP/tokens through the normal dungeon exit";return true;
            }
            if(loot(true))return true;
            if(room.node.boss&&!live.complete){reason+=" - boss mechanics";if(!bosses.pulse(room))throw new IllegalStateException("Native boss action unavailable; take manual control");return true;}
            NPC guardian=room.guardians.stream().filter(n->!n.isRemoved()&&n.getHp()>0&&!n.getCombat().isDead()).min(Comparator.comparingInt(n->distance(p.getPosition(),n.getPosition()))).orElse(null);
            if(guardian!=null){reason+=" - guardians";return combat.attack(room,guardian,null);}
            if(room.puzzle!=null&&(!room.puzzle.complete||room.puzzle instanceof DungeoneeringMaze&&!((DungeoneeringMaze)room.puzzle).looted)){
                reason+=" - "+room.template.puzzle;if(!puzzles.pulse(room))throw new IllegalStateException("Native puzzle action unavailable; take manual control");return true;
            }
            if(loot(false))return true;
            if(combat.prepare(room))return true;
            if(c.cookFood&&combat.replaceFood(room))return true;
            int[] next=frontier(room);
            if(next==null){if(live.complete){Dungeoneering.open(p);IDungeonActions.select(p,"Claim floor rewards and leave");return true;}throw new IllegalStateException("No reachable frontier: check locked doors, current skill levels and supplies");}
            GameObject door=door(room,next[1]);if(door==null)throw new IllegalStateException("The current native doorway is missing");
            if(!a.object(room,door,1))throw new IllegalStateException("No native route to the next dungeon door");return true;
        }
        private boolean entry(long tick,Dungeoneering.Party party){
            if(tick-entrySince>200){reason="Native entry did not complete; take manual control";return false;}
            if(p.wildernessLevel!=0||p.getPosition().getX()>=6400||p.getDuel().stage!=0||p.joinedTournament||p.supplyChestRestricted||p.getCombat().isDefending(10))throw new IllegalStateException("Start iDungeon from a safe normal location outside combat");
            if(resetPending){if(p.yesNoDialogue!=null&&p.isVisibleInterface(Interface.YES_NO_DIALOGUE)){confirm(p);resetPending=false;}return true;}
            if(desiredFloor<0){
                int unlocked=Boolean.getBoolean("kronos.dungeoneering.staffTesting")?60:Dungeoneering.progress(p).unlockedFloor();
                for(int f=c.firstFloor;f<=Math.min(c.lastFloor,unlocked);f++)if(!Dungeoneering.progress(p).completedFloors.contains(f)){desiredFloor=f;break;}
                if(desiredFloor<0){if(c.repeat&&c.prestige&&!Dungeoneering.progress(p).completedFloors.isEmpty()&&c.firstFloor<=unlocked){Dungeoneering.open(p);if(IDungeonActions.select(p,"Reset prestige"))resetPending=true;reason="Normal prestige reset";return true;}reason="Configured floor range is completed or not unlocked";return false;}
            }
            Dungeoneering.open(p);
            if(party==null){IDungeonActions.select(p,"Create party / play solo");return true;}
            if(party.floor!=desiredFloor){if(IDungeonActions.select(p,"Floor "+party.floor+" ("))IDungeonActions.select(p,"Floor "+desiredFloor+" -");return true;}
            int complexity=c.rush&&desiredFloor<=c.rushThrough?c.rushComplexity:c.complexity;
            if(party.complexity!=complexity){if(IDungeonActions.select(p,"Complexity "))IDungeonActions.select(p,"Complexity "+complexity);return true;}
            if(party.size!=c.size){if(IDungeonActions.select(p,"Complexity "))IDungeonActions.select(p,new String[]{"Small","Medium","Large"}[c.size]+" dungeon");return true;}
            p.closeInterface(InterfaceType.MAIN);Dungeoneering.open(p);IDungeonActions.select(p,"Start dungeon");reason="Starting floor "+desiredFloor+" through native escrow/save";return true;
        }
        private boolean loot(boolean required){
            List<GroundItem> items=IDungeonActions.ground(room);items.sort(Comparator.comparingInt(g->distance(p.getPosition(),new Position(g.x,g.y,g.z))));
            for(GroundItem g:items){int src=floor.assets.sourceItem(g.id);boolean key=src>=18202&&src<=18328&&(src-18202)%2==0;
                if(key){if(a.pickup(room,g))return true;continue;}
                if(required){
                    boolean tool=src==16295||src==17678||src==17754||src==17883;
                    if(tool&&combat.want(g.id)&&p.getInventory().getFreeSlots()>0&&a.pickup(room,g))return true;
                    continue;
                }
                if(combat.want(g.id)&&p.getInventory().getFreeSlots()>2||p.getInventory().hasId(g.id)&&io.ruin.cache.ItemDef.get(g.id).stackable&&combat.want(g.id)){if(a.pickup(room,g))return true;}
            }return false;
        }
        private String progress(){StringBuilder s=new StringBuilder().append(room.node.id).append(':').append(floor.openedCount()).append(':').append(floor.keys.size()).append(':').append(room.dead.size()).append(':').append(puzzles.progress(room));for(NPC n:room.bosses)s.append(':').append(n.getHp()).append(':').append(n.getId());int count=0;for(Item i:p.getInventory().getItems())if(i!=null)count++;return s.append(':').append(count).toString();}
        private int[] frontier(DungeoneeringFloor.RoomState from){
            ArrayDeque<Integer> q=new ArrayDeque<>();Map<Integer,Integer> prev=new HashMap<>();q.add(from.node.id);prev.put(from.node.id,-1);Integer goal=null,target=null;
            while(!q.isEmpty()){
                int id=q.remove();DungeoneeringFloor.RoomState r=floor.rooms[id];List<Integer> neighbours=new ArrayList<>(r.node.neighbours);
                neighbours.sort(Comparator.comparingInt(n->floor.rooms[n].node.critical?0:1));
                for(int n:neighbours){DungeoneeringFloor.Edge edge=floor.edges.get(edgeKey(id,n));if(edge==null||!passable(r,n,edge))continue;
                    if(!floor.rooms[n].opened){goal=id;target=n;break;}
                    if(prev.containsKey(n))continue;prev.put(n,id);q.add(n);
                }if(goal!=null)break;
            }
            if(goal==null)return null;if(goal==from.node.id)return new int[]{goal,target};int step=goal;while(prev.get(step)!=from.node.id)step=prev.get(step);return new int[]{from.node.id,step};
        }
        private boolean passable(DungeoneeringFloor.RoomState r,int next,DungeoneeringFloor.Edge e){
            if(e.open)return true;if(e.key>=0&&!floor.keys.contains(e.key)||e.guardian&&r.aliveGuardians()>0||r.puzzle!=null&&!r.puzzle.complete&&floor.rooms[next].node.parent==r.node.id)return false;
            if(e.skill>=0){StatType[] types={StatType.Runecrafting,StatType.Strength,StatType.Mining,StatType.Firemaking,StatType.Magic,StatType.Prayer,StatType.Woodcutting,StatType.Smithing,StatType.Crafting,StatType.Agility,StatType.Thieving,StatType.Herblore,StatType.Farming,StatType.Construction};int level=e.skill==types.length?DungeoneeringSummoning.level(p):DungeoneeringSkills.effectiveLevel(p,types[e.skill]);if(level<e.level)return false;}return true;
        }
        private GameObject door(DungeoneeringFloor.RoomState r,int next){int direction=floor.graph.direction(r.node.id,next);Position at=r.unrotatedTile(direction==0?0:direction==2?15:7,direction==1?15:direction==3?0:7);for(GameObject o:r.objects())if(o.type==10&&o.getPosition().equals(at))return o;return null;}
        private long edgeKey(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    }
    static void confirm(Player p){if(IDungeonActions.ready(p)&&p.yesNoDialogue!=null&&p.isVisibleInterface(Interface.YES_NO_DIALOGUE))ActionButtonHandler.handleAction(p,1,Interface.YES_NO_DIALOGUE<<16,1,-1,false);}
    static int distance(Position a,Position b){return Math.max(Math.abs(a.getX()-b.getX()),Math.abs(a.getY()-b.getY()));}
}
