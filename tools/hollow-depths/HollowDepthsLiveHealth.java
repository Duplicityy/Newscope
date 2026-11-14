import com.sun.tools.attach.VirtualMachine;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Production READ ONLY: no login, fixtures, map creation, setters, saves or combat actions. */
public final class HollowDepthsLiveHealth {
    private static void require(boolean ok,String reason) { if(!ok)throw new IllegalStateException(reason); }
    private static Object field(Class<?> type,Object owner,String name)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static String hash(Path path)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path)){byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:digest.digest())out.append(String.format("%02x",b&255));return out.toString();
    }
    private static final class Snapshot {
        final Properties values=new Properties();
        long tick; int humans,bots;
        final Map<String,double[]> xp=new TreeMap<>();
        final Map<String,int[]> levels=new TreeMap<>();
    }
    public static void agentmain(String requestFile,Instrumentation instrumentation)throws Exception {
        require(!Boolean.getBoolean("kronos.hollowDepths.test")&&!Boolean.getBoolean("kronos.adventurers.test"),"Production-only health probe refuses staging flags");
        require(Integer.getInteger("kronos.port",13302)==13302,"Production port13302 required");
        Path request=Paths.get(requestFile).toRealPath();
        Properties config=new Properties();try(InputStream in=Files.newInputStream(request)){config.load(in);}
        Path root=Paths.get(config.getProperty("workspace")).toRealPath(),runtime=root.resolve("local-runtime").toRealPath();
        Path output=request.getParent();
        require(output.getParent().equals(runtime.resolve("hollow-depths-live-health").toRealPath()),"Health report directory is outside approved private runtime");
        require(Paths.get(System.getProperty("user.dir")).toRealPath().equals(root.resolve("kronos-osrs-184/Kronos-master").toRealPath()),"Production working directory mismatch");
        require(Paths.get(System.getProperty("kronos.playerData")).toRealPath().equals(runtime.resolve("player-data").toRealPath()),"Production player data path mismatch");
        require(Paths.get(System.getProperty("kronos.accounts")).toRealPath().equals(runtime.resolve("accounts.properties").toRealPath()),"Production account registry path mismatch");
        require(ManagementFactory.getRuntimeMXBean().getName().startsWith(config.getProperty("pid")+"@"),"Requested process identity mismatch");
        Class<?> live=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("io.ruin.model.activities.hollowdepths.HollowDepths")
                &&field(type,null,"worldThread")!=null){
            Class<?> candidate=Class.forName("io.ruin.Server",false,type.getClassLoader());
            require(live==null||live==candidate,"Multiple active dungeon runtimes are ambiguous");live=candidate;
        }
        require(live!=null,"Actual running Server class missing");
        final Class<?> server=live;final ClassLoader loader=server.getClassLoader();
        URL resource=server.getResource("/io/ruin/Server.class");
        require(resource!=null&&resource.openConnection() instanceof JarURLConnection,"Running Server must originate from an immutable jar");
        Path jar=Paths.get(((JarURLConnection)resource.openConnection()).getJarFileURL().toURI()).toRealPath();
        require(jar.equals(Paths.get(config.getProperty("jar")).toRealPath()),"Running Server jar differs from selected release");
        require(hash(jar).equals(config.getProperty("sha256")),"Running Server bytes differ from reviewed hash");
        Object worker=server.getField("worker").get(null);
        final ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
        // Keep the AttachListener available. This daemon only schedules bounded reads.
        Thread reader=new Thread(()->{
            Properties report=new Properties();
            try {
                Snapshot first=executor.submit(()->snapshot(server,loader,config)).get(10,TimeUnit.SECONDS);
                Thread.sleep(2200);
                Snapshot second=executor.submit(()->snapshot(server,loader,config)).get(10,TimeUnit.SECONDS);
                require(second.tick>first.tick,"World tick did not advance between read-only snapshots");
                report.putAll(second.values);
                report.setProperty("tick.before",Long.toString(first.tick));report.setProperty("tick.after",Long.toString(second.tick));
                report.setProperty("online.humans",Integer.toString(second.humans));report.setProperty("online.bots",Integer.toString(second.bots));
                int expected=Integer.parseInt(config.getProperty("bots.expected"));
                require(second.bots==expected,"Online autonomous population differs from saved configured population");
                int checked=0;
                for(String key:config.stringPropertyNames())if(key.startsWith("baseline.bot.")&&key.endsWith(".xp")){
                    String id=key.substring("baseline.bot.".length(),key.length()-3);
                    String[] baseline=config.getProperty(key).split(",");double[] actual=second.xp.get(id);int[] levels=second.levels.get(id);
                    require(actual!=null&&baseline.length==23,"Saved bot identity or skill vector missing from live world");
                    Class<?> stat=Class.forName("io.ruin.model.stat.Stat",false,loader);
                    Method levelForXp=stat.getMethod("levelForXp",double.class);
                    for(int i=0;i<23;i++){
                        double prior=Double.parseDouble(baseline[i]);
                        require(Double.isFinite(actual[i])&&actual[i]+0.0001>=prior,"A live bot skill has less XP than its stopped-world snapshot");
                        require(levels[i]>=(Integer)levelForXp.invoke(null,prior),"A live bot base level is below its saved XP level");
                    }
                    report.setProperty("bot."+id+".xp",join(actual));report.setProperty("bot."+id+".baseLevels",join(levels));checked++;
                }
                require(checked==expected,"Stopped-world bot baseline does not cover expected population");
                report.setProperty("bot.baselines.checked",Integer.toString(checked));report.setProperty("bot.progress.retained","true");
                report.setProperty("result","PASS");
                report.setProperty("scope","Read-only availability, bindings, ticks and retained bot progression; no organic gameplay or authenticated login claim");
            }catch(Throwable failure){
                report.setProperty("result","FAIL");
                Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();
                report.setProperty("failure",cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage()));
            }
            report.setProperty("capturedUtc",java.time.Instant.now().toString());
            try {
                try(OutputStream out=Files.newOutputStream(output.resolve("native-health.properties"))){report.store(out,"Read-only production health; no private credentials or player names");}
                Files.write(output.resolve("native-health.done"),new byte[]{1});
            }catch(IOException ignored){/* CLI timeout reports the missing result without touching game state. */}
        },"HollowDepths-readonly-health");
        reader.setContextClassLoader(loader);reader.setDaemon(true);reader.start();
    }
    private static Snapshot snapshot(Class<?> server,ClassLoader loader,Properties config)throws Exception {
        Snapshot out=new Snapshot();
        Class<?> hollow=Class.forName("io.ruin.model.activities.hollowdepths.HollowDepths",false,loader);
        require(field(hollow,null,"worldThread")==Thread.currentThread(),"Read is not running on the authoritative game world thread");
        Object port=field(hollow,null,"port"),rewards=field(hollow,null,"rewards"),service=field(hollow,null,"service");
        require(port!=null&&rewards!=null,"Durable dungeon recovery runtime is unavailable");
        require(!((Boolean)field(hollow,null,"faulted")),"Dungeon runtime has entered fault maintenance");
        Object denial=port.getClass().getMethod("deploymentDenial").invoke(port);
        boolean enabled=denial==null;
        require(enabled==Boolean.parseBoolean(config.getProperty("entries.enabled","true")),"Dungeon entry availability differs from expected deployment mode");
        if(enabled)require(service!=null,"Dungeon service is absent despite available assets");
        Object assets=field(port.getClass(),port,"assets");require(assets!=null,"Audited profile is absent");
        String build=(String)assets.getClass().getMethod("serverBuildId").invoke(assets);
        String cache=(String)assets.getClass().getMethod("cacheSha256").invoke(assets);
        require(build.equals(config.getProperty("sha256")),"Runtime asset verifier sees another server build");
        require(cache.equals(config.getProperty("cache.sha256")),"Runtime cache fingerprint differs from reviewed profile");
        out.values.setProperty("server.build-id",build);out.values.setProperty("cache.sha256",cache);
        out.values.setProperty("entries.enabled",Boolean.toString(enabled));out.values.setProperty("recovery.available","true");
        out.tick=((Number)server.getMethod("currentTick").invoke(null)).longValue();

        // Inspect only already-loaded arrays. Tile.get/Region.get could load terrain.
        Class<?> regionClass=Class.forName("io.ruin.model.map.Region",false,loader);
        Object[] regions=(Object[])regionClass.getField("LOADED").get(null);
        Object region=regions[((2044>>6)<<8)|(3576>>6)];require(region!=null,"Home region is not loaded");
        Object[][][] tiles=(Object[][][])regionClass.getField("tiles").get(region);
        Object tile=tiles==null?null:tiles[0][2044&63][3576&63];require(tile!=null,"Existing home portal tile is absent");
        Object objects=tile.getClass().getField("gameObjects").get(tile);Object portal=null;
        if(objects!=null)for(Object object:(Iterable<?>)objects)if(object.getClass().getField("id").getInt(object)==23311
                &&!(Boolean)object.getClass().getMethod("isRemoved").invoke(object)){portal=object;break;}
        require(portal!=null,"Original home portal23311 is absent");
        Object definition=portal.getClass().getMethod("getDef").invoke(portal);
        int option=(Integer)definition.getClass().getMethod("getOption",String[].class).invoke(definition,(Object)new String[]{"teleport"})-1;
        require(option>=0&&option<5,"Home portal teleport option is absent from its definition");
        Object[] own=(Object[])portal.getClass().getField("actions").get(portal);
        Object[] defaults=(Object[])definition.getClass().getField("defaultActions").get(definition);
        Object handler=own!=null&&own.length>option&&own[option]!=null?own[option]:defaults!=null&&defaults.length>option?defaults[option]:null;
        require(handler!=null&&handler.getClass().getName().startsWith("io.ruin.model.inter.handlers.TeleportInterface"),"Home portal lacks its original teleport-interface handler");
        Class<?> teleports=Class.forName("io.ruin.model.inter.handlers.TeleportInterface$Teleport",false,loader);
        boolean entry=false;for(Object value:teleports.getEnumConstants())if(((Enum<?>)value).name().equals("HOLLOW_DEPTHS"))entry=true;
        require(entry,"Hollow Depths option is missing from teleport menu");
        out.values.setProperty("portal.binding","PASS:23311@2044,3576,0; teleport menu handler and Hollow Depths option present, never invoked");
        Class<?> world=Class.forName("io.ruin.model.World",false,loader),playerType=Class.forName("io.ruin.model.entity.player.Player",false,loader);
        Class<?> botType=Class.forName("io.ruin.model.entity.player.autonomous.Adventurer",false,loader);
        for(Object player:(Iterable<?>)world.getField("players").get(null)){
            if(!(Boolean)playerType.getMethod("isOnline").invoke(player))continue;
            if(!botType.isInstance(player)){out.humans++;continue;}
            out.bots++;
            String id=(String)botType.getField("actorId").get(player);require(id!=null&&id.matches("(?:[1-9]|1[0-9]|2[0-5])"),"Unexpected autonomous identity");
            require(botType.getField("quarantineReason").get(player)==null,"An autonomous player is quarantined");
            Object stats=playerType.getMethod("getStats").invoke(player);Method get=stats.getClass().getMethod("get",int.class);
            double[] xp=new double[23];int[] levels=new int[23];
            for(int i=0;i<23;i++){Object stat=get.invoke(stats,i);xp[i]=stat.getClass().getField("experience").getDouble(stat);levels[i]=stat.getClass().getField("fixedLevel").getInt(stat);}
            out.xp.put(id,xp);out.levels.put(id,levels);
        }
        return out;
    }
    private static String join(double[] values){StringBuilder b=new StringBuilder();for(double n:values){if(b.length()>0)b.append(',');b.append(n);}return b.toString();}
    private static String join(int[] values){StringBuilder b=new StringBuilder();for(int n:values){if(b.length()>0)b.append(',');b.append(n);}return b.toString();}
    public static void main(String[] args)throws Exception {
        require(args.length==3,"pid agent.jar request.properties required");
        VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}
        Path output=Paths.get(args[2]).getParent().resolve("native-health.properties");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        while(System.nanoTime()<deadline){if(Files.isRegularFile(output.resolveSibling("native-health.done"))){
            Properties result=new Properties();try(InputStream in=Files.newInputStream(output)){result.load(in);}
            require("PASS".equals(result.getProperty("result")),"Native read-only health failed; inspect private health report");
            System.out.println("PASS: read-only production runtime health");return;
        }Thread.sleep(100);}
        throw new IllegalStateException("Read-only world health did not finish within35seconds");
    }
}
