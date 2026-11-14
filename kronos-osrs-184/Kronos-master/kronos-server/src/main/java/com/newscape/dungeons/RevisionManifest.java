package com.newscape.dungeons;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Startup asset registry and deployment gate. This class does not render maps.
 * No profile can become deployable merely because its numeric revision is supported:
 * every binding must match the live fork/cache and every source module needs an audit.
 */
public final class RevisionManifest {
    public static final int SCHEMA_VERSION = 1;
    public static final String UNMAPPED = "UNMAPPED";
    private static final List<String> OBJECT_KEYS = immutable(Arrays.asList(
            "portal.entry", "portal.exit", "chest.closed", "chest.open",
            "door.closed", "door.open", "shrine.ready", "shrine.used",
            "obstacle.trigger", "obstacle.landing"));
    private static final List<String> GRAPHIC_KEYS = immutable(Arrays.asList(
            "trap.warning", "trap.success", "trap.failure", "boss.warning",
            "boss.impact", "portal.departure", "portal.arrival", "shrine.activation"));

    /** Reads the actual loaded cache and server build; it must not echo this properties file. */
    public interface CacheProbe {
        int revision();
        /** Exact source/build identifier: full 40/64-digit commit or build hash. */
        String serverBuildId();
        /** SHA-256 of the audited canonical cache export/manifest, computed independently. */
        String cacheSha256();
        /** Null means no such registered NPC; size is its actual cache/server footprint. */
        AssetInfo npc(int id);
        AssetInfo object(int id);
        /** Graphics often have no native name; definitionSha256 identifies the exact graphic. */
        AssetInfo graphic(int id);
        /** Null means the source chunk does not exist or cannot be decoded. */
        ChunkInfo chunk(int sourceChunkX, int sourceChunkY, int plane);
        /**
         * True only for a retained audit of this exact source binding, fork and cache:
         * visible floor, server movement/projectile clipping, trap gap, single-tile
         * door necks, guard containment and supported NPC/object footprints.
         * Per-instance validation remains mandatory after building the live map.
         */
        boolean collisionAuditPassed(TemplateBinding template);
    }

    public static final class AssetInfo {
        public final int id, size;
        public final String name, definitionSha256;
        public AssetInfo(int id, String name, int size, String definitionSha256) {
            this.id = id; this.name = name; this.size = size; this.definitionSha256 = definitionSha256;
        }
    }
    public static final class ChunkInfo {
        public final int x, y, plane;
        public final String sha256;
        public ChunkInfo(int x, int y, int plane, String sha256) {
            this.x = x; this.y = y; this.plane = plane; this.sha256 = sha256;
        }
    }
    public static final class NpcBinding {
        public final String key, expectedName;
        public final int id, footprint;
        private NpcBinding(String key, int id, String name) {
            this.key = key; this.id = id; this.expectedName = name; this.footprint = 1;
        }
    }
    public static final class ObjectBinding {
        public final String key, expectedName;
        public final int id;
        private ObjectBinding(String key, int id, String name) { this.key = key; this.id = id; this.expectedName = name; }
    }
    public static final class GraphicBinding {
        public final String key, expectedDefinitionSha256;
        public final int id;
        private GraphicBinding(String key, int id, String hash) { this.key = key; this.id = id; this.expectedDefinitionSha256 = hash; }
    }
    public static final class ChunkBinding {
        public final int offsetX, offsetY, sourceX, sourceY, plane;
        public final String expectedSha256;
        private ChunkBinding(int dx, int dy, int sourceX, int sourceY, int plane, String hash) {
            this.offsetX = dx; this.offsetY = dy; this.sourceX = sourceX; this.sourceY = sourceY;
            this.plane = plane; this.expectedSha256 = hash;
        }
    }
    public static final class TemplateBinding {
        public final String key;
        /** Logical orientation in DungeonLayout, selected by a separately authored module. */
        public final int logicalRotation;
        public final int sourceChunkX, sourceChunkY, plane;
        /** Always zero: source variants are already authored in the required orientation. */
        public final int chunkRotation;
        public final List<ChunkBinding> chunks;
        private TemplateBinding(String key, int logicalRotation, int x, int y, int plane,
                                int chunkRotation, List<ChunkBinding> chunks) {
            this.key = key; this.logicalRotation = logicalRotation;
            this.sourceChunkX = x; this.sourceChunkY = y; this.plane = plane;
            this.chunkRotation = chunkRotation; this.chunks = immutable(chunks);
        }
        public String variantKey() { return key + ".r" + logicalRotation; }
    }

    /** Constructed only inside a validation pass; callers receive it only after all checks pass. */
    public static final class Bindings {
        public final int revision;
        public final String serverBuildId, cacheSha256;
        public final boolean collisionAuditRequired = true;
        public final Map<String, NpcBinding> npcs;
        public final Map<String, ObjectBinding> objects;
        public final Map<String, GraphicBinding> graphics;
        public final Map<String, TemplateBinding> templates;
        private Bindings(int revision, String serverBuild, String cacheHash,
                         Map<String, NpcBinding> npcs, Map<String, ObjectBinding> objects,
                         Map<String, GraphicBinding> graphics, Map<String, TemplateBinding> templates) {
            this.revision = revision; this.serverBuildId = serverBuild; this.cacheSha256 = cacheHash;
            this.npcs = mapCopy(npcs); this.objects = mapCopy(objects);
            this.graphics = mapCopy(graphics); this.templates = mapCopy(templates);
        }
        public NpcBinding npc(String key) { return lookup(npcs, key, "NPC"); }
        public ObjectBinding object(String key) { return lookup(objects, key, "object"); }
        public GraphicBinding graphic(String key) { return lookup(graphics, key, "graphic"); }
        public TemplateBinding template(String key) { return template(key, 0); }
        public TemplateBinding template(String key, int logicalRotation) {
            if (logicalRotation < 0 || logicalRotation > 3) throw new IllegalArgumentException("Logical rotation must be 0..3");
            return lookup(templates, key + ".r" + logicalRotation, "template variant");
        }
        private static <T> T lookup(Map<String, T> map, String key, String type) {
            T value = map.get(key);
            if (value == null) throw new IllegalArgumentException("Unregistered " + type + " key: " + key);
            return value;
        }
    }

    public static final class Validation {
        public final boolean productionEnabled;
        public final List<String> errors;
        private final Bindings candidate;
        private Validation(boolean enabled, List<String> errors, Bindings candidate) {
            this.productionEnabled = enabled; this.errors = immutable(errors); this.candidate = candidate;
        }
        public boolean ready() { return productionEnabled && errors.isEmpty(); }
        public Bindings requireBindings() {
            if (!ready()) throw new IllegalStateException("Dungeon deployment is blocked:\n - " + join(errors, "\n - "));
            return candidate;
        }
    }

    private final Properties values;
    private final ContentCatalog catalog;
    private RevisionManifest(Properties values, ContentCatalog catalog) {
        this.values = new Properties(); this.values.putAll(values); this.catalog = catalog;
    }
    public static RevisionManifest load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(in); }
        return load(properties);
    }
    public static RevisionManifest load(Properties properties) {
        return new RevisionManifest(Objects.requireNonNull(properties, "properties"), new ContentCatalog());
    }
    public static List<String> requiredObjectKeys() { return OBJECT_KEYS; }
    public static List<String> requiredGraphicKeys() { return GRAPHIC_KEYS; }
    /** Normal modules need r0; both gauntlet module families have pre-authored r0..r3. */
    public static int templateVariants(String key) { return key.startsWith("gauntlet_") ? 4 : 1; }

    /** Collect all configuration and live-cache errors instead of failing at the first bad number. */
    public Validation validate(CacheProbe probe) {
        List<String> errors = new ArrayList<String>();
        boolean enabled = bool("production.enabled", false, errors);
        if (!enabled) errors.add("production.enabled is false; dungeon entry remains disabled.");
        integer("manifest.schema", 1, 1, errors);
        int revision = integer("cache.revision", 184, 242, errors);
        if (revision != -1 && revision != 184 && revision != 242) errors.add("cache.revision must be exactly 184 or 242.");
        String serverBuild = token("server.build-id", errors);
        if (serverBuild != null && !serverBuild.matches("(?i)([0-9a-f]{40}|[0-9a-f]{64})"))
            errors.add("server.build-id must be an exact full 40- or 64-digit hexadecimal commit/build hash.");
        String cacheHash = hash("cache.sha256", errors);
        boolean auditRequired = bool("collision.audit.required", true, errors);
        if (!auditRequired) errors.add("collision.audit.required cannot be disabled for this minigame.");
        if (probe == null) errors.add("No live CacheProbe was supplied.");
        if (probe != null) {
            try {
                if (probe.revision() != revision) errors.add("Running cache revision does not match cache.revision (expected " + revision + ", observed " + probe.revision() + ").");
                if (serverBuild != null && !serverBuild.equalsIgnoreCase(clean(probe.serverBuildId()))) errors.add("Running server build does not match server.build-id.");
                if (cacheHash != null && !cacheHash.equalsIgnoreCase(clean(probe.cacheSha256()))) errors.add("Running cache fingerprint does not match cache.sha256.");
            } catch (RuntimeException failure) { errors.add("Cannot inspect running fork/cache identity: " + safeMessage(failure)); }
        }
        Map<String, NpcBinding> npcs = new LinkedHashMap<String, NpcBinding>();
        for (String key : catalog.npcs.keySet()) {
            String prefix = "npc." + key;
            int id = integer(prefix + ".id", 0, Integer.MAX_VALUE, errors);
            String name = token(prefix + ".expected-name", errors);
            if (id < 0 || name == null) continue;
            npcs.put(key, new NpcBinding(key, id, name));
            if (probe != null) try {
                AssetInfo actual = probe.npc(id);
                if (assetMatches(prefix, id, name, actual, errors) && actual.size != 1)
                    errors.add(prefix + ": actual NPC footprint is " + actual.size + "; this blueprint requires 1x1.");
            } catch (RuntimeException failure) { errors.add(prefix + ": cache inspection failed: " + safeMessage(failure)); }
        }
        Map<String, ObjectBinding> objects = new LinkedHashMap<String, ObjectBinding>();
        for (String key : OBJECT_KEYS) {
            String prefix = "object." + key;
            int id = integer(prefix + ".id", 0, Integer.MAX_VALUE, errors);
            String name = token(prefix + ".expected-name", errors);
            if (id < 0 || name == null) continue;
            objects.put(key, new ObjectBinding(key, id, name));
            if (probe != null) try { assetMatches(prefix, id, name, probe.object(id), errors); }
            catch (RuntimeException failure) { errors.add(prefix + ": cache inspection failed: " + safeMessage(failure)); }
        }
        Map<String, GraphicBinding> graphics = new LinkedHashMap<String, GraphicBinding>();
        for (String key : GRAPHIC_KEYS) {
            String prefix = "graphic." + key;
            int id = integer(prefix + ".id", 0, Integer.MAX_VALUE, errors);
            String definitionHash = hash(prefix + ".expected-sha256", errors);
            if (id < 0 || definitionHash == null) continue;
            graphics.put(key, new GraphicBinding(key, id, definitionHash));
            if (probe != null) try {
                AssetInfo actual = probe.graphic(id);
                if (actual == null) errors.add(prefix + ": ID " + id + " is not a registered graphic.");
                else {
                    if (actual.id != id) errors.add(prefix + ": CacheProbe returned another ID.");
                    if (!definitionHash.equalsIgnoreCase(clean(actual.definitionSha256))) errors.add(prefix + ": graphic definition fingerprint mismatch.");
                }
            } catch (RuntimeException failure) { errors.add(prefix + ": cache inspection failed: " + safeMessage(failure)); }
        }
        Map<String, TemplateBinding> templates = new LinkedHashMap<String, TemplateBinding>();
        for (String key : catalog.roomTemplates) for (int rotation = 0; rotation < templateVariants(key); rotation++) {
            String prefix = "template." + key + ".r" + rotation;
            int x = integer(prefix + ".source-chunk-x", 0, 2045, errors);
            int y = integer(prefix + ".source-chunk-y", 0, 2045, errors);
            int plane = integer(prefix + ".plane", 0, 3, errors);
            int chunkRotation = integer(prefix + ".chunk-rotation", 0, 0, errors);
            List<ChunkBinding> chunks = new ArrayList<ChunkBinding>();
            boolean complete = x >= 0 && y >= 0 && plane >= 0 && chunkRotation == 0;
            for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 3; dx++) {
                String expectedHash = hash(prefix + ".chunk." + dx + "." + dy + ".sha256", errors);
                if (expectedHash == null) { complete = false; continue; }
                if (x < 0 || y < 0 || plane < 0) continue;
                ChunkBinding chunk = new ChunkBinding(dx, dy, x + dx, y + dy, plane, expectedHash); chunks.add(chunk);
                if (probe != null) try {
                    ChunkInfo actual = probe.chunk(chunk.sourceX, chunk.sourceY, plane);
                    if (actual == null) errors.add(prefix + ": missing source chunk (" + chunk.sourceX + "," + chunk.sourceY + "," + plane + ").");
                    else {
                        if (actual.x != chunk.sourceX || actual.y != chunk.sourceY || actual.plane != plane) errors.add(prefix + ": CacheProbe returned the wrong source chunk coordinates.");
                        if (!expectedHash.equalsIgnoreCase(clean(actual.sha256))) errors.add(prefix + ": source chunk fingerprint mismatch at offset " + dx + "," + dy + ".");
                    }
                } catch (RuntimeException failure) { errors.add(prefix + ": source chunk inspection failed: " + safeMessage(failure)); }
            }
            if (!complete || chunks.size() != 9) continue;
            TemplateBinding template = new TemplateBinding(key, rotation, x, y, plane, chunkRotation, chunks);
            templates.put(template.variantKey(), template);
            if (probe != null) try {
                if (!probe.collisionAuditPassed(template)) errors.add(prefix + ": no passing collision/door/gap/containment audit for this exact source binding.");
            } catch (RuntimeException failure) { errors.add(prefix + ": collision audit lookup failed: " + safeMessage(failure)); }
        }
        Bindings bindings = new Bindings(revision, serverBuild, cacheHash, npcs, objects, graphics, templates);
        return new Validation(enabled, errors, bindings);
    }

    private boolean bool(String key, boolean fallback, List<String> errors) {
        String value = clean(values.getProperty(key));
        if (value == null || value.isEmpty()) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        errors.add(key + " must be true or false; observed '" + value + "'."); return fallback;
    }
    private int integer(String key, int min, int max, List<String> errors) {
        String value = token(key, errors); if (value == null) return -1;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) { errors.add(key + " must be in " + min + ".." + max + "; observed '" + value + "'."); return -1; }
            return (int) parsed;
        } catch (NumberFormatException invalid) { errors.add(key + " must be an integer; observed '" + value + "'."); return -1; }
    }
    private String token(String key, List<String> errors) {
        String value = clean(values.getProperty(key));
        if (value == null || value.isEmpty() || UNMAPPED.equalsIgnoreCase(value)) {
            errors.add(key + " is UNMAPPED or missing."); return null;
        }
        return value;
    }
    private String hash(String key, List<String> errors) {
        String value = token(key, errors); if (value == null) return null;
        if (!value.matches("(?i)[0-9a-f]{64}")) { errors.add(key + " must be a 64-digit SHA-256 hexadecimal fingerprint."); return null; }
        return value.toLowerCase(java.util.Locale.ROOT);
    }
    private static boolean assetMatches(String prefix, int expectedId, String expectedName, AssetInfo actual, List<String> errors) {
        if (actual == null) { errors.add(prefix + ": ID " + expectedId + " is not registered in the loaded cache."); return false; }
        boolean matches = true;
        if (actual.id != expectedId) { errors.add(prefix + ": CacheProbe returned another ID."); matches = false; }
        if (!expectedName.equals(clean(actual.name))) { errors.add(prefix + ": expected-name mismatch (expected '" + expectedName + "', observed '" + clean(actual.name) + "')."); matches = false; }
        return matches;
    }
    private static String clean(String text) { return text == null ? null : text.trim(); }
    private static String safeMessage(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }
    private static String join(List<String> values, String separator) {
        StringBuilder text = new StringBuilder(); for (String value : values) { if (text.length() > 0) text.append(separator); text.append(value); } return text.toString();
    }
    private static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<T>(values)); }
    private static <T> Map<String, T> mapCopy(Map<String, T> values) { return Collections.unmodifiableMap(new LinkedHashMap<String, T>(values)); }
}
