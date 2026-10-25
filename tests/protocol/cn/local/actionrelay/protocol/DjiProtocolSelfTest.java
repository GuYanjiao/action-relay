package cn.local.actionrelay.protocol;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DjiProtocolSelfTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i*2,i*2+2),16);
        return bytes;
    }
    private static byte[] join(byte[] a, byte[] b) {
        byte[] joined = Arrays.copyOf(a, a.length+b.length); System.arraycopy(b,0,joined,a.length,b.length); return joined;
    }
    private static byte[] cameraAuthorization(int sequence, int result) {
        byte[] payload = new byte[33]; payload[0]=0x33; payload[1]=(byte)0xff; payload[26]=2; payload[27]=(byte)result;
        return DjiProtocol.frame(0,0x19,2,sequence,payload);
    }
    private static byte[] cameraStatus(int state) {
        byte[] payload = new byte[38]; payload[0]=1; payload[1]=(byte)state; payload[5]=17; payload[37]=87;
        return DjiProtocol.frame(0x1d,2,0,55,payload);
    }

    private static final class Fixture implements DjiSession.Clock, DjiSession.Transport, DjiSession.Listener {
        long now=100;
        boolean ready, closeRequired;
        boolean abortDuringSend;
        List<byte[]> sent = new ArrayList<byte[]>();
        List<String> outcomes = new ArrayList<String>();
        List<String> logs = new ArrayList<String>();
        DjiProtocol.CameraStatus status;
        DjiSession session = new DjiSession(this,this,this,0x12345678L,new byte[]{2,3,4,5,6,7});
        public long nowMs() { return now; }
        public void send(byte[] data) { sent.add(data); if(abortDuringSend) session.disconnect(); }
        public void onReady(boolean value) { ready=value; }
        public void onStatus(DjiProtocol.CameraStatus value) { status=value; }
        public void onLog(String value) { logs.add(value); }
        public void onCommandResult(String id,boolean desired,String outcome,String message) { outcomes.add(id+":"+outcome); }
        public void onDisconnectRequired(String reason) { closeRequired=true; }
        void ready() {
            session.startHandshake(true,1234);
            check(!ready && sent.size()==1,"no ready before authorization");
            // Camera may send its active request directly, without an ACK first.
            session.onBytes(cameraAuthorization(0x7654,0));
            check(ready && sent.size()==3,"authorization -> response + status subscription");
            DjiProtocol.Frame response = DjiProtocol.decode(sent.get(1));
            check(response.isResponse() && response.sequence==0x7654,"echo camera authorization seq");
        }
    }

    public static void main(String[] args) throws Exception {
        String modeExample="AA1B000100000000050057EE1D04000033FF0A01473936F4FAE1D0";
        DjiProtocol.Frame reference = DjiProtocol.decode(hex(modeExample));
        check(reference.is(0x1d,4) && reference.sequence==5,"official mode-switch golden frame");
        check(DjiProtocol.hex(DjiProtocol.frame(0x1d,4,1,5,hex("000033FF0A01473936"))).equals(modeExample),"exact official CRC golden");
        if (args.length>0) {
            List<String> vectors=Files.readAllLines(Paths.get(args[0]));
            for(String line:vectors) if(line.trim().length()>0) {
                byte[] vector=hex(line.trim());
                DjiProtocol.Frame decoded=DjiProtocol.decode(vector);
                byte[] payload=decoded.payload;
                int code=DjiProtocol.u16(payload,27);
                boolean first=payload[26]==1;
                byte[] reconstructed=DjiProtocol.connectionRequest(decoded.sequence,DjiProtocol.u32(payload,0),Arrays.copyOfRange(payload,5,11),first,code);
                check(Arrays.equals(vector,reconstructed),"official connection golden vector");
            }
        }
        byte[] start=DjiProtocol.recording(10,true), stop=DjiProtocol.recording(11,false);
        check(DjiProtocol.decode(start).payload[4]==0 && DjiProtocol.decode(stop).payload[4]==1,"explicit start/stop, no toggle");
        check(DjiProtocol.u32(DjiProtocol.decode(start).payload,0)==0x33ff0000L,"preserve DJI recording device-id constant");
        byte[] bad=start.clone(); bad[15]^=1;
        boolean rejected=false; try { DjiProtocol.decode(bad); } catch(IllegalArgumentException expected) { rejected=true; }
        check(rejected,"corrupt frame rejected");
        DjiProtocol.StreamDecoder stream=new DjiProtocol.StreamDecoder();
        byte[] bytes=join(hex("5500AA010000FF"),join(start,stop));
        List<DjiProtocol.Frame> all=new ArrayList<DjiProtocol.Frame>();
        for(byte b:bytes) all.addAll(stream.feed(new byte[]{b}));
        check(all.size()==2 && all.get(0).sequence==10 && all.get(1).sequence==11,"fragmented noisy/coalesced notification decoding");
        stream.reset();
        check(stream.feed(join(bad,start)).size()==1,"recover after bad frame CRC");

        Fixture f=new Fixture(); f.ready();
        int afterHandshake=f.sent.size();
        f.session.onBytes(cameraAuthorization(0x7654,0));
        check(f.ready && f.sent.size()==afterHandshake+1,"duplicate authorization answered without repeating subscription");
        check(!f.session.canControlRecording(),"no control before status");
        f.session.onBytes(cameraStatus(1));
        check(f.session.canControlRecording() && f.status.batteryPercent==87,"fresh normal-video status enables control");
        f.session.setRecording(true,"a");
        DjiProtocol.Frame command=DjiProtocol.decode(f.sent.get(f.sent.size()-1));
        f.session.onBytes(DjiProtocol.frame(0x1d,3,0x20,command.sequence,new byte[]{0,0,0,0,0}));
        check(f.outcomes.isEmpty(),"successful ACK does not confirm recording");
        f.session.onBytes(cameraStatus(3));
        check(f.outcomes.isEmpty(),"one recording push is not enough to confirm");
        f.now+=500;
        f.session.onBytes(cameraStatus(3));
        check(f.outcomes.equals(Arrays.asList("a:confirmed")),"actual camera push confirms recording");
        f.now+=4000;
        check(!f.session.canControlRecording(),"stale status blocks control");
        f.session.setRecording(false,"stale");
        check(f.outcomes.contains("stale:rejected"),"stale command rejected");
        f.session.onBytes(cameraStatus(3));
        f.session.setRecording(false,"stop");
        f.now+=20001; f.session.tick(f.now);
        check(f.outcomes.contains("stop:unknown"),"no status confirmation -> unknown, not success");
        f.session.onBytes(cameraStatus(5));
        check(!f.status.isRecording() && f.status.isPreRecording(),"pre-record cache is distinct from actual recording");
        f.session.onBytes(cameraStatus(1));
        f.now+=500;
        f.session.onBytes(cameraStatus(1));
        f.session.setRecording(true,"pre");
        f.session.disconnect();
        check(f.outcomes.contains("pre:unknown") && !f.ready,"disconnect never retries in-flight command");
        int count=f.sent.size(); f.session.onBytes(cameraStatus(3));
        check(f.sent.size()==count && f.session.getStatus()==null,"late notifications ignored after disconnect");

        Fixture denied=new Fixture(); denied.session.startHandshake(true,4321);
        denied.session.onBytes(cameraAuthorization(8,1));
        check(denied.closeRequired && !denied.ready && denied.session.getPhase().equals("failed"),"denial requires disconnect");
        check(denied.sent.size()==1,"denial never auto-retries");
        Fixture timeout=new Fixture(); timeout.session.startHandshake(true,1); timeout.now+=35001;timeout.session.tick(timeout.now);
        check(timeout.closeRequired && !timeout.ready,"authorization timeout requires disconnect");
        Fixture ackOnly=new Fixture();ackOnly.session.startHandshake(true,1);
        ackOnly.session.onBytes(DjiProtocol.frame(0,0x19,0x20,1,new byte[9]));
        check(!ackOnly.ready,"connection request ACK alone never marks ready");
        Fixture sendAborted=new Fixture();sendAborted.ready();sendAborted.session.onBytes(cameraStatus(1));
        sendAborted.abortDuringSend=true;
        check(!sendAborted.session.setRecording(true,"aborted"),"synchronous transport disconnect cannot return queued success");
        check(sendAborted.outcomes.contains("aborted:unknown"),"synchronous disconnect keeps unknown result");
        check(!sendAborted.logs.get(sendAborted.logs.size()-1).startsWith("已发送开始录像"),"no success-path log after synchronous disconnect");
        Fixture authAborted=new Fixture();authAborted.session.startHandshake(true,4);authAborted.abortDuringSend=true;
        authAborted.session.onBytes(cameraAuthorization(99,0));
        check(!authAborted.ready && !authAborted.session.isReady() && authAborted.sent.size()==2,"failed authorization-response write cannot proceed to subscribe/ready");
        System.out.println("PASS: "+assertions+" protocol/session assertions (golden frames + state transitions)");
    }
}
