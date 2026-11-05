package cn.local.actionrelay;

import java.nio.ByteBuffer;
import java.util.Arrays;
import cn.local.actionrelay.core.NativeCore;

/** Rust owns bounded queue policy; this adapter only exposes transport packets. */
public final class FrameQueue {
    public static final class Packet {
        public final byte[] bytes;
        public final long deadline,queuedAt;
        public final boolean telemetry;
        private Packet(byte[] encoded){
            telemetry=encoded[0]!=0;queuedAt=NativeCore.read64(encoded,1);
            deadline=NativeCore.read64(encoded,9);bytes=Arrays.copyOfRange(encoded,17,encoded.length);
        }
    }
    private final ByteBuffer state=NativeCore.queueState();
    public synchronized void offerControl(byte[] bytes,long now){NativeCore.offer(state,bytes,now,false);}
    public synchronized void offerGps(byte[] bytes,long now){NativeCore.offer(state,bytes,now,true);}
    public synchronized Packet poll(long now){byte[] packet=NativeCore.poll(state,now);return packet.length==0?null:new Packet(packet);}
    public synchronized void clearGps(){NativeCore.clear(state,true);}
    public synchronized void clear(){NativeCore.clear(state,false);}
    public synchronized int controlCount(){return NativeCore.count(state);}
}
