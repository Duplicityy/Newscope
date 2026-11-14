import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Reads real bot state. Optional relocation affects only an explicitly named ordinary QA observer. */
public final class AdventurerObservation {
    private static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static Object call(Object owner,String method)throws Exception {Method m;try{m=owner.getClass().getMethod(method);}catch(NoSuchMethodException hidden){m=owner.getClass().getDeclaredMethod(method);}m.setAccessible(true);return m.invoke(owner);}
    private static void require(boolean condition,String reason){if(!condition)throw new IllegalStateException(reason);}
    public static void agentmain(String requestFile,Instrumentation instrumentation)throws Exception {
        require(!Boolean.getBoolean("kronos.adventurers.test")&&!Boolean.getBoolean("kronos.hollowDepths.test")&&Integer.getInteger("kronos.port",13302)==13302,"Expected live production world");
        Path request=Paths.get(requestFile).toRealPath();Properties config=new Properties();
        try(java.io.InputStream in=Files.newInputStream(request)){config.load(in);}
        Path directory=request.getParent();String mode=config.getProperty("mode");require("snapshot".equals(mode)||"observe".equals(mode),"Invalid observation action");
        Class<?> server=null;for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("io.ruin.model.activities.hollowdepths.HollowDepths")){
            Field thread=type.getDeclaredField("worldThread");thread.setAccessible(true);if(thread.get(null)==null)continue;
            Class<?> candidate=Class.forName("io.ruin.Server",false,type.getClassLoader());require(server==null||server==candidate,"Multiple active worlds");server=candidate;
        }
        require(server!=null,"Active world not found");final Class<?> actualServer=server;final ClassLoader loader=server.getClassLoader();
        Object worker=server.getField("worker").get(null);ExecutorService executor=(ExecutorService)call(worker,"getExecutor");
        String json=executor.submit(()->{
            Class<?> world=Class.forName("io.ruin.model.World",false,loader),playerType=Class.forName("io.ruin.model.entity.player.Player",false,loader);
            Class<?> botType=Class.forName("io.ruin.model.entity.player.autonomous.Adventurer",false,loader);
            Class<?> combat=Class.forName("io.ruin.model.combat.CombatUtils",false,loader),statType=Class.forName("io.ruin.model.stat.StatType",false,loader);
            List<Map<String,Object>> bots=new ArrayList<>();Object target=null,observer=null;
            for(Object player:(Iterable<?>)world.getField("players").get(null)){
                if(botType.isInstance(player)){
                    Map<String,Object> bot=new LinkedHashMap<>();String id=(String)botType.getField("actorId").get(player);
                    bot.put("id",id);bot.put("name",botType.getField("displayName").get(player));bot.put("build",String.valueOf(botType.getField("archetype").get(player)));
                    bot.put("combat",combat.getMethod("getCombatLevel",playerType).invoke(null,player));
                    bot.put("x",call(player,"getAbsX"));bot.put("y",call(player,"getAbsY"));bot.put("plane",call(player,"getHeight"));bot.put("online",call(player,"isOnline"));
                    Object brain=field(player,"brain"),port=field(player,"port");
                    bot.put("state",String.valueOf(call(brain,"state")));bot.put("reason",call(brain,"reason"));bot.put("activity",call(port,"description"));
                    Object stats=call(player,"getStats");Map<String,Integer> levels=new LinkedHashMap<>();int total=0;
                    for(Object skill:statType.getEnumConstants()){
                        Object stat=stats.getClass().getMethod("get",int.class).invoke(stats,((Enum<?>)skill).ordinal());int level=stat.getClass().getField("fixedLevel").getInt(stat);
                        levels.put(((Enum<?>)skill).name(),level);total+=level;
                    }
                    bot.put("total",total);bot.put("levels",levels);bots.add(bot);
                    if(id.equals(config.getProperty("bot")))target=player;
                }else if(config.getProperty("observer","").equals(call(player,"getName")))observer=player;
            }
            Map<String,Object> report=new LinkedHashMap<>();report.put("capturedUtc",java.time.Instant.now().toString());report.put("tick",actualServer.getMethod("currentTick").invoke(null));report.put("bots",bots);
            if("observe".equals(mode)){
                require(config.getProperty("observer","").matches("hdl[0-9a-f]{8}"),"Only disposable ordinary QA observer may move");
                require(observer!=null&&target!=null&&(Boolean)call(observer,"isOnline"),"Observer or requested bot unavailable");
                Object channel=call(observer,"getChannel");require(channel!=null&&(Boolean)call(channel,"isActive"),"A real QA client must be online");
                Class<?> hollow=Class.forName("io.ruin.model.activities.hollowdepths.HollowDepths",false,loader);
                require(!(Boolean)hollow.getMethod("inRun",playerType).invoke(null,observer),"Observer must leave any dungeon normally first");
                require(!(Boolean)call(call(observer,"getCombat"),"isDead"),"Observer must be alive");
                int x=(Integer)call(target,"getAbsX"),y=(Integer)call(target,"getAbsY"),z=(Integer)call(target,"getHeight");
                Class<?> regionType=Class.forName("io.ruin.model.map.Region",false,loader);Object[] loaded=(Object[])regionType.getField("LOADED").get(null);
                int[] destination=null;
                for(int[] delta:new int[][]{{0,2},{2,0},{0,-2},{-2,0},{2,2},{-2,-2},{0,3}}){
                    int tx=x+delta[0],ty=y+delta[1];Object region=loaded[((tx>>6)<<8)|(ty>>6)];if(region==null)continue;
                    Object[][][] tiles=(Object[][][])regionType.getField("tiles").get(region);Object tile=tiles==null?null:tiles[z][tx&63][ty&63];
                    if(tile!=null&&tile.getClass().getField("clipping").getInt(tile)==0){destination=new int[]{tx,ty,z};break;}
                }
                require(destination!=null,"No already-loaded clear observer tile near bot");
                Object movement=call(observer,"getMovement");call(movement,"reset");movement.getClass().getMethod("teleport",int.class,int.class,int.class).invoke(movement,destination[0],destination[1],destination[2]);
                report.put("observerDestination",destination);report.put("observedBotId",config.getProperty("bot"));
            }
            Object gson=Class.forName("io.ruin.api.utils.JsonUtils",false,loader).getField("GSON_EXPOSE").get(null);
            return (String)gson.getClass().getMethod("toJson",Object.class).invoke(gson,report);
        }).get(10,TimeUnit.SECONDS);
        String filename=config.getProperty("output");require(filename.matches("[a-z0-9-]+\\.json"),"Invalid report filename");
        Files.write(directory.resolve(filename),json.getBytes("UTF-8"));
    }
    public static void main(String[] args)throws Exception {VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}}
}
