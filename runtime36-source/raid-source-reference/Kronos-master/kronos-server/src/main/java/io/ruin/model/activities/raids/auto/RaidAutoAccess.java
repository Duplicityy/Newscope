package io.ruin.model.activities.raids.auto;

import io.ruin.model.entity.player.Player;
import io.ruin.model.item.Item;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Read-only access to the raid implementation's existing private state. */
public final class RaidAutoAccess {
    private static final ConcurrentMap<MemberKey, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<MemberKey, Method> METHODS = new ConcurrentHashMap<>();

    private RaidAutoAccess() {
    }

    public static Object get(Object target, String name) throws ReflectiveOperationException {
        if (target == null) throw new IllegalArgumentException("Missing raid state: " + name);
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        requireGameType(type);
        MemberKey key = new MemberKey(type, name);
        Field field = FIELDS.get(key);
        if (field == null) {
            for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                try {
                    field = at.getDeclaredField(name);
                    requireGameType(at);
                    field.setAccessible(true);
                    Field prior = FIELDS.putIfAbsent(key, field);
                    if (prior != null) field = prior;
                    break;
                } catch (NoSuchFieldException ignored) {
                    // Existing raid state may be declared by a base encounter.
                }
            }
            if (field == null) throw new NoSuchFieldException(type.getName() + "." + name);
        }
        return field.get(target instanceof Class<?> ? null : target);
    }

    public static Object call(Object target, String name) throws ReflectiveOperationException {
        if (target == null) throw new IllegalArgumentException("Missing raid state for " + name);
        if (!readMethod(name)) throw new IllegalArgumentException("Not a read-only raid accessor: " + name);
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        requireGameType(type);
        MemberKey key = new MemberKey(type, name);
        Method method = METHODS.get(key);
        if (method == null) {
            try {
                method = type.getMethod(name);
            } catch (NoSuchMethodException missingPublic) {
                for (Class<?> at = type; at != null; at = at.getSuperclass()) {
                    try {
                        method = at.getDeclaredMethod(name);
                        requireGameType(at);
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // Existing encounter accessors may live on a base class.
                    }
                }
                if (method == null) throw missingPublic;
            }
            method.setAccessible(true);
            Method prior = METHODS.putIfAbsent(key, method);
            if (prior != null) method = prior;
        }
        return method.invoke(target instanceof Class<?> ? null : target);
    }

    public static Item item(Player player, int id) {
        if (player == null || id < 0 || id >= 65535) return null;
        for (Item item : player.getInventory().getItems()) {
            if (item != null && item.getId() == id) return item;
        }
        return null;
    }

    private static void requireGameType(Class<?> type) {
        if (!type.getName().startsWith("io.ruin."))
            throw new IllegalArgumentException("Raid accessor requires game-owned state");
    }

    private static boolean readMethod(String name) {
        return name.startsWith("get") || name.startsWith("is") || name.startsWith("can")
                || name.equals("copy") || name.equals("copyOfAttributes")
                || name.equals("size") || name.equals("toString") || name.equals("values")
                || name.equals("valid");
    }

    private static final class MemberKey {
        private final Class<?> type;
        private final String name;

        MemberKey(Class<?> type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MemberKey)) return false;
            MemberKey key = (MemberKey) other;
            return type == key.type && name.equals(key.name);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(type) + name.hashCode();
        }
    }
}
