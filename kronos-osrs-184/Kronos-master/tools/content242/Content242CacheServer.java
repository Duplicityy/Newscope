package io.ruin.update;

import io.ruin.api.filestore.FileStore;
import io.ruin.api.netty.NettyServer;
import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/** Isolated entry point using the unchanged revision-184 update protocol. */
public final class Content242CacheServer {
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("kronos.content242.test")
                || !"127.0.0.1".equals(System.getProperty("kronos.bind")))
            throw new IllegalStateException("Content test mode and loopback bind are required");
        Properties properties = new Properties();
        try (FileInputStream in = new FileInputStream("server.properties")) {
            properties.load(in);
        }
        File cache = new File(properties.getProperty("cache_path")).getCanonicalFile();
        if (!cache.isDirectory() || !new File(cache, "main_file_cache.dat2").isFile())
            throw new IllegalStateException("Prepared staging cache is missing");
        HandshakeDecoder.REVISION = CacheCapability.revision(cache);
        if (HandshakeDecoder.REVISION != CacheCapability.EXTENDED_REVISION)
            throw new IllegalStateException("Extended242 cache marker required for this test");
        Server.fileStore = new FileStore(cache.getPath(), 8 * 1024 * 1024);
        NettyServer listener = NettyServer.start("Content242 isolated revision-184 update server",
                17304, HandshakeDecoder.class, 0, true);
        Runtime.getRuntime().addShutdownHook(new Thread(listener::shutdown));
        System.out.println("CONTENT242 CACHE READY revision=" + HandshakeDecoder.REVISION + " port=17304");
    }
}
