package io.ruin.model.activities.dungeoneering;

import com.google.gson.annotations.Expose;

/** Saved owner preferences only. Running controllers are deliberately transient. */
public final class IDungeonSettings {
    @Expose public int version = 1;
    @Expose public int firstFloor = 1, lastFloor = 60, complexity = 6, size = 0;
    @Expose public boolean repeat = true, prestige = true, explore = false;
    @Expose public boolean rush = false;
    @Expose public int rushThrough = 18, rushComplexity = 1;
    @Expose public String combat = "AUTO";
    @Expose public boolean switchStyles = true, prayer = true, autoEat = true;
    @Expose public int eatPercent = 55;
    @Expose public boolean lootFood = true, lootRunes = true, lootCoins = true;
    @Expose public boolean lootAmmo = true, upgradeGear = true, buryBones = false;
    @Expose public boolean cookFood = true, stopOnDeath = true;
    @Expose public int maximumDeaths = 0, roomTimeout = 600, maximumMinutes = 120, maximumFloors = 10;

    public void normalize() {
        version = 1;
        firstFloor = clamp(firstFloor, 1, 60);
        lastFloor = clamp(lastFloor, firstFloor, 60);
        complexity = clamp(complexity, 1, 6);
        size = clamp(size, 0, 2);
        rushThrough = clamp(rushThrough, 1, 60);
        rushComplexity = clamp(rushComplexity, 1, 6);
        if (!"AUTO".equals(combat) && !"MELEE".equals(combat)
                && !"RANGED".equals(combat) && !"MAGIC".equals(combat)) combat = "AUTO";
        eatPercent = clamp(eatPercent, 20, 85);
        maximumDeaths = clamp(maximumDeaths, 0, 5);
        roomTimeout = clamp(roomTimeout, 100, 2400);
        maximumMinutes = clamp(maximumMinutes, 0, 480);
        maximumFloors = clamp(maximumFloors, 0, 1000);
    }

    IDungeonSettings copy() {
        IDungeonSettings c = new IDungeonSettings();
        c.firstFloor=firstFloor; c.lastFloor=lastFloor; c.complexity=complexity; c.size=size;
        c.repeat=repeat; c.prestige=prestige; c.explore=explore; c.rush=rush;
        c.rushThrough=rushThrough; c.rushComplexity=rushComplexity; c.combat=combat;
        c.switchStyles=switchStyles; c.prayer=prayer; c.autoEat=autoEat; c.eatPercent=eatPercent;
        c.lootFood=lootFood; c.lootRunes=lootRunes; c.lootCoins=lootCoins; c.lootAmmo=lootAmmo;
        c.upgradeGear=upgradeGear; c.buryBones=buryBones; c.cookFood=cookFood;
        c.stopOnDeath=stopOnDeath; c.maximumDeaths=maximumDeaths; c.roomTimeout=roomTimeout;
        c.maximumMinutes=maximumMinutes; c.maximumFloors=maximumFloors;
        c.normalize(); return c;
    }

    private static int clamp(int n,int low,int high) { return Math.max(low,Math.min(high,n)); }
}
