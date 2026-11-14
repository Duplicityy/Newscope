package com.newscape.dungeons;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** All IDs, cache hashes and chunk coordinates in this test are MOCK DATA ONLY. */
public final class ManifestChecks {
    private ManifestChecks() { }
    public static void main(String[] args) { run(); System.out.println("ManifestChecks passed: disabled/unmapped profiles, revision/fork/cache identity, asset identity, footprints, chunks and audits; valid MOCK fixtures for 184 and 242."); }
    public static void run() {
        for (int revision : new int[]{184, 242}) {
            Fixture f = fixture(revision);
            RevisionManifest.Validation valid = RevisionManifest.load(f.properties).validate(f.probe);
            require(valid.ready(), "Complete MOCK fixture must validate: " + valid.errors);
            RevisionManifest.Bindings bindings = valid.requireBindings();
            require(bindings.revision == revision && bindings.collisionAuditRequired, "Lost revision/audit gate");
            require(bindings.npcs.size() == 30 && bindings.objects.size() == 10 && bindings.graphics.size() == 8, "Incomplete typed asset registries");
            require(bindings.templates.size() == 20, "Missing pre-rotated trap modules");
            require(bindings.template("gauntlet_corner", 3).chunkRotation == 0, "Raw chunk rotation used");
            require(bindings.npc("grave_thrall").footprint == 1, "Wrong reserved NPC footprint");
            boolean immutable = false;
            try { bindings.npcs.clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            require(immutable, "Validated asset registry is mutable");
        }
        Fixture f = fixture(184);
        Properties p = copy(f.properties); p.setProperty("production.enabled", "false");
        RevisionManifest.Validation disabled = RevisionManifest.load(p).validate(f.probe);
        require(!disabled.ready() && has(disabled, "production.enabled is false"), "Disabled profile became usable");
        boolean rejected = false;
        try { disabled.requireBindings(); } catch (IllegalStateException expected) { rejected = expected.getMessage().contains("deployment is blocked"); }
        require(rejected, "Disabled profile exposes usable bindings");

        Properties blank = new Properties();
        blank.setProperty("manifest.schema", "1"); blank.setProperty("cache.revision", "184");
        blank.setProperty("npc.grave_thrall.id", "UNMAPPED");
        RevisionManifest.Validation missing = RevisionManifest.load(blank).validate(f.probe);
        require(!missing.ready() && missing.errors.size() > 50 && has(missing, "UNMAPPED or missing"), "Missing mappings do not produce a readable complete report");
        require(!RevisionManifest.load(f.properties).validate(null).ready(), "Absent live probe accepted");

        p = copy(f.properties); p.setProperty("cache.revision", "242");
        require(has(RevisionManifest.load(p).validate(f.probe), "Running cache revision"), "Wrong runtime revision accepted");
        p = copy(f.properties); p.setProperty("cache.revision", "200");
        require(has(RevisionManifest.load(p).validate(f.probe), "exactly 184 or 242"), "Unsupported profile revision accepted");
        p = copy(f.properties); p.setProperty("server.build-id", repeated('c', 40));
        require(has(RevisionManifest.load(p).validate(f.probe), "Running server build"), "Wrong fork/build accepted");
        p = copy(f.properties); p.setProperty("cache.sha256", repeated('c', 64));
        require(has(RevisionManifest.load(p).validate(f.probe), "Running cache fingerprint"), "Wrong cache hash accepted");
        p = copy(f.properties); p.setProperty("npc.grave_thrall.id", "99999999");
        require(has(RevisionManifest.load(p).validate(f.probe), "not registered"), "Nonexistent numeric NPC ID accepted");
        p = copy(f.properties); p.setProperty("npc.grave_thrall.id", f.properties.getProperty("npc.crypt_rat.id"));
        require(has(RevisionManifest.load(p).validate(f.probe), "expected-name mismatch"), "Existing but wrong NPC ID accepted");
        p = copy(f.properties); p.setProperty("object.door.closed.expected-name", "Wrong existing object");
        require(has(RevisionManifest.load(p).validate(f.probe), "expected-name mismatch"), "Wrong object identity accepted");
        p = copy(f.properties); p.setProperty("graphic.trap.warning.expected-sha256", repeated('d', 64));
        require(has(RevisionManifest.load(p).validate(f.probe), "graphic definition fingerprint mismatch"), "Wrong nameless graphic accepted");
        p = copy(f.properties); p.setProperty("npc.grave_thrall.id", "not-a-number");
        require(has(RevisionManifest.load(p).validate(f.probe), "must be an integer"), "Malformed ID did not get readable validation");
        p = copy(f.properties); p.setProperty("template.crypt_hall.r0.chunk-rotation", "1");
        require(has(RevisionManifest.load(p).validate(f.probe), "must be in 0..0"), "Raw chunk rotation accepted");
        p = copy(f.properties); p.setProperty("collision.audit.required", "false");
        require(has(RevisionManifest.load(p).validate(f.probe), "cannot be disabled"), "Audit requirement bypassed");
        p = copy(f.properties); p.setProperty("template.crypt_hall.r0.chunk.0.0.sha256", repeated('e', 64));
        require(has(RevisionManifest.load(p).validate(f.probe), "source chunk fingerprint mismatch"), "Wrong source terrain accepted");

        int firstNpc = Integer.parseInt(f.properties.getProperty("npc.grave_thrall.id"));
        RevisionManifest.AssetInfo original = f.probe.npcs.get(firstNpc);
        f.probe.npcs.put(firstNpc, new RevisionManifest.AssetInfo(firstNpc, original.name, 2, original.definitionSha256));
        require(has(RevisionManifest.load(f.properties).validate(f.probe), "requires 1x1"), "Oversized NPC footprint accepted");
        f.probe.npcs.put(firstNpc, original);
        f.probe.audited = false;
        require(has(RevisionManifest.load(f.properties).validate(f.probe), "no passing collision"), "Unaudited source module accepted");
        f.probe.audited = true;
        String chunkKey = f.probe.chunks.keySet().iterator().next();
        f.probe.chunks.remove(chunkKey);
        require(has(RevisionManifest.load(f.properties).validate(f.probe), "missing source chunk"), "Missing source chunk accepted");
    }

    private static Fixture fixture(int revision) {
        Properties p = new Properties(); MockProbe probe = new MockProbe(revision);
        p.setProperty("manifest.schema", "1"); p.setProperty("production.enabled", "true");
        p.setProperty("cache.revision", Integer.toString(revision));
        p.setProperty("server.build-id", probe.serverBuildId()); p.setProperty("cache.sha256", probe.cacheSha256());
        p.setProperty("collision.audit.required", "true");
        ContentCatalog catalog = new ContentCatalog(); int id = 1000;
        for (String key : catalog.npcs.keySet()) {
            String name = "MOCK NPC " + key;
            p.setProperty("npc." + key + ".id", Integer.toString(id)); p.setProperty("npc." + key + ".expected-name", name);
            probe.npcs.put(id, new RevisionManifest.AssetInfo(id, name, 1, sha("MOCK-NPC:" + key))); id++;
        }
        id = 2000;
        for (String key : RevisionManifest.requiredObjectKeys()) {
            String name = "MOCK OBJECT " + key;
            p.setProperty("object." + key + ".id", Integer.toString(id)); p.setProperty("object." + key + ".expected-name", name);
            probe.objects.put(id, new RevisionManifest.AssetInfo(id, name, 1, sha("MOCK-OBJECT:" + key))); id++;
        }
        id = 3000;
        for (String key : RevisionManifest.requiredGraphicKeys()) {
            String hash = sha("MOCK-GRAPHIC:" + key);
            p.setProperty("graphic." + key + ".id", Integer.toString(id)); p.setProperty("graphic." + key + ".expected-sha256", hash);
            probe.graphics.put(id, new RevisionManifest.AssetInfo(id, null, 1, hash)); id++;
        }
        int templateIndex = 0;
        for (String key : catalog.roomTemplates) for (int r = 0; r < RevisionManifest.templateVariants(key); r++) {
            int x = 100 + templateIndex % 5 * 4, y = 100 + templateIndex / 5 * 4; templateIndex++;
            String prefix = "template." + key + ".r" + r;
            p.setProperty(prefix + ".source-chunk-x", Integer.toString(x)); p.setProperty(prefix + ".source-chunk-y", Integer.toString(y));
            p.setProperty(prefix + ".plane", "0"); p.setProperty(prefix + ".chunk-rotation", "0");
            for (int dy = 0; dy < 3; dy++) for (int dx = 0; dx < 3; dx++) {
                String coordinates = (x + dx) + ":" + (y + dy) + ":0", hash = sha("MOCK-CHUNK:" + coordinates);
                p.setProperty(prefix + ".chunk." + dx + "." + dy + ".sha256", hash);
                probe.chunks.put(coordinates, new RevisionManifest.ChunkInfo(x + dx, y + dy, 0, hash));
            }
        }
        return new Fixture(p, probe);
    }
    private static final class Fixture {
        final Properties properties; final MockProbe probe;
        Fixture(Properties properties, MockProbe probe) { this.properties = properties; this.probe = probe; }
    }
    private static final class MockProbe implements RevisionManifest.CacheProbe {
        final int revision; boolean audited = true;
        final Map<Integer, RevisionManifest.AssetInfo> npcs = new HashMap<Integer, RevisionManifest.AssetInfo>();
        final Map<Integer, RevisionManifest.AssetInfo> objects = new HashMap<Integer, RevisionManifest.AssetInfo>();
        final Map<Integer, RevisionManifest.AssetInfo> graphics = new HashMap<Integer, RevisionManifest.AssetInfo>();
        final Map<String, RevisionManifest.ChunkInfo> chunks = new HashMap<String, RevisionManifest.ChunkInfo>();
        MockProbe(int revision) { this.revision = revision; }
        @Override public int revision() { return revision; }
        @Override public String serverBuildId() { return repeated('a', 40); }
        @Override public String cacheSha256() { return repeated('b', 64); }
        @Override public RevisionManifest.AssetInfo npc(int id) { return npcs.get(id); }
        @Override public RevisionManifest.AssetInfo object(int id) { return objects.get(id); }
        @Override public RevisionManifest.AssetInfo graphic(int id) { return graphics.get(id); }
        @Override public RevisionManifest.ChunkInfo chunk(int x, int y, int plane) { return chunks.get(x + ":" + y + ":" + plane); }
        @Override public boolean collisionAuditPassed(RevisionManifest.TemplateBinding template) { return audited; }
    }
    private static Properties copy(Properties original) { Properties p = new Properties(); p.putAll(original); return p; }
    private static boolean has(RevisionManifest.Validation validation, String text) {
        for (String error : validation.errors) if (error.contains(text)) return true; return false;
    }
    private static String repeated(char value, int count) { char[] result = new char[count]; java.util.Arrays.fill(result, value); return new String(result); }
    private static String sha(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte value : bytes) { int n = value & 255; b.append("0123456789abcdef".charAt(n >>> 4)).append("0123456789abcdef".charAt(n & 15)); }
            return b.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
