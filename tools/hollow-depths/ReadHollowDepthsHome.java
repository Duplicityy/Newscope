import com.sun.tools.attach.VirtualMachine;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Reads existing region arrays on the world executor; never loads or creates map tiles. */
public final class ReadHollowDepthsHome {
    public static void agentmain(String output,Instrumentation instrumentation)throws Exception{
        Class<?> server=null;
        for(Class<?> type:instrumentation.getAllLoadedClasses())if(type.getName().equals("io.ruin.Server")){server=type;break;}
        if(server==null)throw new IllegalStateException("Server class missing");
        final ClassLoader loader=server.getClassLoader();Object worker=server.getField("worker").get(null);
        ExecutorService executor=(ExecutorService)worker.getClass().getMethod("getExecutor").invoke(worker);
        String report=executor.submit(()->{
            Class<?> world=Class.forName("io.ruin.model.World",false,loader),regionType=Class.forName("io.ruin.model.map.Region",false,loader);
            Object home=world.getField("HOME").get(null);int hx=(Integer)home.getClass().getMethod("getX").invoke(home),hy=(Integer)home.getClass().getMethod("getY").invoke(home);
            StringBuilder b=new StringBuilder("home=").append(hx).append(',').append(hy).append(",0\n");
            Object[] regions=(Object[])regionType.getField("LOADED").get(null);
            for(int x=hx-20;x<=hx+20;x++)for(int y=hy-20;y<=hy+20;y++){
                int id=(x>>6)<<8|(y>>6);Object region=regions[id];
                if(region==null)continue;Object[][][] tiles=(Object[][][])regionType.getField("tiles").get(region);
                Object tile=tiles==null?null:tiles[0][x&63][y&63];
                if((Math.abs(x-hx)<=3&&Math.abs(y-hy)<=3)||(Math.abs(x-2044)<=3&&Math.abs(y-3576)<=3))b.append("lobbyTile=").append(x).append(',').append(y).append(" clip=").append(tile==null?0:tile.getClass().getField("clipping").getInt(tile)).append('\n');
                if(tile==null)continue;Object list=tile.getClass().getField("gameObjects").get(tile);if(list==null)continue;
                for(Object object:(Iterable<?>)list){
                    Class<?> oc=object.getClass();int oid=oc.getField("id").getInt(object);
                    if(oid!=23311&&Math.max(Math.abs(x-hx),Math.abs(y-hy))>8)continue;
                    Class<?> definitions=Class.forName("io.ruin.cache.ObjectDef",false,loader);Object def=definitions.getMethod("get",int.class).invoke(null,oid);
                    b.append("object=").append(oid).append(" xyz=").append(x).append(',').append(y).append(",0 type=").append(oc.getField("type").getInt(object)).append(" direction=").append(oc.getField("direction").getInt(object));
                    if(def!=null){b.append(" name=").append(def.getClass().getField("name").get(def)).append(" size=").append(def.getClass().getField("xLength").getInt(def)).append('x').append(def.getClass().getField("yLength").getInt(def));}
                    b.append('\n');
                }
            }
            return b.toString();
        }).get(20,TimeUnit.SECONDS);
        Files.write(Paths.get(output),report.getBytes("UTF-8"));
    }
    public static void main(String[] args)throws Exception{VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}}
}
