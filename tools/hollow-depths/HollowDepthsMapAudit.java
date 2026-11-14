package io.ruin.model.activities.hollowdepths;

import com.sun.tools.attach.VirtualMachine;
import com.newscape.dungeons.*;
import io.ruin.Server;
import io.ruin.model.map.Region;
import io.ruin.model.map.dynamic.DynamicChunk;
import io.ruin.model.map.dynamic.DynamicMap;
import io.ruin.model.map.Position;
import io.ruin.model.map.route.routes.ProjectileRoute;
import io.ruin.model.entity.player.Player;
import io.ruin.network.PacketSender;
import io.ruin.api.buffer.OutBuffer;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Native map audit. Stage JVM only; the retained proof is emitted only after every assertion passes. */
public final class HollowDepthsMapAudit {
    private static int assertions;
    private static long worstBuildNanos, worstValidationNanos;
    private static <T> T world(Callable<T> action)throws Exception {
        return Server.worker.getExecutor().submit(action).get(30,TimeUnit.SECONDS);
    }
    private static void check(boolean success,String message){assertions++;if(!success)throw new AssertionError(message);}
    public static void agentmain(String directory,Instrumentation ignored)throws Exception {
        if(!Boolean.getBoolean("kronos.hollowDepths.test")||Integer.getInteger("kronos.port",0)!=14312
                ||!"127.0.0.1".equals(System.getProperty("kronos.bind")))throw new IllegalStateException("Isolated184 game required");
        Path stage=Paths.get(directory).toRealPath();
        if(!Paths.get(System.getProperty("user.home")).toRealPath().getParent().equals(stage)
                ||!Server.dataFolder.toPath().toRealPath().startsWith(stage))throw new IllegalStateException("Stage path isolation failed");
        Path profile=Server.dataFolder.toPath().resolve("hollow-depths/revision-184.properties");
        Path report=stage.resolve("native-map-audit.txt");
        if(Files.exists(stage.resolve("native-map-audit.cancel")))throw new CancellationException("Remove the retained audit cancel marker before a new run");
        String origin="Server protectionDomain="+Server.class.getProtectionDomain()+"\nServer resource="+Server.class.getResource("Server.class")+"\nServer loader resource="+Server.class.getClassLoader().getResource("io/ruin/Server.class")+"\n";
        Files.write(report,origin.getBytes(StandardCharsets.UTF_8));System.out.print(origin);
        HollowDepthsAssets assets=world(()->HollowDepthsAssets.forAudit(profile));
        check(assets.deploymentDenial()==null,"Asset audit prerequisites: "+assets.deploymentDenial());
        final int clientSourceRegions=world(()->assets.verifyClientSourceArchives());
        check(clientSourceRegions>0,"Actual184 source landscape/XTEA/decompression audit must cover bound regions");
        world(()->{dynamicRegressions(assets);return null;});
        DungeonGenerator generator=new DungeonGenerator(new ContentCatalog());
        Set<String> variants=new TreeSet<>();int maps=0,rotatedFixtures=0;
        for(int seed=0;seed<64&&(maps<16||variants.size()<assets.bindings().templates.size());seed++){
            // Opposite straight-room rotations have the same exit-direction set, so the
            // generator canonically emits only r0/r1. Exercise the other physical
            // orientations by rotating the ENTIRE generated geometry, not its label.
            final DungeonLayout layout=seed<16?generator.generate(seed):rotate180(generator.generate(seed-16));
            if(seed>=16)rotatedFixtures++;
            final UUID runId=UUID.randomUUID();
            final HollowDepthsMap map=world(()->new HollowDepthsMap(new DungeonPorts.Instance("audit:"+runId,runId),"audit-only",layout,assets));
            boolean complete=false;long lastTick=-1;
            try{
                int rounds=0;
                while(!complete){
                    if(Files.exists(stage.resolve("native-map-audit.cancel")))throw new CancellationException("Native map audit cancelled; no proof emitted");
                    if(++rounds>512)throw new AssertionError("Native build did not terminate");
                    long tick=world(()->Server.currentTick());
                    if(tick==lastTick){Thread.sleep(100);continue;}lastTick=tick;
                    complete=world(()->{long start=System.nanoTime();boolean built=map.step(16);worstBuildNanos=Math.max(worstBuildNanos,System.nanoTime()-start);return built;});
                }
                world(()->{validateMap(map,assets);return null;});
                for(DungeonLayout.Room room:layout.rooms)variants.add(room.templateKey+".r"+room.rotation);
                maps++;
                String progress="Native maps passed="+maps+" variants="+variants.size()+" assertions="+assertions+"\n";
                Files.write(report,progress.getBytes(StandardCharsets.UTF_8));System.out.print("HOLLOW DEPTHS "+progress);
            }finally{world(()->{map.destroy();map.destroy();return null;});}
        }
        check(maps>=16,"At least16 native maps must pass");
        check(variants.size()==assets.bindings().templates.size(),"Every bound template rotation must be exercised");
        assets.writeCollisionAudit(profile.resolveSibling("collision-audit.properties"),maps,variants);
        String summary="PASS native184 maps="+maps+" rotatedFixtures="+rotatedFixtures+" variants="+variants.size()+" assertions="+assertions+" clientSourceRegions="+clientSourceRegions
            +" maxBuildStepMs="+(worstBuildNanos/1000000d)+" maxValidationMs="+(worstValidationNanos/1000000d)
            +"\nEvery144chunk source mapping, all4quadrants, native movement/projectile clipping, connected objectives, doors/chests, cleanup and allocator regression checks passed.\nBounded actual184 source landscape/XTEA decoding and native region packet field/bit/key-order roundtrips passed for all4view quadrants.\n";
        Files.write(report,summary.getBytes(StandardCharsets.UTF_8));System.out.print("HOLLOW DEPTHS "+summary);
    }
    private static DungeonLayout.Tile rotate(DungeonLayout.Tile tile){return new DungeonLayout.Tile(DungeonLayout.MAP_SIZE-1-tile.x,DungeonLayout.MAP_SIZE-1-tile.y);}
    private static List<DungeonLayout.Tile> rotateTiles(List<DungeonLayout.Tile> source){List<DungeonLayout.Tile> out=new ArrayList<>();for(DungeonLayout.Tile tile:source)out.add(rotate(tile));return out;}
    private static List<DungeonLayout.NpcSpawn> rotateNpcs(List<DungeonLayout.NpcSpawn> source){List<DungeonLayout.NpcSpawn> out=new ArrayList<>();for(DungeonLayout.NpcSpawn npc:source)out.add(new DungeonLayout.NpcSpawn(npc.slot,npc.archetypeKey,rotate(npc.tile)));return out;}
    private static DungeonLayout rotate180(DungeonLayout source){
        List<DungeonLayout.Room> rooms=new ArrayList<>();
        for(DungeonLayout.Room room:source.rooms){
            List<DungeonLayout.ChestSpawn> chests=new ArrayList<>();for(DungeonLayout.ChestSpawn chest:room.chests)chests.add(new DungeonLayout.ChestSpawn(chest.slot,chest.lootTableKey,rotate(chest.tile),chest.requiresClear,chest.requiredAgility));
            List<DungeonLayout.TrapSpawn> traps=new ArrayList<>();
            for(DungeonLayout.TrapSpawn trap:room.traps){DungeonLayout.Gauntlet g=trap.gauntlet;
                DungeonLayout.Gauntlet transformed=new DungeonLayout.Gauntlet(rotate(g.entranceOutside),rotate(g.entranceDoor),rotate(g.entranceInside),rotate(g.exitInside),rotate(g.exitDoor),rotate(g.exitOutside),rotateTiles(g.lane),rotateTiles(g.interiorTiles),rotateNpcs(g.guards));
                traps.add(new DungeonLayout.TrapSpawn(trap.slot,trap.trapKey,rotate(trap.trigger),rotate(trap.landing),rotate(trap.bypass),transformed));
            }
            rooms.add(new DungeonLayout.Room(room.id,3-room.gridX,3-room.gridY,room.kind,room.templateKey,room.kind==DungeonLayout.RoomKind.TRAP?(room.rotation+2)%4:0,rotateNpcs(room.npcs),chests,traps,rotate(room.center())));
        }
        boolean[][] floor=new boolean[DungeonLayout.MAP_SIZE][DungeonLayout.MAP_SIZE];
        for(int x=0;x<DungeonLayout.MAP_SIZE;x++)for(int y=0;y<DungeonLayout.MAP_SIZE;y++)floor[x][y]=source.walkable(rotate(new DungeonLayout.Tile(x,y)));
        return new DungeonLayout(source.seed,source.themeKey,rooms,source.edges,source.startRoomId,source.bossRoomId,rotate(source.entry),source.estimatedSeconds,floor);
    }
    private static void validateMap(HollowDepthsMap map,HollowDepthsAssets assets){
        long start=System.nanoTime();String error=map.validate();check(error==null,"Native map validation: "+error);
        worstValidationNanos=Math.max(worstValidationNanos,System.nanoTime()-start);
        verifyProjectileBoundaries(map);
        verifyRegionPackets(map);
        DungeonLayout.Room[][] slots=new DungeonLayout.Room[4][4];
        for(DungeonLayout.Room room:map.layout.rooms)slots[room.gridX][room.gridY]=room;
        for(int x=0;x<12;x++)for(int y=0;y<12;y++){
            DungeonLayout.Room room=slots[x/3][y/3];
            RevisionManifest.TemplateBinding source=room==null?assets.bindings().template("crypt_hall"):assets.bindings().template(room.templateKey,room.rotation);
            int sourceX=source.sourceChunkX+x%3,sourceY=source.sourceChunkY+y%3;
            Region region=Region.get(map.map.swRegion.baseX+x*8,map.map.swRegion.baseY+y*8);
            int[] actual=region.dynamicData[0][x&7][y&7];
            check(actual[0]==((source.plane<<24)|(sourceX<<14)|(sourceY<<3)),"Wrong source descriptor at chunk"+x+","+y);
            check(actual[1]==((sourceX/8)<<8)+(sourceY/8),"Wrong source region at chunk"+x+","+y);
        }
        for(HollowDepthsMap.ObjectHandle object:map.objects()){
            if(object.kind==HollowDepthsMap.ObjectKind.DOOR){
                map.setDoor(object.key,true);check(object.object.id==assets.objectId("door.open"),"Door failed to open");
                map.setDoor(object.key,false);check(object.object.id==assets.objectId("door.closed"),"Door failed to close");
            }else if(object.kind==HollowDepthsMap.ObjectKind.CHEST){
                map.openedChest(object.slot);check(object.object.id==assets.objectId("chest.open"),"Chest visual failed to open");
            }
        }
        check(map.validate()==null,"Native map failed validation after door/chest interactions");
    }
    private static void verifyProjectileBoundaries(HollowDepthsMap map){
        int boundaries=0;
        for(int x=0;x<DungeonLayout.MAP_SIZE;x++)for(int y=0;y<DungeonLayout.MAP_SIZE;y++){
            DungeonLayout.Tile from=new DungeonLayout.Tile(x,y);
            if(!map.layout.walkable(from))continue;
            for(int[] direction:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){
                int targetX=x+direction[0],targetY=y+direction[1];
                if(targetX<0||targetY<0||targetX>=DungeonLayout.MAP_SIZE||targetY>=DungeonLayout.MAP_SIZE)continue;
                DungeonLayout.Tile to=new DungeonLayout.Tile(targetX,targetY);
                if(map.layout.walkable(to))continue;
                Position a=map.world(from),b=map.world(to);
                check(!ProjectileRoute.allow(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY()),
                    "Native projectile route crossed generated wall at "+targetX+","+targetY);
                boundaries++;
            }
        }
        check(boundaries>0,"Every native map must exercise real projectile boundary rejection");
    }
    /** Unregistered packet fixture. No channel, account, region occupancy or World entry exists. */
    private static final class PacketPlayer extends Player{
        private final ArrayList<Region> packetRegions=new ArrayList<>();
        PacketPlayer(Position at){position=at;}
        @Override public void addRegion(Region region){packetRegions.add(region);}
        @Override public void removeFromRegions(){packetRegions.clear();}
        @Override public ArrayList<Region> getRegions(){return packetRegions;}
    }
    private static final class PacketCapture extends PacketSender{
        byte[] bytes;PacketCapture(Player player){super(player,null);}
        @Override public void write(OutBuffer packet){bytes=packet.encode(null).toByteArray();}
    }
    private static final class Reader{
        final byte[] bytes;int offset,bit;
        Reader(byte[] bytes){this.bytes=bytes;offset=3;}
        int u8(){check(offset<bytes.length,"Truncated dynamic packet");return bytes[offset++]&255;}
        int u16(){return u8()<<8|u8();}
        int int32(){return u8()<<24|u8()<<16|u8()<<8|u8();}
        int bits(int count){int result=0;for(int i=0;i<count;i++){check((bit>>3)<bytes.length,"Truncated dynamic bitstream");result=result<<1|((bytes[bit>>3]>>(7-(bit&7)))&1);bit++;}return result;}
    }
    private static void verifyRegionPackets(HollowDepthsMap map){
        // Field order/LEShortA/ByteS/26-bit decoding matches actual184 PlayerType.method3945.
        // The completed-frame client gate separately exercises that original binary parser.
        for(int[] point:new int[][]{{12,12},{76,12},{12,76},{76,76}}){
            Position position=map.world(new DungeonLayout.Tile(point[0],point[1]));PacketPlayer player=new PacketPlayer(position);PacketCapture capture=new PacketCapture(player);capture.sendRegion(false);
            Reader reader=new Reader(capture.bytes);check((capture.bytes[0]&255)==71,"Native instanced map opcode");
            check(((capture.bytes[1]&255)<<8|(capture.bytes[2]&255))==capture.bytes.length-3,"Dynamic packet wire length");
            int chunkY=reader.u16(),force=(128-reader.u8())&255,low=(reader.u8()-128)&255,chunkX=low|(reader.u8()<<8),count=reader.u16();
            check(chunkX==position.getChunkX()&&chunkY==position.getChunkY()&&force==0,"Native184 dynamic packet header order");reader.bit=reader.offset*8;
            LinkedHashSet<Integer> regions=new LinkedHashSet<>();
            for(int plane=0;plane<4;plane++)for(int x=0;x<13;x++)for(int y=0;y<13;y++){
                int decoded=reader.bits(1)==0?-1:reader.bits(26),targetX=chunkX-6+x,targetY=chunkY-6+y;
                Region region=Region.LOADED[(targetX/8)<<8|(targetY/8)];int expected=-1;
                if(region!=null&&region.dynamicData!=null&&region.dynamicIndex==map.map.swRegion.dynamicIndex){int[] data=region.dynamicData[plane][targetX%8][targetY%8];if(data[0]!=0&&data[1]!=0)expected=data[0];}
                check(decoded==expected,"Native packet source descriptor differs from map at quadrant "+point[0]+","+point[1]);
                if(decoded!=-1){int sourceX=(decoded>>14)&1023,sourceY=(decoded>>3)&2047;regions.add((sourceX/8<<8)+sourceY/8);}
            }
            reader.offset=(reader.bit+7)/8;check(regions.size()==count,"Packet key count must equal first-seen client source region count");
            for(int id:regions){int[] actual=Region.LOADED[id].keys;for(int i=0;i<4;i++)check(reader.int32()==(actual==null?0:actual[i]),"XTEA tuple order differs from original184 region traversal");}
            check(reader.offset==capture.bytes.length,"Dynamic packet has trailing/missing source-key bytes");player.removeFromRegions();
        }
    }
    private static void dynamicRegressions(HollowDepthsAssets assets){
        RevisionManifest.TemplateBinding source=assets.bindings().template("crypt_hall");
        DynamicMap map=new DynamicMap();int oldLease=map.swRegion.dynamicIndex;int regionId=map.swRegion.id;
        try{
            DynamicChunk first=new DynamicChunk(source.sourceChunkX,source.sourceChunkY,source.plane).pos(9,10,0);
            map.build(Collections.singletonList(first));
            check(first.pointX==9&&first.pointY==10,"Builder mutated caller chunk target coordinates");
            int descriptor=map.neRegion.dynamicData[0][1][2][0];
            DynamicChunk second=new DynamicChunk(source.sourceChunkX+1,source.sourceChunkY+1,source.plane).pos(0,0,0);
            map.build(Collections.singletonList(second));
            check(map.neRegion.dynamicData[0][1][2][0]==descriptor,"Incremental build erased a previous quadrant");
            check(map.swRegion.dynamicData[0][0][0][1]==second.regionId,"Incremental source chunk missing");
            DynamicChunk third=new DynamicChunk(source.sourceChunkX+2,source.sourceChunkY,source.plane).pos(10,10,0);
            map.build(Collections.singletonList(third));
            check(map.neRegion.dynamicData[0][1][2][0]==descriptor,"Repeated build erased an earlier chunk in the same quadrant");
            check(map.neRegion.dynamicData[0][2][2][1]==third.regionId,"Repeated quadrant new chunk missing");
            DynamicChunk northWest=new DynamicChunk(source.sourceChunkX,source.sourceChunkY+2,source.plane).pos(0,9,0);
            map.build(Collections.singletonList(northWest));
            check(map.nwRegion.dynamicData[0][0][1][1]==northWest.regionId,"North-west chunk was placed in the wrong quadrant");
        }finally{map.destroy();map.destroy();}
        DynamicMap next=new DynamicMap();
        try{
            check(next.swRegion.dynamicIndex!=oldLease,"New dynamic allocation reused stale lease identity");
            check(Region.LOADED[regionId].dynamicIndex!=oldLease,"Released region retained a stale live lease");
        }finally{next.destroy();}
    }
    public static void main(String[] args)throws Exception{
        VirtualMachine vm=VirtualMachine.attach(args[0]);try{vm.loadAgent(args[1],args[2]);}finally{vm.detach();}
    }
}
