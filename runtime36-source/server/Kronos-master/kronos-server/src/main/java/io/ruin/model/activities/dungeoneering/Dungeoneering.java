package io.ruin.model.activities.dungeoneering;

import io.ruin.Server;
import io.ruin.model.World;
import io.ruin.model.combat.Hit;
import io.ruin.model.entity.Entity;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.player.PlayerFile;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.dialogue.YesNoDialogue;
import io.ruin.model.inter.handlers.OptionScroll;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.item.ItemContainerG;
import io.ruin.model.map.Position;
import java.util.*;

/** All mutable party/floor state belongs to the existing world thread. */
public final class Dungeoneering {
    static DungeoneeringAssets assets;
    private static String unavailable="Dungeoneering is not enabled on this world.";
    private static final Map<Player,Party> parties=new IdentityHashMap<>();
    private static final Map<Player,Invitation> invitations=new IdentityHashMap<>();
    private static final Set<DungeoneeringFloor> floors=Collections.newSetFromMap(new IdentityHashMap<DungeoneeringFloor,Boolean>());
    private static final Set<Player> relocating=Collections.newSetFromMap(new IdentityHashMap<Player,Boolean>());
    private static final Map<Player,Runnable> pending=new IdentityHashMap<>();
    private static final Map<Player,ExitTransition> exiting=new IdentityHashMap<>();
    private static long lastPulse=-1;
    private static volatile boolean draining;
    private Dungeoneering(){}

    public static final class Party {
        final LinkedHashSet<Player> members=new LinkedHashSet<>();
        Player leader;
        int floor=1,complexity=6,size,selectedBoss=-1;
        boolean fullClear, supplyKit;
        DungeoneeringFloor run;
        Party(Player p){leader=p;members.add(p);DungeoneeringProgress s=progress(p);floor=s.selectedFloor;complexity=s.complexity;size=s.size;}
        void say(String text){for(Player p:new ArrayList<>(members))if(p.isOnline())p.sendMessage("<col=6084a4>"+text);}
    }
    private static final class Invitation {
        final Party party;final Player leader;final long expires;
        Invitation(Party party){this.party=party;leader=party.leader;expires=Server.currentTick()+100;}
    }
    private static final class ExitTransition {
        final DungeoneeringFloor run;
        final DungeoneeringProgress.Escrow saved;
        final boolean claim;
        final Position destination;
        ExitTransition(DungeoneeringFloor run,DungeoneeringProgress.Escrow saved,boolean claim){this.run=run;this.saved=saved;this.claim=claim;destination=new Position(saved.x,saved.y,saved.z);}
    }

    public static void prepare(){
        if(!Boolean.getBoolean("kronos.dungeoneering.enabled")||Server.dataOnlyMode)return;
        try{
            DungeoneeringAssets candidate=DungeoneeringAssets.load(Server.dataFolder.toPath().resolve("dungeoneering/darkan-assets.json"));
            DungeoneeringRuntimeAssets.verify(candidate);assets=candidate;DungeoneeringCombat.register(candidate);DungeoneeringSkills.register(candidate);DungeoneeringRewards.register(candidate);DungeoneeringKinship.register(candidate);DungeoneeringSpells.register(candidate);
            unavailable=null;Server.println("Dungeoneering: "+candidate.rooms.length+" source room variants loaded; native party floors enabled.");
            DungeoneeringLobby.install();
        }catch(Exception e){assets=null;unavailable="Dungeoneering is awaiting a compatible asset/build update.";Server.logError(unavailable,e);}
    }
    public static boolean available(){return assets!=null&&unavailable==null;}
    public static DungeoneeringProgress progress(Player p){
        if(p.dungeoneering==null)p.dungeoneering=new DungeoneeringProgress();
        p.dungeoneering.normalize();return p.dungeoneering;
    }
    static Party party(Player p){return parties.get(p);}
    public static DungeoneeringFloor floor(Player p){Party party=parties.get(p);return party==null?null:party.run;}
    public static boolean inRun(Player p){return p!=null&&p.dungeoneering!=null&&p.dungeoneering.escrow!=null;}
    static double trackXp(Player p,int currentLevel,double base){if(p.experienceLock||base<=0)return 0;double xp=base;if(World.xpMultiplier>0)xp+=base*(World.xpMultiplier-1);xp*=currentLevel>=99?p.xpMode.getAfter99Rate():p.xpMode.getSkillRate();if(p.expBonus.isDelayed())xp*=2;if(p.first3.isDelayed())xp*=1.10;if(World.weekendExpBoost)xp*=1.25;return Math.max(0,xp);}
    public static boolean owns(NPC npc){for(DungeoneeringFloor f:floors)if(f.owns(npc))return true;return false;}
    public static boolean allowCombat(Player p,Entity target){
        if(inRun(p)){DungeoneeringFloor f=floor(p);return f!=null&&target instanceof NPC&&!DungeoneeringSummoning.owns((NPC)target)&&f.owns((NPC)target)&&f.contains(p);}
        return !(target instanceof NPC)||!owns((NPC)target);
    }
    public static void tick(Player p){
        DungeoneeringEquipmentRewards.tick(p);
        if(p.dungeoneering==null)return;
        resolvePending(p);
        if(exiting.containsKey(p)){finishExit(p);return;}
        DungeoneeringSummoning.tick(p);
        DungeoneeringFloor run=floor(p);
        boolean queuedArrival=run!=null&&p.getMovement().isTeleportQueued()&&run.contains(p.getMovement().teleportX,p.getMovement().teleportY,p.getMovement().teleportZ);
        if(run!=null&&inRun(p)&&!pending.containsKey(p)&&!PlayerFile.isSaveQuarantined(p)&&!run.contains(p)&&!queuedArrival)exit(p,false);
    }
    private static void resolvePending(Player p){
        Runnable waiting=pending.get(p);
        if(waiting!=null){
            PlayerFile.SaveResolution result=PlayerFile.resolveDurableSave(p);
            if(result==PlayerFile.SaveResolution.COMMITTED){pending.remove(p);p.unlock();waiting.run();}
            else if(result==PlayerFile.SaveResolution.NOT_COMMITTED){pending.remove(p);p.unlock();save(p,waiting);}
        }
    }
    public static void pulse(){
        if(lastPulse==Server.currentTick())return;lastPulse=Server.currentTick();
        for(Player p:new ArrayList<>(pending.keySet()))resolvePending(p);
        if(draining||World.updating){for(DungeoneeringFloor f:new ArrayList<>(floors))f.abort("The world is updating. Your original equipment is restored.");return;}
        invitations.entrySet().removeIf(e->e.getValue().expires<lastPulse||!e.getKey().isOnline());
        for(DungeoneeringFloor floor:new ArrayList<>(floors))try{floor.pulse();}catch(Exception failure){
            Server.logError("Dungeoneering floor fault; original equipment remains in saved escrow",failure);floor.abort("This floor has closed after an internal error. Your original equipment is being restored.");
        }
    }
    public static void shutdown(){draining=true;}

    public static void open(Player p){
        if(!available()){p.sendMessage(unavailable);return;}
        if(PlayerFile.isSaveQuarantined(p)){p.sendMessage("Your previous save is still being confirmed. Please wait.");return;}
        if(inRun(p)&&floor(p)==null){openRun(p);return;}
        DungeoneeringExpedition.open(p);
    }
    static void legacyOpen(Player p){
        if(inRun(p)){openRun(p);return;}
        DungeoneeringProgress s=progress(p);Party party=parties.get(p);
        List<Option> options=new ArrayList<>();
        if(party==null){
            options.add(new Option("Create party / play solo",()->{Party created=new Party(p);parties.put(p,created);open(p);}));
            Invitation invite=invitations.get(p);
            if(invite!=null&&invite.expires>=Server.currentTick())options.add(new Option("Join "+invite.leader.getName()+"'s party",()->accept(p)));
        }else{
            options.add(new Option("Party: "+party.members.size()+"/5, leader "+party.leader.getName(),()->roster(p,party)));
            if(party.leader==p){
                options.add(new Option("Invite player",()->p.nameInput("Player to invite:",name->invite(p,name))));
                options.add(new Option("Floor "+party.floor+" ("+DungeoneeringAssets.theme(party.floor)+")",()->selectFloor(p,party)));
                options.add(new Option("Complexity "+party.complexity+" / size "+sizeName(party.size),()->settings(p,party)));
                options.add(new Option("Start dungeon",()->start(p,party)));
            }
            options.add(new Option("Leave party",()->{leaveParty(p);open(p);}));
        }
        options.add(new Option("Progress: level "+s.level()+", "+s.tokens+" tokens",()->status(p)));
        options.add(new Option("Token rewards",()->DungeoneeringRewards.open(p)));
        options.add(new Option("Renewal prayer",()->DungeoneeringCombatRewards.open(p)));
        options.add(new Option("Reward equipment / repairs",()->DungeoneeringEquipmentRewards.open(p)));
        options.add(new Option("Reset prestige",()->resetPrestige(p)));
        options.add(new Option("How to play",()->instructions(p)));
        OptionScroll.open(p,"Dungeoneering",false,options);
    }
    static String sizeName(int size){return new String[]{"Small","Medium","Large"}[size];}
    private static void roster(Player p,Party party){List<Option> options=new ArrayList<>();for(Player member:party.members)options.add(new Option(member.getName()+" - Dungeoneering "+progress(member).level(),()->{
        if(party.leader==p&&member!=p&&party.run==null){leaveParty(member);party.say(member.getName()+" was removed from the party.");}open(p);
    }));OptionScroll.open(p,"Dungeon party (select to remove if leader)",false,options);}
    static void invite(Player p,String name){
        Party party=parties.get(p);Player invited=World.getPlayer(name);
        if(party==null||party.leader!=p||party.run!=null){p.sendMessage("Only the idle party leader can invite players.");return;}
        if(party.members.size()>=5){p.sendMessage("Your party is full.");return;}
        if(invited==null||invited==p||parties.containsKey(invited)||inRun(invited)){p.sendMessage("That player is unavailable or already in a dungeon party.");return;}
        invitations.put(invited,new Invitation(party));invited.sendMessage(p.getName()+" invited you to Dungeoneering. Use ::dung to accept within one minute.");p.sendMessage("Invitation sent to "+invited.getName()+".");
    }
    static void accept(Player p){
        Invitation invite=invitations.remove(p);
        if(invite==null||invite.expires<Server.currentTick()||invite.party.leader!=invite.leader||!invite.leader.isOnline()||!invite.party.members.contains(invite.leader)||parties.get(invite.leader)!=invite.party||invite.party.run!=null||invite.party.members.size()>=5||parties.containsKey(p)||inRun(p)||PlayerFile.isSaveQuarantined(p)||p.isLocked()){p.sendMessage("That invitation is no longer available.");return;}
        invite.party.members.add(p);parties.put(p,invite.party);invite.party.say(p.getName()+" joined the party.");open(p);
    }
    private static void selectFloor(Player p,Party party){
        int maximum=60;if(!staffTesting(party))for(Player member:party.members)maximum=Math.min(maximum,progress(member).unlockedFloor());
        List<Option> choices=new ArrayList<>();for(int f=1;f<=maximum;f++){final int floor=f;choices.add(new Option("Floor "+f+" - "+DungeoneeringAssets.theme(f)+(progress(p).completedFloors.contains(f)?" (completed)":""),()->{
            if(parties.get(p)!=party||party.leader!=p||party.run!=null)return;party.floor=floor;progress(p).selectedFloor=floor;party.say("Floor set to "+floor+".");open(p);
        }));}OptionScroll.open(p,staffTesting(party)?"STAFF TEST: select any floor (XP is earned normally)":"Select floor (all members' levels apply)",false,choices);
    }
    static boolean canEditParty(Player p,Party party){
        return party!=null&&parties.get(p)==party&&party.leader==p&&party.run==null
            &&p.isOnline()&&!p.isLocked()&&!inRun(p)&&!PlayerFile.isSaveQuarantined(p);
    }
    static int maximumFloor(Party party){
        int maximum=60;
        if(!staffTesting(party))for(Player member:party.members)maximum=Math.min(maximum,progress(member).unlockedFloor());
        return maximum;
    }
    static void setFloor(Player p,Party party,int floor){
        if(!canEditParty(p,party)||floor<1||floor>maximumFloor(party))return;
        if(party.floor!=floor)party.selectedBoss=-1;
        party.floor=floor;
        progress(p).selectedFloor=floor;
        party.say("Floor set to "+floor+".");
    }
    static void formParty(Player p){
        if(!available()||p.isLocked()||!p.isOnline()||inRun(p)||parties.containsKey(p)||PlayerFile.isSaveQuarantined(p))return;
        parties.put(p,new Party(p));
        open(p);
    }
    static String pendingLeader(Player p){
        Invitation invite=invitations.get(p);
        return invite!=null&&invite.expires>=Server.currentTick()&&invite.leader.isOnline()?invite.leader.getName():null;
    }
    private static boolean staffTesting(Party party){return Boolean.getBoolean("kronos.dungeoneering.staffTesting")&&party.leader.isAdmin();}
    static void settings(Player p,Party party){
        List<Option> choices=new ArrayList<>();for(int c=1;c<=6;c++){final int value=c;choices.add(new Option("Complexity "+c,()->{if(canEditParty(p,party)){if(party.complexity!=value)party.selectedBoss=-1;party.complexity=value;progress(p).complexity=value;}open(p);}));}
        for(int z=0;z<3;z++){final int value=z;choices.add(new Option(sizeName(z)+" dungeon",()->{if(canEditParty(p,party)){party.size=value;progress(p).size=value;}open(p);}));}OptionScroll.open(p,"Dungeon settings",false,choices);
    }
    static void start(Player p,Party party){
        if(parties.get(p)!=party||party.leader!=p||party.run!=null)return;
        if(draining||World.updating){p.sendMessage("The world is updating. New dungeons are temporarily unavailable.");return;}
        if(floors.size()>=Math.max(1,Math.min(8,Integer.getInteger("kronos.dungeoneering.maxInstances",3)))){p.sendMessage("All dungeon instances are occupied. Please try again shortly.");return;}
        for(Player member:party.members)if(!eligible(member)||!staffTesting(party)&&progress(member).unlockedFloor()<party.floor){p.sendMessage(member.getName()+" is not ready to enter that floor.");return;}
        try{
            DungeoneeringFloor run=new DungeoneeringFloor(party,assets,System.nanoTime());
            party.run=run;floors.add(run);
            for(Player member:new ArrayList<>(party.members))enter(member,run);
            party.say("Floor "+party.floor+", complexity "+party.complexity+", "+sizeName(party.size)+". Use ::dung for progress, gatestones, binding and exit.");
        }catch(Exception e){Server.logError("Dungeoneering floor could not be built",e);if(party.run!=null)party.run.abort("This floor could not be prepared. Your equipment is safe.");p.sendMessage("The selected dungeon is not available in this build. Staff have been notified.");}
    }
    private static boolean eligible(Player p){return p.isOnline()&&!p.isLocked()&&!inRun(p)&&!PlayerFile.isSaveQuarantined(p)&&p.wildernessLevel==0&&p.getPosition().getX()<6400&&p.getDuel().stage==0&&!p.joinedTournament&&!p.supplyChestRestricted&&!io.ruin.model.activities.hollowdepths.HollowDepths.inRun(p)&&!p.getCombat().isDefending(10);}
    private static void enter(Player p,DungeoneeringFloor run){
        DungeoneeringProgress s=progress(p);if(s.escrow!=null)throw new IllegalStateException("Existing dungeon escrow");
        DungeoneeringUtilityRewards.beforeDungeon(p);
        p.resetActions(true,true,true);p.getTrade().close();
        DungeoneeringProgress.Escrow original=new DungeoneeringProgress.Escrow();original.runId=run.id;DungeoneeringSpells.capture(p,original);original.inventory=copy(p.getInventory().getItems());original.equipment=copy(p.getEquipment().getItems());original.x=p.getPosition().getX();original.y=p.getPosition().getY();original.z=p.getPosition().getZ();
        s.escrow=original;p.resetActions(true,true,true);p.getCombat().reset();p.clearHits();p.lock(LockType.FULL_NULLIFY_DAMAGE);p.getInventory().clear();p.getEquipment().clear();
        // Queue the native teleport only after escrow commits. Pre-setting the live
        // position would erase the previous-position delta used by the client update.
        DungeoneeringSkills.starter(p,run);Position start=run.startTile();
        save(p,()->{if(!p.isOnline()||run.destroyed||run.party.run!=run){exit(p,false);if(!p.isOnline())leaveParty(p);return;}run.entered(p);DungeoneeringSpells.entered(p);relocate(p,start);p.unlock();});
    }
    static void save(Player p,Runnable success){
        try{PlayerFile.saveDurably(p);success.run();}
        catch(PlayerFile.DurableSaveException failure){
            if(failure.uncertain)pending.put(p,success);
            else PlayerFile.quarantine(p,"Dungeoneering could not save equipment/progression atomically");
            Server.logError("Dungeoneering save held for account "+p.getUserId(),failure);
        }
    }
    static Item[] copy(Item[] source){Item[] result=new Item[source.length];for(int i=0;i<source.length;i++)if(source[i]!=null)result[i]=source[i].copy();return result;}
    private static void restore(ItemContainerG<Item> container,Item[] original){if(original==null||original.length!=container.getItems().length)throw new IllegalStateException("Dungeon equipment snapshot dimensions changed");container.clear();for(int i=0;i<original.length;i++)if(original[i]!=null)container.set(i,original[i].copy());}
    static void relocate(Player p,Position target){relocating.add(p);try{p.getMovement().teleport(target);}finally{relocating.remove(p);}}
    public static boolean allowTeleport(Player p,int x,int y,int z){
        if(!inRun(p)||relocating.contains(p))return true;
        DungeoneeringFloor run=floor(p);
        if(run!=null&&run.contains(x,y,z))return true;
        if(!PlayerFile.isSaveQuarantined(p))exit(p,false);
        return false;
    }
    public static void onLogin(Player p){
        DungeoneeringUtilityRewards.login(p);
        if(p.dungeoneering==null||p.dungeoneering.escrow==null)return;
        try{progress(p);exit(p,false);p.sendMessage("Your previous dungeon ended. Your original inventory and equipment are being restored.");}
        catch(Exception e){PlayerFile.quarantine(p,"Dungeoneering recovery could not restore saved original equipment");Server.logError("Dungeon login recovery blocked",e);}
    }
    public static void onLogout(Player p){
        DungeoneeringExpedition.logout(p);
        DungeoneeringUtilityRewards.logout(p);
        DungeoneeringCombatRewards.clearActive(p);
        invitations.remove(p);DungeoneeringSpells.clear(p);DungeoneeringSummoning.dismiss(p,false);
        if(inRun(p)&&!PlayerFile.isSaveQuarantined(p))exit(p,false);
        leaveParty(p);
    }
    public static boolean onDeath(Player p){
        if(!inRun(p))return false;
        DungeoneeringFloor run=floor(p);
        DungeoneeringSpells.onDeath(p);
        p.resetActions(true,true,true);p.getMovement().reset();p.getCombat().reset();p.getCombat().resetKillers();p.getCombat().setDead(false);p.getCombat().setTruelyDead(false);p.clearHits();p.getCombat().restore();p.setHp(p.getMaxHp());
        if(run==null){exit(p,false);return true;}
        run.deaths.merge(p.getUserId(),1,Integer::sum);relocate(p,run.startTile());p.sendMessage("You return to the starting room. Each death reduces this floor's XP by 10%, up to 60%.");return true;
    }
    public static void exit(Player p,boolean claim){
        DungeoneeringProgress s=p.dungeoneering;if(s==null||s.escrow==null)return;
        if(PlayerFile.isSaveQuarantined(p)){p.sendMessage("Your save is still pending; your original equipment remains protected.");return;}
        if(exiting.containsKey(p)){finishExit(p);return;}
        DungeoneeringFloor run=floor(p);DungeoneeringProgress.Escrow saved=s.escrow;
        if(claim&&(run==null||!run.complete||!run.id.equals(saved.runId))){p.sendMessage("Defeat the dungeon boss before claiming floor rewards.");return;}
        if(claim&&!run.mayClaim()){p.sendMessage("Explore every room before claiming this full-clear floor.");return;}
        p.resetActions(true,true,true);p.getCombat().reset();p.clearHits();p.lock(LockType.FULL_NULLIFY_DAMAGE);
        DungeoneeringSpells.clear(p);DungeoneeringSummoning.dismiss(p,false);
        ExitTransition leaving=new ExitTransition(run,saved,claim);exiting.put(p,leaving);
        // Keep the complete escrow until native movement has actually reached the
        // outside position. Any autosave or disconnect in between remains recoverable.
        relocate(p,leaving.destination);finishExit(p);
    }
    private static void finishExit(Player p){
        ExitTransition leaving=exiting.get(p);if(leaving==null)return;
        if(!p.isOnline())p.getMovement().finishTeleport(p.getPosition());
        if(p.getMovement().isTeleportQueued()||!p.getPosition().equals(leaving.destination))return;
        DungeoneeringProgress s=p.dungeoneering;
        if(s==null||s.escrow!=leaving.saved)throw new IllegalStateException("Dungeon exit escrow changed before restoration");
        final String reward=leaving.claim&&!leaving.saved.runId.equals(s.lastRewardRun)?DungeoneeringRewards.complete(p,leaving.run):null;
        final long recordTicks=reward==null?0:DungeoneeringExpedition.record(s,leaving.run,reward);
        restore(p.getInventory(),leaving.saved.inventory);restore(p.getEquipment(),leaving.saved.equipment);DungeoneeringSpells.restore(p,leaving.saved);s.escrow=null;
        p.getCombat().resetKillers();p.getCombat().restore();p.setHp(p.getMaxHp());exiting.remove(p);
        save(p,()->{if(leaving.run!=null)leaving.run.departed(p);p.unlock();if(reward!=null){DungeoneeringExpedition.worldRecord(leaving.run.party.floor,recordTicks);p.sendMessage(reward);}p.sendMessage(leaving.claim?"Floor complete. Your equipment is restored and your rewards are saved.":"You leave the dungeon. Your original equipment is restored.");});
    }
    static void closed(DungeoneeringFloor run){floors.remove(run);if(run.party.run==run)run.party.run=null;}
    static boolean exitPending(Player p){return exiting.containsKey(p);}

    /** Read-only acknowledgement for the owner bot. Escrow disappearing alone is not cleanup. */
    static IDungeonRecovery.Location recoveryLocation(Player p,DungeoneeringFloor expected){
        if(PlayerFile.isSaveQuarantined(p)||pending.containsKey(p))return IDungeonRecovery.Location.SAVE_PENDING;
        Party party=parties.get(p);
        if(party!=null&&(party.leader!=p||party.members.size()!=1||expected!=null&&party!=expected.party
                ||party.run!=null&&party.run!=expected))return IDungeonRecovery.Location.WRONG_DUNGEON;
        if(inRun(p)){
            if(expected==null||!expected.id.equals(p.dungeoneering.escrow.runId))return IDungeonRecovery.Location.WRONG_DUNGEON;
            return exiting.containsKey(p)?IDungeonRecovery.Location.TRANSITION:IDungeonRecovery.Location.INSIDE;
        }
        if(exiting.containsKey(p)||p.isLocked()||p.getMovement().isTeleportQueued())return IDungeonRecovery.Location.TRANSITION;
        if(expected!=null&&(!expected.destroyed||floors.contains(expected)||expected.entered.contains(p)
                ||party!=null&&party.run==expected))return IDungeonRecovery.Location.OUTSIDE_DIRTY;
        return IDungeonRecovery.Location.CLEAN;
    }

    /**
     * Narrow owner/solo fallback to the SAME exit transaction as the native leave button.
     * Never resolves an uncertain save by force, unlocks a player, fabricates a reward,
     * or removes another participant. The caller must observe CLEAN before restarting.
     */
    static IDungeonRecovery.Location recoverSoloExit(Player p,DungeoneeringFloor expected,boolean claim){
        if(!IDungeon.owner(p)||!p.isOnline()||p.logoutStage!=0)return IDungeonRecovery.Location.WRONG_DUNGEON;
        IDungeonRecovery.Location state=recoveryLocation(p,expected);
        if(state==IDungeonRecovery.Location.WRONG_DUNGEON||state==IDungeonRecovery.Location.SAVE_PENDING)return state;
        ExitTransition transition=exiting.get(p);
        if(transition!=null){
            if(transition.run!=expected||expected==null||!expected.id.equals(transition.saved.runId))return IDungeonRecovery.Location.WRONG_DUNGEON;
            // Requeue only the escrow's authorized destination; movement/update still confirms arrival.
            if(!p.getPosition().equals(transition.destination)
                    &&(!p.getMovement().isTeleportQueued()||p.getMovement().teleportX!=transition.destination.getX()
                    ||p.getMovement().teleportY!=transition.destination.getY()||p.getMovement().teleportZ!=transition.destination.getZ()))relocate(p,transition.destination);
            finishExit(p);
        }else if(inRun(p)){
            exit(p,claim&&expected!=null&&expected.complete);
        }else if(expected!=null&&state==IDungeonRecovery.Location.OUTSIDE_DIRTY){
            if(expected.contains(p))return IDungeonRecovery.Location.OUTSIDE_DIRTY;
            for(Player member:expected.entered)if(member!=p)return IDungeonRecovery.Location.WRONG_DUNGEON;
            // A definite, completed native save must precede participant/map cleanup.
            expected.departed(p);
        }
        return recoveryLocation(p,expected);
    }
    static void leaveParty(Player p){Party party=parties.get(p);if(party==null)return;if(inRun(p)){exit(p,false);if(inRun(p))return;}parties.remove(p);party.members.remove(p);if(party.leader==p&&!party.members.isEmpty())party.leader=party.members.iterator().next();party.say(p.getName()+" left the party.");}
    private static void openRun(Player p){
        DungeoneeringFloor run=floor(p);if(run==null){onLogin(p);return;}
        List<Option> options=new ArrayList<>();options.add(new Option("Floor progress / party map",()->run.status(p)));
        options.add(new Option("Dungeon spellbook / gatestones",()->DungeoneeringSpells.open(p)));
        options.add(new Option("Bind equipment for future dungeons",()->bindMenu(p)));
        options.add(new Option("Crafting and resource recipes",()->DungeoneeringSkills.menu(p)));
        options.add(new Option("Dungeon Summoning",()->DungeoneeringSummoning.menu(p)));
        options.add(new Option("Ring of kinship class / perks",()->DungeoneeringKinship.open(p)));
        options.add(new Option("Renewal prayer",()->DungeoneeringCombatRewards.open(p)));
        if(run.complete)options.add(new Option("Claim floor rewards and leave",()->exit(p,true)));
        options.add(new Option("Abandon floor (no reward)",()->p.dialogue(new YesNoDialogue("Leave this floor?","Original equipment is restored. Unfinished floor progress is lost.",995,1,()->exit(p,false)))));
        OptionScroll.open(p,"Dungeoneering - floor "+run.party.floor,false,options);
    }
    static void bindMenu(Player p){
        DungeoneeringProgress s=progress(p);List<Option> choices=new ArrayList<>();int maximum=s.level()>=120?4:s.level()>=100?3:s.level()>=50?2:1;
        choices.add(new Option("Clear bound equipment ("+s.boundItems.length+"/"+maximum+")",()->{if(!inRun(p))return;s.boundItems=new Item[0];save(p,()->openRun(p));}));
        for(Item item:p.getInventory().getItems())if(item!=null&&assets.sourceItem(item.getId())>=0&&item.getDef().equipSlot>=0){int slot=item.getSlot(),id=item.getId();choices.add(new Option("Bind "+item.getDef().name,()->{
            Item current=p.getInventory().get(slot,id);if(!inRun(p)||current==null)return;if(s.boundItems.length>=maximum){p.sendMessage("Your binding limit is "+maximum+".");return;}
            for(Item bound:s.boundItems)if(bound.getId()==id){p.sendMessage("That item is already bound.");return;}
            Item[] next=Arrays.copyOf(s.boundItems,s.boundItems.length+1);next[next.length-1]=current.copy();next[next.length-1].setAmount(Math.min(current.getAmount(),current.getDef().stackable?125:1));s.boundItems=next;save(p,()->{p.sendMessage("Bound "+current.getDef().name+" for future floors.");openRun(p);});
        }));}
        OptionScroll.open(p,"Bind dungeon equipment",false,choices);
    }
    private static void status(Player p){DungeoneeringProgress s=progress(p);p.sendScroll("Dungeoneering progress","Level: "+s.level()+" / 120","Experience: "+s.experience,"Tokens: "+s.tokens,"Completed dungeons: "+s.completedDungeons,"Floors in current prestige: "+s.completedFloors.size(),"Prestige: "+s.prestige(),"Highest available floor: "+s.unlockedFloor(),"Bound items: "+s.boundItems.length);}
    static void resetPrestige(Player p){if(inRun(p)){p.sendMessage("Leave your dungeon before resetting prestige.");return;}p.dialogue(new YesNoDialogue("Reset floor progress?","Your best completed-floor count becomes your prestige. XP, tokens and bound items are kept.",995,1,()->{
        DungeoneeringProgress s=progress(p);if(inRun(p))return;s.previousPrestige=Math.max(s.previousPrestige,s.completedFloors.size());s.completedFloors.clear();save(p,()->status(p));
    }));}
    private static void instructions(Player p){p.sendScroll("Dungeoneering","Create a party or accept an invitation with ::dung. Parties support one to five players.","The leader chooses a floor, size and complexity. Your Dungeoneering level unlocks later floors.","Original equipment and inventory are stored safely and restored when you leave.","Gather supplies, share keys, overcome doors and solve puzzles together.","Defeat the boss, then use the exit or ::dung to claim XP and tokens.","Deaths return you to the start with an XP penalty. Logout safely ends your participation.","Bind equipment inside a dungeon to bring it into future floors.","Reset prestige after completing your available floors for better ongoing XP.");}
    public static boolean command(Player p,String token,String full){
        if(token.equals("dungpet")){DungeoneeringUtilityRewards.petMenu(p);return true;}
        if(token.equals("dgspells")){DungeoneeringSpells.open(p);return true;}
        if(token.equals("dungui")){DungeoneeringExpedition.enable(p,!full.trim().equalsIgnoreCase("dungui classic"));return true;}
        switch(token){case "dung":case "dungeoneering":open(p);return true;case "dungaccept":accept(p);return true;case "dunginvite":invite(p,full.length()>token.length()?full.substring(token.length()).trim():"");return true;case "dungleave":if(inRun(p))exit(p,false);else leaveParty(p);return true;default:return false;}
    }
}
