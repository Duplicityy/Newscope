package io.ruin.update;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.ruin.api.buffer.OutBuffer;
import io.ruin.api.filestore.FileStore;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Detached wire/storage/backpressure checks; no server listener or account is created. */
public final class Js5CacheChecks {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static byte[] bytes(ByteBuffer view){byte[] data=new byte[view.remaining()];view.get(data);return data;}
    private static byte[] container(int compression,int length,int revision)throws Exception{
        byte[] data=new byte[5+length+(compression==0?0:4)+(revision<0?0:2)];ByteBuffer b=ByteBuffer.wrap(data);b.put((byte)compression).putInt(length);
        for(int i=5;i<data.length-(revision<0?0:2);i++)data[i]=(byte)(i*31+(i>>>8));
        if(revision>=0)b.putShort(data.length-2,(short)revision);return data;
    }
    private static byte[] oldWire(int index,int group,byte[] archive){
        int compression=archive[0]&255,length=ByteBuffer.wrap(archive,1,4).getInt();int payload=length+(compression==0?0:4);
        OutBuffer out=new OutBuffer(8+archive.length);out.addByte(index).addShort(group).addByte(compression).addInt(length);int offset=8;
        for(int i=5;i<payload+5;i++){if(offset==512){out.addByte(255);offset=1;}out.addByte(archive[i]);offset++;}return out.toByteArray();
    }
    private static byte[] unframe(byte[] wire){
        ByteBuffer header=ByteBuffer.wrap(wire);header.get();header.getShort();int codec=header.get()&255,length=header.getInt(),size=length+(codec==0?0:4);
        byte[] payload=new byte[size];int offset=8;
        for(int i=0;i<size;i++){if(offset%512==0){check((wire[offset++]&255)==255,"continuation marker");}payload[i]=wire[offset++];}
        check(offset==wire.length,"no trailing marker or duplicated byte");return payload;
    }
    private static void codec()throws Exception{
        int[] lengths={0,1,499,500,503,504,505,507,508,509,510,511,512,1011,1014,1015,1016,1024,2048,8191};
        for(int codec=0;codec<=2;codec++)for(int length:lengths)for(int revision:new int[]{-1,42}){
            byte[] raw=container(codec,length,revision);CacheManager.Response response=CacheManager.Response.archive(7,65442,raw);byte[] wire=bytes(response.view(0));
            check(Arrays.equals(wire,oldWire(7,65442,raw)),"byte-identical native184 framing");
            int payload=length+(codec==0?0:4);check(Arrays.equals(unframe(wire),Arrays.copyOfRange(raw,5,5+payload)),"container payload roundtrip");
            for(int key:new int[]{1,127,255}){byte[] encrypted=bytes(response.view(key));for(int i=0;i<encrypted.length;i++)encrypted[i]^=key;check(Arrays.equals(encrypted,wire),"XOR roundtrip");}
            check(Arrays.equals(bytes(response.view(0)),wire),"XOR cannot contaminate cached bytes");
            ByteBuffer first=response.view(0),second=response.view(0);first.position(first.limit());check(second.position()==0&&!second.hasArray(),"independent immutable views");
            try{second.put(0,(byte)0);throw new AssertionError("Mutable view");}catch(ReadOnlyBufferException expected){checks++;}
        }
        byte[] body=new byte[8*256];new Random(184).nextBytes(body);
        check(Arrays.equals(bytes(CacheManager.Response.uncompressed(255,255,body).view(0)),oldWire(255,255,plain(body))),"master-reference table framing");
        for(byte[] invalid:new byte[][]{new byte[4],new byte[]{0,-1,0,0,0},new byte[]{2,0,0,0,1},new byte[]{0,0,0,0,0,1,2,3}}){
            try{CacheManager.Response.archive(7,1,invalid);throw new AssertionError("Malformed container accepted");}catch(IOException expected){checks++;}
        }
    }
    private static byte[] plain(byte[] body){return ByteBuffer.allocate(5+body.length).put((byte)0).putInt(body.length).put(body).array();}
    private static void cache()throws Exception{
        CacheManager.Response tiny=CacheManager.Response.uncompressed(0,0,new byte[2]);CacheManager.ResponseCache cache=new CacheManager.ResponseCache(tiny.length()*2L);
        cache.put(7,1,tiny);cache.put(7,2,tiny);check(cache.get(7,1)==tiny,"LRU hit");cache.put(7,3,tiny);
        check(cache.get(7,2)==null&&cache.get(7,1)==tiny&&cache.get(7,3)==tiny,"least-recently-used eviction");
        cache.put(7,1,tiny);check(cache.bytes()==20&&cache.size()==2,"replacement accounting");
        cache.put(7,4,CacheManager.Response.uncompressed(7,4,new byte[100]));check(cache.size()==2&&cache.get(7,4)==null,"oversized response served uncached");
        cache.clear();check(cache.bytes()==0&&cache.size()==0,"clear releases weights");
        CacheManager.ResponseCache entries=new CacheManager.ResponseCache(200000);for(int i=0;i<10000;i++)entries.put(1,i,tiny);check(entries.size()==8192,"tiny entry-count bound");
        CacheManager.ResponseCache stress=new CacheManager.ResponseCache(16L*1024*1024);
        byte[] bank=container(0,5548500,-1);
        for(int i=0;i<96;i++){
            CacheManager.Response response=CacheManager.Response.archive(7,65408+i,bank);stress.put(7,65408+i,response);
            check(stress.bytes()<=16L*1024*1024&&stress.size()<=3,"5.55MB response churn bounded");
            check(bytes(response.view(i&255)).length==response.length(),"one exact XOR output");
        }
        CacheManager.Response response=CacheManager.Response.archive(7,65442,bank);List<ByteBuf> views=new ArrayList<>();
        // If wrapping copies the5.55MB body, these150 views cannot fit a128MiB heap.
        for(int i=0;i<150;i++)views.add(Unpooled.wrappedBuffer(response.view(0)));
        check(views.get(0).isReadOnly()&&views.get(149).readableBytes()==response.length(),"Netty zero-copy immutable views under128MiB");
        views.get(0).readByte();check(views.get(1).readerIndex()==0,"independent Netty reader positions");for(ByteBuf view:views)view.release();
    }
    private static byte[] table(int... groups)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeByte(5);out.writeByte(0);out.writeShort(groups.length);
        int last=0;for(int group:groups){out.writeShort(group-last);last=group;}for(int ignored:groups)out.writeInt(0);for(int ignored:groups)out.writeInt(1);
        for(int ignored:groups)out.writeShort(1);for(int ignored:groups)out.writeShort(0);return plain(bytes.toByteArray());
    }
    private static void write(Path folder,int index,int group,byte[] data)throws Exception{
        try(RandomAccessFile dat=new RandomAccessFile(folder.resolve("main_file_cache.dat2").toFile(),"rw");RandomAccessFile idx=new RandomAccessFile(folder.resolve("main_file_cache.idx"+index).toFile(),"rw")){
            int sector=(int)Math.max(1,(dat.length()+519)/520),first=sector;
            for(int offset=0,part=0;offset<data.length;offset+=512,part++,sector++){
                int count=Math.min(512,data.length-offset),next=offset+count<data.length?sector+1:0;dat.seek(sector*520L);dat.writeShort(group);dat.writeShort(part);dat.writeByte(next>>>16);dat.writeShort(next);dat.writeByte(index);dat.write(data,offset,count);
            }
            idx.seek(group*6L);idx.writeByte(data.length>>>16);idx.writeShort(data.length);idx.writeByte(first>>>16);idx.writeShort(first);
        }
    }
    private static void storage(Path folder)throws Exception{
        Files.createDirectories(folder);byte[] large=container(0,1081249,-1);
        for(int index=0;index<8;index++){
            new File(folder.toFile(),"main_file_cache.idx"+index).createNewFile();write(folder,255,index,index==7?table(100,65442):index==6?table(65442):table());
        }
        write(folder,7,65442,large);write(folder,7,100,large);write(folder,6,65442,large);
        FileStore legacy=new FileStore(folder.toString());check(legacy.get(7).getArchiveData(65442)==null,"legacy1MB limit unchanged");
        FileStore extended=new FileStore(folder.toString(),8*1024*1024);
        check(Arrays.equals(extended.get(7).getArchiveData(65442),large),"extended native1.08MB bank roundtrip");
        check(extended.get(7).getArchiveData(100)==null&&extended.get(6).getArchiveData(65442)==null,"ordinary/index6 limits unchanged");
        try{new FileStore(folder.toString(),8*1024*1024+1);throw new AssertionError("Unbounded extension");}catch(IllegalArgumentException expected){checks++;}
        Server.fileStore=extended;CacheManager.Response normal=CacheManager.get(7,65442,false),priority=CacheManager.get(7,65442,true);
        check(normal==priority,"one cache entry for normal and priority");check(Arrays.equals(unframe(bytes(normal.view(0))),Arrays.copyOfRange(large,5,large.length)),"native disk through response roundtrip");
        check(CacheManager.get(255,255,true)!=null,"native master-reference response");
        pipeline();
        xorPipeline();
        Server.fileStore=legacy;check(CacheManager.get(7,65442,false)==null,"store replacement cannot reuse previous cache bytes");
    }
    private static final class Gate extends ChannelOutboundHandlerAdapter {
        ByteBuf body;ChannelPromise promise;int writes;
        @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise){check(body==null,"only one outstanding encoded response");body=(ByteBuf)message;this.promise=promise;writes++;}
        void complete(){ByteBuf current=body;ChannelPromise done=promise;body=null;promise=null;current.release();done.setSuccess();}
        void fail(){ByteBuf current=body;ChannelPromise done=promise;body=null;promise=null;current.release();done.setFailure(new IOException("Detached closed transport"));}
    }
    private static void pipeline(){
        Gate gate=new Gate();EmbeddedChannel channel=new EmbeddedChannel(gate,new Js5Decoder());ByteBuf requests=Unpooled.buffer(1600);
        for(int i=0;i<400;i++)requests.writeByte(i&1).writeByte(7).writeShort(65442);channel.writeInbound(requests);
        check(gate.writes==1,"400 pipeline descriptors do not encode400 full responses");
        for(int i=0;i<400;i++){check(gate.body!=null&&gate.body.isReadOnly(),"queued response ready");gate.complete();channel.runPendingTasks();}
        check(gate.writes==400&&gate.body==null,"all400 requests finish without loss");channel.finishAndReleaseAll();
    }
    private static void xorPipeline(){
        Gate gate=new Gate();EmbeddedChannel channel=new EmbeddedChannel(gate,new Js5Decoder());
        ByteBuf requests=Unpooled.buffer();requests.writeByte(0).writeByte(7).writeShort(65442);
        requests.writeByte(4).writeByte(85).writeShort(0).writeByte(1).writeByte(7).writeShort(65442);
        requests.writeByte(4).writeByte(17).writeShort(0).writeByte(1).writeByte(255).writeShort(255);
        channel.writeInbound(requests);check(gate.body.getUnsignedByte(0)==7,"first response remains unencrypted");gate.complete();channel.runPendingTasks();
        check(gate.body.getUnsignedByte(0)==(7^85),"queued request captures its own XOR key");gate.complete();channel.runPendingTasks();
        check(gate.body.getUnsignedByte(0)==255&&gate.body.getUnsignedByte(1)==0&&gate.body.getUnsignedByte(2)==255,"master reference retains legacy XOR exemption");gate.complete();channel.runPendingTasks();channel.finishAndReleaseAll();
        Gate failed=new Gate();EmbeddedChannel disconnected=new EmbeddedChannel(failed,new Js5Decoder());ByteBuf two=Unpooled.buffer();
        for(int i=0;i<2;i++)two.writeByte(1).writeByte(7).writeShort(65442);disconnected.writeInbound(two);failed.fail();disconnected.runPendingTasks();
        check(!disconnected.isActive()&&failed.writes==1,"failed write closes and drops queued descriptors");disconnected.finishAndReleaseAll();
        Gate flooded=new Gate();EmbeddedChannel bounded=new EmbeddedChannel(flooded,new Js5Decoder());ByteBuf many=Unpooled.buffer();
        for(int i=0;i<514;i++)many.writeByte(0).writeByte(7).writeShort(65442);bounded.writeInbound(many);
        check(!bounded.isActive()&&flooded.writes==1,"request-descriptor overflow fails closed");flooded.fail();bounded.runPendingTasks();bounded.finishAndReleaseAll();
    }
    public static void main(String[] args){int code=1;try{codec();cache();storage(Paths.get(args[0]));System.out.println("PASS JS5 exact wire/LRU/XOR/native bank/backpressure checks="+checks+" maxHeap="+Runtime.getRuntime().maxMemory()+"; no server listener started");code=0;}catch(Throwable failure){failure.printStackTrace();}finally{System.exit(code);}}
}
