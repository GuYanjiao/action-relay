package cn.local.actionrelay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.AuthApi
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.service.ServiceApi
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** Official XMS SDK channel; one uncancellable SDK send may be in flight. */
class XiaomiWatchBridge(context: Context, private val listener: Listener) {
    interface Listener { fun onMessage(text: String); fun onState(text: String); fun onLog(text: String) }
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private lateinit var nodes: NodeApi
    private lateinit var auth: AuthApi
    private lateinit var messages: MessageApi
    private lateinit var service: ServiceApi
    private var apisReady=false
    private var running=false
    private var querying=false
    private var authorizing=false
    private var checking=false
    private var registering=false
    private var nodeId=""
    private var registeredNode=""
    private var autoRequestedNode=""
    private var lastState=""
    private var lastError=""
    private var lastErrorAt=0L
    private var lastMessageAt=0L
    private val outbound=WatchOutbox()
    private var sendTimeout: Runnable?=null
    private var generation=0
    private fun ensureApis(): Boolean {
        if(apisReady)return true
        return try{nodes=Wearable.getNodeApi(context);auth=Wearable.getAuthApi(context);messages=Wearable.getMessageApi(context);service=Wearable.getServiceApi(context);apisReady=true;true}
        catch(error: Exception){report("小米通信接口初始化失败",error);false}
    }
    fun start(){
        if(running)return;running=true;generation++
        if(!ensureApis()){running=false;return}
        try{service.registerServiceConnectionListener(serviceListener)}catch(error: Exception){report("监听小米服务失败",error)}
        state("正在查询已连接的手表");main.post(poll)
    }
    fun stop(){
        running=false;generation++;main.removeCallbacks(poll);querying=false;checking=false;registering=false;authorizing=false
        clearSends();detach();nodeId="";autoRequestedNode=""
        if(apisReady)try{service.unregisterServiceConnectionListener(serviceListener)}catch(_: Exception){}
        state("中转已关闭")
    }
    private val poll=object: Runnable {override fun run(){if(!running)return;refresh();main.postDelayed(this,if(registeredNode.isEmpty())5000 else 30000)}}
    private val serviceListener=object: OnServiceConnectionListener {
        override fun onServiceConnected(){main.post{if(running){state("小米运动健康服务已连接");refresh()}}}
        override fun onServiceDisconnected(){main.post{
            if(!running)return@post
            generation++;clearSends();registeredNode="";nodeId="";autoRequestedNode="";checking=false;registering=false;querying=false;state("小米运动健康服务已断开")
        }}
    }
    private fun refresh(){
        if(!running || querying || !ensureApis())return
        querying=true;val run=generation
        try{nodes.connectedNodes.addOnSuccessListener{list -> main.post{
            if(!running || run!=generation)return@post;querying=false
            val selected=select(list)
            if(selected==null){detach();nodeId="";state("未找到已连接手表，请检查小米运动健康");return@post}
            if(selected.id!=nodeId){detach();nodeId=selected.id;autoRequestedNode="";checking=false;registering=false}
            if(registeredNode!=nodeId)checkAndListen(run,nodeId)
        }}.addOnFailureListener{error -> main.post{if(running && run==generation){querying=false;state("无法查询手表连接");report("查询手表失败",error)}}}}
        catch(error: Exception){querying=false;report("查询手表异常",error)}
        main.postDelayed({if(run==generation)querying=false},12000)
    }
    private fun select(list: List<Node>?): Node? = list?.firstOrNull{it.id!=null && it.name?.lowercase(Locale.ROOT)?.contains("s4")==true} ?: list?.firstOrNull{it.id!=null}
    private fun checkAndListen(run: Int, id: String){
        if(checking || registering || authorizing)return;checking=true
        try{auth.checkPermission(id,Permission.DEVICE_MANAGER).addOnSuccessListener{granted -> main.post{
            if(!running || run!=generation || id!=nodeId)return@post;checking=false
            if(granted==true)attach(run,id)else autoRequest(run,id)
        }}.addOnFailureListener{error -> main.post{if(running && run==generation && id==nodeId){checking=false;state("通信授权尚未就绪");report("查询手表授权失败",error)}}}}
        catch(error: Exception){checking=false;report("查询手表授权异常",error)}
        main.postDelayed({if(run==generation && id==nodeId)checking=false},12000)
    }
    private fun autoRequest(run: Int, id: String){
        if(id==autoRequestedNode){state("需要点击“手表通信授权”");return}
        autoRequestedNode=id;state("正在申请手表通信授权");requestPermission(run,id)
    }
    private fun requestPermission(run: Int, id: String){
        if(authorizing || !ensureApis())return;authorizing=true
        try{auth.requestPermission(id,Permission.DEVICE_MANAGER).addOnSuccessListener{main.post{
            if(!running || run!=generation || id!=nodeId){authorizing=false;return@post};authorizing=false
            // requestPermission returns Permission[], not Boolean. Verify the actual grant.
            state("授权流程已返回，正在核对权限");checkAndListen(run,id)
        }}.addOnFailureListener{error -> main.post{
            if(!running || run!=generation || id!=nodeId){authorizing=false;return@post}
            authorizing=false;state("通信授权尚未就绪");report("申请通信授权失败",error)
        }}}
        catch(error: Exception){authorizing=false;report("申请通信授权异常",error)}
        main.postDelayed({if(run==generation && id==nodeId)authorizing=false},45000)
    }
    private fun attach(run: Int, id: String){
        if(registering || id==registeredNode)return;registering=true
        try{messages.addListener(id,messageListener).addOnSuccessListener{main.post{
            if(!running || run!=generation || id!=nodeId){if(!running || id!=nodeId)try{messages.removeListener(id)}catch(_: Exception){};return@post}
            registering=false;registeredNode=id;state("消息通道已就绪，请打开手表端应用")
        }}.addOnFailureListener{error -> main.post{if(running && run==generation && id==nodeId){registering=false;state("手表消息监听未建立");report("注册手表消息监听失败",error)}}}}
        catch(error: Exception){registering=false;report("注册手表消息监听异常",error)}
        main.postDelayed({if(run==generation && id==nodeId)registering=false},12000)
    }
    private val messageListener=object: OnMessageReceivedListener {
        override fun onMessageReceived(id: String, bytes: ByteArray){
            if(bytes.size>4096)return
            val copy=bytes.clone();main.post{
                if(!running || id!=nodeId)return@post
                lastMessageAt=SystemClock.elapsedRealtime()
                val clean=normalize(copy.toString(Charsets.UTF_8))
                if(clean==null){report("已忽略格式不符的手表消息",null);return@post}
                state("已收到手表端消息");listener.onMessage(clean)
            }
        }
    }
    companion object {
        @JvmStatic fun normalize(raw: String?): String? {
            if(raw==null || raw.length>4096)return null
            var current: Any?=raw
            try{repeat(4){
                val value=current
                if(value is String){current=JSONTokener(value).nextValue();return@repeat}
                if(value !is JSONObject)return null
                if(value.optInt("v",-1)==1 && value.opt("type") is String)return value.toString()
                if(!value.has("data"))return null;current=value.opt("data")
            }}catch(_: Exception){}
            return null
        }
    }
    fun isWatchConnected()=running && registeredNode.isNotEmpty() && lastMessageAt>0 && SystemClock.elapsedRealtime()-lastMessageAt<=15000
    fun hasRegisteredChannel()=running && registeredNode.isNotEmpty()
    fun send(json: String){
        if(!running || !apisReady || nodeId.isEmpty() || registeredNode.isEmpty() || json.length>4096)return
        try{val packet=JSONObject(json);val result=packet.optString("type")=="result"
            outbound.offer(json,packet.optString("id"),result,result && packet.optString("phase")!="accepted",SystemClock.elapsedRealtime())
        }catch(error: Exception){report("手表消息未入队",error);return};pumpSend()
    }
    private fun pumpSend(){
        if(!running || !apisReady || nodeId.isEmpty() || registeredNode.isEmpty())return
        val item=outbound.begin() ?: return;val id=nodeId;val run=generation
        try{
            messages.sendMessage(id,item.json.toByteArray(Charsets.UTF_8)).addOnSuccessListener{main.post{finishSend(id,run,item,null)}}.addOnFailureListener{error -> main.post{finishSend(id,run,item,error)}}
            sendTimeout=Runnable{if(run==generation && outbound.isInflight(item)){detach();state("手表发送超时，正在恢复消息通道");refresh()}}.also{main.postDelayed(it,5000)}
        }catch(error: Exception){finishSend(id,run,item,error)}
    }
    private fun finishSend(id: String, run: Int, item: WatchOutbox.Item, error: Exception?){
        if(run!=generation || id!=nodeId || !outbound.finish(item))return
        sendTimeout?.let(main::removeCallbacks);sendTimeout=null
        if(item.result && item.terminal)listener.onLog("控制分段：结果队列至SDK回调 ${maxOf(0,SystemClock.elapsedRealtime()-item.queuedAt)} ms")
        if(error!=null)report("发送手表消息失败",error);pumpSend()
    }
    fun clearPending()=clearSends()
    private fun clearSends(){outbound.clear();sendTimeout?.let(main::removeCallbacks);sendTimeout=null}
    private fun detach(){
        clearSends();val old=registeredNode;registeredNode=""
        if(apisReady && old.isNotEmpty())try{messages.removeListener(old)}catch(_: Exception){}
    }
    fun openAuthorization(activity: Activity){
        if(authorizing){state("正在申请通信授权，请稍候");return}
        if(!ensureApis())return;authorizing=true;val run=generation
        try{nodes.connectedNodes.addOnSuccessListener{list -> main.post{
            if(activity.isFinishing || activity.isDestroyed){authorizing=false;return@post}
            val selected=select(list)
            if(selected==null){authorizing=false;AlertDialog.Builder(activity).setTitle("先连接 S4").setMessage("请先在小米运动健康中连接手表，然后返回这里申请通信授权。")
                .setPositiveButton("打开小米运动健康"){_,_ -> openCompanion(activity)}.setNegativeButton("取消",null).show();return@post}
            try{auth.requestPermission(selected.id,Permission.DEVICE_MANAGER).addOnSuccessListener{main.post{
                authorizing=false;state("授权流程已返回，正在核对权限");if(running)refresh()else state("授权流程已返回，请开启手机中转")
            }}.addOnFailureListener{error -> main.post{authorizing=false;state("手表通信授权失败");report("申请通信授权失败",error)}}}
            catch(error: Exception){authorizing=false;report("申请通信授权异常",error)}
        }}.addOnFailureListener{error -> main.post{authorizing=false;report("授权前查询手表失败",error);openCompanion(activity)}}}
        catch(error: Exception){authorizing=false;report("启动授权异常",error)}
        main.postDelayed({if(run==generation)authorizing=false},45000)
    }
    private fun openCompanion(activity: Activity){
        for(pkg in listOf("com.mi.health","com.xiaomi.wearable")){
            val launch=context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try{activity.startActivity(launch);return}catch(_: Exception){}
        };state("未找到小米运动健康，请先安装并连接手表")
    }
    private fun state(text: String){if(text!=lastState){lastState=text;listener.onState(text)}}
    private fun report(prefix: String, error: Exception?){
        val reason=error?.let{it.javaClass.simpleName+": "+it.message}.orEmpty();val text=prefix+if(reason.isEmpty())"" else " · $reason"
        val now=SystemClock.elapsedRealtime();if(text==lastError && now-lastErrorAt<15000)return
        lastError=text;lastErrorAt=now;listener.onLog(text.take(250))
    }
}
