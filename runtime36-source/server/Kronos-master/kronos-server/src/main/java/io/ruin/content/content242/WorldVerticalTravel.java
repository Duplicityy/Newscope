package io.ruin.content.content242;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ruin.Server;
import io.ruin.cache.ObjectDef;
import io.ruin.model.World;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.LockType;
import io.ruin.model.map.Position;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.model.map.object.GameObject;
import io.ruin.model.map.object.actions.ObjectAction;
import io.ruin.model.map.object.actions.impl.Ladder;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Reciprocal world stair, ladder, and trapdoor routes checked against the loaded map. */
final class WorldVerticalTravel {
    private WorldVerticalTravel() {}

    static void initialize() {
        Path path = Server.dataFolder.toPath().resolve("world-vertical-travel.json");
        if (!Files.isRegularFile(path)) return;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            if (Files.size(path) > 1_048_576) throw new IllegalArgumentException("Oversized vertical route manifest");
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            World242.integer(root.get("schema"), 1, 1);
            List<Binding> bindings = new ArrayList<>();
            Set<String> used = new HashSet<>();
            int excluded = 0;
            for (JsonElement value : World242.array(root, "pairs", 500)) {
                JsonObject pair = value.getAsJsonObject();
                Endpoint first = new Endpoint(pair.getAsJsonObject("first"));
                Endpoint second = new Endpoint(pair.getAsJsonObject("second"));
                if (!used.add(first.key()) || !used.add(second.key()))
                    throw new IllegalArgumentException("Duplicate vertical route endpoint");
                GameObject firstObject = first.available();
                GameObject secondObject = second.available();
                if (firstObject == null || secondObject == null) {
                    excluded++;
                    continue;
                }
                List<Landing> options = new ArrayList<>();
                List<Landing> reverse = new ArrayList<>();
                for (JsonElement route : World242.array(pair, "landings", 8)) {
                    JsonObject row = route.getAsJsonObject();
                    Position firstStand = WorldTravel242.point(row.getAsJsonArray("first"));
                    Position secondStand = WorldTravel242.point(row.getAsJsonArray("second"));
                    if (!first.approach(firstObject, firstStand) || !second.approach(secondObject, secondStand)
                            || !WorldTravel242.walkable(firstStand) || !WorldTravel242.walkable(secondStand)) continue;
                    options.add(new Landing(firstStand, secondStand));
                    reverse.add(new Landing(secondStand, firstStand));
                }
                if (options.isEmpty()) {
                    excluded++;
                    continue;
                }
                bindings.add(new Binding(first, firstObject, options, second, secondObject, reverse));
            }
            bindings.forEach(Binding::register);
            Server.println("World vertical travel: " + bindings.size() + " reciprocal links, " + excluded + " skipped.");
            int sewerEntrances = 0;
            if (bindSewer(3084, 3272, 17385, 9672, 1, Position.of(3085, 3272, 0), Position.of(3085, 9672, 0)))
                sewerEntrances++;
            if (bindSewer(3118, 3244, 6436, 9643, 2, Position.of(3118, 3243, 0), Position.of(3118, 9644, 0)))
                sewerEntrances++;
            Server.println("Draynor Sewers: " + sewerEntrances + " of 2 entrances bound.");
        } catch (Exception invalid) {
            Server.println("Vertical travel manifest rejected. " + invalid.getMessage());
        }
    }

    private static final int CLOSED_TRAPDOOR = 6434;
    private static final int OPEN_TRAPDOOR = 6435;

    private static boolean bindSewer(int x, int y, int ladderId, int ladderY, int ladderRotation,
                                     Position surface, Position sewer) {
        GameObject trapdoor = worldObject(CLOSED_TRAPDOOR, x, y, 0, 10, 0);
        GameObject ladder = worldObject(ladderId, x, ladderY, 0, 10, ladderRotation);
        if (trapdoor == null || ladder == null || !WorldTravel242.walkable(surface) || !WorldTravel242.walkable(sewer))
            return false;
        ObjectDef open = ObjectDef.get(OPEN_TRAPDOOR);
        if (open == null || !open.name.equalsIgnoreCase("Trapdoor") || open.getOption("Climb-down") != 1
                || action(open.defaultActions, 1) != null
                || !trapdoor.getDef().name.equalsIgnoreCase("Trapdoor") || trapdoor.getDef().getOption("Open") != 1
                || !ladder.getDef().name.equalsIgnoreCase("Ladder") || ladder.getDef().getOption("Climb-up") != 1
                || action(trapdoor.actions, 1) != null || action(ladder.actions, 1) != null
                || !Ladder.isGenericFloorAction(action(ladder.getDef().defaultActions, 1))) return false;

        BooleanSupplier hatchPresent = () -> (trapdoor.id == CLOSED_TRAPDOOR || trapdoor.id == OPEN_TRAPDOOR)
                && worldObject(trapdoor.id, x, y, 0, 10, 0) == trapdoor;
        BooleanSupplier ladderPresent = () -> worldObject(ladderId, x, ladderY, 0, 10, ladderRotation) == ladder;
        ObjectAction.register(trapdoor, 1, (player, clicked) -> {
            if (player.isLocked() || clicked != trapdoor || !beside(player, trapdoor)
                    || !hatchPresent.getAsBoolean() || !ladderPresent.getAsBoolean()) return;
            if (trapdoor.id == CLOSED_TRAPDOOR) {
                trapdoor.setId(OPEN_TRAPDOOR);
                World.startEvent(event -> {
                    event.delay(300);
                    if (trapdoor.id == OPEN_TRAPDOOR && hatchPresent.getAsBoolean()) trapdoor.restore();
                });
                return;
            }
            climb(player, sewer, 827, 1, () -> beside(player, trapdoor) && trapdoor.id == OPEN_TRAPDOOR
                    && hatchPresent.getAsBoolean() && ladderPresent.getAsBoolean());
        });
        ObjectAction.register(ladder, 1, (player, clicked) -> {
            if (player.isLocked() || clicked != ladder || !beside(player, ladder)
                    || !ladderPresent.getAsBoolean() || !hatchPresent.getAsBoolean()) return;
            climb(player, surface, 828, 2, () -> beside(player, ladder)
                    && ladderPresent.getAsBoolean() && hatchPresent.getAsBoolean());
        });
        return true;
    }

    private static GameObject worldObject(int id, int x, int y, int z, int type, int rotation) {
        Region region = Region.get(x, y);
        if (region == null || region.empty || region.dynamicIndex != -1) return null;
        return Tile.getObject(id, x, y, z, type, rotation);
    }

    private static ObjectAction action(ObjectAction[] actions, int slot) {
        return actions == null ? null : actions[slot - 1];
    }

    private static boolean beside(Player player, GameObject object) {
        return player.getHeight() == object.z
                && Math.abs(player.getAbsX() - object.x) + Math.abs(player.getAbsY() - object.y) <= 1;
    }

    private static void climb(Player player, Position destination, int animation, int ticks, BooleanSupplier connected) {
        if (!WorldTravel242.walkable(destination)) return;
        player.startEvent(event -> {
            player.lock(LockType.FULL_DELAY_DAMAGE);
            try {
                if (animation != -1) player.animate(animation);
                event.delay(ticks);
                if (connected.getAsBoolean() && WorldTravel242.walkable(destination))
                    player.getMovement().teleport(destination.getX(), destination.getY(), destination.getZ());
            } finally {
                if (animation != -1) player.resetAnimation();
                player.unlock();
            }
        });
    }

    private static final class Endpoint {
        final int id;
        final Position at;
        final int type;
        final int rotation;
        final int slot;
        final int ticks;
        final String name;
        final String option;

        Endpoint(JsonObject row) {
            id = World242.integer(row.get("id"), 0, 65534);
            at = WorldTravel242.point(row.getAsJsonArray("at"));
            type = World242.integer(row.get("type"), 0, 22);
            rotation = World242.integer(row.get("rotation"), 0, 3);
            slot = World242.integer(row.get("slot"), 1, 5);
            ticks = World242.integer(row.get("ticks"), 1, 4);
            name = row.get("name").getAsString();
            option = row.get("option").getAsString();
            if (!name.matches("(?i)ladder|stairs?|staircase|trapdoor")
                    || !option.matches("(?i)climb(-up|-down)?|enter"))
                throw new IllegalArgumentException("Unsupported vertical route action");
        }

        String key() {
            return id + ":" + at.getX() + ":" + at.getY() + ":" + at.getZ() + ":" + type + ":" + rotation + ":" + slot;
        }

        GameObject current() {
            return worldObject(id, at.getX(), at.getY(), at.getZ(), type, rotation);
        }

        GameObject available() {
            GameObject object = current();
            if (object == null || !object.getDef().name.equalsIgnoreCase(name)
                    || object.getDef().getOption(option) != slot || action(object.actions, slot) != null) return null;
            ObjectAction fallback = action(object.getDef().defaultActions, slot);
            return fallback == null || Ladder.isGenericFloorAction(fallback) ? object : null;
        }

        boolean approach(GameObject object, Position stand) {
            if (stand.getZ() != at.getZ()) return false;
            int width = rotation % 2 == 0 ? object.getDef().xLength : object.getDef().yLength;
            int height = rotation % 2 == 0 ? object.getDef().yLength : object.getDef().xLength;
            int dx = Math.max(0, Math.max(object.x - stand.getX(), stand.getX() - (object.x + width - 1)));
            int dy = Math.max(0, Math.max(object.y - stand.getY(), stand.getY() - (object.y + height - 1)));
            return dx + dy <= 1;
        }

        int animation(Position destination) {
            if (name.equalsIgnoreCase("ladder") || name.equalsIgnoreCase("trapdoor")) {
                boolean up = option.equalsIgnoreCase("climb-up") || destination.getZ() > at.getZ();
                return up ? 828 : 827;
            }
            return -1;
        }

        void bind(GameObject object, List<Landing> options, Endpoint exit, GameObject exitObject) {
            ObjectAction.register(object, slot, (player, clicked) -> {
                if (player.isLocked() || clicked != object || !approach(object, player.getPosition())
                        || current() != object || exit.current() != exitObject) return;
                Landing closest = null;
                int distance = Integer.MAX_VALUE;
                for (Landing landing : options) {
                    int offset = Math.abs(player.getAbsX() - landing.from.getX()) + Math.abs(player.getAbsY() - landing.from.getY());
                    if (offset < distance && WorldTravel242.walkable(landing.to)) {
                        distance = offset;
                        closest = landing;
                    }
                }
                if (closest == null || distance > 2) {
                    player.sendMessage("Step closer to the stairs or ladder.");
                    return;
                }
                Position destination = closest.to;
                climb(player, destination, animation(destination), ticks, () -> current() == object
                        && exit.current() == exitObject && approach(object, player.getPosition()));
            });
        }
    }

    private static final class Landing {
        final Position from;
        final Position to;

        Landing(Position from, Position to) {
            this.from = from;
            this.to = to;
        }
    }

    private static final class Binding {
        final Endpoint first;
        final GameObject firstObject;
        final List<Landing> forward;
        final Endpoint second;
        final GameObject secondObject;
        final List<Landing> reverse;

        Binding(Endpoint first, GameObject firstObject, List<Landing> forward, Endpoint second,
                GameObject secondObject, List<Landing> reverse) {
            this.first = first;
            this.firstObject = firstObject;
            this.forward = forward;
            this.second = second;
            this.secondObject = secondObject;
            this.reverse = reverse;
        }

        void register() {
            first.bind(firstObject, forward, second, secondObject);
            second.bind(secondObject, reverse, first, firstObject);
        }
    }
}
