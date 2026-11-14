package net.runelite.standalone;

import java.io.*;
import java.util.*;

/** Runs the shipped client.jar's actual Frames/Animation/SequenceDefinition and
 * Model animation methods headlessly. Input is verify_animations.py's output.
 * This class is compiled alone against client.jar; no Gradle build is needed.
 */
public final class VerifyAnimations184 {
    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
    private static byte[] blob(DataInputStream input) throws IOException {
        int size = input.readInt();
        require(size > 0 && size < 10000000, "invalid record size");
        byte[] raw = new byte[size]; input.readFully(raw); return raw;
    }
    private static final class MemoryArchive extends AbstractArchive {
        final Map<Integer, Map<Integer, byte[]>> records = new TreeMap<>();
        MemoryArchive() { super(false, false); }
        public int fileCount(int group) { return Collections.max(records.get(group).keySet()) + 1; }
        public int[] method4042(int group) {
            int[] ids = new int[records.get(group).size()]; int offset = 0;
            for (int id : records.get(group).keySet()) ids[offset++] = id;
            return ids;
        }
        public byte[] method4020(int group, int file, short ignored) { return records.get(group).get(file); }
        public byte[] method4028(int group, int file) { return records.get(group).get(file); }
    }
    public static void main(String[] args) throws Exception {
        MemoryArchive skeletonArchive = new MemoryArchive(), frameArchive = new MemoryArchive();
        Map<Integer, Frames> frames = new TreeMap<>();
        Map<Integer, SequenceDefinition> sequences = new TreeMap<>();
        int frameCount = 0, references = 0, animatedFrames = 0, movedFrames = 0, outfits;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))) {
            int skeletonCount = input.readInt();
            for (int i = 0; i < skeletonCount; i++) {
                int id = input.readInt(); byte[] raw = blob(input);
                Skeleton skeleton = new Skeleton(id, raw);
                require(skeleton.count > 0, "empty legacy skeleton " + id);
                skeletonArchive.records.put(id, Collections.singletonMap(0, raw));
            }
            int groupCount = input.readInt();
            for (int i = 0; i < groupCount; i++) {
                int group = input.readInt(), count = input.readInt();
                Map<Integer, byte[]> records = new TreeMap<>();
                for (int j = 0; j < count; j++) records.put(input.readInt(), blob(input));
                frameArchive.records.put(group, records);
                Frames decoded = new Frames(frameArchive, skeletonArchive, group, false);
                frames.put(group, decoded);
                for (int id : records.keySet()) {
                    Animation animation = decoded.frames[id];
                    require(animation != null && animation.transformCount <= 500, "invalid decoded frame");
                    frameCount++;
                }
            }
            int sequenceCount = input.readInt();
            for (int i = 0; i < sequenceCount; i++) {
                int id = input.readInt(); byte[] raw = blob(input);
                Buffer buffer = new Buffer(raw);
                SequenceDefinition sequence = new SequenceDefinition();
                sequence.method4658(buffer); sequence.method4687();
                require(buffer.offset == raw.length, "unread sequence bytes " + id);
                require(sequence.frameIds != null && sequence.frameIds.length > 0, "no legacy frames " + id);
                require(sequence.frameIds.length == sequence.frameLengths.length, "frame/duration mismatch");
                for (int reference : sequence.frameIds) {
                    Frames group = frames.get(reference >>> 16);
                    require(group != null && group.frames[reference & 65535] != null, "missing frame reference " + id);
                    references++;
                }
                if (sequence.chatFrameIds != null) for (int reference : sequence.chatFrameIds) {
                    if ((reference & 65535) == 65535) continue;
                    Frames group = frames.get(reference >>> 16);
                    require(group != null && group.frames[reference & 65535] != null, "missing chat frame " + id);
                    references++;
                }
                sequences.put(id, sequence);
            }
            outfits = input.readInt();
            for (int i = 0; i < outfits; i++) {
                int gender = input.readInt(), count = input.readInt();
                ModelData[] meshes = new ModelData[count];
                for (int j = 0; j < count; j++) meshes[j] = new ModelData(blob(input));
                ModelData combined = new ModelData(meshes, count);
                Model original = combined.copy$toModel(64, 768, -50, -10, -50);
                require(original.verticesCount <= 4700 && original.indicesCount <= 4700, "outfit exceeds legacy geometry limit");
                require(original.vertexLabels != null, "outfit has no legacy vertex groups");
                int testCount = input.readInt(), applied = 0, changed = 0;
                for (int j = 0; j < testCount; j++) {
                    int sequenceId = input.readInt();
                    for (int reference : sequences.get(sequenceId).frameIds) {
                        Frames group = frames.get(reference >>> 16);
                        Model model = (Model) original.toSharedModel(false);
                        model.method2363(group, reference & 65535);
                        require(model.verticesCount == original.verticesCount && model.indicesCount == original.indicesCount, "animation changed topology");
                        boolean moved = false;
                        for (int vertex = 0; vertex < model.verticesCount; vertex++) {
                            require(Math.abs((long) model.verticesX[vertex]) < 100000 && Math.abs((long) model.verticesY[vertex]) < 100000
                                && Math.abs((long) model.verticesZ[vertex]) < 100000, "animation coordinates out of range");
                            moved |= model.verticesX[vertex] != original.verticesX[vertex] || model.verticesY[vertex] != original.verticesY[vertex]
                                || model.verticesZ[vertex] != original.verticesZ[vertex];
                        }
                        applied++; if (moved) changed++;
                    }
                }
                require(changed > 0, "outfit animation did not move any vertex");
                animatedFrames += applied; movedFrames += changed;
                System.out.println("outfit\t" + gender + "\tOK\tvertices=" + original.verticesCount + "\tfaces=" + original.indicesCount
                    + "\tframes=" + applied + "\tmovingFrames=" + changed);
            }
            require(input.read() == -1, "unread input records");
        }
        System.out.println("PASS skeletons=" + skeletonArchive.records.size() + " frameGroups=" + frames.size() + " frames=" + frameCount
            + " sequences=" + sequences.size() + " references=" + references + " outfits=" + outfits + " appliedFrames=" + animatedFrames + " movingFrames=" + movedFrames);
    }
}
