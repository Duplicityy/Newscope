package io.ruin.model.activities.hollowdepths;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/** Small offline regression; never initializes Server or changes a cache. */
public final class HollowDepthsArchiveProbe {
    public static void main(String[] args) throws Exception {
        int passed = 0;
        for (String argument : args) {
            Path file = Paths.get(argument);
            byte[] fixture = Files.readAllBytes(file);
            ByteBuffer input = ByteBuffer.wrap(fixture);
            int[] keys = {input.getInt(), input.getInt(), input.getInt(), input.getInt()};
            byte[] raw = Arrays.copyOfRange(fixture, 16, fixture.length);
            boolean reject = file.getFileName().toString().startsWith("reject-");
            try {
                byte[] decoded = HollowDepthsAssets.decodeSourceArchive(raw, keys);
                if (reject) throw new AssertionError("Invalid fixture was accepted: " + file);
                String expected = new String(Files.readAllBytes(Paths.get(argument + ".sha256")), "UTF-8").trim();
                if (!expected.equals(HollowDepthsAssets.hash(decoded))) throw new AssertionError("Decoder mismatch: " + file);
                System.out.println("PASS decoded " + file.getFileName() + " bytes=" + decoded.length);
            } catch (IllegalStateException expectedFailure) {
                if (!reject) throw expectedFailure;
                System.out.println("PASS rejected " + file.getFileName() + " reason=" + expectedFailure.getMessage());
            }
            passed++;
        }
        if (passed < 6) throw new AssertionError("Expected four source regions plus invalid-key/header regressions");
        System.out.println("PASS " + passed + " bounded source archive regressions");
    }
}
