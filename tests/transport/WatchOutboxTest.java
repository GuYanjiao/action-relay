import cn.local.actionrelay.WatchOutbox;

public final class WatchOutboxTest {
    static void check(boolean v,String reason){if(!v)throw new AssertionError(reason);}
    public static void main(String[] args){
        WatchOutbox q=new WatchOutbox();
        q.offer("old status","",false,false,0);
        WatchOutbox.Item stalled=q.begin();
        q.offer("latest status","",false,false,1);
        q.offer("accepted","a",true,false,2);
        q.offer("confirmed+state","a",true,true,3);
        check(q.begin()==null,"only one SDK send in flight");
        check(q.finish(stalled),"original finishes");
        WatchOutbox.Item result=q.begin();
        check(result.terminal&&result.json.equals("confirmed+state"),"terminal replaces receipt and outranks stale telemetry");
        check(!q.finish(stalled)&&q.begin()==null,"late callback cannot unlock next operation");
        q.clear();q.offer("new connection","",false,false,4);WatchOutbox.Item next=q.begin();
        check(!q.finish(result)&&q.isInflight(next),"previous generation cannot corrupt new connection");
        q.finish(next);q.offer("s1","",false,false,5);q.offer("s2","",false,false,6);
        check(q.begin().json.equals("s2"),"periodic status backlog is coalesced");
        q.clear();q.offer("first stop","a",true,true,7);q.offer("second stop","b",true,true,8);
        WatchOutbox.Item first=q.begin();check(first.id.equals("a"),"results retain chronological order");q.finish(first);
        check(q.begin().id.equals("b"),"newest result does not reverse terminal order");
        q.clear();for(int i=0;i<32;i++)q.offer("r",""+i,true,true,i);
        boolean bounded=false;try{q.offer("overflow","overflow",true,true,33);}catch(IllegalStateException e){bounded=true;}
        check(bounded,"outage cannot cause an unbounded result queue");
        System.out.println("PASS: watch result priority, coalescing, stale callbacks, session reset and queue bound");
    }
}
