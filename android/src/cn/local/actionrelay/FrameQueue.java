package cn.local.actionrelay;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Control first; one replaceable GPS sample; no stale shutter command after stop. */
public final class FrameQueue {
    public static final class Packet {
        public final byte[] bytes;
        public final long deadline;
        public final boolean telemetry;
        private Packet(byte[] bytes,long deadline,boolean telemetry){this.bytes=bytes.clone();this.deadline=deadline;this.telemetry=telemetry;}
    }
    private final ArrayDeque<Packet> controls=new ArrayDeque<>();
    private Packet latestGps;
    public void offerControl(byte[] bytes,long now){
        if(isRecordingCommand(bytes,false)){
            Iterator<Packet> it=controls.iterator();
            while(it.hasNext()){Packet p=it.next();if(isRecordingCommand(p.bytes,true)||isRecordingCommand(p.bytes,false))it.remove();}
            latestGps=null;
            controls.addFirst(new Packet(bytes,now+4000,false));
        }else{
            if(controls.size()>=32)throw new IllegalStateException("相机指令队列繁忙");
            controls.addLast(new Packet(bytes,now+4000,false));
        }
    }
    public void offerGps(byte[] bytes,long now){latestGps=new Packet(bytes,now+1500,true);}
    public Packet poll(long now){
        if(!controls.isEmpty())return controls.removeFirst();
        Packet p=latestGps;latestGps=null;
        return p!=null&&now<p.deadline?p:null;
    }
    public void clearGps(){latestGps=null;}
    public void clear(){controls.clear();latestGps=null;}
    public int controlCount(){return controls.size();}
    private static boolean isRecordingCommand(byte[] bytes,boolean start){
        return bytes!=null&&bytes.length>=19&&(bytes[12]&255)==0x1d&&(bytes[13]&255)==3&&(bytes[18]&255)==(start?0:1);
    }
}
