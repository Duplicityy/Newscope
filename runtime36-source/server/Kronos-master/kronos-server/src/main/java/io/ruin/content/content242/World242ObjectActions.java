package io.ruin.content.content242;

import io.ruin.Server;
import io.ruin.cache.ObjectDef;
import io.ruin.model.map.object.actions.ObjectAction;

import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/** Connects audited cache aliases to native actions and preserves existing handlers. */
public final class World242ObjectActions {
    private static final int[][] TREE_ALIASES = {
            {1277, 1276}, {1279, 1278}, {1280, 1278}, {1283, 1282}, {1284, 1282}, {1285, 1282},
            {5902, 1282}, {9730, 1276}, {9731, 1278}, {9734, 10820}, {14308, 1276}, {14309, 1278},
            {27060, 2091}, {37651, 37650}, {39900, 37648}, {39903, 38213}, {52823, 37649},
            {62386, 37648}, {62387, 37648}, {62388, 37649}, {62389, 37649}
    };
    private static final int[][] ORE_ALIASES = {{39893, 38327}, {39894, 38346}};

    static void bindReviewedResourceAliases() {
        int trees = bindReviewedAliases(TREE_ALIASES, "Chop down");
        int rocks = bindReviewedAliases(ORE_ALIASES, "Mine");
        Server.println("Content242 resources: " + trees + " tree and " + rocks + " ore aliases bound.");
    }

    private static int bindReviewedAliases(int[][] pairs, String option) {
        int bound = 0;
        for (int[] pair : pairs) {
            ObjectDef alias = ObjectDef.get(pair[0]);
            ObjectDef source = ObjectDef.get(pair[1]);
            if (!sameResource(alias, source) || source.defaultActions == null) continue;
            int index = source.getOption(option) - 1;
            if (index < 0 || source.defaultActions[index] == null
                    || (alias.defaultActions != null && alias.defaultActions[index] != null)) continue;
            ObjectAction.register(alias.id, index + 1, source.defaultActions[index]);
            bound++;
        }
        return bound;
    }

    private static boolean sameResource(ObjectDef alias, ObjectDef source) {
        return alias != null && source != null && alias.showIds == null && source.showIds == null
                && alias.name.equalsIgnoreCase(source.name)
                && alias.xLength == source.xLength && alias.yLength == source.yLength
                && alias.clipType == source.clipType && alias.tall == source.tall
                && alias.render0x1 == source.render0x1 && alias.render0x2 == source.render0x2 && alias.render0x4 == source.render0x4
                && Arrays.equals(alias.options, source.options)
                && Arrays.equals(alias.modelIds, source.modelIds) && Arrays.equals(alias.modelTypes, source.modelTypes)
                && Arrays.equals(alias.originalModelColors, source.originalModelColors)
                && Arrays.equals(alias.modifiedModelColors, source.modifiedModelColors);
    }

    private World242ObjectActions() {
    }

    public static void bind(int source, int depletedSource, String option, Predicate<ObjectDef> resource,
                            Predicate<ObjectDef> depleted, IntFunction<ObjectAction> action) {
        Content242Registry registry = Content242Registry.current();
        if (!registry.feature("world")) return;
        ObjectDef mapped = alias(registry, source, resource);
        if (mapped == null) return;
        ObjectDef empty = ObjectDef.get(registry.resolve("objects", depletedSource));
        if (!matches(empty, depleted)) empty = ObjectDef.get(depletedSource);
        if (!matches(empty, depleted) || empty.id == mapped.id) return;
        bindOption(mapped, option, action.apply(empty.id));
    }

    public static void bind(int source, String option, Predicate<ObjectDef> resource, ObjectAction action) {
        Content242Registry registry = Content242Registry.current();
        if (!registry.feature("world")) return;
        ObjectDef mapped = alias(registry, source, resource);
        if (mapped != null) bindOption(mapped, option, action);
    }

    private static ObjectDef alias(Content242Registry registry, int source, Predicate<ObjectDef> predicate) {
        ObjectDef mapped = ObjectDef.get(registry.resolve("objects", source));
        return matches(mapped, predicate) && mapped.id != source ? mapped : null;
    }

    private static boolean matches(ObjectDef def, Predicate<ObjectDef> predicate) {
        return def != null && def.showIds == null && predicate.test(def);
    }

    private static void bindOption(ObjectDef def, String name, ObjectAction action) {
        int option = def.getOption(name);
        if (option > 0 && (def.defaultActions == null || def.defaultActions[option - 1] == null))
            ObjectAction.register(def.id, option, action);
    }
}
