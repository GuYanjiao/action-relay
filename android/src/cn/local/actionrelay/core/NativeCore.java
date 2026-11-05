package cn.local.actionrelay.core;

import java.nio.ByteBuffer;

/** Synchronous bulk JNI adapter. Rust owns protocol/queue policy; JVM owns state.
 * Missing or incompatible native binaries fail visibly, never fall back silently. */
public final class NativeCore {
    static { System.loadLibrary("action_relay_core"); }
    private static final int[] STATE_SIZES=sizes();
    private NativeCore() {}
    private static native int[] sizes();
    public static ByteBuffer streamState(){return ByteBuffer.allocateDirect(STATE_SIZES[0]);}
    public static ByteBuffer queueState(){return ByteBuffer.allocateDirect(STATE_SIZES[1]);}
    public static native long crc(byte[] input,int offset,int length,boolean wide);
    public static native byte[] frame(int set,int id,int kind,int sequence,byte[] payload);
    public static native void validate(byte[] input);
    public static native byte[] feed(ByteBuffer state,byte[] input);
    public static native void offer(ByteBuffer state,byte[] input,long now,boolean gps);
    public static native byte[] poll(ByteBuffer state,long now);
    public static native void clear(ByteBuffer state,boolean gpsOnly);
    public static native int count(ByteBuffer state);
    public static long read64(byte[] bytes,int offset){
        long value=0;for(int i=0;i<8;i++)value|=(long)(bytes[offset+i]&255)<<(i*8);return value;
    }
}
