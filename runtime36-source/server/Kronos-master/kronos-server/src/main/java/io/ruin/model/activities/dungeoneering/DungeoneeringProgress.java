package io.ruin.model.activities.dungeoneering;

import com.google.gson.annotations.Expose;
import io.ruin.model.item.Item;
import java.util.HashSet;
import java.util.Set;

/** Saved alongside the player's ordinary inventory: escrow and rewards are one atomic save. */
public final class DungeoneeringProgress {
    @Expose public int version = 1;
    @Expose public long experience, tokens, completedDungeons;
    @Expose public int previousPrestige, highestFloor = 1, selectedFloor = 1, complexity = 6, size;
    @Expose public Set<Integer> completedFloors = new HashSet<>();
    @Expose public Item[] boundItems = new Item[0];
    @Expose public Item[] smugglerStorage = new Item[15];
    @Expose public Item utilityPet;
    @Expose public Escrow escrow;
    @Expose public String lastRewardRun, lastFloorResult;
    @Expose public long[] bestFloorTicks = new long[60];
    @Expose public int miningBeltTier = -1, woodcuttingBeltTier = -1;
    @Expose public double summoningExperience;
    @Expose public boolean scrollLife, scrollCleansing, scrollEfficiency, renewalUnlocked;
    @Expose public int summoningPoints = 1;
    @Expose public int[] kinshipTiers = new int[12];
    @Expose public int activeKinship = -1, quickKinship = -1, kinshipResets = 5;
    @Expose public IDungeonSettings idungeon;

    public static final class Escrow {
        @Expose public String runId;
        @Expose public Item[] inventory, equipment;
        @Expose public int x,y,z,spellbook;
        @Expose public Integer originalMagicBook, originalAutocast, originalDefensiveCast;
    }

    public int level() {
        int level=1;
        while(level<120 && experience >= experienceAt(level+1)) level++;
        return level;
    }

    public static long experienceAt(int level) {
        if(level<1 || level>120) throw new IllegalArgumentException("Dungeoneering level outside 1–120");
        long points=0;
        for(int i=1;i<level;i++) points+=(long)Math.floor(i+300.0*Math.pow(2.0,i/7.0));
        return points/4;
    }

    public int unlockedFloor() { return Math.min(60,Math.max(1,(level()+1)/2)); }
    public int prestige() { return Math.max(previousPrestige,completedFloors==null?0:completedFloors.size()); }
    public void normalize() {
        if(version!=1 || experience<0 || tokens<0) throw new IllegalStateException("Invalid Dungeoneering progress");
        if(completedFloors==null)completedFloors=new HashSet<>();
        completedFloors.removeIf(f->f==null || f<1 || f>60);
        previousPrestige=Math.max(0,Math.min(60,previousPrestige));
        highestFloor=Math.max(1,Math.min(60,highestFloor));
        selectedFloor=Math.max(1,Math.min(unlockedFloor(),selectedFloor));
        complexity=Math.max(1,Math.min(6,complexity)); size=Math.max(0,Math.min(2,size));
        if(boundItems==null)boundItems=new Item[0];
        if(smugglerStorage==null)smugglerStorage=new Item[15];
        if(smugglerStorage.length!=15)smugglerStorage=java.util.Arrays.copyOf(smugglerStorage,15);
        miningBeltTier=Math.max(-1,Math.min(10,miningBeltTier));
        woodcuttingBeltTier=Math.max(-1,Math.min(10,woodcuttingBeltTier));
        if(bestFloorTicks==null)bestFloorTicks=new long[60];
        if(bestFloorTicks.length!=60)bestFloorTicks=java.util.Arrays.copyOf(bestFloorTicks,60);
        for(int floor=0;floor<60;floor++)bestFloorTicks[floor]=Math.max(0L,Math.min(10_000_000L,bestFloorTicks[floor]));
        if(!Double.isFinite(summoningExperience)||summoningExperience<0)throw new IllegalStateException("Invalid dungeon Summoning XP");
        if(kinshipTiers==null)kinshipTiers=new int[12];
        if(kinshipTiers.length!=12)throw new IllegalStateException("Invalid kinship state");
        for(int i=0;i<kinshipTiers.length;i++)kinshipTiers[i]=Math.max(0,Math.min(10,kinshipTiers[i]));
        if(idungeon!=null)idungeon.normalize();
    }
}
