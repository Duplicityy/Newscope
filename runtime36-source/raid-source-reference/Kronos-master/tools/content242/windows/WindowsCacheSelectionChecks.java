package net.runelite.standalone;

import java.io.File;
import java.nio.file.*;

/** Actual packaged selector: stale locators and globals must never override it. */
public final class WindowsCacheSelectionChecks {
    public static void main(String[] args) throws Exception {
        Path home=Paths.get(args[0]).toAbsolutePath();
        String release=(String)Class.forName("WindowsFreshCacheLauncher").getField("RELEASE").get(null);
        Path cache=home.resolve("newscape-cache/"+release);
        Files.createDirectories(cache);
        Files.write(home.resolve("kronos_cl_oldschool_live.dat"),new byte[]{3,1,0,99});
        System.setProperty("user.home",home.toString());
        System.setProperty("newscape.cache.dir",cache.toString());
        // Leave every old cache global uninitialized. Touching the legacy path fails.
        int checks=0;
        for(int index=0;index<4;index++) {
            if(!WorldMapRegion.method337("oldschool","live",index).equals(cache.toFile().getCanonicalFile()))throw new AssertionError("Wrong cache");
            checks++;
        }
        for(String invalid:new String[]{"", "relative", home.toString(), home.resolve("newscape-cache").toString(), home.resolve("newscape-cache/missing").toString()}) {
            System.setProperty("newscape.cache.dir",invalid);
            try {WorldMapRegion.method337("oldschool","live",0);throw new AssertionError("Accepted invalid cache: "+invalid);}
            catch(IllegalStateException expected){checks++;}
        }
        if(!Files.exists(home.resolve("kronos_cl_oldschool_live.dat")))throw new AssertionError("Legacy locator modified");
        System.out.println("Actual Windows cache selector PASS "+(checks+1)+"; bypasses legacy locators/fallback for all four native cache attempts");
    }
}
