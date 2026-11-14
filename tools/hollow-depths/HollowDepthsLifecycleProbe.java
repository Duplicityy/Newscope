package io.ruin.model.activities.hollowdepths;

import com.newscape.dungeons.*;
import com.newscape.dungeons.DungeonLayout.*;
import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.combat.*;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.StepType;
import io.ruin.model.map.Position;
import io.ruin.model.stat.StatType;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.model.inter.utils.Config;
import io.ruin.api.utils.JsonUtils;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Stage-only acceptance probe. Fixture stats/healing/kills; real map, movement, actions and hit/death pipelines. */
public final class HollowDepthsLifecycleProbe {
    private static Path report;
    private static int assertions, steps, kills;
    private static long tickSamples,tickTotalMs,maxSampledTickMs,slowTicksAtStart;
    private static Player one,two;
    private static Path stageDirectory;
    private static boolean heal=true, killWhileWalking;
    private static <T>T world(Callable<T> task)throws Exception{return Server.worker.getExecutor().submit(task).get(30,TimeUnit.SECONDS);}
    private static void check(boolean pass,String label){assertions++;if(!pass)throw new AssertionError(label);}
    private static void note(String text)throws Exception{Files.write(report,(text+"\n").getBytes("UTF-8"),StandardOpenOption.CREATE,StandardOpenOption.APPEND);System.out.println("HOLLOW DEPTHS LIFECYCLE "+text);}
    private static void requestCapture(Player p,String phase)throws Exception{
        Path commands=stageDirectory.resolve("captures-"+p.getName().toLowerCase()).resolve("commands");Files.createDirectories(commands);
        Path temporary=commands.resolve(phase+".tmp"),target=commands.resolve(phase+".cmd");
        Files.write(temporary,"action=capture\nwaitMs=0\nsettleMs=0\n".getBytes("UTF-8"));Files.move(temporary,target);
    }
    private static void tick()throws Exception{
        if(Files.exists(report.resolveSibling("native-lifecycle-probe.cancel")))throw new CancellationException("Native lifecycle probe cancelled");
        long before=world(()->Server.currentTick());
        for(int n=0;n<100;n++){
            Thread.sleep(50);
            long[] observed=world(()->{if(heal){if(one!=null)one.setHp(one.getMaxHp());if(two!=null)two.setHp(two.getMaxHp());}return new long[]{Server.currentTick(),Server.worker.getLastExecutionTime()};});
            if(observed[0]>before){tickSamples++;tickTotalMs+=observed[1];maxSampledTickMs=Math.max(maxSampledTickMs,observed[1]);return;}
        }
        throw new AssertionError("World tick stalled");
    }
    private static Player player(String name){for(Player p:World.players)if(name.equalsIgnoreCase(p.getName())&&p.isOnline())return p;throw new IllegalStateException(name+" must be online");}
    private static DungeonService.RunView run(Player p){DungeonService.RunView r=HollowDepths.inspect(p);if(r==null)throw new AssertionError("Active run missing for "+p.getName());return r;}
    private static HollowDepthsMap map(Player p){return HollowDepths.port.maps.get(run(p).instanceId);}
    private static void fixture(Player p){
        check(p.getName().matches("(?i)hdqa[12]"),"Disposable character only");check(!HollowDepths.inRun(p),"Fixture requires lobby");
        p.closeDialogue();p.resetActions(true,true,true);p.getCombat().reset();p.clearHits();
        Config.AUTO_RETALIATE.set(p,1); // controlled fixtures must not kill the boss between phase checks
        for(int i=0;i<23;i++){io.ruin.model.stat.Stat s=p.getStats().get(i);s.fixedLevel=s.currentLevel=99;s.experience=13034431;s.updated=true;}
        p.getMovement().teleport(HollowDepthsGamePort.LOBBY);p.setHp(p.getMaxHp());
    }
    private static HollowDepthsMap enter(Player p)throws Exception{
        final long requestedAt=System.currentTimeMillis();
        for(int i=0;i<30&&world(()->HollowDepths.port.entryDenial(HollowDepths.account(p),Rewards.Tier.NOVICE)!=null);i++)tick();
        world(()->{check(HollowDepths.port.entryDenial(HollowDepths.account(p),Rewards.Tier.NOVICE)==null,"Real portal entry preconditions");return null;});
        boolean accepted=false;for(int attempt=0;attempt<20&&!accepted;attempt++){
            DungeonService.Result result=world(()->HollowDepths.service.clickEntrancePortal(HollowDepths.account(p),Rewards.Tier.NOVICE));
            check(result==DungeonService.Result.ACCEPTED||result==DungeonService.Result.BUSY,"Portal entry accepted or authentic cooldown");accepted=result==DungeonService.Result.ACCEPTED;if(!accepted)tick();
        }check(accepted,"Portal request accepted");
        for(int i=0;i<100;i++){tick();HollowDepthsMap ready=world(()->{DungeonService.RunView r=HollowDepths.inspect(p);return r!=null&&r.state==DungeonService.State.ACTIVE?HollowDepths.port.maps.get(r.instanceId):null;});if(ready!=null){waitClientFrame(p,ready,requestedAt);note("Entered with completed184 frame "+p.getName()+" run="+ready.identity.runId+" seed="+ready.layout.seed);return ready;}}
        throw new AssertionError("Native entry did not become ACTIVE");
    }
    private static void waitClientFrame(Player p,HollowDepthsMap m,long after)throws Exception{
        Path captures=stageDirectory.resolve("captures-"+p.getName().toLowerCase());long deadline=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<deadline){
            if(Files.exists(captures.resolve("client-fatal.txt")))throw new AssertionError("Actual184 client failed at entry: "+new String(Files.readAllBytes(captures.resolve("client-fatal.txt")),"UTF-8").split("\n",2)[0]);
            try(DirectoryStream<Path> files=Files.newDirectoryStream(captures,"auto-instance-*.state.txt")){
                for(Path file:files)if(Files.getLastModifiedTime(file).toMillis()>=after){
                    Properties state=new Properties();try(java.io.Reader reader=Files.newBufferedReader(file)){state.load(reader);}
                    if(state.getProperty("playerX")==null)continue;
                    Position at=new Position(Integer.parseInt(state.getProperty("playerX")),Integer.parseInt(state.getProperty("playerY")),Integer.parseInt(state.getProperty("playerPlane")));
                    Path png=file.resolveSibling(file.getFileName().toString().replace(".state.txt",".png"));
                    if(m.local(at)!=null&&Files.isRegularFile(png)&&Files.size(png)>1024){check(true,"Actual184 completed instance frame observed");return;}
                }
            }tick();
        }
        throw new AssertionError("No completed actual184 instance frame within45seconds; stopping unseen lifecycle");
    }
    private static void untilGone(Player p)throws Exception{for(int i=0;i<40;i++){if(world(()->!HollowDepths.inRun(p)))return;tick();}throw new AssertionError("Run cleanup did not finish");}
    private static Tile current(Player p,HollowDepthsMap m){return m.local(p.getPosition());}
    private static int clip(HollowDepthsMap m,Tile t){Position p=m.world(t);io.ruin.model.map.Tile tile=io.ruin.model.map.Tile.get(p.getX(),p.getY(),p.getZ());return tile.clipping;}
    private static HollowDepthsMap.ObjectHandle doorAt(HollowDepthsMap m,Tile t){for(HollowDepthsMap.ObjectHandle o:m.objects())if(o.kind==HollowDepthsMap.ObjectKind.DOOR&&o.local.equals(t))return o;return null;}
    private static List<Tile> route(Player p,HollowDepthsMap m,Tile target){
        Tile start=current(p,m);Map<Tile,Tile> previous=new HashMap<>();ArrayDeque<Tile> queue=new ArrayDeque<>();previous.put(start,null);queue.add(start);
        DungeonService.RunView r=run(p);
        while(!queue.isEmpty()){
            Tile at=queue.remove();if(at.equals(target))break;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){Tile next=at.translate(d[0],d[1]);
                if(previous.containsKey(next)||!m.layout.walkable(next)||(r.guardians<3&&m.layout.room(m.layout.bossRoomId).contains(next)))continue;
                if(clip(m,next)!=0&&doorAt(m,next)==null)continue;
                previous.put(next,at);queue.add(next);
            }
        }
        if(!previous.containsKey(target))throw new AssertionError("No native floor/door path "+start+" -> "+target);
        List<Tile> path=new ArrayList<>();for(Tile at=target;!at.equals(start);at=previous.get(at))path.add(at);Collections.reverse(path);return path;
    }
    private static void nativeKills(Player p,HollowDepthsMap m){
        for(HollowDepthsGamePort.Actor actor:new ArrayList<>(HollowDepths.port.actors.values()))
            if(actor.map==m&&!actor.retired&&!actor.ticket.gauntletGuard&&actor.npc.getHp()>0&&HollowDepths.allowCombat(p,actor.npc)){
                actor.npc.hit(new Hit(p,AttackStyle.CRUSH,AttackType.ACCURATE).fixedDamage(actor.npc.getHp()).ignoreDefence().ignorePrayer());kills++;
            }
    }
    private static void walk(Player p,HollowDepthsMap m,Tile target)throws Exception{
        int stalled=0;Tile previous=null;
        for(int loops=0;loops<400;loops++){
            Tile at=world(()->current(p,m));if(target.equals(at))return;
            if(at.equals(previous)){if(++stalled>15)throw new AssertionError("Native walk stalled at "+at+" toward "+target);}else{stalled=0;previous=at;}
            world(()->{
                check(run(p).state==DungeonService.State.ACTIVE,"Walking retains active run");
                if(killWhileWalking)nativeKills(p,m);
                if(HollowDepths.port.motions.containsKey(p))return null;
                List<Tile> path=route(p,m,target);if(path.isEmpty())return null;
                HollowDepthsMap.ObjectHandle door=doorAt(m,path.get(0));
                if(door!=null){door.object.actions[0].handle(p,door.object);return null;}
                p.resetActions(false,true,true);int count=0;
                for(Tile step:path){if(count>=40||doorAt(m,step)!=null)break;Position pos=m.world(step);p.getMovement().getStepsX()[count]=pos.getX();p.getMovement().getStepsY()[count]=pos.getY();count++;}
                p.getMovement().writeOffset=count;p.getMovement().stepType=StepType.NORMAL;p.getMovement().setCtrlRun(true);p.energyUnits=10000;steps++;return null;
            });tick();
        }
        throw new AssertionError("Native walk exceeded budget");
    }
    private static Tile adjacent(HollowDepthsMap m,Tile target){for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){Tile t=target.translate(d[0],d[1]);if(m.layout.walkable(t)&&clip(m,t)==0)return t;}throw new AssertionError("No adjacent native floor: "+target);}
    private static void settled(Player p,HollowDepthsMap m,Tile destination)throws Exception{
        for(int i=0;i<15;i++){tick();if(world(()->destination.equals(current(p,m))&&!HollowDepths.port.motions.containsKey(p)))return;}
        throw new AssertionError("Motion failed to finish at "+destination);
    }
    private static void door(Player p,HollowDepthsMap m,String key,Tile from,Tile to)throws Exception{
        walk(p,m,from);for(int i=0;i<2;i++)tick();
        HollowDepthsMap.ObjectHandle object=world(()->{for(HollowDepthsMap.ObjectHandle o:m.objects())if(o.key.equals(key))return o;throw new AssertionError("Door missing "+key);});
        world(()->{check(object.object.id==HollowDepths.port.assets.objectId("door.closed"),"Door never auto opens by proximity");object.object.actions[0].handle(p,object.object);return null;});
        settled(p,m,to);world(()->{check(object.object.id==HollowDepths.port.assets.objectId("door.closed"),"Door closes after native passage");return null;});
    }
    private static void traps(Player p,HollowDepthsMap m)throws Exception{
        TrapSpawn trap=null;for(Room room:m.layout.rooms)if(!room.traps.isEmpty()){trap=room.traps.get(0);break;}check(trap!=null,"Generated trap present");final TrapSpawn t=trap;
        killWhileWalking=false;
        walk(p,m,t.trigger);
        world(()->{p.getStats().get(StatType.Agility).fixedLevel=p.getStats().get(StatType.Agility).currentLevel=1;
            check(HollowDepths.service.clickAgilityTrap(HollowDepths.account(p),m.identity.runId,t.slot)==DungeonService.Result.DENIED,"Base Agility1 denied shortcut");return null;});
        door(p,m,"trap:"+t.slot+":entry",t.gauntlet.entranceOutside,t.gauntlet.entranceInside);
        tick();
        door(p,m,"trap:"+t.slot+":exit",t.gauntlet.exitInside,t.gauntlet.exitOutside);
        world(()->{int guards=0;for(HollowDepthsGamePort.Actor actor:HollowDepths.port.actors.values())if(actor.map==m&&!actor.retired){
            boolean guard=actor.ticket.gauntletGuard&&t.gauntlet.contains(actor.ticket.tile);
            if(guard){guards++;check(t.gauntlet.contains(m.local(actor.npc.getPosition())),"Gauntlet guard remains inside leash");check(!HollowDepths.allowCombat(actor.npc,p),"Gauntlet guard cannot attack outside owner");Position out=m.world(t.gauntlet.exitOutside);check(!HollowDepths.allowNpcRelocation(actor.npc,out.getX(),out.getY(),out.getZ()),"Gauntlet native relocation rejects escape");}
        }check(guards>0,"Live native gauntlet guards exercised");return null;});
        walk(p,m,t.landing);world(()->{check(HollowDepths.service.clickAgilityTrap(HollowDepths.account(p),m.identity.runId,t.slot)==DungeonService.Result.DENIED,"Agility1 reverse shortcut denied");return null;});
        door(p,m,"trap:"+t.slot+":exit",t.gauntlet.exitOutside,t.gauntlet.exitInside);
        door(p,m,"trap:"+t.slot+":entry",t.gauntlet.entranceInside,t.gauntlet.entranceOutside);
        note("Agility1 guarded route passed in both directions; native doors closed behind owner.");
        // Interrupt the real door motion through the same lifecycle hooks used by logout/login.
        for(int i=0;i<3;i++)tick(); // honor the normal interaction cooldown after the last passage
        world(()->{check(HollowDepths.service.clickBypassDoor(HollowDepths.account(p),m.identity.runId,"trap:"+t.slot+":entry")==DungeonService.Result.ACCEPTED,"Door interruption setup");HollowDepths.onLogout(p);HollowDepths.onLogin(p);return null;});
        settled(p,m,t.gauntlet.entranceOutside);world(()->{check(run(p).state==DungeonService.State.ACTIVE,"Interrupted door resumes same active run");return null;});
        walk(p,m,t.trigger);world(()->{p.getStats().get(StatType.Agility).fixedLevel=p.getStats().get(StatType.Agility).currentLevel=99;return null;});
        for(Tile destination:Arrays.asList(t.landing,t.trigger)){
            boolean crossed=false;for(int attempt=0;attempt<30&&!crossed;attempt++){
                world(()->{HollowDepths.service.clickAgilityTrap(HollowDepths.account(p),m.identity.runId,t.slot);return null;});
                for(int i=0;i<4;i++)tick();crossed=world(()->destination.equals(current(p,m))&&!HollowDepths.port.motions.containsKey(p));
            }check(crossed,"Agility99 native forced movement crosses both directions");
        }
        boolean interrupted=false;
        for(int attempt=0;attempt<30&&!interrupted;attempt++){
            interrupted=world(()->{HollowDepths.service.clickAgilityTrap(HollowDepths.account(p),m.identity.runId,t.slot);
                if(!HollowDepths.port.motions.containsKey(p))return false;HollowDepths.onLogout(p);HollowDepths.onLogin(p);return true;});
            for(int i=0;i<4;i++)tick();
        }
        check(interrupted,"Agility motion interruption exercised");settled(p,m,t.trigger);
        killWhileWalking=true;
        note("Agility99 crossing passed both directions; interrupted native doorway restored safe tile and run.");
    }
    private static void boundary(Player p,Player stranger,HollowDepthsMap m)throws Exception{
        Room combat=null;for(Room r:m.layout.rooms)if(!r.npcs.isEmpty()&&r.kind!=RoomKind.BOSS){combat=r;break;}final Room room=combat;
        walk(p,m,adjacent(m,room.center()));for(int i=0;i<3;i++)tick();
        world(()->{
            HollowDepthsGamePort.Actor actor=null;for(HollowDepthsGamePort.Actor a:HollowDepths.port.actors.values())if(a.map==m&&!a.retired&&a.ticket.roomId==room.id){actor=a;break;}
            check(actor!=null,"Native actor spawned on room discovery");
            check(!HollowDepths.allowCombat(stranger,actor.npc)&&!HollowDepths.allowCombat(actor.npc,stranger),"Cross-account native attack targeting denied");
            check(!new Hit(stranger,AttackStyle.MAGIC).fixedDamage(1).defend(actor.npc),"Cross-account hit rejected at native launch");
            check(!HollowDepths.allowStep(stranger,p.getAbsX(),p.getAbsY()),"Cross-account movement gate denied");
            stranger.getMovement().teleport(p.getPosition());check(!stranger.getMovement().isTeleportQueued(),"Cross-account native teleport rejected");
            int opened=run(p).openedChests;for(HollowDepthsMap.ObjectHandle o:m.objects())if(o.kind==HollowDepthsMap.ObjectKind.CHEST){o.object.actions[0].handle(stranger,o.object);break;}
            check(run(p).openedChests==opened,"Cross-account bound object callback denied");return null;
        });note("Cross-account targeting, hit launch, movement, teleport and object callbacks rejected.");
        world(()->{
            HollowDepthsGamePort.Actor actor=null;for(HollowDepthsGamePort.Actor candidate:HollowDepths.port.actors.values())if(candidate.map==m&&!candidate.retired&&HollowDepths.allowCombat(candidate.npc,p)){actor=candidate;break;}
            check(actor!=null,"Prayer fixture requires actual owned native NPC");
            AttackStyle[] styles={AttackStyle.CRUSH,AttackStyle.RANGED,AttackStyle.MAGIC};Prayer[] protection={Prayer.PROTECT_FROM_MELEE,Prayer.PROTECT_FROM_MISSILES,Prayer.PROTECT_FROM_MAGIC};
            for(int i=0;i<styles.length;i++){
                p.getPrayer().deactivateAll();p.setHp(p.getMaxHp());
                Hit ordinary=new Hit(actor.npc,styles[i]).fixedDamage(20).ignoreDefence().delay(0);check(ordinary.defend(p)&&ordinary.damage>0,"Unprotected native NPC-owned hit retains damage");ordinary.finish(p);
                p.getPrayer().toggle(protection[i]);check(p.getPrayer().isActive(protection[i]),"Actual protection prayer activated");
                Hit protectedHit=new Hit(actor.npc,styles[i]).fixedDamage(20).ignoreDefence().delay(0);check(protectedHit.defend(p)&&protectedHit.damage==0,"Actual native protection prayer blocks matching NPC style");protectedHit.finish(p);
            }p.getPrayer().deactivateAll();p.setHp(p.getMaxHp());return null;
        });note("Controlled native NPC-owned melee/ranged/magic hits: unprotected damage>0; matching actual protection prayers reduce each to0. Accuracy was bypassed for deterministic protection assertions.");
    }
    private static void clearRoom(Player p,HollowDepthsMap m,Room room)throws Exception{
        walk(p,m,adjacent(m,room.center()));for(int i=0;i<12;i++){
            boolean alive=world(()->{nativeKills(p,m);for(HollowDepthsGamePort.Actor a:HollowDepths.port.actors.values())if(a.map==m&&!a.retired&&a.ticket.roomId==room.id&&a.npc.getHp()>0)return true;return false;});
            tick();if(!alive&&i>=2)break;
        }
        for(ChestSpawn chest:room.chests){walk(p,m,adjacent(m,chest.tile));for(int i=0;i<2;i++)tick();
            world(()->{HollowDepthsMap.ObjectHandle found=null;for(HollowDepthsMap.ObjectHandle o:m.objects())if(o.kind==HollowDepthsMap.ObjectKind.CHEST&&o.slot==chest.slot){found=o;break;}
                check(found!=null,"Chest native callback exists");int before=run(p).openedChests;found.object.actions[0].handle(p,found.object);
                check(run(p).openedChests==before+1,"Native chest callback secures unbanked loot");found.object.actions[0].handle(p,found.object);check(run(p).openedChests==before+1,"Chest replay denied");return null;});
        }
    }
    private static void bossWarning(Player p,HollowDepthsMap m)throws Exception{
        killWhileWalking=false;walk(p,m,adjacent(m,m.layout.room(m.layout.bossRoomId).center()));
        for(int phase=0;phase<3;phase++){
        final int currentPhase=phase;
        world(()->{HollowDepthsGamePort.Actor boss=null;for(HollowDepthsGamePort.Actor actor:HollowDepths.port.actors.values())if(actor.map==m&&!actor.retired&&actor.ticket.boss){boss=actor;break;}
            check(boss!=null,"Native boss exists for phase fixture");int percent=currentPhase==0?100:currentPhase==1?65:30;boss.npc.setHp(Math.max(1,boss.ticket.stats.hp*percent/100));p.getCombat().reset();return null;});
        note("Boss native HP phase="+phase+" (controlled HP threshold fixture; boss remains alive).");
        boolean seen=false;
        for(int i=0;i<40&&!seen;i++){
            seen=world(()->{for(HollowDepthsGamePort.Warning warning:HollowDepths.port.telegraphs.values())if(warning.map==m&&warning.tiles.contains(current(p,m))){check(warning.due>Server.currentTick(),"Native boss warning precedes damage");return true;}return false;});
            if(!seen)tick();
        }
        check(seen,"Native boss floor warning observed");
        requestCapture(p,"native-boss-phase-"+phase+"-"+m.identity.runId.toString().substring(0,8));
        final Set<Hit> before=world(()->new HashSet<>(HollowDepths.port.hazardHits.keySet())),observed=new HashSet<>();heal=false;
        for(int i=0;i<6;i++){tick();world(()->{for(Map.Entry<Hit,HollowDepthsGamePort.HazardHit> hit:HollowDepths.port.hazardHits.entrySet())if(!before.contains(hit.getKey())&&hit.getValue().runId.equals(m.identity.runId))observed.add(hit.getKey());return null;});}heal=true;
        world(()->{check(HollowDepths.inRun(p),"Boss hazard survives with fixture max HP");check(!observed.isEmpty(),"Boss warning resolved through native lease-bound Hit");return null;});
        note("Native boss phase="+phase+" warning preceded due tick and resolved through lease-bound damage.");
        for(int i=0;i<6;i++)tick();
        }killWhileWalking=true;
    }
    private static void fixtureLethalHit(Player p){
        Hit hit=new Hit(null,null).fixedDamage(p.getHp()).ignoreDefence().ignorePrayer().delay(0);
        check(hit.defend(p),"Controlled environmental lethal hit accepted by native defend");
        check(hit.finish(p),"Controlled lethal hit completed through native finish/death");
    }
    private static void assertSafeUnlocked(Player p)throws Exception{
        untilGone(p);world(()->{check(p.getAbsX()==2043&&p.getAbsY()==3576,"Motion death returns safe lobby");check(!p.isLocked(),"No orphan movement/freeze lock after death");check(!p.getMovement().isTeleportQueued(),"No old forced teleport after death");return null;});
    }
    private static void deathDuringMotion(Player p,boolean agility)throws Exception{
        HollowDepthsMap m=enter(p);TrapSpawn nearest=null;int distance=Integer.MAX_VALUE;
        for(Room room:m.layout.rooms)for(TrapSpawn trap:room.traps){int d=trap.trigger.manhattanDistance(m.layout.entry);if(d<distance){distance=d;nearest=trap;}}
        check(nearest!=null,"Motion-death trap exists");final TrapSpawn t=nearest;
        world(()->{p.getStats().get(StatType.Agility).fixedLevel=p.getStats().get(StatType.Agility).currentLevel=99;return null;});
        walk(p,m,agility?t.trigger:t.gauntlet.entranceOutside);
        boolean killed=false;for(int attempt=0;attempt<30&&!killed;attempt++){
            killed=world(()->{
                DungeonService.Result result=agility?HollowDepths.service.clickAgilityTrap(HollowDepths.account(p),m.identity.runId,t.slot):HollowDepths.service.clickBypassDoor(HollowDepths.account(p),m.identity.runId,"trap:"+t.slot+":entry");
                if(!HollowDepths.port.motions.containsKey(p))return false;
                check(result==DungeonService.Result.ACCEPTED&&p.isLocked(),"Real motion permit held before lethal hit");fixtureLethalHit(p);return true;
            });if(!killed)for(int i=0;i<4;i++)tick();
        }
        check(killed,"Lethal native hit during "+(agility?"Agility":"door")+" motion exercised");assertSafeUnlocked(p);
        note("Death during actual "+(agility?"Agility":"door")+" motion passed; native cleanup left lobby unlocked.");
    }
    private static void deathDuringGeneration(Player p)throws Exception{
        for(int i=0;i<18;i++)tick();
        world(()->{check(HollowDepths.service.clickEntrancePortal(HollowDepths.account(p),Rewards.Tier.NOVICE)==DungeonService.Result.ACCEPTED,"Generating-death portal entry");
            check(run(p).state==DungeonService.State.GENERATING&&run(p).instanceId==null,"Lethal fixture precedes map reservation");fixtureLethalHit(p);return null;});
        assertSafeUnlocked(p);note("Death during GENERATING before any instance existed passed; lobby remained unlocked.");
    }
    /** Leaves an authentic run at a bounded real-client interaction checkpoint. */
    public static void preview(String directory,String mode)throws Exception{
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||Integer.getInteger("kronos.port",0)!=14312)throw new IllegalStateException("Isolated stage required");
        Path stage=Paths.get(directory).toRealPath();stageDirectory=stage;if(!Server.dataFolder.toPath().toRealPath().startsWith(stage))throw new IllegalStateException("Stage data mismatch");
        report=stage.resolve("native-preview-"+mode+".txt");Files.deleteIfExists(report);one=world(()->player("hdqa1"));
        note("observedUtc="+java.time.Instant.now());
        note(world(()->"server.build-id="+HollowDepths.port.assets.serverBuildId()+"\ncache.sha256="+HollowDepths.port.assets.cacheSha256()+"\nobservedTick="+Server.currentTick()));
        if(mode.equals("abandon")){
            two=world(()->player("hdqa2"));
            world(()->{for(Player p:new Player[]{one,two}){DungeonService.RunView r=HollowDepths.inspect(p);if(r!=null)check(HollowDepths.service.abandon(HollowDepths.account(p),r.runId)==DungeonService.Result.ACCEPTED,"Normal disposable-run abandonment accepted");}return null;});
            untilGone(one);untilGone(two);note("Both disposable runs abandoned through normal service cleanup.");
        }else if(!mode.equals("status")){
            boolean active=world(()->HollowDepths.inRun(one));
            if(!active){world(()->{fixture(one);return null;});tick();tick();enter(one);}
            HollowDepthsMap m=world(()->map(one));killWhileWalking=mode.equals("chest");
            if(mode.equals("trap")){
                TrapSpawn target=null;int distance=Integer.MAX_VALUE;Tile current=world(()->current(one,m));
                for(Room room:m.layout.rooms)for(TrapSpawn trap:room.traps){int d=current.manhattanDistance(trap.gauntlet.entranceOutside);if(d<distance){distance=d;target=trap;}}
                check(target!=null,"Preview trap exists");walk(one,m,target.gauntlet.entranceOutside);final TrapSpawn trap=target;
                note(world(()->"checkpoint=closed-door\ntrapSlot="+trap.slot+"\ndoorWorld="+m.world(trap.gauntlet.entranceDoor)+"\ntriggerWorld="+m.world(trap.trigger)+"\nlandingWorld="+m.world(trap.landing)));
            }else if(mode.equals("chest")){
                Room selected=null;for(Room room:m.layout.rooms)if(!room.chests.isEmpty()&&room.kind!=RoomKind.BOSS&&room.kind!=RoomKind.TRAP){selected=room;break;}
                check(selected!=null,"Preview chest exists");final Room room=selected;walk(one,m,adjacent(m,room.center()));
                for(int i=0;i<8;i++){world(()->{nativeKills(one,m);return null;});tick();}
                final ChestSpawn chest=room.chests.get(0);walk(one,m,adjacent(m,chest.tile));
                note(world(()->"checkpoint=unopened-chest\nchestSlot="+chest.slot+"\nchestWorld="+m.world(chest.tile)+"\nfixture=Native forced kills cleared this room; chest remains for actual client click."));
            }else throw new IllegalArgumentException("Unknown preview mode");
        }
        note(world(()->{DungeonService.RunView r=HollowDepths.inspect(one);return "account=hdqa1\nworld="+one.getPosition()+"\nlocked="+one.isLocked()+"\nrun="+(r==null?"none":r.runId)+"\ninstance="+(r==null?"none":r.instanceId)+"\nstate="+(r==null?"none":r.state)+"\nopenedChests="+(r==null?0:r.openedChests)+"\n";}));
        note("PASS preview/status checkpoint; control returned to actual client.");
    }
    public static void agentmain(String directory,Instrumentation ignored)throws Exception{
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||Integer.getInteger("kronos.port",0)!=14312)throw new IllegalStateException("Isolated stage required");
        Path stage=Paths.get(directory).toRealPath();stageDirectory=stage;if(!Server.dataFolder.toPath().toRealPath().startsWith(stage))throw new IllegalStateException("Stage data mismatch");
        report=stage.resolve("native-lifecycle-probe.txt");Files.deleteIfExists(report);
        try{
            note("startedUtc="+java.time.Instant.now());
            slowTicksAtStart=world(()->Server.worker.getSlowExecutions());
            note(world(()->"server.build-id="+HollowDepths.port.assets.serverBuildId()+"\ncache.sha256="+HollowDepths.port.assets.cacheSha256()+"\nstartedTick="+Server.currentTick()));
            world(()->{one=player("hdqa1");two=player("hdqa2");check(HollowDepths.port.deploymentDenial()==null,"Audited assets enabled");fixture(one);fixture(two);return null;});tick();tick();
            final Rewards.Bundle priorCredits=world(()->HollowDepths.rewards.available(one));
            HollowDepthsMap first=enter(one),second=enter(two);check(!first.identity.id.equals(second.identity.id),"Independent private leases");
            boundary(one,two,first);killWhileWalking=true;traps(one,first);
            // Native safe death of the second owner, followed by stale-impact rejection and fresh lease.
            Room secondRoom=null;for(Room r:second.layout.rooms)if(!r.npcs.isEmpty()&&r.kind!=RoomKind.BOSS){secondRoom=r;break;}
            killWhileWalking=false;walk(two,second,adjacent(second,secondRoom.center()));for(int i=0;i<3;i++)tick();
            final int oldLease=world(()->second.map.swRegion.dynamicIndex);
            final String inventory=world(()->JsonUtils.GSON_EXPOSE.toJson(two.getInventory())),equipment=world(()->JsonUtils.GSON_EXPOSE.toJson(two.getEquipment()));
            final Hit late=world(()->{for(HollowDepthsGamePort.Actor a:HollowDepths.port.actors.values())if(a.map==second&&!a.retired&&HollowDepths.allowCombat(a.npc,two)){
                Hit delayed=new Hit(a.npc,AttackStyle.MAGIC).fixedDamage(17).ignoreDefence().ignorePrayer().delay(8);two.hit(delayed);
                heal=false;two.hit(new Hit(a.npc,AttackStyle.CRUSH).fixedDamage(two.getHp()).ignoreDefence().ignorePrayer());return delayed;}throw new AssertionError("Native lethal actor missing");});
            untilGone(two);heal=true;
            world(()->{check(inventory.equals(JsonUtils.GSON_EXPOSE.toJson(two.getInventory()))&&equipment.equals(JsonUtils.GSON_EXPOSE.toJson(two.getEquipment())),"Native death preserves inventory and gear");
                check(two.getAbsX()==2043&&two.getAbsY()==3576,"Native death returns exact verified lobby");check(!HollowDepths.allowHit(late,two),"Retired actor impact rejected");
                int hp=two.getHp();for(int i=0;i<12;i++)late.finish(two);check(two.getHp()==hp,"Native late impact finish cannot damage lobby player");return null;});
            HollowDepthsMap fresh=enter(two);check(!fresh.identity.runId.equals(second.identity.runId)&&fresh.map.swRegion.dynamicIndex!=oldLease,"Cleanup creates fresh run and lease identity");
            world(()->{HollowDepths.service.abandon(HollowDepths.account(two),fresh.identity.runId);return null;});untilGone(two);
            note("Native lethal hit preserved equipment/inventory, returned lobby, rejected late impact, and allowed a fresh lease.");
            killWhileWalking=true;deathDuringMotion(two,false);deathDuringMotion(two,true);deathDuringGeneration(two);
            killWhileWalking=true;world(()->{one.getStats().get(StatType.Agility).fixedLevel=one.getStats().get(StatType.Agility).currentLevel=99;return null;});
            for(Room room:first.layout.rooms)if(room.kind==RoomKind.GUARDIAN)clearRoom(one,first,room);
            world(()->{check(run(one).guardians==3,"Three native guardian deaths unlock boss");return null;});
            bossWarning(one,first);
            clearRoom(one,first,first.layout.room(first.layout.bossRoomId));
            walk(one,first,adjacent(first,first.layout.room(first.layout.bossRoomId).center()));for(int i=0;i<2;i++)tick();
            final Rewards.Bundle haul=world(()->{check(run(one).bossDefeated,"Native boss death recorded");Rewards.Bundle result=run(one).unbankedLoot;
                for(HollowDepthsMap.ObjectHandle o:first.objects())if(o.kind==HollowDepthsMap.ObjectKind.EXIT){o.object.actions[0].handle(one,o.object);return result;}throw new AssertionError("Native exit missing");});
            untilGone(one);world(()->{check(!haul.amounts.isEmpty()&&HollowDepths.rewards.available(one).equals(priorCredits.plus(haul)),"Extraction credits exactly the frozen haul once");check(one.getAbsX()==2043&&one.getAbsY()==3576,"Extraction returns exact lobby");return null;});
            note("PASS native lifecycle assertions="+assertions+" movementBatches="+steps+" forcedNpcKills="+kills+" extracted="+haul);
            note("finishedUtc="+java.time.Instant.now()+"\nfinishedTick="+world(()->Server.currentTick()));
            note("nativeWorldTickSamples="+tickSamples+" meanMs="+(tickSamples==0?0:((double)tickTotalMs/tickSamples))+" maxMs="+maxSampledTickMs+" ticksOverPeriod="+world(()->Server.worker.getSlowExecutions()-slowTicksAtStart)+" periodMs="+Server.worker.getPeriod());
            note("Fixture scope: stats99/Agility1, healing, full run energy, auto-retaliate disabled, native forced lethal NPC hits, boss HP thresholds, controlled NORMAL native step queue with clipping enabled; logout/login hooks invoked for interruption. No core run-state reflection or fabricated state transitions. Organic combat duration/pacing and network relogin are separate checks.");
        }catch(Throwable failure){
            note("FAIL "+failure);
            try{
                String snapshot=world(()->{
                    StringBuilder text=new StringBuilder("World tick=").append(Server.currentTick()).append('\n');
                    for(Player p:new Player[]{one,two})if(p!=null){
                        text.append(p.getName()).append(" xyz=").append(p.getAbsX()).append(',').append(p.getAbsY()).append(',').append(p.getHeight()).append(" hp=").append(p.getHp()).append(" locked=").append(p.isLocked()).append(" teleportQueued=").append(p.getMovement().isTeleportQueued()).append(" movement=").append(p.getMovement().readOffset).append('/').append(p.getMovement().writeOffset).append('\n');
                        DungeonService.RunView r=HollowDepths.inspect(p);if(r==null){text.append("run=none\n");continue;}
                        text.append("run=").append(r.runId).append(" state=").append(r.state).append(" seed=").append(r.layout==null?null:r.layout.seed).append(" guardians=").append(r.guardians).append(" boss=").append(r.bossDefeated).append(" openedChests=").append(r.openedChests).append(" livingNpcs=").append(r.livingNpcs).append('\n');
                        HollowDepthsMap m=HollowDepths.port.maps.get(r.instanceId);if(m!=null){Tile local=current(p,m);text.append("local=").append(local).append(" motion=").append(HollowDepths.port.motions.containsKey(p)).append('\n');
                            for(HollowDepthsGamePort.Actor a:HollowDepths.port.actors.values())if(a.map==m)text.append("npc=").append(a.ticket.archetypeKey).append(" room=").append(a.ticket.roomId).append(" local=").append(m.local(a.npc.getPosition())).append(" hp=").append(a.npc.getHp()).append(" retired=").append(a.retired).append('\n');
                        }
                    }return text.toString();
                });
                java.io.StringWriter stack=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(stack));
                Files.write(stage.resolve("native-lifecycle-failure.txt"),(snapshot+"\n"+stack).getBytes("UTF-8"));
            }catch(Throwable evidenceFailure){failure.addSuppressed(evidenceFailure);}
            throw failure;
        }
    }
}
