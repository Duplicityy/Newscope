package com.newscape.dungeons;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable server-side blueprint. Local tiles are not world coordinates. */
public final class DungeonLayout {
    public static final int GRID_SIZE = 4;
    public static final int ROOM_SIZE = 24;
    public static final int MAP_SIZE = GRID_SIZE * ROOM_SIZE;
    public enum RoomKind { ENTRY, COMBAT, ELITE, TREASURE, TRAP, SHRINE, GUARDIAN, BOSS }

    public static final class Tile {
        public final int x, y;
        public Tile(int x, int y) { this.x = x; this.y = y; }
        public Tile translate(int dx, int dy) { return new Tile(x + dx, y + dy); }
        public int chebyshevDistance(Tile other) {
            return Math.max(Math.abs(x - other.x), Math.abs(y - other.y));
        }
        public int manhattanDistance(Tile other) { return Math.abs(x - other.x) + Math.abs(y - other.y); }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Tile)) return false;
            Tile t = (Tile) other; return x == t.x && y == t.y;
        }
        @Override public int hashCode() { return x * 1009 + y; }
        @Override public String toString() { return x + "," + y; }
    }

    public static final class NpcSpawn {
        public final int slot;
        public final String archetypeKey;
        public final Tile tile;
        /** Baseline blueprints reserve 1x1; cache-bound renderer must verify real size. */
        public final int footprint;
        public NpcSpawn(int slot, String archetypeKey, Tile tile) {
            if (slot < 0 || archetypeKey == null || tile == null) throw new IllegalArgumentException("NPC spawn");
            this.slot = slot; this.archetypeKey = archetypeKey; this.tile = tile; this.footprint = 1;
        }
    }

    public static final class ChestSpawn {
        public final int slot;
        public final String lootTableKey;
        public final Tile tile;
        public final boolean requiresClear;
        public final int requiredAgility;
        public ChestSpawn(int slot, String table, Tile tile, boolean clear, int agility) {
            if (slot < 0 || table == null || tile == null || agility < 1 || agility > 99)
                throw new IllegalArgumentException("Chest spawn");
            this.slot = slot; this.lootTableKey = table; this.tile = tile;
            this.requiresClear = clear; this.requiredAgility = agility;
        }
    }

    /**
     * A physically separate combat bypass around an Agility shortcut. Door actions
     * are click-to-pass, never proximity-auto-open. The runtime authorizes one walk,
     * closes the door behind the player and keeps every guard inside this leash mask.
     */
    public static final class Gauntlet {
        public final Tile entranceOutside, entranceDoor, entranceInside;
        public final Tile exitInside, exitDoor, exitOutside;
        /** Ordered centerline including both outside approaches and both door tiles. */
        public final List<Tile> lane;
        public final List<Tile> interiorTiles;
        public final List<NpcSpawn> guards;
        private final Set<Tile> interior;
        public Gauntlet(Tile entranceOutside, Tile entranceDoor, Tile entranceInside,
                        Tile exitInside, Tile exitDoor, Tile exitOutside,
                        List<Tile> lane, List<Tile> interiorTiles, List<NpcSpawn> guards) {
            if (entranceOutside == null || entranceDoor == null || entranceInside == null ||
                    exitInside == null || exitDoor == null || exitOutside == null ||
                    lane == null || interiorTiles == null || guards == null)
                throw new IllegalArgumentException("Gauntlet values");
            this.entranceOutside = entranceOutside; this.entranceDoor = entranceDoor;
            this.entranceInside = entranceInside; this.exitInside = exitInside;
            this.exitDoor = exitDoor; this.exitOutside = exitOutside;
            this.lane = immutable(lane); this.interiorTiles = immutable(interiorTiles);
            this.guards = immutable(guards);
            this.interior = Collections.unmodifiableSet(new HashSet<Tile>(interiorTiles));
        }
        /** True only for interior leash tiles, excluding doors and outside approaches. */
        public boolean contains(Tile tile) { return interior.contains(tile); }
    }

    public static final class TrapSpawn {
        public final int slot;
        public final String trapKey;
        public final Tile trigger, landing, bypass;
        public final Gauntlet gauntlet;
        public TrapSpawn(int slot, String trapKey, Tile trigger, Tile landing,
                         Tile bypass, Gauntlet gauntlet) {
            if (slot < 0 || trapKey == null || trigger == null || landing == null ||
                    bypass == null || gauntlet == null) throw new IllegalArgumentException("Trap spawn");
            this.slot = slot; this.trapKey = trapKey; this.trigger = trigger;
            this.landing = landing; this.bypass = bypass; this.gauntlet = gauntlet;
        }
    }

    public static final class Room {
        public final int id, gridX, gridY;
        public final RoomKind kind;
        public final String templateKey;
        public final int rotation;
        public final List<NpcSpawn> npcs;
        public final List<ChestSpawn> chests;
        public final List<TrapSpawn> traps;
        private final Tile anchor;
        public Room(int id, int gridX, int gridY, RoomKind kind, String templateKey,
                    int rotation, List<NpcSpawn> npcs, List<ChestSpawn> chests,
                    List<TrapSpawn> traps, Tile anchor) {
            if (id < 0 || gridX < 0 || gridX >= GRID_SIZE || gridY < 0 || gridY >= GRID_SIZE ||
                    kind == null || templateKey == null || rotation < 0 || rotation > 3 ||
                    npcs == null || chests == null || traps == null || anchor == null)
                throw new IllegalArgumentException("Room values");
            this.id = id; this.gridX = gridX; this.gridY = gridY; this.kind = kind;
            this.templateKey = templateKey; this.rotation = rotation;
            this.npcs = immutable(npcs); this.chests = immutable(chests); this.traps = immutable(traps);
            this.anchor = anchor;
        }
        /** Walkable room hub; in a trap room the geometric center is intentionally void. */
        public Tile center() { return anchor; }
        public boolean contains(Tile t) {
            return t != null && t.x >= gridX * ROOM_SIZE && t.x < (gridX + 1) * ROOM_SIZE &&
                    t.y >= gridY * ROOM_SIZE && t.y < (gridY + 1) * ROOM_SIZE;
        }
    }

    public static final class Edge {
        public final int a, b;
        public Edge(int a, int b) {
            if (a < 0 || b < 0 || a == b) throw new IllegalArgumentException("Edge");
            this.a = Math.min(a, b); this.b = Math.max(a, b);
        }
        public boolean connects(int x, int y) { return (a == x && b == y) || (a == y && b == x); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Edge)) return false;
            Edge e = (Edge) other; return a == e.a && b == e.b;
        }
        @Override public int hashCode() { return a * 31 + b; }
    }

    public final long seed;
    public final String themeKey;
    public final List<Room> rooms;
    public final List<Edge> edges;
    public final int startRoomId, bossRoomId, estimatedSeconds, guardianSigilsRequired = 3;
    public final Tile entry;
    private final Map<Integer, Room> byId;
    private final Map<Integer, List<Integer>> adjacent;
    private final boolean[][] floors;

    public DungeonLayout(long seed, String themeKey, List<Room> rooms, List<Edge> edges,
                         int startRoomId, int bossRoomId, Tile entry,
                         int estimatedSeconds, boolean[][] floors) {
        if (themeKey == null || rooms == null || edges == null || entry == null ||
                estimatedSeconds < 1 || floors == null || floors.length != MAP_SIZE)
            throw new IllegalArgumentException("Layout values");
        this.seed = seed; this.themeKey = themeKey; this.rooms = immutable(rooms);
        this.edges = immutable(edges); this.startRoomId = startRoomId;
        this.bossRoomId = bossRoomId; this.entry = entry; this.estimatedSeconds = estimatedSeconds;
        Map<Integer, Room> index = new LinkedHashMap<Integer, Room>();
        Map<Integer, List<Integer>> graph = new HashMap<Integer, List<Integer>>();
        for (Room r : rooms) {
            if (index.put(r.id, r) != null) throw new IllegalArgumentException("Duplicate room ID");
            graph.put(r.id, new ArrayList<Integer>());
        }
        for (Edge e : edges) {
            if (!graph.containsKey(e.a) || !graph.containsKey(e.b)) throw new IllegalArgumentException("Unknown edge endpoint");
            graph.get(e.a).add(e.b); graph.get(e.b).add(e.a);
        }
        for (Map.Entry<Integer, List<Integer>> e : graph.entrySet()) {
            Collections.sort(e.getValue()); e.setValue(immutable(e.getValue()));
        }
        this.byId = Collections.unmodifiableMap(index);
        this.adjacent = Collections.unmodifiableMap(graph);
        this.floors = new boolean[MAP_SIZE][];
        for (int x = 0; x < MAP_SIZE; x++) {
            if (floors[x] == null || floors[x].length != MAP_SIZE) throw new IllegalArgumentException("Floor dimensions");
            this.floors[x] = floors[x].clone();
        }
    }

    public Room room(int roomId) {
        Room r = byId.get(roomId);
        if (r == null) throw new IllegalArgumentException("Unknown room " + roomId);
        return r;
    }
    public List<Integer> neighbors(int roomId) {
        List<Integer> r = adjacent.get(roomId);
        if (r == null) throw new IllegalArgumentException("Unknown room " + roomId);
        return r;
    }
    public boolean walkable(Tile tile) {
        return tile != null && tile.x >= 0 && tile.y >= 0 && tile.x < MAP_SIZE && tile.y < MAP_SIZE && floors[tile.x][tile.y];
    }
    public boolean reachable(Tile from, Tile to) {
        if (!walkable(from) || !walkable(to)) return false;
        return flood(from, Collections.<Tile>emptySet(), null)[to.x][to.y];
    }

    /** Static terrain reachability; dynamic doors/NPC occupancy are enforced by the bridge. */
    public boolean[][] flood(Tile start, Set<Tile> blocked, Room restrictTo) {
        boolean[][] visited = new boolean[MAP_SIZE][MAP_SIZE];
        if (!walkable(start) || blocked.contains(start) || (restrictTo != null && !restrictTo.contains(start))) return visited;
        int[] queue = new int[MAP_SIZE * MAP_SIZE]; int head = 0, tail = 0;
        queue[tail++] = start.x * MAP_SIZE + start.y; visited[start.x][start.y] = true;
        int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
        while (head < tail) {
            int v = queue[head++], x = v / MAP_SIZE, y = v % MAP_SIZE;
            for (int d = 0; d < 4; d++) {
                int nx = x + dx[d], ny = y + dy[d];
                if (nx < 0 || ny < 0 || nx >= MAP_SIZE || ny >= MAP_SIZE || visited[nx][ny] || !floors[nx][ny]) continue;
                Tile next = new Tile(nx, ny);
                if (blocked.contains(next) || (restrictTo != null && !restrictTo.contains(next))) continue;
                visited[nx][ny] = true; queue[tail++] = nx * MAP_SIZE + ny;
            }
        }
        return visited;
    }

    /** Stable regression/audit fingerprint, independent of JVM identity or hash iteration. */
    public String fingerprint() {
        StringBuilder b = new StringBuilder(24000);
        b.append("newscape-layout-v1|").append(seed).append('|').append(themeKey).append('|')
                .append(startRoomId).append('|').append(bossRoomId).append('|').append(entry).append('|').append(estimatedSeconds);
        for (Room r : rooms) {
            b.append(";R:").append(r.id).append(':').append(r.gridX).append(':').append(r.gridY)
                    .append(':').append(r.kind).append(':').append(r.templateKey).append(':').append(r.rotation).append(':').append(r.center());
            for (NpcSpawn n : r.npcs) appendNpc(b, n);
            for (ChestSpawn c : r.chests) b.append(";C:").append(c.slot).append(':').append(c.lootTableKey)
                    .append(':').append(c.tile).append(':').append(c.requiresClear).append(':').append(c.requiredAgility);
            for (TrapSpawn t : r.traps) {
                b.append(";T:").append(t.slot).append(':').append(t.trapKey).append(':').append(t.trigger)
                        .append(':').append(t.landing).append(':').append(t.bypass);
                Gauntlet g = t.gauntlet;
                b.append(':').append(g.entranceOutside).append(':').append(g.entranceDoor).append(':').append(g.entranceInside)
                        .append(':').append(g.exitInside).append(':').append(g.exitDoor).append(':').append(g.exitOutside);
                for (Tile p : g.lane) b.append(";L:").append(p);
                for (Tile p : g.interiorTiles) b.append(";I:").append(p);
                for (NpcSpawn n : g.guards) appendNpc(b, n);
            }
        }
        for (Edge e : edges) b.append(";E:").append(e.a).append(':').append(e.b);
        for (int y = 0; y < MAP_SIZE; y++) for (int x = 0; x < MAP_SIZE; x++) b.append(floors[x][y] ? '1' : '0');
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes) { int n = value & 255; hex.append("0123456789abcdef".charAt(n >>> 4)).append("0123456789abcdef".charAt(n & 15)); }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void appendNpc(StringBuilder b, NpcSpawn n) {
        b.append(";N:").append(n.slot).append(':').append(n.archetypeKey).append(':').append(n.tile).append(':').append(n.footprint);
    }
    private static <T> List<T> immutable(List<T> list) { return Collections.unmodifiableList(new ArrayList<T>(list)); }
}
