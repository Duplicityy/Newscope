package io.ruin.update;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.ruin.api.buffer.InBuffer;
import io.ruin.api.netty.MessageDecoder;
import io.ruin.api.utils.IPAddress;
import java.io.FileNotFoundException;
import java.util.ArrayDeque;
import java.util.Deque;

public class Js5Decoder extends MessageDecoder<Channel> {
    // The184 client pipelines up to200 priority and200 ordinary requests. Retain
    // descriptors, not hundreds of full responses, while a slow socket drains.
    private static final int MAX_PENDING=512;
    private static final class Request {
        final int index,group,xor;final boolean priority;
        Request(int index,int group,int xor,boolean priority){this.index=index;this.group=group;this.xor=xor;this.priority=priority;}
    }
    private final Deque<Request> pending=new ArrayDeque<>();
    private int encryptionValue;
    private boolean writing;
    public Js5Decoder(){super(null,false);}

    @Override protected void handle(Channel channel,InBuffer in,int opcode){
        if(opcode==0||opcode==1){
            if(pending.size()>=MAX_PENDING){pending.clear();channel.close();return;}
            int index=in.readUnsignedByte(),group=in.readUnsignedShort();
            // Preserve the existing184 master-reference response's XOR exemption.
            pending.addLast(new Request(index,group,index==255&&group==255?0:encryptionValue,opcode==1));
            drain(channel);return;
        }
        if(opcode==4)encryptionValue=in.readUnsignedByte();
    }
    private void drain(Channel channel){
        if(writing)return;
        if(!channel.isActive()){pending.clear();return;}
        Request request=pending.pollFirst();if(request==null)return;
        try{
            CacheManager.Response response=CacheManager.get(request.index,request.group,request.priority);
            if(response==null)throw new FileNotFoundException();
            writing=true;
            // Read-only unencrypted view shares immutable cached bytes. Only XOR
            // allocates a private exact-size copy; Netty owns just this one write.
            channel.writeAndFlush(Unpooled.wrappedBuffer(response.view(request.xor))).addListener(done->{
                writing=false;
                if(!done.isSuccess()){pending.clear();channel.close();return;}
                if(!pending.isEmpty())channel.eventLoop().execute(()->drain(channel));
            });
        }catch(Exception failure){
            writing=false;pending.clear();
            System.err.println("Invalid File Request ("+request.index+","+request.group+","+request.priority+") from: "+IPAddress.get(channel));
            channel.close();
        }
    }
    @Override protected int getSize(int opcode){return opcode>=0&&opcode<=4?3:-3;}
}
