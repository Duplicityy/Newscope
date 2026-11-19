package net.runelite.standalone;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.DrawManager;

/** Bounded login-screen check only: no account login or world interaction. */
public final class WindowsFreshCacheLoginChecks {
    public static void main(String[] args) throws Exception {
        int exit=1;
        try {
            Class.forName("WindowsFreshCacheLauncher").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            net.runelite.api.Client client=RuneLite.getInjector().getInstance(net.runelite.api.Client.class);
            long until=System.currentTimeMillis()+90000;
            while(client.getGameState()!=GameState.LOGIN_SCREEN&&System.currentTimeMillis()<until)Thread.sleep(100);
            if(client.getGameState()!=GameState.LOGIN_SCREEN)throw new AssertionError("Login screen not reached: "+client.getGameState());
            if(!FileSystem.FileSystem_cacheDir.getCanonicalFile().equals(new java.io.File(System.getProperty("newscape.cache.dir")).getCanonicalFile()))
                throw new AssertionError("Native cache did not use the installed directory");
            CompletableFuture<Image> frame=new CompletableFuture<>();
            RuneLite.getInjector().getInstance(DrawManager.class).requestNextFrameListener(frame::complete);
            Image actual=frame.get(10,TimeUnit.SECONDS);
            BufferedImage copy=new BufferedImage(actual.getWidth(null),actual.getHeight(null),BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics=copy.createGraphics();graphics.drawImage(actual,0,0,null);graphics.dispose();
            ImageIO.write(copy,"png",Paths.get(args[0]).toFile());
            System.out.println("Fresh packaged Windows login screen PASS; native cache="+FileSystem.FileSystem_cacheDir+"; no account login attempted");
            exit=0;
        } finally {System.exit(exit);}
    }
}
