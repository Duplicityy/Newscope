package com.newscape.dungeons;

/** No JUnit download needed. Run from a JDK through verify.sh / verify.ps1. */
public final class AllChecks {
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        LayoutChecks.run();
        System.out.println("LayoutChecks passed: 1000 seeds plus signed-long boundary seeds.");
        RewardChecks.run();
        ManifestChecks.run();
        System.out.println("ManifestChecks passed: revision, build, cache, entity, footprint and collision gates (mock cache).");
        LifecycleChecks.run();
        System.out.printf(java.util.Locale.ROOT, "ALL CHECKS PASSED in %.3f seconds%n", (System.nanoTime() - start) / 1_000_000_000.0);
        System.out.println("Verified logical engine/headless world only; actual Kronos/cache/client integration remains a separate gate.");
    }
}
