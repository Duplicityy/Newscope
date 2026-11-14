package com.newscape.dungeons;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.newscape.dungeons.DungeonLayout.ChestSpawn;
import com.newscape.dungeons.DungeonLayout.Edge;
import com.newscape.dungeons.DungeonLayout.Gauntlet;
import com.newscape.dungeons.DungeonLayout.NpcSpawn;
import com.newscape.dungeons.DungeonLayout.Room;
import com.newscape.dungeons.DungeonLayout.RoomKind;
import com.newscape.dungeons.DungeonLayout.Tile;
import com.newscape.dungeons.DungeonLayout.TrapSpawn;

/** Executable invariants, including physical containment of Agility bypass gauntlets. */
public final class DungeonValidator {
    private DungeonValidator() { }
    public static void validate(DungeonLayout layout, ContentCatalog catalog) {
        require(layout != null && catalog != null, "Null layout/catalog");
        require(layout.rooms.size() >= 14 && layout.rooms.size() <= 16, "Room budget");
        require(layout.estimatedSeconds >= 900 && layout.estimatedSeconds <= 1200, "Pacing design budget");
        require(catalog.themes.contains(layout.themeKey), "Unknown theme");
        require(layout.room(layout.startRoomId).kind == RoomKind.ENTRY, "Entry room");
        require(layout.room(layout.bossRoomId).kind == RoomKind.BOSS, "Boss room");
        require(layout.neighbors(layout.bossRoomId).size() == 1, "Boss must remain a leaf");
        require(layout.walkable(layout.entry), "Blocked entry");
        Set<Integer> ids = new HashSet<Integer>(), cells = new HashSet<Integer>();
        Set<Integer> npcSlots = new HashSet<Integer>(), chestSlots = new HashSet<Integer>(), trapSlots = new HashSet<Integer>();
        Set<Tile> occupied = new HashSet<Tile>(); Set<Edge> edgeSet = new HashSet<Edge>();
        boolean[][] reached = layout.flood(layout.entry, Collections.<Tile>emptySet(), null);
        int guardians = 0, bosses = 0, entries = 0, trapRooms = 0, chestCount = 0;
        for (Edge e : layout.edges) {
            require(edgeSet.add(e), "Duplicate edge");
            Room a = layout.room(e.a), b = layout.room(e.b);
            require(Math.abs(a.gridX - b.gridX) + Math.abs(a.gridY - b.gridY) == 1, "Non-cardinal room edge");
        }
        Set<Integer> graphReached = new HashSet<Integer>(); floodGraph(layout, layout.startRoomId, -1, graphReached);
        require(graphReached.size() == layout.rooms.size(), "Disconnected room graph");
        Set<Integer> withoutBoss = new HashSet<Integer>(); floodGraph(layout, layout.startRoomId, layout.bossRoomId, withoutBoss);
        Set<String> guardianKeys = new HashSet<String>();
        for (Room room : layout.rooms) {
            require(ids.add(room.id), "Duplicate room ID");
            require(cells.add(room.gridX + room.gridY * 4), "Overlapping room grid cell");
            require(catalog.roomTemplates.contains(room.templateKey), "Unknown room template");
            require(layout.walkable(room.center()) && room.contains(room.center()), "Blocked room hub");
            require(reached[room.center().x][room.center().y], "Unreachable room hub");
            if (room.kind == RoomKind.ENTRY) entries++;
            if (room.kind == RoomKind.BOSS) bosses++;
            if (room.kind == RoomKind.GUARDIAN) {
                guardians++; require(withoutBoss.contains(room.id), "Sigil is locked behind boss");
                int guardianMobs = 0;
                for (NpcSpawn n : room.npcs) if (n.archetypeKey.startsWith("guardian_")) { guardianMobs++; guardianKeys.add(n.archetypeKey); }
                require(guardianMobs == 1, "Guardian room requires one sigil guardian");
            }
            for (NpcSpawn spawn : room.npcs) validateNpc(layout, catalog, room, spawn, npcSlots, occupied, reached);
            for (ChestSpawn chest : room.chests) {
                chestCount++; require(chestSlots.add(chest.slot), "Duplicate chest slot");
                require(Arrays.asList("BASIC", "GUARDED", "AGILITY", "ELITE", "BOSS").contains(chest.lootTableKey), "Unknown loot table");
                require(room.contains(chest.tile) && layout.walkable(chest.tile), "Chest off floor");
                require(reached[chest.tile.x][chest.tile.y] && occupied.add(chest.tile), "Unreachable/overlapping chest");
            }
            if (room.kind == RoomKind.TRAP) {
                trapRooms++; require(layout.neighbors(room.id).size() == 2, "Trap room is not a bottleneck");
                require(room.traps.size() == 1, "Trap-room trap count");
            } else require(room.traps.isEmpty(), "Trap geometry must have a dedicated room");
            for (TrapSpawn trap : room.traps) {
                require(trapSlots.add(trap.slot), "Duplicate trap slot");
                catalog.trap(trap.trapKey);
                require(room.contains(trap.trigger) && room.contains(trap.landing), "Trap outside room");
                require(layout.walkable(trap.trigger) && layout.walkable(trap.landing), "Invalid trap banks");
                require(occupied.add(trap.trigger) && occupied.add(trap.landing), "Overlapping trap endpoint");
                validateGauntlet(layout, catalog, room, trap, npcSlots, occupied, reached);
            }
        }
        require(entries == 1 && bosses == 1 && guardians == 3 && guardianKeys.size() == 3, "Mandatory objectives");
        require(trapRooms == 2, "Expected two trap rooms");
        require(chestCount >= 10 && chestCount <= 16, "Chest budget");
        // Room IDs are deliberately contiguous even when 1–2 grid cells are unused.
        for (int i = 0; i < layout.rooms.size(); i++) require(ids.contains(i), "Non-contiguous IDs");
        for (int x = 0; x < DungeonLayout.MAP_SIZE; x++) for (int y = 0; y < DungeonLayout.MAP_SIZE; y++) {
            Tile p = new Tile(x, y); if (layout.walkable(p)) require(reached[x][y], "Isolated floor island at " + p);
        }
    }

    private static void validateGauntlet(DungeonLayout layout, ContentCatalog catalog, Room room,
                                         TrapSpawn trap, Set<Integer> npcSlots,
                                         Set<Tile> occupied, boolean[][] reached) {
        Gauntlet g = trap.gauntlet;
        require(g != null && g.guards.size() >= 3 && g.guards.size() <= 5, "Gauntlet guard budget");
        require(g.entranceOutside.manhattanDistance(g.entranceDoor) == 1 && g.entranceDoor.manhattanDistance(g.entranceInside) == 1,
                "Entrance passage geometry");
        require(g.exitInside.manhattanDistance(g.exitDoor) == 1 && g.exitDoor.manhattanDistance(g.exitOutside) == 1,
                "Exit passage geometry");
        require(g.entranceOutside.manhattanDistance(g.entranceInside) == 2 &&
                g.exitOutside.manhattanDistance(g.exitInside) == 2, "Door endpoints must be opposite sides");
        require(!g.entranceDoor.equals(g.exitDoor), "Collapsed gauntlet doors");
        List<Tile> special = Arrays.asList(g.entranceOutside, g.entranceDoor, g.entranceInside,
                g.exitInside, g.exitDoor, g.exitOutside);
        for (Tile p : special) require(room.contains(p) && layout.walkable(p), "Passage endpoint off floor");
        require(!g.contains(g.entranceDoor) && !g.contains(g.exitDoor) && !g.contains(g.entranceOutside) && !g.contains(g.exitOutside), "Guard leash includes outside");
        require(g.contains(g.entranceInside) && g.contains(g.exitInside), "Guard leash misses inner endpoints");
        require(g.lane.size() >= 20 && g.lane.get(0).equals(g.entranceOutside) && g.lane.get(g.lane.size() - 1).equals(g.exitOutside), "Gauntlet lane endpoints/length");
        Set<Tile> laneSeen = new HashSet<Tile>(); Tile previous = null;
        for (Tile p : g.lane) {
            require(laneSeen.add(p) && layout.walkable(p) && room.contains(p), "Invalid gauntlet lane");
            if (previous != null) require(previous.manhattanDistance(p) == 1, "Discontinuous gauntlet lane"); previous = p;
        }
        Set<Tile> closed = new HashSet<Tile>(Arrays.asList(g.entranceDoor, g.exitDoor));
        boolean[][] fromBank = layout.flood(trap.trigger, closed, room);
        require(!fromBank[trap.landing.x][trap.landing.y], "Walking bypasses both Agility and the guard corridor");
        boolean[][] interior = layout.flood(g.entranceInside, closed, room);
        require(interior[g.exitInside.x][g.exitInside.y], "Disconnected gauntlet interior");
        for (int x = room.gridX * 24; x < (room.gridX + 1) * 24; x++) for (int y = room.gridY * 24; y < (room.gridY + 1) * 24; y++) {
            Tile p = new Tile(x, y);
            require(!interior[x][y] || g.contains(p), "Guard lane leaks through a wall");
        }
        for (Tile p : g.interiorTiles) require(room.contains(p) && layout.walkable(p) && interior[p.x][p.y], "Invalid leash interior tile");
        require(layout.reachable(trap.trigger, trap.landing), "No low-Agility combat alternative");
        require(occupied.add(g.entranceDoor) && occupied.add(g.exitDoor), "Overlapping gauntlet door");
        for (NpcSpawn guard : g.guards) {
            require(g.contains(guard.tile), "Guard spawned outside containment");
            validateNpc(layout, catalog, room, guard, npcSlots, occupied, reached);
        }
    }

    private static void validateNpc(DungeonLayout layout, ContentCatalog catalog, Room room,
                                    NpcSpawn spawn, Set<Integer> slots, Set<Tile> occupied,
                                    boolean[][] reached) {
        catalog.npc(spawn.archetypeKey);
        require(slots.add(spawn.slot), "Duplicate NPC slot");
        require(spawn.footprint == 1, "Unaudited larger NPC footprint");
        require(room.contains(spawn.tile) && layout.walkable(spawn.tile), "NPC off floor");
        require(reached[spawn.tile.x][spawn.tile.y], "Unreachable NPC");
        require(occupied.add(spawn.tile), "Overlapping NPC/object footprint");
    }
    private static void floodGraph(DungeonLayout layout, int room, int excluded, Set<Integer> reached) {
        if (room == excluded || !reached.add(room)) return;
        for (int neighbor : layout.neighbors(room)) floodGraph(layout, neighbor, excluded, reached);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Dungeon invariant failed: " + message);
    }
}
