package net.runelite.standalone;

import java.io.*;
import java.util.*;

/** Uses the shipped 184 client's actual decoder and lighting implementation.
 * Input: repeated big-endian int ID, int byte length, mesh bytes; EOF ends it.
 * Output is TSV. Run headless with -Xmx256m; no cache/server/client is started.
 */
public final class VerifyModels184 {
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static void vertex(int index, int count) {
        require(index >= 0 && index < count, "invalid vertex " + index + "/" + count);
    }

    private static String verify(byte[] data) {
        return verifyMesh(new ModelData(data));
    }

    private static String verifyMesh(ModelData mesh) {
        require(mesh.verticesCount <= 4700 && mesh.faceCount <= 4700, "renderer mesh limit");
        TreeSet<Integer> textures = new TreeSet<>();
        int[] priorities = new int[12];
        for (int i = 0; i < mesh.faceCount; i++) {
            vertex(mesh.indices1[i], mesh.verticesCount);
            vertex(mesh.indices2[i], mesh.verticesCount);
            vertex(mesh.indices3[i], mesh.verticesCount);
            if (mesh.faceTextures != null && mesh.faceTextures[i] != -1) {
                require(mesh.faceTextures[i] >= 0, "negative texture ID");
                textures.add((int) mesh.faceTextures[i]);
                if (mesh.textureCoords != null && mesh.textureCoords[i] != -1) {
                    int index = mesh.textureCoords[i] & 255;
                    require(index < mesh.textureTriangleCount, "invalid texture coordinate");
                    require(mesh.textureRenderTypes[index] == 0, "unsupported used texture triangle");
                }
            }
            if (mesh.faceRenderPriorities != null) {
                int priority = mesh.faceRenderPriorities[i] & 255;
                require(priority < 12, "invalid priority");
                ++priorities[priority];
            }
        }
        for (int i = 0; i < mesh.textureTriangleCount; i++) {
            if (mesh.textureRenderTypes[i] == 0) {
                vertex(mesh.texTriangleX[i] & 65535, mesh.verticesCount);
                vertex(mesh.texTriangleY[i] & 65535, mesh.verticesCount);
                vertex(mesh.texTriangleZ[i] & 65535, mesh.verticesCount);
            }
        }
        boolean legacySkin = mesh.vertexSkins != null;
        Model lit = mesh.copy$toModel(64, 768, -50, -10, -50);
        require(lit.indicesCount == mesh.faceCount, "lighting lost faces");
        int maxPriority = 0;
        for (int count : priorities) maxPriority = Math.max(maxPriority, count);
        // Buckets contain visible faces only. Total >2000 is a risk to inspect
        // in integration, not proof a mesh fails the decoder or renderer.
        return mesh.verticesCount + "\t" + mesh.faceCount + "\t" + mesh.textureTriangleCount
            + "\t" + legacySkin + "\t" + textures.toString().replace(" ", "") + "\t" + maxPriority;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("--heights")) {
            int x = Integer.parseInt(args[1]), y = Integer.parseInt(args[2]);
            int width = Integer.parseInt(args[3]), height = Integer.parseInt(args[4]);
            for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++)
                System.out.println((x + dx) + "\t" + (y + dy) + "\t"
                    + WorldMapDecoration.method5195(x + dx + 932731, y + dy + 556238));
            return;
        }
        boolean merge = args.length == 2 && args[0].equals("--merge");
        int pass = 0, fail = 0;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(args[merge ? 1 : 0])))) {
            for (;;) {
                int id;
                try { id = input.readInt(); } catch (EOFException done) { break; }
                int modelCount = merge ? input.readInt() : 1;
                require(modelCount > 0 && modelCount <= 20, "invalid merge model count");
                byte[][] data = new byte[modelCount][];
                for (int index = 0; index < modelCount; index++) {
                    int length = input.readInt();
                    require(length >= 18 && length < 10000000, "invalid framed model length");
                    data[index] = new byte[length];
                    input.readFully(data[index]);
                }
                try {
                    String result;
                    if (merge) {
                        ModelData[] meshes = new ModelData[modelCount];
                        for (int index = 0; index < modelCount; index++) meshes[index] = new ModelData(data[index]);
                        result = verifyMesh(new ModelData(meshes, modelCount));
                    } else result = verify(data[0]);
                    System.out.println(id + "\tOK\t" + result);
                    pass++;
                } catch (Throwable error) {
                    System.out.println(id + "\tFAIL\t" + error.toString().replace('\t', ' ').replace('\n', ' '));
                    fail++;
                }
            }
        }
        System.err.println("models: " + pass + " passed, " + fail + " failed");
        if (fail != 0) System.exit(1);
    }
}
