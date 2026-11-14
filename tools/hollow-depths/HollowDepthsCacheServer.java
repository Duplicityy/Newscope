package io.ruin.update;
import io.ruin.api.filestore.FileStore;
import io.ruin.api.netty.NettyServer;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
/** The original184 update protocol with isolated local assets and loopback listener. */
public final class HollowDepthsCacheServer {
    public static void main(String[] args) throws Exception {
        if(!Boolean.getBoolean("kronos.hollowDepths.test") || !"127.0.0.1".equals(System.getProperty("kronos.bind")))
            throw new IllegalStateException("Explicit isolated test mode required");
        Properties p=new Properties();
        try(FileInputStream in=new FileInputStream("server.properties")){p.load(in);}
        File stage=new File(".").getCanonicalFile(),cache=new File(p.getProperty("cache_path")).getCanonicalFile();
        if(!cache.equals(new File(stage,"Cache")) || !new File(cache,"main_file_cache.dat2").isFile())
            throw new IllegalStateException("Only this stage's copied184 cache is allowed");
        Server.fileStore=new FileStore(cache.getPath());
        NettyServer listener=NettyServer.start("Hollow Depths isolated184 cache",17314,HandshakeDecoder.class,0,true);
        Runtime.getRuntime().addShutdownHook(new Thread(listener::shutdown));
        System.out.println("HOLLOW DEPTHS CACHE READY revision="+HandshakeDecoder.REVISION+" port=17314");
    }
}
