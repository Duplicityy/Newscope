package com.newscape.dungeons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import com.newscape.dungeons.DungeonLayout.ChestSpawn;
import com.newscape.dungeons.DungeonLayout.Edge;
import com.newscape.dungeons.DungeonLayout.Gauntlet;
import com.newscape.dungeons.DungeonLayout.NpcSpawn;
import com.newscape.dungeons.DungeonLayout.Room;
import com.newscape.dungeons.DungeonLayout.RoomKind;
import com.newscape.dungeons.DungeonLayout.Tile;
import com.newscape.dungeons.DungeonLayout.TrapSpawn;

/**
 * Bounded seeded generator. Topology, decoration, encounters and traps have isolated
 * deterministic streams so changing loot rolls cannot change the map or trap outcomes.
 * No world mutation occurs here; generation may safely run away from the game thread.
 */
public final class DungeonGenerator {
    private static final int[] CORNERS = {0, 3, 12, 15};
    private static final int[][] MOB_ANCHORS = {{7,7},{16,7},{7,16},{16,16},{12,8},{12,16}};
    private static final int[][] GUARD_ANCHORS = {{5,7},{8,5},{12,5},{16,5},{19,7}};
    private final ContentCatalog catalog;

    public DungeonGenerator(ContentCatalog catalog) {
        if (catalog == null) throw new IllegalArgumentException("catalog");
        this.catalog = catalog;
    }

    public DungeonLayout generate(long seed) {
        SplittableRandom sizeRandom = new SplittableRandom(mix(seed, 0x1122L));
        int roomCount = 14 + sizeRandom.nextInt(3);
        Topology topology = chooseTopology(seed, roomCount);
        final Map<Integer, Integer> depth = distances(topology, topology.cells.get(0));
        int bossCell = deepestLeaf(topology, depth);
        List<Integer> trapCells = chooseTraps(topology, depth, bossCell, seed);
        addLoops(topology, trapCells, bossCell, new SplittableRandom(mix(seed, 0x2211L)));
        Map<Integer, RoomKind> kinds = assignKinds(topology, trapCells, bossCell, seed);

        Map<Integer, Integer> ids = new HashMap<Integer, Integer>();
        for (int i = 0; i < topology.cells.size(); i++) ids.put(topology.cells.get(i), i);
        List<Edge> edges = new ArrayList<Edge>();
        for (CellEdge e : topology.edges) edges.add(new Edge(ids.get(e.a), ids.get(e.b)));
        Collections.sort(edges, new Comparator<Edge>() {
            @Override public int compare(Edge a, Edge b) {
                int c = Integer.compare(a.a, b.a); return c != 0 ? c : Integer.compare(a.b, b.b);
            }
        });
        boolean[][] floors = new boolean[DungeonLayout.MAP_SIZE][DungeonLayout.MAP_SIZE];
        List<Room> rooms = new ArrayList<Room>();
        List<String> trapKeys = new ArrayList<String>(catalog.traps.keySet());
        shuffle(trapKeys, new SplittableRandom(mix(seed, 0x3344L)));
        // One minute covers entry, inspection, looting choices and the final exit.
        int guardianIndex = 0, trapIndex = 0, estimate = 60;
        for (int cell : topology.cells) {
            int id = ids.get(cell); RoomKind kind = kinds.get(cell);
            SplittableRandom decor = new SplittableRandom(mix(seed, 0x4000L + cell));
            SplittableRandom mobs = new SplittableRandom(mix(seed, 0x5000L + cell));
            List<NpcSpawn> npcs = new ArrayList<NpcSpawn>();
            List<ChestSpawn> chests = new ArrayList<ChestSpawn>();
            List<TrapSpawn> traps = new ArrayList<TrapSpawn>();
            String template; int rotation = 0; Tile center = world(cell, 12, 12, 0);
            if (kind == RoomKind.TRAP) {
                List<Integer> directions = directions(topology, cell);
                boolean opposite = Math.floorMod(directions.get(0) - directions.get(1), 4) == 2;
                rotation = rotationFor(directions, opposite);
                template = opposite ? "gauntlet_straight" : "gauntlet_corner";
                ContentCatalog.TrapDef def = catalog.trap(trapKeys.get(trapIndex++ % trapKeys.size()));
                TrapSpawn trap = createTrap(cell, id, rotation, opposite, def, floors, mobs);
                traps.add(trap);
                center = trap.gauntlet.entranceOutside;
                // Both the skill shortcut and the slower combat route earn this cache.
                chests.add(new ChestSpawn(id * 100, "AGILITY", world(cell, 20, 13, rotation), false, 1));
            } else {
                template = template(kind, decor);
                carveNormal(cell, template, directions(topology, cell), floors);
                if (kind == RoomKind.COMBAT) {
                    int count = 3 + (mobs.nextInt(3) == 0 ? 1 : 0);
                    for (int i = 0; i < count; i++) npcs.add(normalSpawn(cell, id * 100 + i, i, mobs, i == 0));
                    if (decor.nextInt(100) < 35) chests.add(chest(cell, id * 100, "BASIC", 4, 4, true));
                } else if (kind == RoomKind.ELITE) {
                    npcs.add(new NpcSpawn(id * 100, pick(catalog.eliteNpcKeys, mobs), world(cell, 12, 16, 0)));
                    npcs.add(normalSpawn(cell, id * 100 + 1, 0, mobs, true));
                    npcs.add(normalSpawn(cell, id * 100 + 2, 1, mobs, false));
                    chests.add(chest(cell, id * 100, "ELITE", 4, 4, true));
                } else if (kind == RoomKind.GUARDIAN) {
                    npcs.add(new NpcSpawn(id * 100, catalog.guardianNpcKeys.get(guardianIndex++), world(cell, 12, 16, 0)));
                    npcs.add(normalSpawn(cell, id * 100 + 1, 0, mobs, true));
                    npcs.add(normalSpawn(cell, id * 100 + 2, 1, mobs, false));
                    chests.add(chest(cell, id * 100, "GUARDED", 4, 4, true));
                } else if (kind == RoomKind.TREASURE) {
                    npcs.add(normalSpawn(cell, id * 100, 0, mobs, true));
                    chests.add(chest(cell, id * 100, "BASIC", 4, 4, true));
                    chests.add(chest(cell, id * 100 + 1, "GUARDED", 19, 19, true));
                } else if (kind == RoomKind.BOSS) {
                    npcs.add(new NpcSpawn(id * 100, pick(catalog.bossNpcKeys, mobs), world(cell, 12, 16, 0)));
                    chests.add(chest(cell, id * 100, "BOSS", 4, 4, true));
                }
            }
            estimate += pacingSeconds(kind);
            rooms.add(new Room(id, cell % 4, cell / 4, kind, template, rotation, npcs, chests, traps, center));
        }
        int startId = ids.get(topology.cells.get(0));
        String theme = pick(catalog.themes, new SplittableRandom(mix(seed, 0x7788L)));
        DungeonLayout layout = new DungeonLayout(seed, theme, rooms, edges, startId,
                ids.get(bossCell), rooms.get(startId).center(), estimate, floors);
        DungeonValidator.validate(layout, catalog);
        return layout;
    }

    private TrapSpawn createTrap(int cell, int id, int rotation, boolean opposite,
                                 ContentCatalog.TrapDef def, boolean[][] floors,
                                 SplittableRandom random) {
        // Two approach banks; there is deliberately no normal floor across x=8..16.
        fill(floors, cell, rotation, 2, 11, 7, 14);
        fill(floors, cell, rotation, 17, 11, 21, 14);
        fill(floors, cell, rotation, 0, 11, 2, 13);
        if (opposite) fill(floors, cell, rotation, 21, 11, 23, 13);
        else {
            fill(floors, cell, rotation, 18, 13, 20, 21);
            fill(floors, cell, rotation, 12, 20, 19, 22);
            fill(floors, cell, rotation, 11, 21, 13, 23);
        }
        List<Tile> interior = new ArrayList<Tile>();
        for (int y = 4; y <= 9; y++) for (int x = 4; x <= 20; x++) {
            boolean inLane = y <= 6 || x <= 6 || x >= 18;
            if (inLane) {
                Tile p = world(cell, x, y, rotation); floors[p.x][p.y] = true; interior.add(p);
            }
        }
        Tile entranceOutside = world(cell, 5, 11, rotation), entranceDoor = world(cell, 5, 10, rotation);
        Tile entranceInside = world(cell, 5, 9, rotation), exitInside = world(cell, 19, 9, rotation);
        Tile exitDoor = world(cell, 19, 10, rotation), exitOutside = world(cell, 19, 11, rotation);
        floors[entranceDoor.x][entranceDoor.y] = true; floors[exitDoor.x][exitDoor.y] = true;
        List<Tile> lane = new ArrayList<Tile>();
        for (int y = 11; y >= 5; y--) lane.add(world(cell, 5, y, rotation));
        for (int x = 6; x <= 19; x++) lane.add(world(cell, x, 5, rotation));
        for (int y = 6; y <= 11; y++) lane.add(world(cell, 19, y, rotation));
        List<NpcSpawn> guards = new ArrayList<NpcSpawn>();
        List<Integer> anchors = new ArrayList<Integer>(Arrays.asList(0, 1, 2, 3, 4)); shuffle(anchors, random);
        int count = 3 + random.nextInt(3);
        List<String> guardKeys = Arrays.asList("grave_thrall", "ash_reaver", "bone_guard", "chainbound_dead", "hollow_swordsman");
        for (int i = 0; i < count; i++) {
            int[] a = GUARD_ANCHORS[anchors.get(i)];
            guards.add(new NpcSpawn(id * 100 + 50 + i, pick(guardKeys, random), world(cell, a[0], a[1], rotation)));
        }
        Gauntlet gauntlet = new Gauntlet(entranceOutside, entranceDoor, entranceInside,
                exitInside, exitDoor, exitOutside, lane, interior, guards);
        return new TrapSpawn(id * 100, def.key, world(cell, 7, 12, rotation),
                world(cell, 17, 12, rotation), entranceOutside, gauntlet);
    }

    private void carveNormal(int cell, String template, List<Integer> directions, boolean[][] floor) {
        fill(floor, cell, 0, 2, 2, 21, 21);
        // Different templates carry different small pillar groups; all door lanes stay clear.
        int[][] pillars = {{9,5},{15,5},{9,17},{15,17}};
        int offset = Math.floorMod(template.hashCode(), pillars.length);
        int count = 2 + Math.floorMod(template.hashCode(), 3);
        for (int i = 0; i < count; i++) {
            int[] p = pillars[(offset + i) % pillars.length];
            for (int x = p[0]; x <= p[0] + 1; x++) for (int y = p[1]; y <= p[1] + 1; y++) {
                Tile t = world(cell, x, y, 0); floor[t.x][t.y] = false;
            }
        }
        for (int d : directions) {
            if (d == 0) fill(floor, cell, 0, 11, 21, 13, 23);
            if (d == 1) fill(floor, cell, 0, 21, 11, 23, 13);
            if (d == 2) fill(floor, cell, 0, 11, 0, 13, 2);
            if (d == 3) fill(floor, cell, 0, 0, 11, 2, 13);
        }
    }

    private NpcSpawn normalSpawn(int cell, int slot, int anchor, SplittableRandom random, boolean meleeOnly) {
        List<String> eligible = new ArrayList<String>(); int total = 0;
        for (String key : catalog.normalNpcKeys) {
            ContentCatalog.NpcDef n = catalog.npc(key);
            if (meleeOnly && n.style != ContentCatalog.Style.MELEE) continue;
            eligible.add(key); total += n.weight;
        }
        int roll = random.nextInt(total); String selected = eligible.get(eligible.size() - 1);
        for (String key : eligible) { roll -= catalog.npc(key).weight; if (roll < 0) { selected = key; break; } }
        int[] p = MOB_ANCHORS[anchor % MOB_ANCHORS.length];
        return new NpcSpawn(slot, selected, world(cell, p[0], p[1], 0));
    }
    private ChestSpawn chest(int cell, int slot, String table, int x, int y, boolean clear) {
        return new ChestSpawn(slot, table, world(cell, x, y, 0), clear, 1);
    }
    private String template(RoomKind kind, SplittableRandom random) {
        if (kind == RoomKind.ENTRY) return "crypt_hall";
        if (kind == RoomKind.SHRINE) return "forgotten_shrine";
        if (kind == RoomKind.TREASURE) return "sealed_reliquary";
        if (kind == RoomKind.GUARDIAN) return "ritual_chamber";
        if (kind == RoomKind.BOSS) return "iron_vault";
        return catalog.roomTemplates.get(random.nextInt(10));
    }
    private static int pacingSeconds(RoomKind kind) {
        switch (kind) {
            case ENTRY: return 20; case COMBAT: return 50; case ELITE: return 65;
            case TREASURE: return 45; case TRAP: return 65; case SHRINE: return 25;
            case GUARDIAN: return 80; case BOSS: return 150;
            default: throw new IllegalArgumentException("kind");
        }
    }

    private Topology chooseTopology(long seed, int count) {
        Topology best = null; int bestScore = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < 96; attempt++) {
            SplittableRandom random = new SplittableRandom(mix(seed, 0xA000L + attempt));
            Topology t = new Topology(); int start = CORNERS[random.nextInt(CORNERS.length)];
            t.cells.add(start); List<Integer> stack = new ArrayList<Integer>(); stack.add(start);
            while (t.cells.size() < count && !stack.isEmpty()) {
                int current = stack.get(stack.size() - 1);
                List<Integer> next = new ArrayList<Integer>();
                for (int n : adjacentCells(current)) if (!t.cells.contains(n)) next.add(n);
                if (next.isEmpty()) stack.remove(stack.size() - 1);
                else { int chosen = pick(next, random); t.cells.add(chosen); t.edges.add(new CellEdge(current, chosen)); stack.add(chosen); }
            }
            if (t.cells.size() != count) continue;
            Map<Integer, Integer> depth = distances(t, start);
            int leaves = 0, degreeTwo = 0, maxDepth = 0;
            for (int cell : t.cells) {
                int degree = t.neighbors(cell).size(); if (degree == 1) leaves++; if (degree == 2) degreeTwo++;
                maxDepth = Math.max(maxDepth, depth.get(cell));
            }
            if (degreeTwo < 3) continue;
            int score = maxDepth + leaves * 2;
            if (score > bestScore) { best = t; bestScore = score; }
            if (maxDepth >= 7 && leaves >= 3) return t;
        }
        if (best == null) throw new IllegalStateException("Bounded topology generation failed for seed " + seed);
        return best;
    }
    private static int deepestLeaf(Topology t, Map<Integer, Integer> depth) {
        int result = t.cells.get(1), distance = -1;
        for (int cell : t.cells) if (t.neighbors(cell).size() == 1 && depth.get(cell) > distance) {
            result = cell; distance = depth.get(cell);
        }
        return result;
    }
    private static List<Integer> chooseTraps(Topology t, final Map<Integer, Integer> depth, int boss, long seed) {
        List<Integer> eligible = new ArrayList<Integer>();
        for (int cell : t.cells) if (cell != boss && cell != t.cells.get(0) && t.neighbors(cell).size() == 2) eligible.add(cell);
        shuffle(eligible, new SplittableRandom(mix(seed, 0xB11L)));
        if (eligible.size() < 2) throw new IllegalStateException("No trap bottlenecks");
        int a = eligible.get(0), b = eligible.get(1);
        Map<Integer, Integer> distance = distances(t, a);
        for (int candidate : eligible) if (candidate != a && distance.get(candidate) >= 3) { b = candidate; break; }
        return Arrays.asList(a, b);
    }
    private static void addLoops(Topology t, List<Integer> traps, int boss, SplittableRandom random) {
        List<CellEdge> possible = new ArrayList<CellEdge>();
        for (int a : t.cells) for (int b : adjacentCells(a)) {
            if (a >= b || !t.cells.contains(b) || traps.contains(a) || traps.contains(b) || a == boss || b == boss || t.linked(a, b)) continue;
            possible.add(new CellEdge(a, b));
        }
        shuffle(possible, random);
        int count = Math.min(possible.size(), random.nextInt(3));
        for (int i = 0; i < count; i++) t.edges.add(possible.get(i));
    }
    private static Map<Integer, RoomKind> assignKinds(final Topology t, List<Integer> traps, int boss, long seed) {
        LinkedHashMap<Integer, RoomKind> result = new LinkedHashMap<Integer, RoomKind>();
        int start = t.cells.get(0); result.put(start, RoomKind.ENTRY); result.put(boss, RoomKind.BOSS);
        for (int cell : traps) result.put(cell, RoomKind.TRAP);
        final Map<Integer, Integer> depth = distances(t, start);
        List<Integer> remaining = new ArrayList<Integer>(); for (int cell : t.cells) if (!result.containsKey(cell)) remaining.add(cell);
        Collections.sort(remaining, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) { int c = Integer.compare(depth.get(a), depth.get(b)); return c != 0 ? c : Integer.compare(a, b); }
        });
        // Spread sigils across the whole exploration route. No sigil is tied to a skill check.
        List<Integer> guardians = Arrays.asList(remaining.get(remaining.size() / 4),
                remaining.get(remaining.size() / 2), remaining.get(remaining.size() * 3 / 4));
        for (int cell : guardians) { result.put(cell, RoomKind.GUARDIAN); remaining.remove(Integer.valueOf(cell)); }
        Collections.sort(remaining, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                int c = Integer.compare(t.neighbors(a).size(), t.neighbors(b).size());
                return c != 0 ? c : Integer.compare(depth.get(b), depth.get(a));
            }
        });
        for (int i = 0; i < 2; i++) result.put(remaining.remove(0), RoomKind.TREASURE);
        shuffle(remaining, new SplittableRandom(mix(seed, 0xB22L)));
        result.put(remaining.remove(0), RoomKind.SHRINE); result.put(remaining.remove(0), RoomKind.ELITE);
        for (int cell : remaining) result.put(cell, RoomKind.COMBAT);
        return result;
    }
    private static List<Integer> directions(Topology t, int cell) {
        List<Integer> dirs = new ArrayList<Integer>();
        for (int neighbor : t.neighbors(cell)) {
            int dx = neighbor % 4 - cell % 4, dy = neighbor / 4 - cell / 4;
            dirs.add(dy == 1 ? 0 : dx == 1 ? 1 : dy == -1 ? 2 : 3);
        }
        return dirs;
    }
    private static int rotationFor(List<Integer> directions, boolean opposite) {
        Set<Integer> wanted = new HashSet<Integer>(directions);
        for (int r = 0; r < 4; r++) {
            Set<Integer> actual = new HashSet<Integer>(Arrays.asList((3 + 3*r) % 4, ((opposite ? 1 : 0) + 3*r) % 4));
            if (actual.equals(wanted)) return r;
        }
        throw new IllegalStateException("Unsupported trap room exits " + directions);
    }
    private static List<Integer> adjacentCells(int cell) {
        List<Integer> result = new ArrayList<Integer>(4); int x = cell % 4, y = cell / 4;
        if (y < 3) result.add(cell + 4); if (x < 3) result.add(cell + 1);
        if (y > 0) result.add(cell - 4); if (x > 0) result.add(cell - 1);
        return result;
    }
    private static Map<Integer, Integer> distances(Topology t, int start) {
        Map<Integer, Integer> d = new HashMap<Integer, Integer>(); List<Integer> queue = new ArrayList<Integer>();
        d.put(start, 0); queue.add(start);
        for (int i = 0; i < queue.size(); i++) { int p = queue.get(i);
            for (int n : t.neighbors(p)) if (!d.containsKey(n)) { d.put(n, d.get(p) + 1); queue.add(n); }
        }
        return d;
    }
    private static void fill(boolean[][] floor, int cell, int rotation, int x1, int y1, int x2, int y2) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) { Tile t = world(cell, x, y, rotation); floor[t.x][t.y] = true; }
    }
    private static Tile world(int cell, int x, int y, int rotation) {
        for (int r = 0; r < rotation; r++) { int nextX = 23 - y; y = x; x = nextX; }
        return new Tile(cell % 4 * 24 + x, cell / 4 * 24 + y);
    }
    private static <T> T pick(List<T> list, SplittableRandom random) { return list.get(random.nextInt(list.size())); }
    private static <T> void shuffle(List<T> list, SplittableRandom random) {
        for (int i = list.size() - 1; i > 0; i--) { int j = random.nextInt(i + 1); T value = list.get(i); list.set(i, list.get(j)); list.set(j, value); }
    }
    static long mix(long seed, long salt) {
        long z = seed + 0x9E3779B97F4A7C15L * (salt + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L; z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    private static final class CellEdge { final int a, b; CellEdge(int a, int b) { this.a = a; this.b = b; } }
    private static final class Topology {
        final List<Integer> cells = new ArrayList<Integer>(); final List<CellEdge> edges = new ArrayList<CellEdge>();
        List<Integer> neighbors(int cell) { List<Integer> n = new ArrayList<Integer>(); for (CellEdge e : edges) { if (e.a == cell) n.add(e.b); else if (e.b == cell) n.add(e.a); } return n; }
        boolean linked(int a, int b) { for (CellEdge e : edges) if ((e.a == a && e.b == b) || (e.a == b && e.b == a)) return true; return false; }
    }
}
