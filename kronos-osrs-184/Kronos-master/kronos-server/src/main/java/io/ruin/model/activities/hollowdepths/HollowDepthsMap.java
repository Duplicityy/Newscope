package io.ruin.model.activities.hollowdepths;

import com.newscape.dungeons.DungeonLayout;
import com.newscape.dungeons.DungeonPorts;
import com.newscape.dungeons.RevisionManifest;
import io.ruin.cache.ObjectDef;
import io.ruin.model.entity.Entity;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.dynamic.DynamicChunk;
import io.ruin.model.map.dynamic.DynamicMap;
import io.ruin.model.map.object.GameObject;

import java.util.*;

/** Real 184 terrain with visible, clipped geometry generated from the exact 96x96 blueprint. */
public final class HollowDepthsMap {
    public enum ObjectKind { CHEST, TRAP, DOOR, SHRINE, EXIT }
    public static final class ObjectHandle {
        public final GameObject object;
        public final ObjectKind kind;
        public final String key;
        public final int roomId, slot;
        public final DungeonLayout.Tile local;
        private ObjectHandle(GameObject object, ObjectKind kind, String key, int roomId,
                             int slot, DungeonLayout.Tile local) {
            this.object = object; this.kind = kind; this.key = key;
            this.roomId = roomId; this.slot = slot; this.local = local;
        }
    }

    public final DungeonPorts.Instance identity;
    public final String account;
    public final DungeonLayout layout;
    public final DynamicMap map;
    private final HollowDepthsAssets assets;
    private final int baseX, baseY, lease;
    private final List<DynamicChunk> chunks = new ArrayList<>();
    private final List<GameObject> copiedObjects = new ArrayList<>();
    private final List<ObjectHandle> handles = new ArrayList<>();
    private final Map<String, ObjectHandle> doors = new LinkedHashMap<>();
    private final Map<Integer, ObjectHandle> chests = new HashMap<>();
    private final Set<DungeonLayout.Tile> solidInteractions = new HashSet<>();
    private final Set<DungeonLayout.Tile> gapTiles = new HashSet<>();
    private final Set<DungeonLayout.Tile> visibleBoundary = new HashSet<>();
    private final Map<DungeonLayout.Tile, GameObject> boundaryObjects = new HashMap<>();
    private int nextChunk, nextTile;
    private boolean complete, destroyed;

    public HollowDepthsMap(DungeonPorts.Instance identity, String account,
                           DungeonLayout layout, HollowDepthsAssets assets) {
        this.identity = Objects.requireNonNull(identity); this.account = Objects.requireNonNull(account);
        this.layout = Objects.requireNonNull(layout); this.assets = Objects.requireNonNull(assets);
        assets.bindings();
        map = new DynamicMap(); baseX = map.swRegion.baseX; baseY = map.swRegion.baseY;
        lease = map.swRegion.dynamicIndex;
        map.setObjectConsumer(object -> {
            // These copied objects are removed before entry. Suppress their temporary
            // clipping so a source wall on an outer edge cannot clear a neighbour's mask.
            // Keep them as non-spawned objects: remove() below still records client tombstones.
            object.skipClipping(true);
            copiedObjects.add(object);
        });
        try {
            DungeonLayout.Room[][] slots = new DungeonLayout.Room[4][4];
            for (DungeonLayout.Room room : layout.rooms) {
                slots[room.gridX][room.gridY] = room;
                for (DungeonLayout.TrapSpawn trap : room.traps) {
                    int dx = Integer.signum(trap.landing.x - trap.trigger.x);
                    int dy = Integer.signum(trap.landing.y - trap.trigger.y);
                    for (int step = 1; step < trap.trigger.manhattanDistance(trap.landing); step++) {
                        DungeonLayout.Tile center = trap.trigger.translate(dx * step, dy * step);
                        // Four-tile-wide visible hazard matching the two approach banks.
                        for (int width = -1; width <= 2; width++) {
                            DungeonLayout.Tile tile = center.translate(-dy * width, dx * width);
                            if (!layout.walkable(tile)) gapTiles.add(tile);
                        }
                    }
                }
            }
            RevisionManifest.TemplateBinding fallback = assets.bindings().template("crypt_hall");
            for (int x = 0; x < 12; x++) for (int y = 0; y < 12; y++) {
                DungeonLayout.Room room = slots[x / 3][y / 3];
                RevisionManifest.TemplateBinding source = room == null ? fallback
                        : assets.bindings().template(room.templateKey, room.rotation);
                chunks.add(new DynamicChunk(source.sourceChunkX + x % 3, source.sourceChunkY + y % 3,
                        source.plane).pos(x, y, 0));
            }
        } catch (RuntimeException failure) { map.destroy(); throw failure; }
    }

    public Position world(DungeonLayout.Tile tile) {
        if (!inBounds(tile)) throw new IllegalArgumentException("Tile outside dungeon");
        return new Position(baseX + tile.x, baseY + tile.y, 0);
    }
    public DungeonLayout.Tile local(Position position) {
        if (position == null || position.getZ() != 0) return null;
        DungeonLayout.Tile result = new DungeonLayout.Tile(position.getX() - baseX, position.getY() - baseY);
        return inBounds(result) ? result : null;
    }
    public boolean contains(Entity entity) {
        return !destroyed && entity != null && local(entity.getPosition()) != null
                && Region.get(entity.getPosition().getX(), entity.getPosition().getY()).dynamicIndex == lease;
    }
    public List<ObjectHandle> objects() { return Collections.unmodifiableList(handles); }

    /** Source copying is bounded; static geometry also advances in bounded 64-tile work units. */
    public boolean step(int chunkBudget) {
        ensureLease();
        if (chunkBudget <= 0 || complete) return complete;
        int end = Math.min(chunks.size(), nextChunk + chunkBudget);
        if (nextChunk < end) {
            map.build(chunks.subList(nextChunk, end));
            nextChunk = end;
            // A skipped source object is still drawn by the client. Keep a native removal tombstone instead.
            for (GameObject object : copiedObjects) object.remove();
            copiedObjects.clear();
        }
        if (nextChunk != chunks.size()) return false;
        int tiles = Math.min(96 * 96, nextTile + chunkBudget * 64);
        for (; nextTile < tiles; nextTile++) buildTile(nextTile / 96, nextTile % 96);
        if (nextTile != 96 * 96) return false;
        populateInteractions();
        complete = true;
        return true;
    }

    private void buildTile(int x, int y) {
        DungeonLayout.Tile local = new DungeonLayout.Tile(x, y);
        Tile tile = Tile.get(baseX + x, baseY + y, 0, true);
        // Audited source floor is flat and clear; all scenery has already been removed natively.
        if (tile.clipping != 0 || tile.projectileClipping != 0)
            throw new IllegalStateException("Residual source clipping at " + local);
        tile.multi = false; tile.nearBank = false;
        if (layout.walkable(local)) return;
        tile.flagUnmovable();
        // Match native full-object projectile clipping: ProjectileRoute also checks 0x100.
        tile.projectileClipping |= 0x20100;
        boolean gap = gapTiles.contains(local);
        if (gap || bordersFloor(local)) {
            int id = assets.objectId(gap ? "obstacle.gap" : "wall." + layout.themeKey);
            int type = gap ? 22 : 10;
            GameObject object = spawn(id, local, type, 0);
            boundaryObjects.put(local, object); visibleBoundary.add(local);
        }
    }

    private boolean bordersFloor(DungeonLayout.Tile tile) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            if ((dx != 0 || dy != 0) && layout.walkable(tile.translate(dx, dy))) return true;
        return false;
    }

    private void populateInteractions() {
        for (DungeonLayout.Room room : layout.rooms) {
            for (DungeonLayout.ChestSpawn chest : room.chests) {
                ObjectHandle handle = add("chest.closed", chest.tile, 10, room.rotation, ObjectKind.CHEST,
                        "chest:" + chest.slot, room.id, chest.slot);
                chests.put(chest.slot, handle); solidInteractions.add(chest.tile);
            }
            for (DungeonLayout.TrapSpawn trap : room.traps) {
                add("obstacle.trigger", trap.trigger, 22, room.rotation, ObjectKind.TRAP,
                        "trap:" + trap.slot, room.id, trap.slot);
                // A real, visible landing marker; its per-object callback can offer the reverse traversal.
                add("obstacle.landing", trap.landing, 22, room.rotation, ObjectKind.TRAP,
                        "trap:" + trap.slot, room.id, trap.slot);
                addDoor("trap:" + trap.slot + ":entry", room, trap.slot, trap.gauntlet.entranceDoor);
                addDoor("trap:" + trap.slot + ":exit", room, trap.slot, trap.gauntlet.exitDoor);
            }
            if (room.kind == DungeonLayout.RoomKind.SHRINE) {
                add("shrine.ready", room.center(), 10, 0, ObjectKind.SHRINE, "shrine:" + room.id, room.id, room.id);
                solidInteractions.add(room.center());
            }
            if (room.kind == DungeonLayout.RoomKind.BOSS)
                add("portal.exit", room.center(), 22, 0, ObjectKind.EXIT, "exit", room.id, room.id);
            decorate(room);
        }
    }

    private void decorate(DungeonLayout.Room room) {
        // Template-specific pillar geometry comes from the blueprint. Themes add native scenery
        // on blocked cells behind the boundary, never on an actor/chest/corridor reservation.
        int selection = Math.floorMod(room.templateKey.hashCode(), 4);
        int[][] corners = {{3,1},{20,1},{1,3},{1,20}};
        int[] point = corners[selection];
        DungeonLayout.Tile at = new DungeonLayout.Tile(room.gridX * 24 + point[0], room.gridY * 24 + point[1]);
        if (layout.walkable(at) || gapTiles.contains(at) || boundaryObjects.containsKey(at)) return;
        spawn(assets.objectId("decor." + layout.themeKey), at, 10, selection);
    }

    private void addDoor(String key, DungeonLayout.Room room, int slot, DungeonLayout.Tile tile) {
        ObjectHandle handle = add("door.closed", tile, 10, room.rotation, ObjectKind.DOOR, key, room.id, slot);
        doors.put(key, handle);
    }
    private ObjectHandle add(String asset, DungeonLayout.Tile tile, int type, int direction,
                             ObjectKind kind, String key, int room, int slot) {
        GameObject object = spawn(assets.objectId(asset), tile, type, direction);
        ObjectHandle handle = new ObjectHandle(object, kind, key, room, slot, tile);
        handles.add(handle); return handle;
    }
    private GameObject spawn(int id, DungeonLayout.Tile tile, int type, int direction) {
        ObjectDef definition = ObjectDef.get(id);
        if (definition == null || definition.xLength != 1 || definition.yLength != 1
                || !HollowDepthsAssets.supports(definition, type))
            throw new IllegalStateException("Unsupported 1x1 object " + id + " / type " + type);
        Position position = world(tile);
        GameObject object = new GameObject(id, position.getX(), position.getY(), 0, type, direction);
        // Scenery shares native cache IDs with other activities. Never inherit their global actions.
        // The adapter binds the intended callbacks to registered interaction handles after building.
        object.actions = new io.ruin.model.map.object.actions.ObjectAction[5];
        Arrays.fill(object.actions, (io.ruin.model.map.object.actions.ObjectAction) (player, clicked) -> { });
        object.spawn(); return object;
    }

    public void setDoor(String key, boolean open) {
        ensureLease();
        ObjectHandle handle = doors.get(key);
        if (handle == null) throw new IllegalArgumentException("Unknown instance door: " + key);
        handle.object.setId(assets.objectId(open ? "door.open" : "door.closed"));
    }
    public void openedChest(int slot) {
        ensureLease(); ObjectHandle handle = chests.get(slot);
        if (handle == null) throw new IllegalArgumentException("Unknown instance chest: " + slot);
        handle.object.setId(assets.objectId("chest.open"));
    }

    /** Checks the actual constructed scene, movement masks, projectile masks and reachability. */
    public String validate() {
        if (destroyed || !complete) return "Dungeon construction is incomplete";
        try { ensureLease(); } catch (RuntimeException error) { return error.getMessage(); }
        for (int x = 0; x < 12; x++) for (int y = 0; y < 12; y++) {
            Region region = Region.get(baseX + x * 8, baseY + y * 8);
            if (region.dynamicData == null || region.dynamicData[0][x & 7][y & 7][1] == 0)
                return "Missing dynamic terrain chunk " + x + "," + y;
        }
        Set<DungeonLayout.Tile> closedDoors = new HashSet<>();
        for (ObjectHandle door : doors.values()) {
            boolean closed = door.object.id == assets.objectId("door.closed");
            if (closed) closedDoors.add(door.local);
            Tile tile = nativeTile(door.local);
            if (((tile.clipping & 0x100) != 0) != closed || ((tile.projectileClipping & 0x20000) != 0) != closed)
                return "Door visual/collision mismatch: " + door.key;
        }
        for (int x = 0; x < 96; x++) for (int y = 0; y < 96; y++) {
            DungeonLayout.Tile local = new DungeonLayout.Tile(x,y); Tile tile = nativeTile(local);
            boolean floor = layout.walkable(local);
            boolean solid = solidInteractions.contains(local) || closedDoors.contains(local);
            if (tile == null) return "Missing native tile " + local;
            if (floor) {
                if (solid ? tile.isFloorFree() : tile.clipping != 0)
                    return "Floor clipping mismatch at " + local;
                if (!solid && tile.projectileClipping != 0) return "Projectile floor mismatch at " + local;
            } else {
                if ((tile.clipping & 0x200000) == 0 || (tile.projectileClipping & 0x20100) != 0x20100)
                    return "Unsealed blueprint boundary at " + local;
                if (bordersFloor(local) && (!visibleBoundary.contains(local)
                        || boundaryObjects.get(local).id < 0)) return "Invisible boundary at " + local;
            }
        }
        // Opening and closing the real definitions checks both states rather than trusting names.
        List<ObjectHandle> temporarilyOpened = new ArrayList<>();
        try {
            for (ObjectHandle door : doors.values()) if (closedDoors.contains(door.local)) {
                setDoor(door.key, true); temporarilyOpened.add(door);
                Tile tile = nativeTile(door.local);
                if (tile.clipping != 0 || tile.projectileClipping != 0) return "Open door still clips: " + door.key;
            }
            Set<DungeonLayout.Tile> reached = floodActual(layout.entry);
            for (DungeonLayout.Room room : layout.rooms) {
                if (!reached.contains(room.center()) && !adjacentReached(reached, room.center()))
                    return "Unreachable room " + room.id;
                for (DungeonLayout.ChestSpawn chest : room.chests)
                    if (!adjacentReached(reached, chest.tile)) return "Unreachable chest " + chest.slot;
                for (DungeonLayout.NpcSpawn npc : room.npcs)
                    if (!reached.contains(npc.tile) || assets.npc(assets.npcId(npc.archetypeKey)).size != 1)
                        return "Invalid NPC reservation " + npc.slot;
                for (DungeonLayout.TrapSpawn trap : room.traps) {
                    if (!reached.contains(trap.trigger) || !reached.contains(trap.landing)) return "Disconnected trap " + trap.slot;
                    for (DungeonLayout.NpcSpawn guard : trap.gauntlet.guards)
                        if (!trap.gauntlet.contains(guard.tile) || !reached.contains(guard.tile)
                                || assets.npc(assets.npcId(guard.archetypeKey)).size != 1)
                            return "Invalid guard reservation " + guard.slot;
                }
            }
        } finally {
            for (ObjectHandle door : temporarilyOpened) setDoor(door.key, false);
        }
        return null;
    }

    private Set<DungeonLayout.Tile> floodActual(DungeonLayout.Tile start) {
        Set<DungeonLayout.Tile> reached = new HashSet<>(); ArrayDeque<DungeonLayout.Tile> queue = new ArrayDeque<>();
        if (nativeTile(start).clipping != 0) return reached;
        reached.add(start); queue.add(start);
        int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!queue.isEmpty()) {
            DungeonLayout.Tile at = queue.remove();
            for (int[] direction : directions) {
                DungeonLayout.Tile next = at.translate(direction[0],direction[1]);
                if (inBounds(next) && nativeTile(next).clipping == 0 && reached.add(next)) queue.add(next);
            }
        }
        return reached;
    }
    private static boolean adjacentReached(Set<DungeonLayout.Tile> reached, DungeonLayout.Tile at) {
        return reached.contains(at.translate(1,0)) || reached.contains(at.translate(-1,0))
                || reached.contains(at.translate(0,1)) || reached.contains(at.translate(0,-1));
    }
    private Tile nativeTile(DungeonLayout.Tile local) { return Tile.get(baseX + local.x,baseY + local.y,0,false); }
    private static boolean inBounds(DungeonLayout.Tile tile) {
        return tile != null && tile.x >= 0 && tile.y >= 0 && tile.x < 96 && tile.y < 96;
    }
    private void ensureLease() {
        if (destroyed || map.swRegion == null || map.swRegion.dynamicIndex != lease)
            throw new IllegalStateException("Stale dungeon map lease");
        for (Region region : map.getRegions())
            if (region != null && region.dynamicIndex != lease) throw new IllegalStateException("Dungeon region lease changed");
    }
    public void destroy() {
        if (destroyed) return;
        ensureLease();
        for (Region region : map.getRegions())
            if (region != null && !region.players.isEmpty()) throw new IllegalStateException("Cannot release an occupied dungeon");
        map.destroy(); destroyed = true;
        handles.clear(); doors.clear(); chests.clear(); copiedObjects.clear();
    }
}
