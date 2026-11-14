package io.ruin.model.activities.hollowdepths;
import io.ruin.Server;
import io.ruin.model.map.dynamic.*;
import com.newscape.dungeons.RevisionManifest;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Narrow native allocator regression, independent of the full map corpus. */
public final class HollowDepthsDynamicRegression {
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    public static void agentmain(String directory,Instrumentation ignored)throws Exception{
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||Integer.getInteger("kronos.port",0)!=14312)throw new IllegalStateException("Isolated test server required");
        Path stage=Paths.get(directory).toRealPath();if(!Server.dataFolder.toPath().toRealPath().startsWith(stage))throw new IllegalStateException("Stage data mismatch");
        Server.worker.getExecutor().submit(()->{
            HollowDepthsAssets assets=HollowDepthsAssets.forAudit(Server.dataFolder.toPath().resolve("hollow-depths/revision-184.properties"));
            check(assets.deploymentDenial()==null,"Valid native assets required");
            RevisionManifest.TemplateBinding source=assets.bindings().template("crypt_hall");DynamicMap map=new DynamicMap();int lease=map.swRegion.dynamicIndex;
            try{
                DynamicChunk first=new DynamicChunk(source.sourceChunkX,source.sourceChunkY,source.plane).pos(9,10,0);map.build(Collections.singletonList(first));
                int descriptor=map.neRegion.dynamicData[0][1][2][0];check(first.pointX==9&&first.pointY==10,"Caller coordinates mutated");
                DynamicChunk second=new DynamicChunk(source.sourceChunkX+1,source.sourceChunkY,source.plane).pos(10,10,0);map.build(Collections.singletonList(second));
                check(map.neRegion.dynamicData[0][1][2][0]==descriptor,"Same quadrant incremental build erased previous chunk");
                check(map.neRegion.dynamicData[0][2][2][0]==((source.plane<<24)|((source.sourceChunkX+1)<<14)|(source.sourceChunkY<<3)),"Same quadrant new source descriptor incorrect");
                DynamicChunk northWest=new DynamicChunk(source.sourceChunkX,source.sourceChunkY+1,source.plane).pos(0,9,0);map.build(Collections.singletonList(northWest));
                check(map.nwRegion.dynamicData[0][0][1][0]==((source.plane<<24)|(source.sourceChunkX<<14)|((source.sourceChunkY+1)<<3)),"NW descriptor assigned to wrong quadrant");
                check(map.neRegion.dynamicData[0][1][2][0]==descriptor,"NW build erased NE state");
            }finally{map.destroy();map.destroy();}
            DynamicMap next=new DynamicMap();try{check(next.swRegion.dynamicIndex!=lease,"Destroyed lease reused same identity");}finally{next.destroy();}
            return null;
        }).get(30,TimeUnit.SECONDS);
        Files.write(stage.resolve("native-dynamic-regression.txt"),"PASS native184: unchanged caller coordinates, incremental same-quadrant preservation, exact NE/NW descriptors, idempotent destroy, fresh lease identity.\n".getBytes("UTF-8"));
    }
}
