package cn.local.actionrelay;

import java.util.ArrayDeque;
import java.util.Iterator;

/** One transport operation in flight. Results supersede accepted receipts; telemetry is latest-only. */
public final class WatchOutbox {
    public static final class Item {
        public final String json,id;
        public final boolean result,terminal;
        public final long queuedAt;
        private Item(String json,String id,boolean result,boolean terminal,long now){
            this.json=json;this.id=id;this.result=result;this.terminal=terminal;queuedAt=now;
        }
    }
    private final ArrayDeque<Item> results=new ArrayDeque<>();
    private Item status,inflight;
    public void offer(String json,String id,boolean result,boolean terminal,long now){
        Item item=new Item(json,id,result,terminal,now);
        if(!result){status=item;return;}
        for(Iterator<Item> it=results.iterator();it.hasNext();){
            Item old=it.next();
            if(old.id.equals(id)){
                if(old.terminal&&!terminal)return;
                it.remove();
            }
        }
        if(results.size()>=32)throw new IllegalStateException("手表结果队列繁忙");
        results.addLast(item);
        // A result already embeds authoritative state; drop older queued snapshots.
        if(terminal)status=null;
    }
    public Item begin(){
        if(inflight!=null)return null;
        Item next=results.pollFirst();
        if(next==null){next=status;status=null;}
        return inflight=next;
    }
    public boolean finish(Item item){if(item==null||item!=inflight)return false;inflight=null;return true;}
    public boolean isInflight(Item item){return item!=null&&item==inflight;}
    public void clear(){results.clear();status=null;inflight=null;}
}
