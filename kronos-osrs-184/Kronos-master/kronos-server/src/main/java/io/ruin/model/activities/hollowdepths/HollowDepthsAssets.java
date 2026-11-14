package io.ruin.model.activities.hollowdepths;

import com.newscape.dungeons.RevisionManifest;
import io.ruin.Server;
import io.ruin.api.buffer.InBuffer;
import io.ruin.api.filestore.Archive;
import io.ruin.api.filestore.IndexFile;
import io.ruin.cache.GfxDef;
import io.ruin.cache.NPCDef;
import io.ruin.cache.ObjectDef;
import io.ruin.model.map.Region;
import io.ruin.model.map.Tile;
import io.ruin.network.LoginDecoder;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/** Independent probe of the loaded 184 fork. A profile never supplies observed values. */
public final class HollowDepthsAssets implements RevisionManifest.CacheProbe {
    private final Properties profile = new Properties();
    private final Properties collisionAudit = new Properties();
    private final Map<Integer, Terrain> terrain = new HashMap<>();
    private final Map<Integer, Boolean> solidFloors = new HashMap<>();
    private final Map<Integer, String> sourceLandscapes = new HashMap<>();
    private static final int MAX_SOURCE_ARCHIVE = 4 * 1024 * 1024;
    private final boolean auditMode;
    private String denial;
    private String buildHash, cacheHash;
    private RevisionManifest.Bindings bindings;

    public HollowDepthsAssets(Path manifestPath) { this(manifestPath, false); }

    /** Only the package-local offline test harness may construct maps before retained proof exists. */
    static HollowDepthsAssets forAudit(Path manifestPath) { return new HollowDepthsAssets(manifestPath, true); }

    private HollowDepthsAssets(Path path, boolean auditMode) {
        this.auditMode = auditMode;
        try {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { profile.load(reader); }
            Path proof = path.resolveSibling("collision-audit.properties");
            if (Files.isRegularFile(proof)) {
                try (Reader reader = Files.newBufferedReader(proof, StandardCharsets.UTF_8)) { collisionAudit.load(reader); }
            }
            if (!"1".equals(profile.getProperty("adapter.geometry.version")))
                throw new IllegalStateException("Unsupported dungeon geometry version");
            Properties validation = new Properties(); validation.putAll(profile);
            if (auditMode) validation.setProperty("production.enabled", "true");
            RevisionManifest.Validation result = RevisionManifest.load(validation).validate(this);
            if (!result.ready()) denial = String.join("; ", result.errors);
            else {
                bindings = result.requireBindings();
                validateSupplementalBindings();
            }
        } catch (Exception failure) {
            denial = "Hollow Depths assets unavailable: " + failure.getMessage(); bindings = null;
        }
    }

    public String deploymentDenial() { return denial; }
    public RevisionManifest.Bindings bindings() {
        if (denial != null || bindings == null) throw new IllegalStateException(denial);
        return bindings;
    }
    public int npcId(String key) { return bindings().npc(key).id; }
    public int objectId(String key) {
        bindings(); return requiredInt("object." + key + ".id");
    }
    public int gfxId(String key) {
        bindings(); return requiredInt("graphic." + key + ".id");
    }

    @Override public int revision() {
        try {
            Field revision = LoginDecoder.class.getDeclaredField("BUILD_HASH"); revision.setAccessible(true);
            return revision.getInt(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot inspect login revision", e); }
    }

    @Override public String serverBuildId() {
        if (buildHash != null) return buildHash;
        try {
            java.security.CodeSource source = Server.class.getProtectionDomain().getCodeSource();
            Path code;
            // Kilim's observed CodeSource is file:...jar!/ rather than a filesystem jar.
            // Its class resource has a normal jar: URL; resolve that actual container first.
            java.net.URL resource = Server.class.getResource("/io/ruin/Server.class");
            java.net.URLConnection connection = resource == null ? null : resource.openConnection();
            if (connection instanceof java.net.JarURLConnection) {
                code = Paths.get(((java.net.JarURLConnection) connection).getJarFileURL().toURI());
            } else if (resource != null && "file".equals(resource.getProtocol())) {
                code = Paths.get(resource.toURI()).getParent().getParent().getParent();
            } else if (source != null && source.getLocation() != null) {
                code = Paths.get(source.getLocation().toURI());
            } else throw new IllegalStateException("Running server class resource is missing");
            MessageDigest digest = digest();
            if (Files.isRegularFile(code)) {
                hashFile(digest, code);
            } else {
                // Development builds use the exact sorted class tree, never an arbitrary Git label.
                List<Path> classes;
                try (Stream<Path> stream = Files.walk(code)) {
                    classes = stream.filter(p -> p.toString().endsWith(".class")).sorted().collect(Collectors.toList());
                }
                for (Path file : classes) {
                    byte[] name = code.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                    digest.update(ints(name.length)); digest.update(name); hashFile(digest, file);
                }
            }
            return buildHash = hex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint running server", e); }
    }

    @Override public String cacheSha256() {
        if (cacheHash != null) return cacheHash;
        if (Server.fileStore == null) throw new IllegalStateException("Cache is not loaded");
        MessageDigest digest = digest();
        for (int i = 0; i < Server.fileStore.files.length; i++) {
            byte[] raw = Server.fileStore.index255.getArchiveData(i);
            if (raw == null) continue;
            digest.update(ints(i, raw.length)); digest.update(raw);
        }
        return cacheHash = hex(digest.digest());
    }

    @Override public RevisionManifest.AssetInfo npc(int id) {
        NPCDef def = NPCDef.get(id); byte[] raw = definition(9, id);
        if (def == null || raw == null || def.models == null || def.models.length == 0) return null;
        for (int model : def.models) if (!modelExists(model)) return null;
        return new RevisionManifest.AssetInfo(id, def.name, def.size, hash(raw));
    }
    @Override public RevisionManifest.AssetInfo object(int id) {
        ObjectDef def = ObjectDef.get(id); byte[] raw = definition(6, id);
        if (def == null || raw == null || def.modelIds == null || def.modelIds.length == 0) return null;
        for (int model : def.modelIds) if (!modelExists(model)) return null;
        return new RevisionManifest.AssetInfo(id, def.name, Math.max(def.xLength, def.yLength), hash(raw));
    }
    @Override public RevisionManifest.AssetInfo graphic(int id) {
        GfxDef def = GfxDef.get(id); byte[] raw = definition(13, id);
        if (def == null || raw == null || !modelExists(def.modelId)
                || def.animationId >= 0 && definition(12, def.animationId) == null) return null;
        return new RevisionManifest.AssetInfo(id, "graphic:" + id, 1, hash(raw));
    }
    private static byte[] definition(int group, int id) {
        if (Server.fileStore == null || !Server.fileStore.get(2).fileExists(group, id)) return null;
        return Server.fileStore.get(2).getFile(group, id);
    }
    private static boolean modelExists(int id) {
        return id >= 0 && Server.fileStore.get(7).archiveExists(id)
                && Server.fileStore.get(7).getFile(id, 0) != null;
    }

    @Override public RevisionManifest.ChunkInfo chunk(int x, int y, int plane) {
        if (x < 0 || y < 0 || plane != 0) return null;
        Terrain actual = terrain(x >> 3, y >> 3);
        if (actual == null) return null;
        MessageDigest digest = digest();
        digest.update(ints(x,y,plane,actual.raw.length)); digest.update(actual.raw);
        return new RevisionManifest.ChunkInfo(x,y,plane,hex(digest.digest()));
    }

    /** A retained real-server audit plus an independent source-terrain check, never a constant success. */
    @Override public boolean collisionAuditPassed(RevisionManifest.TemplateBinding template) {
        if (!sourceTerrainSafe(template)) return false;
        if (auditMode) return true;
        return "2".equals(collisionAudit.getProperty("audit.version"))
                && serverBuildId().equals(collisionAudit.getProperty("server.build-id"))
                && cacheSha256().equals(collisionAudit.getProperty("cache.sha256"))
                && "true".equals(collisionAudit.getProperty("maps.passed"))
                && "true".equals(collisionAudit.getProperty("client.source-archives.passed"))
                && templateFingerprint(template).equals(collisionAudit.getProperty("template." + template.variantKey()));
    }

    boolean sourceTerrainSafe(RevisionManifest.TemplateBinding template) {
        if (template.chunkRotation != 0 || template.plane != 0) return false;
        for (RevisionManifest.ChunkBinding chunk : template.chunks) {
            sourceLandscape((chunk.sourceX >> 3) * 256 + (chunk.sourceY >> 3));
            Terrain actual = terrain(chunk.sourceX >> 3, chunk.sourceY >> 3);
            if (actual == null) return false;
            int bx = (chunk.sourceX & 7) * 8, by = (chunk.sourceY & 7) * 8;
            for (int x = bx; x < bx + 8; x++) for (int y = by; y < by + 8; y++) {
                if (actual.underlay[x][y] == 0 || actual.flags[x][y] != 0 || actual.height[x][y] != 30) return false;
                if (!solidFloor(1, actual.underlay[x][y])
                        || actual.overlay[x][y] != 0 && !solidFloor(4, actual.overlay[x][y])) return false;
                // defaultClipping is cached floor clipping before scenery; scenery gets real removal tombstones.
                Tile tile = Tile.get((chunk.sourceX >> 3) * 64 + x, (chunk.sourceY >> 3) * 64 + y, 0, false);
                if (tile != null && (tile.defaultClipping != 0 || tile.roofExists)) return false;
            }
        }
        return true;
    }

    /** Underlays alone do not prove visibility: magenta overlays erase client floor faces. */
    private boolean solidFloor(int group, int oneBasedId) {
        int key = group * 65536 + oneBasedId;
        Boolean previous = solidFloors.get(key);
        if (previous != null) return previous;
        byte[] raw = definition(group, oneBasedId - 1);
        if (raw == null) return false;
        ByteBuffer data = ByteBuffer.wrap(raw); int rgb = 0, texture = -1;
        for (int op; (op = data.get() & 255) != 0;) {
            if (op == 1) rgb = ((data.get() & 255) << 16) | ((data.get() & 255) << 8) | (data.get() & 255);
            else if (group == 4 && op == 2) texture = data.get() & 255;
            else if (group == 4 && (op == 5 || op == 8)) { /* native flags, no payload */ }
            else if (group == 4 && op == 7) { data.get(); data.get(); data.get(); }
            else throw new IllegalStateException("Unknown native floor definition opcode: " + op);
        }
        boolean solid = !data.hasRemaining() && texture == -1 && rgb != 0 && rgb != 0xff00ff
                && Math.max(rgb >> 16, Math.max((rgb >> 8) & 255, rgb & 255)) >= 32;
        solidFloors.put(key, solid); return solid;
    }

    /** Re-read the actual loaded keys and archives, as sent to the unmodified 184 client. */
    public int verifyClientSourceArchives() {
        sourceLandscapes.clear();
        for (String property : profile.stringPropertyNames()) {
            if (!property.startsWith("template.") || !property.endsWith(".source-chunk-x")) continue;
            String prefix = property.substring(0, property.length() - ".source-chunk-x".length());
            int sx = requiredInt(property), sy = requiredInt(prefix + ".source-chunk-y");
            for (int dx = 0; dx < 3; dx++) for (int dy = 0; dy < 3; dy++)
                sourceLandscape(((sx + dx) >> 3) * 256 + ((sy + dy) >> 3));
        }
        if (sourceLandscapes.isEmpty()) throw new IllegalStateException("No client source archives");
        return sourceLandscapes.size();
    }

    private String sourceLandscape(int regionId) {
        String existing = sourceLandscapes.get(regionId);
        if (existing != null) return existing;
        Region region = Region.get(regionId);
        if (region == null || region.keys == null || region.keys.length != 4)
            throw new IllegalStateException("Source region has no validated landscape keys: " + regionId);
        IndexFile index = Server.fileStore.get(5);
        int group = index.getArchiveId("l" + (regionId >> 8) + "_" + (regionId & 255));
        if (group < 0 || index.getValidFilesCount(group) != 1)
            throw new IllegalStateException("Missing single-file source landscape: " + regionId);
        byte[] raw = index.getArchiveData(group);
        byte[] plain = decodeSourceArchive(raw, region.keys);
        validateLandscape(plain);
        byte[] nativeData = region.getLandscapeData();
        if (!Arrays.equals(plain, nativeData))
            throw new IllegalStateException("Native/client source landscape disagreement: " + regionId);
        String landscapeHash = hash(plain), keysHash = hash(ints(region.keys));
        String prefix = "source.region." + regionId;
        if (!landscapeHash.equals(profile.getProperty(prefix + ".landscape-sha256"))
                || !keysHash.equals(profile.getProperty(prefix + ".keys-sha256")))
            throw new IllegalStateException("Source landscape/key fingerprint mismatch: " + regionId);
        String fingerprint = landscapeHash + ":" + keysHash;
        sourceLandscapes.put(regionId, fingerprint);
        return fingerprint;
    }

    /** Checks decrypted sizes before either the server or client-style decoder may allocate. */
    static byte[] decodeSourceArchive(byte[] raw, int[] keys) {
        if (raw == null || raw.length < 5 || raw.length > MAX_SOURCE_ARCHIVE || keys == null || keys.length != 4)
            throw new IllegalStateException("Invalid source landscape container/keys");
        byte[] decoded = raw.clone();
        if (keys[0] != 0 || keys[1] != 0 || keys[2] != 0 || keys[3] != 0)
            new InBuffer(decoded).decode(keys, 5, decoded.length);
        ByteBuffer header = ByteBuffer.wrap(decoded);
        int codec = header.get() & 255, compressed = header.getInt();
        int start = codec == 0 ? 5 : 9;
        if (codec > 2 || compressed < 0 || compressed > MAX_SOURCE_ARCHIVE || decoded.length < start
                || compressed > decoded.length - start || decoded.length - start - compressed > 2)
            throw new IllegalStateException("Invalid source landscape compression header");
        int expanded = codec == 0 ? compressed : header.getInt();
        if (expanded < 0 || expanded > MAX_SOURCE_ARCHIVE)
            throw new IllegalStateException("Invalid decrypted source landscape length (wrong XTEA keys)");
        if (codec == 0) return Arrays.copyOfRange(decoded, start, start + compressed);
        if (codec == 1) return SourceArchive.inflate(decoded);
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(
                new ByteArrayInputStream(decoded, start, compressed)))) {
            byte[] plain = new byte[expanded];
            input.readFully(plain);
            if (input.read() != -1) throw new IOException("Expanded source landscape exceeds declared length");
            return plain;
        } catch (IOException e) { throw new IllegalStateException("Invalid source landscape gzip stream", e); }
    }

    private static final class SourceArchive extends Archive {
        static byte[] inflate(byte[] bounded) { return decompress(bounded, null); }
    }

    private static void validateLandscape(byte[] plain) {
        ByteBuffer data = ByteBuffer.wrap(plain);
        int objectId = -1;
        for (int delta; (delta = smart(data)) != 0;) {
            // The native server reads one smart; the 184 client continues on 32767.
            if (delta == 32767) throw new IllegalStateException("Ambiguous native/client source object delta");
            objectId += delta;
            if (!Server.fileStore.get(2).fileExists(6, objectId))
                throw new IllegalStateException("Missing source landscape object: " + objectId);
            int position = 0;
            for (int step; (step = smart(data)) != 0;) {
                position += step - 1;
                int type = (data.get() & 255) >> 2;
                if (position >= 16384 || type > 22)
                    throw new IllegalStateException("Invalid source landscape object placement");
            }
        }
        if (data.hasRemaining()) throw new IllegalStateException("Unread source landscape bytes");
    }

    private static int smart(ByteBuffer data) {
        int first = data.get() & 255;
        return first < 128 ? first : ((first << 8) | (data.get() & 255)) - 32768;
    }

    private Terrain terrain(int x, int y) {
        int key = (x << 8) | y;
        if (terrain.containsKey(key)) return terrain.get(key);
        int group = Server.fileStore.get(5).getArchiveId("m" + x + "_" + y);
        byte[] data = group < 0 ? null : Server.fileStore.get(5).getFile(group, 0);
        Terrain result = data == null ? null : new Terrain(data);
        terrain.put(key, result); return result;
    }

    private void validateSupplementalBindings() {
        for (String property : profile.stringPropertyNames()) {
            if (!property.endsWith(".id")) continue;
            String prefix = property.substring(0, property.length() - 3);
            int id = requiredInt(property);
            RevisionManifest.AssetInfo actual = prefix.startsWith("npc.") ? npc(id)
                    : prefix.startsWith("object.") ? object(id) : prefix.startsWith("graphic.") ? graphic(id) : null;
            if (actual == null || !actual.definitionSha256.equals(profile.getProperty(prefix + ".expected-sha256")))
                throw new IllegalStateException("Definition fingerprint mismatch: " + prefix);
            if (prefix.startsWith("object.") && !prefix.equals("object.portal.entry")) {
                ObjectDef def = ObjectDef.get(id);
                if (def.xLength != 1 || def.yLength != 1) throw new IllegalStateException("Object is not 1x1: " + prefix);
            }
        }
        for (String theme : Arrays.asList("ashen_catacombs", "drowned_ossuary", "ironbound_crypt", "hollow_sanctum")) {
            ObjectDef wall = ObjectDef.get(requiredInt("object.wall." + theme + ".id"));
            // Native decoding makes the low boulder decorative (someFlag clears clipType
            // and tall). Dungeon boundaries independently install movement 0x200000 and
            // projectile 0x20000, then validate both after placement. Keep global defs intact.
            if (!supports(wall, 10) || wall.xLength != 1 || wall.yLength != 1)
                throw new IllegalStateException("Theme barrier must support visible 1x1 type-10 geometry: " + theme);
        }
        if (ObjectDef.get(objectId("door.closed")).clipType == 0
                || ObjectDef.get(objectId("door.open")).clipType != 0)
            throw new IllegalStateException("Door definitions have incompatible clipping");
    }

    static boolean supports(ObjectDef def, int type) {
        if (def.modelTypes == null) return type == 10;
        for (int t : def.modelTypes) if (t == type) return true;
        return false;
    }
    private int requiredInt(String key) {
        String value = profile.getProperty(key);
        if (value == null) throw new IllegalStateException("Missing asset: " + key);
        return Integer.parseInt(value);
    }
    private String templateFingerprint(RevisionManifest.TemplateBinding template) {
        StringBuilder value = new StringBuilder("geometry=1\n").append(template.variantKey()).append('\n');
        for (RevisionManifest.ChunkBinding source : template.chunks) {
            RevisionManifest.ChunkInfo current = chunk(source.sourceX, source.sourceY, source.plane);
            if (current == null) return "missing";
            value.append(current.sha256).append('\n');
            value.append(sourceLandscape((source.sourceX >> 3) * 256 + (source.sourceY >> 3))).append('\n');
        }
        return hash(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Called only after the offline harness builds and validates its entire stated seed corpus. */
    void writeCollisionAudit(Path path, int maps, Set<String> variants) throws IOException {
        if (!auditMode || maps < 16 || !variants.containsAll(bindings().templates.keySet()))
            throw new IllegalStateException("Incomplete real-map collision audit");
        Properties proof = new Properties();
        int sourceRegions = verifyClientSourceArchives();
        proof.setProperty("audit.version", "2"); proof.setProperty("server.build-id", serverBuildId());
        proof.setProperty("client.source-archives.passed", "true");
        proof.setProperty("client.source-regions.count", Integer.toString(sourceRegions));
        proof.setProperty("cache.sha256", cacheSha256()); proof.setProperty("maps.passed", "true");
        proof.setProperty("maps.count", Integer.toString(maps));
        for (RevisionManifest.TemplateBinding template : bindings().templates.values())
            proof.setProperty("template." + template.variantKey(), templateFingerprint(template));
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            proof.store(writer, "Retained real-server HollowDepthsMap collision audit; invalid after build/cache changes");
        }
    }

    private static final class Terrain {
        final byte[] raw;
        final int[][] underlay = new int[64][64], overlay = new int[64][64], flags = new int[64][64], height = new int[64][64];
        Terrain(byte[] raw) {
            this.raw = raw;
            ByteBuffer data = ByteBuffer.wrap(raw);
            for (int z = 0; z < 4; z++) for (int x = 0; x < 64; x++) for (int y = 0; y < 64; y++) {
                int un = 0, ov = 0, flag = 0, h = -1;
                while (true) {
                    int op = data.get() & 255;
                    if (op == 0) break;
                    if (op == 1) { h = data.get() & 255; break; }
                    if (op <= 49) ov = data.get() & 255; else if (op <= 81) flag = op - 49; else un = op - 81;
                }
                if (z == 0) { underlay[x][y] = un; overlay[x][y] = ov; flags[x][y] = flag; height[x][y] = h; }
            }
            if (data.hasRemaining()) throw new IllegalStateException("Unread 184 terrain data");
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String hash(byte[] data) { return hex(digest().digest(data)); }
    private static byte[] ints(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static void hashFile(MessageDigest digest, Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
    }
}
