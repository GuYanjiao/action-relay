package cn.local.actionrelay;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.service.ServiceApi;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Official Xiaomi XMS Wearable SDK 1.4 bridge. No private intents or BLE proxy. */
public final class XiaomiWatchBridge {
    public interface Listener {
        void onMessage(String text);
        void onState(String text);
        void onLog(String text);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private NodeApi nodes;
    private AuthApi auth;
    private MessageApi messages;
    private ServiceApi service;
    private boolean running,querying,authorizing,checking,registering;
    private String nodeId="",registeredNode="",lastState="",lastError="";
    private long lastErrorAt;
    private long lastMessageAt;
    private final WatchOutbox outbound=new WatchOutbox();
    private Runnable sendTimeout;
    private int generation;

    public XiaomiWatchBridge(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
    }
    private boolean ensureApis(){
        if(nodes!=null&&auth!=null&&messages!=null&&service!=null)return true;
        try{
            nodes=Wearable.getNodeApi(context);auth=Wearable.getAuthApi(context);
            messages=Wearable.getMessageApi(context);service=Wearable.getServiceApi(context);
            return true;
        }catch(Exception error){report("小米通信接口初始化失败",error);return false;}
    }
    public void start(){
        if(running)return;
        running=true;generation++;
        if(!ensureApis()){running=false;return;}
        try{service.registerServiceConnectionListener(serviceListener);}catch(Exception error){report("监听小米服务失败",error);}
        state("正在查询已连接的手表");main.post(poll);
    }
    public void stop(){
        running=false;generation++;main.removeCallbacks(poll);querying=false;checking=false;registering=false;authorizing=false;
        clearSends();
        detach();nodeId="";
        if(service!=null)try{service.unregisterServiceConnectionListener(serviceListener);}catch(Exception ignored){}
        state("中转已关闭");
    }
    private final Runnable poll=new Runnable(){public void run(){
        if(!running)return;refresh();main.postDelayed(this,5000);
    }};
    private final OnServiceConnectionListener serviceListener=new OnServiceConnectionListener(){
        public void onServiceConnected(){main.post(()->{if(running){state("小米运动健康服务已连接");refresh();}});}
        public void onServiceDisconnected(){main.post(()->{
            if(!running)return;generation++;clearSends();registeredNode="";nodeId="";checking=false;registering=false;querying=false;
            state("小米运动健康服务已断开");
        });}
    };
    private void refresh(){
        if(!running||querying||!ensureApis())return;
        querying=true;final int run=generation;
        try{
            nodes.getConnectedNodes().addOnSuccessListener(list->main.post(()->{
                if(!running||run!=generation)return;querying=false;
                Node selected=select(list);
                if(selected==null){detach();nodeId="";state("未找到已连接手表，请检查小米运动健康");return;}
                if(!selected.id.equals(nodeId)){detach();nodeId=selected.id;checking=false;registering=false;}
                if(registeredNode.equals(nodeId))return;
                checkAndListen(run,nodeId);
            })).addOnFailureListener(error->main.post(()->{
                if(!running||run!=generation)return;querying=false;state("无法查询手表连接");report("查询手表失败",error);
            }));
        }catch(Exception error){querying=false;report("查询手表异常",error);}
        // A stale SDK Task must not prevent later user-initiated recovery forever.
        main.postDelayed(()->{if(run==generation)querying=false;},12000);
    }
    private static Node select(List<Node> list){
        if(list==null||list.isEmpty())return null;
        for(Node n:list)if(n!=null&&n.id!=null&&n.name!=null&&n.name.toLowerCase(java.util.Locale.ROOT).contains("s4"))return n;
        for(Node n:list)if(n!=null&&n.id!=null)return n;
        return null;
    }
    private void checkAndListen(int run,String id){
        if(checking||registering)return;checking=true;
        try{
            auth.checkPermission(id,Permission.DEVICE_MANAGER).addOnSuccessListener(granted->main.post(()->{
                if(!running||run!=generation||!id.equals(nodeId))return;checking=false;
                if(Boolean.TRUE.equals(granted))attach(run,id);
                else state("需要点击“手表通信授权”");
            })).addOnFailureListener(error->main.post(()->{
                if(!running||run!=generation||!id.equals(nodeId))return;checking=false;
                state("通信授权尚未就绪");report("查询手表授权失败",error);
            }));
        }catch(Exception error){checking=false;report("查询手表授权异常",error);}
        main.postDelayed(()->{if(run==generation&&id.equals(nodeId))checking=false;},12000);
    }
    private void attach(int run,String id){
        if(registering||id.equals(registeredNode))return;registering=true;
        try{
            messages.addListener(id,messageListener).addOnSuccessListener(ignored->main.post(()->{
                if(!running||run!=generation||!id.equals(nodeId)){
                    if(!running||!id.equals(nodeId))try{messages.removeListener(id);}catch(Exception ignoredError){}return;
                }
                registering=false;registeredNode=id;state("消息通道已就绪，请打开手表端应用");
            })).addOnFailureListener(error->main.post(()->{
                if(!running||run!=generation||!id.equals(nodeId))return;registering=false;
                state("手表消息监听未建立");report("注册手表消息监听失败",error);
            }));
        }catch(Exception error){registering=false;report("注册手表消息监听异常",error);}
        main.postDelayed(()->{if(run==generation&&id.equals(nodeId))registering=false;},12000);
    }
    private final OnMessageReceivedListener messageListener=new OnMessageReceivedListener(){
      @Override public void onMessageReceived(String id,byte[] bytes){
        if(bytes==null||bytes.length>4096)return;
        final byte[] copy=bytes.clone();
        main.post(()->{
            if(!running||!id.equals(nodeId))return;
            lastMessageAt=SystemClock.elapsedRealtime();
            String clean=normalize(new String(copy,StandardCharsets.UTF_8));
            if(clean==null){report("已忽略格式不符的手表消息",null);return;}
            state("已收到手表端消息");listener.onMessage(clean);
        });
      }
    };
    static String normalize(String raw){
        if(raw==null||raw.length()>4096)return null;
        Object current=raw;
        try{
            for(int depth=0;depth<4;depth++){
                if(current instanceof String){current=new JSONTokener((String)current).nextValue();continue;}
                if(!(current instanceof JSONObject))return null;
                JSONObject object=(JSONObject)current;
                if(object.optInt("v",-1)==1&&object.opt("type") instanceof String)return object.toString();
                if(!object.has("data"))return null;
                current=object.opt("data");
            }
        }catch(Exception ignored){}
        return null;
    }
    public boolean isWatchConnected(){
        return running&&!registeredNode.isEmpty()&&lastMessageAt>0
            &&SystemClock.elapsedRealtime()-lastMessageAt<=15000;
    }
    public void send(String json){
        if(!running||messages==null||nodeId.isEmpty()||registeredNode.isEmpty()||json==null||json.length()>4096)return;
        try{
            JSONObject packet=new JSONObject(json);
            boolean result="result".equals(packet.optString("type"));
            boolean terminal=result&&!"accepted".equals(packet.optString("phase"));
            outbound.offer(json,packet.optString("id"),result,terminal,SystemClock.elapsedRealtime());
        }catch(Exception error){report("手表消息未入队",error);return;}
        pumpSend();
    }
    private void pumpSend(){
        if(!running||messages==null||nodeId.isEmpty()||registeredNode.isEmpty())return;
        final WatchOutbox.Item item=outbound.begin();if(item==null)return;
        final String id=nodeId;final int run=generation;
        try{
            messages.sendMessage(id,item.json.getBytes(StandardCharsets.UTF_8))
                .addOnSuccessListener(ignored->main.post(()->finishSend(id,run,item,null)))
                .addOnFailureListener(error->main.post(()->finishSend(id,run,item,error)));
            sendTimeout=()->{if(run==generation&&outbound.isInflight(item)){
                // SDK operations cannot be canceled. Invalidate the lane instead of overlapping sends.
                detach();state("手表发送超时，正在恢复消息通道");refresh();
            }};
            main.postDelayed(sendTimeout,5000);
        }catch(Exception error){finishSend(id,run,item,error);}
    }
    private void finishSend(String id,int run,WatchOutbox.Item item,Exception error){
        if(run!=generation||!id.equals(nodeId)||!outbound.finish(item))return;
        if(sendTimeout!=null)main.removeCallbacks(sendTimeout);sendTimeout=null;
        if(item.result&&item.terminal)listener.onLog("控制分段：结果队列至SDK回调 "+Math.max(0,SystemClock.elapsedRealtime()-item.queuedAt)+" ms");
        if(error!=null)report("发送手表消息失败",error);
        pumpSend();
    }
    public void clearPending(){clearSends();}
    private void clearSends(){outbound.clear();if(sendTimeout!=null)main.removeCallbacks(sendTimeout);sendTimeout=null;}
    private void detach(){
        clearSends();
        String old=registeredNode;registeredNode="";
        if(messages!=null&&!old.isEmpty())try{messages.removeListener(old);}catch(Exception ignored){}
    }
    public void openAuthorization(Activity activity){
        if(authorizing){state("正在申请通信授权，请稍候");return;}
        if(!ensureApis())return;
        authorizing=true;final int run=generation;
        try{
            nodes.getConnectedNodes().addOnSuccessListener(list->main.post(()->{
                if(activity.isFinishing()||activity.isDestroyed()){authorizing=false;return;}
                Node selected=select(list);
                if(selected==null){authorizing=false;
                    new AlertDialog.Builder(activity).setTitle("先连接 S4").setMessage("请先在小米运动健康中连接手表，然后返回这里申请通信授权。")
                        .setPositiveButton("打开小米运动健康",(d,w)->openCompanion(activity)).setNegativeButton("取消",null).show();return;}
                try{
                    auth.requestPermission(selected.id,Permission.DEVICE_MANAGER)
                        .addOnSuccessListener(granted->main.post(()->{
                            authorizing=false;state("授权流程已返回，正在核对权限");
                            if(running)refresh();
                            else state("授权流程已返回，请开启手机中转");
                        })).addOnFailureListener(error->main.post(()->{authorizing=false;state("手表通信授权失败");report("申请通信授权失败",error);}));
                }catch(Exception error){authorizing=false;report("申请通信授权异常",error);}
            })).addOnFailureListener(error->main.post(()->{authorizing=false;report("授权前查询手表失败",error);openCompanion(activity);}));
        }catch(Exception error){authorizing=false;report("启动授权异常",error);}
        main.postDelayed(()->{if(run==generation)authorizing=false;},45000);
    }
    private void openCompanion(Activity activity){
        for(String pkg:new String[]{"com.mi.health","com.xiaomi.wearable"}){
            Intent launch=context.getPackageManager().getLaunchIntentForPackage(pkg);
            if(launch!=null){try{activity.startActivity(launch);return;}catch(Exception ignored){}}
        }
        state("未找到小米运动健康，请先安装并连接手表");
    }
    private void state(String text){if(!text.equals(lastState)){lastState=text;listener.onState(text);}}
    private void report(String prefix,Exception error){
        String reason=error==null?"":error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
        String text=prefix+(reason.isEmpty()?"":" · "+reason);
        long now=SystemClock.elapsedRealtime();
        if(text.equals(lastError)&&now-lastErrorAt<15000)return;
        lastError=text;lastErrorAt=now;listener.onLog(text.length()>250?text.substring(0,250):text);
    }
}
