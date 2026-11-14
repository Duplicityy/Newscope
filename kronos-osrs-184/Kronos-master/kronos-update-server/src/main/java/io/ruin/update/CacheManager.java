package io.ruin.update;

import io.ruin.api.filestore.FileStore;
import io.ruin.api.filestore.IndexFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exact-size immutable wire responses, shared by normal/priority requests and bounded by bytes. */
public final class CacheManager {
    private static final int MIB=1024*1024;
    private static final ResponseCache cached=new ResponseCache(cacheBudget());
    private static FileStore owner;
    private CacheManager(){}

    private static long cacheBudget(){
        long defaultBudget=Math.min(32L*MIB,Math.max(4L*MIB,Runtime.getRuntime().maxMemory()/8));
        String configured=System.getProperty("kronos.js5.cacheBytes");
        if(configured==null)return defaultBudget;
        try{long bytes=Long.parseLong(configured);if(bytes>=0&&bytes<=defaultBudget)return bytes;}
        catch(NumberFormatException ignored){}
        throw new IllegalArgumentException("kronos.js5.cacheBytes outside heap-bounded budget");
    }

    public static synchronized Response get(int index,int archiveId,boolean priority)throws IOException{
        if(index<0||index>255||archiveId<0||archiveId>65535)return null;
        FileStore store=Server.fileStore;if(store==null)throw new IOException("Cache is not initialized");
        if(owner!=store){cached.clear();owner=store;}
        // Priority is scheduling metadata, with identical184 response bytes.
        Response response=cached.get(index,archiveId);if(response!=null)return response;
        if(index==255&&archiveId==255){
            byte[] table=new byte[Math.multiplyExact(store.files.length,8)];int offset=0;
            for(IndexFile file:store.files){
                putInt(table,offset,file==null?0:file.table.crc);
                putInt(table,offset+4,file==null?0:file.table.revision);offset+=8;
            }
            response=Response.uncompressed(index,archiveId,table);
        }else{
            IndexFile file=store.get(index);
            if(file==null||(index==255?archiveId>=store.files.length:!file.archiveExists(archiveId)))return null;
            byte[] archive=file.getArchiveData(archiveId);if(archive==null)return null;
            response=Response.archive(index,archiveId,archive);
        }
        cached.put(index,archiveId,response);return response;
    }
    private static void putInt(byte[] out,int offset,int value){out[offset]=(byte)(value>>>24);out[offset+1]=(byte)(value>>>16);out[offset+2]=(byte)(value>>>8);out[offset+3]=(byte)value;}

    /** Only read-only views escape. XOR never modifies the cached bytes. */
    static final class Response {
        static final int MAX_CONTAINER_BYTES=16*MIB;
        private final byte[] wire;
        private Response(byte[] wire){this.wire=wire;}
        int length(){return wire.length;}
        ByteBuffer view(int xor){
            if((xor&~255)!=0)throw new IllegalArgumentException("XOR byte");
            if(xor==0)return ByteBuffer.wrap(wire).asReadOnlyBuffer();
            byte[] copy=wire.clone();for(int i=0;i<copy.length;i++)copy[i]^=(byte)xor;
            return ByteBuffer.wrap(copy).asReadOnlyBuffer();
        }
        static Response archive(int index,int group,byte[] archive)throws IOException{
            if(archive==null||archive.length<5||archive.length>MAX_CONTAINER_BYTES)throw new IOException("Invalid container size");
            int compression=archive[0]&255;
            int length=(archive[1]&255)<<24|(archive[2]&255)<<16|(archive[3]&255)<<8|archive[4]&255;
            long payload=(long)length+(compression==0?0:4);
            if(length<0||payload>archive.length-5L||archive.length-5L-payload>2)throw new IOException("Invalid container length");
            return encode(index,group,compression,length,archive,5,(int)payload);
        }
        static Response uncompressed(int index,int group,byte[] body)throws IOException{
            if(body==null||body.length>MAX_CONTAINER_BYTES-5)throw new IOException("Invalid plain container");
            return encode(index,group,0,body.length,body,0,body.length);
        }
        static int encodedLength(int payload)throws IOException{
            if(payload<0||payload>MAX_CONTAINER_BYTES)throw new IOException("Invalid payload size");
            int markers=payload>504?(payload-504+510)/511:0;
            return 8+payload+markers;
        }
        private static Response encode(int index,int group,int compression,int length,byte[] source,int offset,int payload)throws IOException{
            if(index<0||index>255||group<0||group>65535||compression<0||compression>255)throw new IOException("Invalid response identifier");
            byte[] wire=new byte[encodedLength(payload)];wire[0]=(byte)index;wire[1]=(byte)(group>>>8);wire[2]=(byte)group;wire[3]=(byte)compression;putInt(wire,4,length);
            int out=8,room=504,left=payload;
            while(left>0){
                int count=Math.min(room,left);System.arraycopy(source,offset,wire,out,count);offset+=count;out+=count;left-=count;
                if(left>0){wire[out++]=(byte)255;room=511;}
            }
            if(out!=wire.length)throw new IOException("JS5 framing length mismatch");
            return new Response(wire);
        }
    }

    /** Access-ordered weighted LRU; tiny responses also have a bounded entry count. */
    static final class ResponseCache {
        private static final int MAX_ENTRIES=8192;
        private final long budget;
        private long bytes;
        private final LinkedHashMap<Long,Response> entries=new LinkedHashMap<>(128,.75f,true);
        ResponseCache(long budget){if(budget<0)throw new IllegalArgumentException("Cache budget");this.budget=budget;}
        private static long key(int index,int group){return ((long)index<<32)|(group&0xffffffffL);}
        Response get(int index,int group){return entries.get(key(index,group));}
        void put(int index,int group,Response response){
            long key=key(index,group);Response previous=entries.remove(key);if(previous!=null)bytes-=previous.length();
            if(response.length()>budget)return;
            Iterator<Map.Entry<Long,Response>> iterator=entries.entrySet().iterator();
            while((bytes+response.length()>budget||entries.size()>=MAX_ENTRIES)&&iterator.hasNext()){bytes-=iterator.next().getValue().length();iterator.remove();}
            entries.put(key,response);bytes+=response.length();
        }
        void clear(){entries.clear();bytes=0;}
        long bytes(){return bytes;}
        int size(){return entries.size();}
    }
}
