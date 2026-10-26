import cn.local.actionrelay.ReconnectPolicy;

public final class ReconnectPolicyTest {
  static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
  public static void main(String[] args){
    ReconnectPolicy p=new ReconnectPolicy();
    ReconnectPolicy.Ticket old=p.schedule();
    check(old.delayMs==0&&p.schedule()==null,"first reconnect is immediate, no duplicate task");
    p.cancel();check(!p.claim(old)&&p.schedule()==null,"stop/forget invalidate queued callback");
    p.reset();ReconnectPolicy.Ticket current=p.schedule();
    check(!p.claim(old)&&p.claim(current)&&!p.claim(current),"old target and duplicate callbacks cannot connect");
    long previous=0;
    for(int i=1;i<5;i++){
      ReconnectPolicy.Ticket t=p.schedule();check(t!=null&&t.delayMs>=previous&&t.delayMs<=30000,"bounded backoff");
      previous=t.delayMs;check(p.claim(t),"next failure may retry");
    }
    check(p.exhausted()&&p.schedule()==null,"five failures stop retries");
    p.reset();check(p.claim(p.schedule()),"explicit retry/environment recovery starts a new bounded budget");
    System.out.println("PASS: reconnect cancellation, stale callbacks, deduplication, retry budget and recovery");
  }
}
