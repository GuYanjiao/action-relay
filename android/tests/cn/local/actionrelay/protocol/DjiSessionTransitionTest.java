package cn.local.actionrelay.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JVM regression tests for stop transitions; no camera or Android runtime is simulated. */
public final class DjiSessionTransitionTest {
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++; if (!ok) throw new AssertionError(message);
    }
    private static byte[] authorization(int sequence, int allowed) {
        byte[] p=new byte[33];p[0]=0x33;p[1]=(byte)0xff;p[26]=2;p[27]=(byte)allowed;
        return DjiProtocol.frame(0,0x19,2,sequence,p);
    }
    private static byte[] stateFrame(int sequence,int mode,int state,int nextMode,int seconds) {
        byte[] p=new byte[38];p[0]=(byte)mode;p[1]=(byte)state;p[5]=(byte)seconds;p[6]=(byte)(seconds>>8);
        p[28]=0;p[29]=(byte)nextMode;p[37]=87;
        return DjiProtocol.frame(0x1d,2,0,sequence,p);
    }
    private static final class Result {
        final String id,outcome; final boolean desired;
        Result(String id,boolean desired,String outcome){this.id=id;this.desired=desired;this.outcome=outcome;}
    }
    private static final class F implements DjiSession.Clock,DjiSession.Transport,DjiSession.Listener {
        long now=1000;int stateSeq=50;boolean ready,abort;
        final List<byte[]> frames=new ArrayList<byte[]>();
        final List<Result> results=new ArrayList<Result>();
        final List<String> logs=new ArrayList<String>();
        final DjiSession s=new DjiSession(this,this,this,0x12345678L,new byte[]{2,3,4,5,6,7});
        public long nowMs(){return now;}
        public void send(byte[] frame){frames.add(frame);if(abort)s.disconnect();}
        public void onReady(boolean value){ready=value;}
        public void onStatus(DjiProtocol.CameraStatus status){}
        public void onLog(String text){logs.add(text);}
        public void onCommandResult(String id,boolean desired,String outcome,String text){results.add(new Result(id,desired,outcome));}
        public void onDisconnectRequired(String reason){}
        void ready(int state){s.startHandshake(true,1234);s.onBytes(authorization(7,0));push(0,state);check(ready,"fixture handshake");}
        void push(long elapsed,int state){now+=elapsed;s.onBytes(stateFrame(stateSeq++,1,state,1,17));}
        void advance(long elapsed){now+=elapsed;s.tick(now);}
        int records(){int n=0;for(byte[] b:frames)if(DjiProtocol.decode(b).is(0x1d,3))n++;return n;}
        int seq(){for(int i=frames.size()-1;i>=0;i--){DjiProtocol.Frame f=DjiProtocol.decode(frames.get(i));if(f.is(0x1d,3))return f.sequence;}throw new AssertionError();}
        void ack(int seq,int code){s.onBytes(DjiProtocol.frame(0x1d,3,0x20,seq,new byte[]{(byte)code,0,0,0,0}));}
        boolean has(String id,String outcome){for(Result r:results)if(r.id.equals(id)&&r.outcome.equals(outcome))return true;return false;}
        long count(String id,String outcome){long n=0;for(Result r:results)if(r.id.equals(id)&&r.outcome.equals(outcome))n++;return n;}
    }

    public static void main(String[] args) {
        F save=new F();save.ready(3);
        check(save.s.setRecording(false,"save"),"stop enqueued");save.ack(save.seq(),0);
        check(!save.has("save","confirmed"),"ACK not recording truth");
        save.advance(6000);
        check(save.s.isBusy() && !save.has("save","unknown"),"stop survives old five-second cutoff");
        save.push(500,4);check(!save.s.getStatus().hasKnownRecordingState(),"undocumented transition not invented as saved/stopped");
        save.push(500,1);check(!save.has("save","confirmed"),"single idle push insufficient");
        save.push(500,1);check(save.has("save","confirmed")&&!save.s.isBusy(),"stable late idle confirms stop");
        check(save.records()==1,"no automatic recording retry while saving");

        F quick=new F();quick.ready(1);quick.s.setRecording(true,"start");int oldSeq=quick.seq();
        check(quick.s.canStopRecording()&&!quick.s.canStartRecording(),"stop available before start confirmation");
        check(quick.s.setRecording(false,"stop"),"stop covers unconfirmed start");
        check(quick.has("start","unknown")&&quick.records()==2,"old start invalidated and explicit stop still sent from idle snapshot");
        check(Boolean.FALSE.equals(quick.s.getPendingDesired()),"desired state switched to stop");
        quick.ack(oldSeq,0);check(!quick.has("start","confirmed"),"superseded start ACK cannot revive start");
        quick.push(200,1);quick.push(300,1);
        check(!quick.has("stop","confirmed"),"pre-start idle burst cannot instantly confirm quick stop");
        quick.push(500,3);quick.push(500,1);quick.push(500,1);
        check(quick.has("stop","confirmed")&&!quick.has("start","confirmed"),"late start report does not override desired stop");
        check(quick.records()==2,"quick stop no toggle or automatic start resend");

        F duplicate=new F();duplicate.ready(3);duplicate.s.setRecording(false,"a");duplicate.s.setRecording(false,"b");duplicate.s.setRecording(false,"a");
        check(duplicate.records()==1,"duplicate stop goals share one wire command");
        duplicate.push(500,1);duplicate.push(500,1);
        check(duplicate.count("a","confirmed")==1&&duplicate.count("b","confirmed")==1,"coalesced stop callers each receive one result");

        F late=new F();late.ready(3);late.s.setRecording(false,"late");long r0=late.s.getRevision();
        late.advance(20001);
        check(late.has("late","unknown")&&!late.s.isBusy(),"long stop cutoff remains honest unknown");
        check(late.s.getCommandPhase().equals("recovering")&&Boolean.FALSE.equals(late.s.getPendingDesired()),"uncertain stop goal survives for late confirmation");
        check(!late.s.canStartRecording()&&late.s.canStopRecording(),"uncertain goal blocks start but permits explicit stop retry");
        late.push(500,1);late.push(500,1);
        check(late.has("late","confirmed")&&late.s.getCommandPhase().equals("idle"),"late actual stop upgrades unknown to confirmed");
        check(late.s.getRevision()>r0&&late.records()==1,"revision advances without any command retry");

        F noRevive=new F();noRevive.ready(1);noRevive.s.setRecording(true,"old");noRevive.advance(8001);
        check(noRevive.has("old","unknown")&&noRevive.s.canStopRecording(),"uncertain start can still be stopped with stale status");
        noRevive.s.setRecording(false,"newStop");noRevive.push(500,3);noRevive.push(500,1);noRevive.push(500,1);
        check(noRevive.has("newStop","confirmed")&&!noRevive.has("old","confirmed"),"new stop cancels old late-confirmation recovery");
        check(noRevive.count("old","unknown")==1,"superseding a recovery does not repeat an already reported unknown outcome");
        check(noRevive.records()==2,"uncertain start never auto replays");

        F negative=new F();negative.ready(3);negative.s.setRecording(false,"n");negative.ack(negative.seq(),2);
        check(!negative.has("n","failed"),"negative ACK does not discard an in-progress stop goal immediately");
        negative.push(700,1);negative.push(500,1);
        check(negative.has("n","confirmed"),"actual stopped state wins over rejected duplicate/busy response");

        F rejected=new F();rejected.ready(3);rejected.s.setRecording(false,"reject");rejected.ack(rejected.seq(),2);
        rejected.push(19900,3);rejected.advance(101);
        check(rejected.has("reject","failed"),"explicit negative ACK plus fresh opposite state reports failure at cutoff");

        F unknown=new F();unknown.ready(3);unknown.s.setRecording(false,"u");unknown.push(5000,0);unknown.push(5000,4);unknown.push(5000,2);
        check(!unknown.has("u","confirmed")&&unknown.s.isBusy(),"screen-off/playback/unknown are not invented as stopped");
        unknown.advance(5001);check(unknown.has("u","unknown"),"unresolved transition eventually unknown");
        int count=unknown.records();unknown.advance(30001);
        check(unknown.s.getPendingDesired()==null&&unknown.records()==count,"expired recovery never sends a delayed recording command");

        F expiresOnReceive=new F();expiresOnReceive.ready(3);expiresOnReceive.s.setRecording(false,"expired");expiresOnReceive.advance(20001);
        expiresOnReceive.push(30001,1);expiresOnReceive.push(500,1);
        check(!expiresOnReceive.has("expired","confirmed"),"receive path enforces recovery expiry even before tick");

        F start=new F();start.ready(1);start.s.setRecording(true,"s");start.push(500,3);start.push(500,5);
        check(!start.has("s","confirmed")&&start.s.getStatus().isPreRecording(),"pre-record buffer interrupts actual recording confirmation");
        start.push(500,3);start.push(500,3);check(start.has("s","confirmed"),"two actual-recording reports confirm start");

        F idle=new F();idle.ready(1);int before=idle.records();idle.s.setRecording(false,"already");
        check(idle.has("already","confirmed")&&idle.records()==before,"already-stopped request is idempotent");
        idle.advance(3100);check(!idle.s.setRecording(true,"stale")&&idle.has("stale","rejected"),"stale status never authorizes a new start");

        F disconnected=new F();disconnected.ready(3);disconnected.s.setRecording(false,"disconnect");disconnected.s.disconnect();
        disconnected.push(500,1);disconnected.push(500,1);
        check(disconnected.count("disconnect","unknown")==1&&!disconnected.has("disconnect","confirmed"),"late notifications after disconnect cannot confirm previous session");

        F synchronous=new F();synchronous.ready(3);synchronous.abort=true;
        check(!synchronous.s.setRecording(false,"sync")&&synchronous.has("sync","unknown"),"synchronous transport loss does not revive operation");
        check(!synchronous.logs.get(synchronous.logs.size()-1).startsWith("已发送停止录像"),"no send-success log after synchronous loss");

        byte[] golden=hex("AA1B000100000000050057EE1D04000033FF0A01473936F4FAE1D0");
        DjiProtocol.Frame f=DjiProtocol.decode(golden);
        check(Arrays.equals(golden,DjiProtocol.frame(f.commandSet,f.commandId,f.commandType,f.sequence,f.payload)),"official frame encoding unchanged");
        DjiProtocol.CameraStatus decoded=DjiProtocol.cameraStatus(DjiProtocol.decode(stateFrame(8,1,3,5,17)),55);
        check(decoded.powerMode==0&&decoded.nextMode==5&&decoded.isModeTransition(),"documented transition fields decoded without changing their meaning");
        System.out.println("PASS: "+assertions+" stop-transition/session assertions");
    }
    private static byte[] hex(String value){byte[] b=new byte[value.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return b;}
}
