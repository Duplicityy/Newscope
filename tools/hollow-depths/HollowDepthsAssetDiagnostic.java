package io.ruin.model.activities.hollowdepths;
import io.ruin.Server;
import io.ruin.cache.ObjectDef;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Read-only effective native definitions, isolated stage only. */
public final class HollowDepthsAssetDiagnostic {
    public static void agentmain(String directory,Instrumentation ignored)throws Exception{
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||Integer.getInteger("kronos.port",0)!=14312)throw new IllegalStateException("Isolated test server required");
        Path stage=Paths.get(directory).toRealPath();
        if(!Server.dataFolder.toPath().toRealPath().startsWith(stage))throw new IllegalStateException("Stage data mismatch");
        String result=Server.worker.getExecutor().submit(()->{
            StringBuilder text=new StringBuilder("Read-only loaded184 ObjectDef diagnostics\n");
            for(int id:new int[]{6950,158,15732,22091,23348,2010,19871}){
                ObjectDef d=ObjectDef.get(id);
                text.append(id).append(" name=").append(d.name).append(" clipType=").append(d.clipType)
                    .append(" tall=").append(d.tall).append(" someFlag=").append(d.someFlag)
                    .append(" xLength=").append(d.xLength).append(" yLength=").append(d.yLength)
                    .append(" modelTypes=").append(Arrays.toString(d.modelTypes))
                    .append(" modelIds=").append(Arrays.toString(d.modelIds))
                    .append(" supports10=").append(HollowDepthsAssets.supports(d,10)).append('\n');
            }
            return text.toString();
        }).get(30,TimeUnit.SECONDS);
        Files.write(stage.resolve("native-asset-diagnostic.txt"),result.getBytes("UTF-8"));
    }
}
