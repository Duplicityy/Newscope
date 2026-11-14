package com.newscape.dungeons;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.newscape.dungeons.DungeonLayout.Gauntlet;
import com.newscape.dungeons.DungeonLayout.Room;
import com.newscape.dungeons.DungeonLayout.Tile;
import com.newscape.dungeons.DungeonLayout.TrapSpawn;

/** Executable property checks with no test-framework dependency. */
public final class LayoutChecks {
    private LayoutChecks() { }
    public static void main(String[] args) {
        run();
        System.out.println("LayoutChecks passed: 1000 seeds + long boundaries; geometry, containment, determinism, catalogue and Agility.");
    }
    public static void run() {
        ContentCatalog catalog = new ContentCatalog();
        DungeonGenerator generator = new DungeonGenerator(catalog);
        Set<String> fingerprints = new HashSet<String>(), observedTraps = new HashSet<String>();
        Set<Integer> sizes = new HashSet<Integer>(), rotations = new HashSet<Integer>();
        for (int i = 0; i < 1000; i++) {
            long seed = i * 0x9E3779B97F4A7C15L;
            DungeonLayout layout = generator.generate(seed);
            require(fingerprints.add(layout.fingerprint()), "Different seeds unexpectedly duplicate complete blueprints");
            if (i < 32) require(layout.fingerprint().equals(generator.generate(seed).fingerprint()), "Non-deterministic generation");
            sizes.add(layout.rooms.size());
            for (Room room : layout.rooms) for (DungeonLayout.ChestSpawn chest : room.chests)
                require(chest.requiredAgility == 1, "Default treasure must be obtainable via the combat alternative");
            for (Room room : layout.rooms) for (TrapSpawn trap : room.traps) {
                observedTraps.add(trap.trapKey); rotations.add(room.rotation);
                Gauntlet g = trap.gauntlet;
                Set<Tile> shut = new HashSet<Tile>(Arrays.asList(g.entranceDoor, g.exitDoor));
                require(!canWalk(layout, room, trap.trigger, trap.landing, shut), "Diagonal shortcut bypasses trap/guards");
                require(canWalk(layout, room, trap.trigger, trap.landing, new HashSet<Tile>()), "Low Agility player cannot cross via guard lane");
                require(!canWalk(layout, room, g.entranceInside, g.entranceOutside, shut), "Closed entrance leaks");
                require(!canWalk(layout, room, g.exitInside, g.exitOutside, shut), "Closed exit leaks");
                require(g.lane.size() - 1 > trap.trigger.manhattanDistance(trap.landing), "Combat bypass is not longer");
                for (DungeonLayout.NpcSpawn n : g.guards) require(g.contains(n.tile), "Guard starts outside leash");
            }
        }
        generator.generate(Long.MIN_VALUE); generator.generate(Long.MAX_VALUE);
        require(sizes.size() == 3, "Room count diversity missing");
        require(rotations.size() == 4, "Rotation coverage missing");
        require(observedTraps.size() == catalog.traps.size(), "Not all traps appear across seeds");
        for (ContentCatalog.TrapDef def : catalog.traps.values()) {
            require(def.successPermille(def.requiredAgility - 1) == 0, "Level requirement bypass");
            require(def.successPermille(def.requiredAgility) == def.baseSuccessPermille, "Wrong baseline trap chance");
            int previous = 0;
            for (int agility = 1; agility <= 99; agility++) {
                int chance = def.successPermille(agility);
                require(chance >= previous && chance <= def.maxSuccessPermille, "Agility chance is not monotonic/capped");
                previous = chance;
                for (int roll = -2; roll <= def.maxDamage + 2; roll++) {
                    int damage = def.damageAt(agility, roll);
                    require(damage >= 1 && damage <= def.maxDamage, "Trap damage out of range");
                }
            }
        }
        DungeonLayout sample = generator.generate(42L);
        boolean protectedRooms = false, protectedCatalog = false;
        try { sample.rooms.clear(); } catch (UnsupportedOperationException expected) { protectedRooms = true; }
        try { catalog.npcs.clear(); } catch (UnsupportedOperationException expected) { protectedCatalog = true; }
        require(protectedRooms && protectedCatalog, "Public data is mutable");
    }

    /** Independent OSRS-like eight-way walk model; diagonals may not cut blocked corners. */
    private static boolean canWalk(DungeonLayout layout, Room room, Tile start, Tile goal, Set<Tile> blocked) {
        ArrayDeque<Tile> queue = new ArrayDeque<Tile>(); Set<Tile> seen = new HashSet<Tile>();
        if (!open(layout, room, start, blocked)) return false;
        queue.add(start); seen.add(start);
        while (!queue.isEmpty()) {
            Tile p = queue.removeFirst(); if (p.equals(goal)) return true;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                Tile next = p.translate(dx, dy);
                if (!open(layout, room, next, blocked) || seen.contains(next)) continue;
                if (dx != 0 && dy != 0 && (!open(layout, room, p.translate(dx, 0), blocked) || !open(layout, room, p.translate(0, dy), blocked))) continue;
                seen.add(next); queue.addLast(next);
            }
        }
        return false;
    }
    private static boolean open(DungeonLayout layout, Room room, Tile p, Set<Tile> blocked) {
        return room.contains(p) && layout.walkable(p) && !blocked.contains(p);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
