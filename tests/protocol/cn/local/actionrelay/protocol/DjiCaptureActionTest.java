package cn.local.actionrelay.protocol;
import java.util.ArrayList;
import java.util.List;
/** Protocol state-machine checks only; no real camera is represented. */
public final class DjiCaptureActionTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static final class F implements DjiSession.Clock,DjiSession.Transport,DjiSession.Listener {
        long now=1000;int count;boolean ready;
        List<DjiProtocol.Frame> sent=new ArrayList<DjiProtocol.Frame>();
        List<String> results=new ArrayList<String>();
        DjiSession s=new DjiSession(this,this,this,123,new byte[]{1,2,3,4,5,6});
        public long nowMs(){return now;}
        public void send(byte[] b){sent.add(DjiProtocol.decode(b));}
        public void onReady(boolean r){ready=r;}
        public void onStatus(DjiProtocol.CameraStatus st){}
        public void onLog(String m){}
        public void onCommandResult(String id,boolean desired,String outcome,String m){results.add(id+":"+outcome);}
        void ready(int mode,int state){s.startHandshake(false,1234);byte[] p=new byte[33];p[0]=0x33;p[1]=(byte)0xff;p[26]=2;s.onBytes(DjiProtocol.frame(0,0x19,2,5,p));push(mode,state,100);check(ready,"fixture ready");}
        void push(int mode,int state,int photos){now+=300;byte[] p=new byte[38];p[0]=(byte)mode;p[1]=(byte)state;p[19]=(byte)photos;p[29]=(byte)mode;s.onBytes(DjiProtocol.frame(0x1d,2,0,++count,p));}
        int frames(int id){int n=0;for(DjiProtocol.Frame f:sent)if(f.is(0x1d,id))n++;return n;}
        DjiProtocol.Frame last(){return sent.get(sent.size()-1);}
        void ack(int id,int seq,int code){s.onBytes(DjiProtocol.frame(0x1d,id,0x20,seq,new byte[]{(byte)code}));}
        boolean has(String id,String result){return results.contains(id+":"+result);}
    }
    public static void main(String[] args){
        DjiProtocol.Frame mode=DjiProtocol.decode(DjiProtocol.switchMode(5,1));
        check(mode.is(0x1d,4)&&mode.payload.length==9&&mode.payload[4]==1,"mode command payload");
        check(DjiProtocol.u32(mode.payload,0)==DjiProtocol.RECORD_CONTROL_DEVICE_ID,"official device byte order preserved");
        F playback=new F();playback.ready(1,2);check(playback.s.canStartRecording(),"playback offers start preparation");
        playback.s.setRecording(true,"resume");int seq=playback.last().sequence;
        check(playback.frames(4)==1&&playback.frames(3)==0,"only mode switch sent from playback");
        playback.push(1,1,100);check(playback.frames(3)==0,"live state before ACK does not start");
        playback.ack(4,seq+1,0);playback.push(1,1,100);check(playback.frames(3)==0,"wrong ACK ignored");
        playback.ack(4,seq,0);check(playback.frames(3)==0,"mode ACK alone does not start");
        playback.push(1,1,100);check(playback.frames(3)==1,"one explicit start after post-ACK live state");
        playback.push(1,3,100);playback.push(1,3,100);check(playback.has("resume","confirmed"),"existing recording truth confirms start");
        F rejected=new F();rejected.ready(1,2);rejected.s.setRecording(true,"r");rejected.ack(4,rejected.last().sequence,2);rejected.push(1,1,100);
        check(rejected.frames(3)==0&&rejected.has("r","failed"),"failed mode switch never records");
        F cancelled=new F();cancelled.ready(1,2);cancelled.s.setRecording(true,"a");seq=cancelled.last().sequence;
        check(cancelled.s.canStopRecording(),"pending playback start can be cancelled");cancelled.s.setRecording(false,"b");cancelled.ack(4,seq,0);cancelled.push(1,1,100);
        check(cancelled.frames(3)==0&&cancelled.has("a","unknown"),"stop cancels preparation and late ACK cannot start");
        F changed=new F();changed.ready(1,2);changed.s.setRecording(true,"c");seq=changed.last().sequence;changed.ack(4,seq,0);changed.push(5,1,100);
        check(changed.frames(3)==0&&changed.has("c","unknown"),"manual mode change cancels queued capture");
        F timeout=new F();timeout.ready(1,2);timeout.s.setRecording(true,"t");seq=timeout.last().sequence;timeout.now+=9000;timeout.s.tick(timeout.now);timeout.ack(4,seq,0);timeout.push(1,1,100);
        check(timeout.frames(3)==0&&timeout.has("t","unknown"),"expired mode preparation never starts late");
        F photo=new F();photo.ready(5,1);check(photo.s.canTakePhoto()&&!photo.s.canStartRecording(),"photo and video actions are separate");
        check(photo.s.takePhoto("p"),"photo sent");seq=photo.last().sequence;check(photo.frames(3)==1&&!photo.s.canTakePhoto(),"photo blocks duplicate taps");
        photo.s.takePhoto("duplicate");check(photo.frames(3)==1,"no duplicated shutter");photo.ack(3,seq,0);
        check(!photo.has("p","confirmed"),"photo ACK is not completion");photo.push(5,1,100);check(!photo.has("p","confirmed"),"unchanged idle is not a new photo");
        photo.push(5,1,99);check(photo.has("p","confirmed"),"post-ACK remaining count confirms capture");
        F cycle=new F();cycle.ready(5,1);cycle.s.takePhoto("cycle");seq=cycle.last().sequence;cycle.push(5,3,100);cycle.ack(3,seq,0);cycle.push(5,1,100);
        check(cycle.has("cycle","confirmed"),"capture-to-idle transition can confirm when count unavailable");
        F photoPlayback=new F();photoPlayback.ready(5,2);photoPlayback.s.takePhoto("pp");seq=photoPlayback.last().sequence;
        check(photoPlayback.last().payload[4]==5&&photoPlayback.frames(3)==0,"photo playback returns to photo mode first");
        photoPlayback.ack(4,seq,0);photoPlayback.push(5,1,100);check(photoPlayback.frames(3)==1,"photo after mode confirmation only");
        F disconnected=new F();disconnected.ready(1,2);disconnected.s.setRecording(true,"d");seq=disconnected.last().sequence;disconnected.s.disconnect();disconnected.ack(4,seq,0);disconnected.push(1,1,100);
        check(disconnected.frames(3)==0&&disconnected.has("d","unknown"),"disconnect discards prepared recording");
        F lostPhoto=new F();lostPhoto.ready(5,1);lostPhoto.s.takePhoto("lost");lostPhoto.now+=31000;lostPhoto.s.tick(lostPhoto.now);
        check(lostPhoto.frames(3)==1&&lostPhoto.has("lost","unknown"),"photo timeout does not replay shutter");
        System.out.println("PASS: "+checks+" playback/photo protocol checks");
    }
}
