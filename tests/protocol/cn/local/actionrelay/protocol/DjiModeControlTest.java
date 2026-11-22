package cn.local.actionrelay.protocol;

/** A mode change is independent of the shutter and needs post-ACK camera evidence. */
public final class DjiModeControlTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        DjiCaptureActionTest.F f=new DjiCaptureActionTest.F();
        check(!f.s.canChangeMode(),"disconnected cannot change mode");
        f.ready(1,1);check(f.s.canChangeMode(),"fresh standby can change mode");
        check(f.s.setMode(5,"photo-mode"),"explicit photo mode request");
        int seq=f.last().sequence;
        check(f.frames(4)==1&&f.last().payload[4]==5&&f.frames(3)==0,"mode switch sends no shutter");
        check(f.s.isBusy()&&f.s.getCommandPhase().equals("switching"),"mode has its own pending state");
        check(!f.s.canStartRecording()&&!f.s.canTakePhoto()&&!f.s.canChangeMode(),"pending switch gates capture");
        check(!f.s.setMode(1,"duplicate")&&f.frames(4)==1,"duplicate taps cannot overlap");
        check(!f.s.setRecording(true,"start-during-mode")&&f.frames(3)==0,"no queued capture during mode switch");
        f.push(5,1,100);check(!f.has("photo-mode","confirmed"),"pre-ACK state is not confirmation");
        f.ack(4,seq+1,0);f.push(5,1,100);check(!f.has("photo-mode","confirmed"),"wrong ACK ignored");
        f.ack(4,seq,0);check(!f.has("photo-mode","confirmed"),"ACK alone is acceptance");
        f.push(5,1,100);check(f.has("photo-mode","confirmed")&&f.s.canTakePhoto()&&f.frames(3)==0,"post-ACK target confirms without taking a photo");
        check(f.s.setMode(5,"already")&&f.has("already","confirmed")&&f.frames(4)==1,"already active mode is idempotent");
        check(f.s.setMode(1,"video-mode"),"video mode request");seq=f.last().sequence;f.ack(4,seq,0);f.push(1,1,100);
        check(f.has("video-mode","confirmed")&&f.s.canStartRecording()&&f.frames(3)==0,"video switch never starts recording");
        DjiCaptureActionTest.F recording=new DjiCaptureActionTest.F();recording.ready(1,3);
        check(!recording.s.canChangeMode()&&!recording.s.setMode(5,"recording")&&recording.frames(4)==0,"recording blocks mode changes");
        DjiCaptureActionTest.F rejected=new DjiCaptureActionTest.F();rejected.ready(1,1);rejected.s.setMode(5,"reject");seq=rejected.last().sequence;rejected.ack(4,seq,2);rejected.push(5,1,100);
        check(rejected.has("reject","failed")&&rejected.frames(4)==1&&rejected.frames(3)==0,"reject does not retry or capture");
        DjiCaptureActionTest.F timeout=new DjiCaptureActionTest.F();timeout.ready(1,1);timeout.s.setMode(5,"timeout");seq=timeout.last().sequence;timeout.now+=9000;timeout.s.tick(timeout.now);timeout.ack(4,seq,0);timeout.push(5,1,100);
        check(timeout.has("timeout","unknown")&&!timeout.has("timeout","confirmed")&&timeout.frames(4)==1,"timeout discards late completion, no retry");
        DjiCaptureActionTest.F disconnect=new DjiCaptureActionTest.F();disconnect.ready(1,1);disconnect.s.setMode(5,"disconnect");disconnect.s.disconnect();
        check(disconnect.has("disconnect","unknown")&&!disconnect.s.isReady()&&!disconnect.s.canChangeMode(),"disconnect clears pending switch");
        DjiCaptureActionTest.F playback=new DjiCaptureActionTest.F();playback.ready(1,2);playback.s.setMode(1,"playback");seq=playback.last().sequence;playback.ack(4,seq,0);playback.push(1,1,100);
        check(playback.has("playback","confirmed")&&playback.frames(3)==0,"return from playback can be requested without recording");
        DjiCaptureActionTest.F external=new DjiCaptureActionTest.F();external.ready(1,1);external.s.setMode(5,"external");external.push(1,3,100);
        check(external.has("external","unknown")&&external.s.canStopRecording(),"external recording cancels mode wait and exposes stop");
        System.out.println("PASS: "+checks+" explicit mode checks (ACK/status, capture isolation, no replay, timeout and disconnect)");
    }
}
