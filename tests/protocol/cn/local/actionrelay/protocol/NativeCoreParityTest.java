package cn.local.actionrelay.protocol;
import cn.local.actionrelay.core.NativeCore;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

/** Independent Java bitwise reference; production has no Java fallback. */
public final class NativeCoreParityTest {
    static long reference(byte[] b,int at,int n,boolean wide){
        long crc=0x3aa3,poly=wide?0xedb88320L:0xa001L;
        for(int i=at;i<at+n;i++){crc^=b[i]&255;for(int bit=0;bit<8;bit++)crc=(crc&1)!=0?(crc>>>1)^poly:crc>>>1;}
        return crc;
    }
    static void check(boolean ok){if(!ok)throw new AssertionError("Rust/JNI parity failed");}
    static void rejects(Runnable call){try{call.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        Random r=new Random(4242);
        for(int i=0;i<3000;i++){
            byte[] bytes=new byte[r.nextInt(8193)];r.nextBytes(bytes);
            int offset=bytes.length==0?0:r.nextInt(bytes.length+1),length=bytes.length-offset;
            check(DjiProtocol.crc16(bytes,offset,length)==reference(bytes,offset,length,false));
            check(DjiProtocol.crc32(bytes,offset,length)==reference(bytes,offset,length,true));
            byte[] payload=new byte[r.nextInt(1006)];r.nextBytes(payload);
            byte[] framed=DjiProtocol.frame(0x1d,i&255,1,i,payload);
            check(DjiProtocol.u16(framed,10)==reference(framed,0,10,false));
            check(DjiProtocol.u32(framed,framed.length-4)==reference(framed,0,framed.length-4,true));
            check(Arrays.equals(DjiProtocol.decode(framed).payload,payload));
            framed[framed.length-1]^=1;rejects(()->DjiProtocol.decode(framed));
        }
        rejects(()->NativeCore.crc(new byte[2],1,2,true));
        rejects(()->NativeCore.feed(ByteBuffer.allocate(1040),new byte[]{1}));
        ByteBuffer state=NativeCore.streamState();
        state.put(0,(byte)255);state.put(1,(byte)255);
        rejects(()->NativeCore.feed(state,new byte[]{1}));
        NativeCore.clear(state,false);check(NativeCore.feed(state,new byte[]{1}).length==8);
        rejects(()->NativeCore.offer(NativeCore.queueState(),new byte[]{1},-1,false));
        System.out.println("PASS: Rust/JNI randomized CRC/frame parity, corruption and invalid buffers");
    }
}
