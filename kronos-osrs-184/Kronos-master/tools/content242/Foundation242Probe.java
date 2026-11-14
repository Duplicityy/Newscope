package net.runelite.standalone;

import java.io.*;
import java.util.*;

/** Exercises the real packaged184 definition and terrain readers on assembled cache bytes. */
public final class Foundation242Probe {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static int mix(int hash, int value) { return (hash ^ value) * 16777619; }
    public static void main(String[] args) throws Exception {
        // Native NPC constructors publish RuneLite events. No plugins/window are
        // needed for decoding; provide the normal callback interface with no subscribers.
        ViewportMouse.client = new Client();
        ViewportMouse.client.callbacks = (net.runelite.api.hooks.Callbacks) java.lang.reflect.Proxy.newProxyInstance(
                Foundation242Probe.class.getClassLoader(),new Class[]{net.runelite.api.hooks.Callbacks.class},
                (proxy,method,arguments) -> method.getReturnType()==boolean.class ? false : null);
        Map<Integer,Integer> counts = new TreeMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])))) {
            int count = in.readInt();
            for (int record = 0; record < count; record++) {
                int group = in.readInt(), id = in.readInt(), length = in.readInt();
                check(length > 0 && length < 16*1024*1024,"bounded fixture payload");
                byte[] data = new byte[length];in.readFully(data);
                Buffer buffer = new Buffer(data);
                try {
                    switch (group) {
                        case 3: new KitDefinition().method4707(buffer); break;
                        case 6: new ObjectDefinition().method4717(buffer); break;
                        case 9: new NPCDefinition().method4429(buffer); break;
                        case 10: new ItemDefinition().method4527(buffer); break;
                        case 13: new SpotAnimationDefinition().method4390(buffer); break;
                        default: throw new AssertionError("Unknown fixture group");
                    }
                    check(buffer.offset == length,"definition reader did not consume exact payload");
                } catch (Throwable failure) {
                    throw new AssertionError("Native decode failed group="+group+" id="+id,failure);
                }
                counts.put(group,counts.getOrDefault(group,0)+1);
            }
            check(in.read()==-1,"trailing definition fixture data");
        }
        int regions;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(args[1])))) {
            regions = in.readInt();
            for (int region = 0; region < regions; region++) {
                int id = in.readInt(), expected = in.readInt(), length = in.readInt();
                byte[] data = new byte[length];in.readFully(data);
                Tiles.field540 = new short[4][104][104];Tiles.field522 = new short[4][104][104];
                Tiles.Tiles_renderFlags = new byte[4][104][104];Tiles.Tiles_heights = new int[4][105][105];
                DevicePcmPlayerProvider.field156 = new byte[4][104][104];class298.field3719 = new byte[4][104][104];
                Buffer buffer = new Buffer(data);int hash = 0x811c9dc5;
                for(int z=0;z<4;z++) for(int x=0;x<64;x++) for(int y=0;y<64;y++) {
                    GrandExchangeOfferWorldComparator.method91(buffer,z,x,y,0,0,0);
                    hash=mix(hash,Tiles.field540[z][x][y]&65535);
                    hash=mix(hash,Tiles.field522[z][x][y]&65535);
                    hash=mix(hash,Tiles.Tiles_renderFlags[z][x][y]&255);
                    hash=mix(hash,Tiles.Tiles_heights[z][x][y]);
                    hash=mix(hash,DevicePcmPlayerProvider.field156[z][x][y]&255);
                    hash=mix(hash,class298.field3719[z][x][y]&255);
                }
                check(buffer.offset==data.length,"Terrain trailing data: "+id);
                check(hash==expected,"Native terrain field mismatch: "+id);
            }
            check(in.read()==-1,"trailing terrain fixture data");
        }
        check(Content242Models.group(65536)==65408 && Content242Models.file(65536)==0,"first bank address");
        check(Content242Models.group(66048)==65409 && Content242Models.file(66048)==0,"second bank address");
        check(Content242Models.group(130559)==65534 && Content242Models.file(130559)==511,"last bank address");
        try { Content242Models.validate(130560);throw new AssertionError("Model overflow accepted"); }
        catch(IllegalArgumentException expected) { }
        int models=0, weighted=0, large=0;
        if(args.length>2) try(DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(args[2])))) {
            int count=in.readInt();
            for(int record=0;record<count;record++) {
                int id=in.readInt(),length=in.readInt();
                check(length>0 && length<8*1024*1024,"bounded model payload");
                byte[] raw=new byte[length];in.readFully(raw);
                try {
                    ModelData data=new ModelData(raw);
                    for(int face=0;face<data.faceCount;face++) {
                        check(data.indices1[face]>=0 && data.indices1[face]<data.verticesCount,"triangle1 bounds");
                        check(data.indices2[face]>=0 && data.indices2[face]<data.verticesCount,"triangle2 bounds");
                        check(data.indices3[face]>=0 && data.indices3[face]<data.verticesCount,"triangle3 bounds");
                    }
                    Model lit=data.copy$toModel(64,768,-50,-10,-50);
                    check(lit.verticesCount==data.verticesCount && lit.indicesCount==data.faceCount,"lighting topology");
                    if(data.skeletalBones242!=null) {
                        check(data.skeletalBones242.length==data.verticesCount,"vertex weights cardinality");
                        check(lit.skeletalBones242==data.skeletalBones242,"lighting retains weights");weighted++;
                    }
                    if(Math.max(data.verticesCount,data.faceCount)>4700)large++;
                    models++;
                } catch(Throwable failure) { throw new AssertionError("Native banked model failed id="+id,failure); }
            }
            check(in.read()==-1,"trailing model fixture data");
        }
        System.out.println("PASS actual packaged-client definitions="+counts+" wideTerrainRegions="+regions+
                " bankedModels="+models+" weightedModels="+weighted+" largeModels="+large);
        System.exit(0); // Applet construction initializes an AWT helper; this standalone probe owns the JVM.
    }
}
