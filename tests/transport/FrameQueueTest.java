import cn.local.actionrelay.FrameQueue;
import java.util.Arrays;
public final class FrameQueueTest {
  static byte[] record(boolean start){byte[] a=new byte[27];a[12]=0x1d;a[13]=3;a[18]=(byte)(start?0:1);return a;}
  static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
  public static void main(String[] args){
    FrameQueue q=new FrameQueue();byte[] start=record(true),stop=record(false),other=new byte[]{9};
    q.offerGps(new byte[]{1},0);q.offerGps(new byte[]{2},100);q.offerControl(other,100);
    check(Arrays.equals(q.poll(200).bytes,other),"control precedes GPS");
    check(q.poll(200).bytes[0]==2&&q.poll(200)==null,"only newest GPS survives");
    q.offerControl(start,1000);q.offerGps(new byte[]{3},1000);q.offerControl(stop,1001);
    check(Arrays.equals(q.poll(1001).bytes,stop),"stop preempts queued start");check(q.poll(1001)==null,"superseded start and GPS removed");
    q.offerGps(new byte[]{4},0);check(q.poll(1600)==null,"stale GPS is dropped");
    q.offerControl(start,0);check(q.poll(4100).deadline<=4100,"expired command exposed for safe disconnect, never silently sent");
    q.offerControl(stop,0);q.offerControl(stop,1);check(q.controlCount()==1,"duplicate queued stop replaced");
    q.clear();check(q.poll(2)==null,"disconnect clears all pending frames");
    System.out.println("PASS: control priority, stop supersession, GPS coalescing/expiry and disconnect");
  }
}
