package cn.local.actionrelay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.local.actionrelay.protocol.*
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** Main-thread owner of local command routing and remembered device state. */
class RelayHub(ctx: Context) {
    fun interface UiListener { fun onChanged() }
    data class Candidate(val address: String, val name: String, val rssi: Int)
    val context: Context=ctx.applicationContext
    val logs=ArrayList<String>()
    val candidates=LinkedHashMap<String,Candidate>()
    // Keep initialization guards for callbacks into this interdependent object graph.
    @Suppress("UNNECESSARY_LATEINIT") lateinit var ble: DjiBle; private set
    @Suppress("UNNECESSARY_LATEINIT") lateinit var session: DjiSession; private set
    @Suppress("UNNECESSARY_LATEINIT") lateinit var bridge: XiaomiWatchBridge; private set
    private val observers=ArrayList<UiListener>()
    private val tickets=LinkedHashMap<String,Long>()
    private val results=LinkedHashMap<String,JSONObject>()
    private val main=Handler(Looper.getMainLooper())
    private val prefs=context.getSharedPreferences("relay",0)
    private val random=SecureRandom()
    var enabled=false; private set
    var busy=false; private set
    private var requestedGps=prefs.getBoolean("gps_enabled",false)
    val gpsEnabled: Boolean get()=requestedGps
    var autoReconnectEnabled=prefs.getBoolean("auto_reconnect",false); private set
    var phase="disconnected"; private set
    var message="先开启手机中转"; private set
    var watchMessage="等待手表连接"; private set
    var selectedAddress=""; private set
    var gpsState="off"; private set
    var gpsMessage="GPS推送未开启"; private set
    var rememberedCameraName=prefs.getString("camera_name","").orEmpty(); private set
    var rememberedCameraAddress=prefs.getString("camera_address","").orEmpty(); private set
    var rememberedCameraLastSuccess=prefs.getLong("camera_last_success",0); private set
    var gpsAccuracy=-1f; private set
    var gpsSpeed=-1f; private set
    var verificationCode=0; private set
    private var epoch=UUID.randomUUID().toString()
    private var ticket=""
    private var lastPublish=0L
    private var activeRequest=""
    private val commandStarts=HashMap<String,Long>()
    private var lastPublishedControl=""
    private var statusSeq=0L
    private var gpsSequence=0
    private val reconnectPolicy=ReconnectPolicy()
    private var reconnectTask: Runnable?=null
    private var reconnectRequested=false
    private var suppressReconnect=false
    private var receiverRegistered=false
    private var retryAddress=""
    private val gps: PhoneGps
    private val gpsLifecycle: GpsLifecycle
    init {
        if(!BluetoothAdapter.checkBluetoothAddress(rememberedCameraAddress) || !prefs.getBoolean("paired_$rememberedCameraAddress",false)){
            rememberedCameraAddress="";rememberedCameraName="";rememberedCameraLastSuccess=0
            prefs.edit().remove("camera_address").remove("camera_name").remove("camera_last_success").apply()
        }
        selectedAddress=rememberedCameraAddress;retryAddress=rememberedCameraAddress
        val identity=ByteArray(6);var identityLong=prefs.getLong("identity",0)
        if(identityLong==0L){
            random.nextBytes(identity);identity[0]=((identity[0].toInt() and 0xfc) or 2).toByte()
            for(i in 0..5)identityLong=identityLong or ((identity[i].toLong() and 255) shl (8*i))
            prefs.edit().putLong("identity",identityLong).apply()
        }else for(i in 0..5)identity[i]=(identityLong ushr (8*i)).toByte()
        var controller=prefs.getLong("controller",-1)
        if(controller<0){controller=random.nextInt().toLong() and 0xffffffffL;prefs.edit().putLong("controller",controller).apply()}
        ble=DjiBle(context,object: DjiBle.Listener {
            override fun onCandidate(address: String, name: String, rssi: Int){
                if(address.isEmpty())return
                if(address !in candidates && candidates.size>=24)candidates.remove(candidates.keys.first())
                candidates[address]=Candidate(address,name,rssi);changed()
            }
            override fun onPhase(phase: String, message: String){this@RelayHub.phase=phase;this@RelayHub.message=message;publish();changed()}
            override fun onTransportReady(){
                phase="authorizing";message=String.format(Locale.ROOT,"请在相机上允许连接 · 验证码 %04d",verificationCode)
                session.startHandshake(!prefs.getBoolean("paired_$selectedAddress",false),verificationCode);wakePulse();publish();changed()
            }
            override fun onBytes(bytes: ByteArray)=session.onBytes(bytes)
            override fun onDisconnected(reason: String){
                phase="disconnected";message=reason;busy=false;rotateEpoch()
                if(::session.isInitialized)session.disconnect();publish();changed()
                if(!suppressReconnect && enabled && (autoReconnectEnabled || reconnectRequested) && retryAddress.isNotEmpty() && prefs.getBoolean("paired_$retryAddress",false) && !reason.contains("拒绝") && !reason.contains("授权") && !reason.contains("重新建立"))scheduleReconnect()
            }
            override fun onLog(message: String){if(message.startsWith("控制分段"))diagnostic(message)else log(message)}
        })
        session=DjiSession(SystemClock::elapsedRealtime,ble::send,object: DjiSession.Listener {
            override fun onReady(ready: Boolean){
                if(ready){
                    phase="ready";message="相机已连接，等待状态";reconnectRequested=false;cancelReconnect();reconnectPolicy.reset()
                    val candidate=candidates[selectedAddress]
                    if(candidate!=null && candidate.name.isNotEmpty())rememberedCameraName=candidate.name
                    else if(selectedAddress!=rememberedCameraAddress)rememberedCameraName="DJI Osmo Action 4"
                    rememberedCameraAddress=selectedAddress;rememberedCameraLastSuccess=System.currentTimeMillis();retryAddress=selectedAddress
                    prefs.edit().putBoolean("paired_$selectedAddress",true).putString("camera_address",selectedAddress).putString("camera_name",rememberedCameraName).putLong("camera_last_success",rememberedCameraLastSuccess).apply()
                };reconfigureGps();changed()
            }
            override fun onStatus(status: DjiProtocol.CameraStatus){
                if(!busy)message=when{
                    status.mode==5 -> "相机处于拍照模式"
                    status.cameraState==2 -> "点击拍摄可尝试退出回放"
                    !status.isNormalVideoMode() -> "请将相机设为普通视频或拍照模式"
                    !status.isControllableState() -> "相机暂不可控，状态码 ${status.cameraState}"
                    status.isRecording() -> "相机正在录像"
                    status.isPreRecording() -> "相机处于预录缓存"
                    else -> "相机已待机"
                }
                if(controlKey()!=lastPublishedControl || SystemClock.elapsedRealtime()-lastPublish>500)publish();changed()
            }
            override fun onLog(message: String)=log(message)
            override fun onCommandResult(requestId: String, desired: Boolean, outcome: String, message: String){
                val finishedAt=SystemClock.elapsedRealtime()
                if(requestId==activeRequest){busy=false;activeRequest="";ble.setControlPriority(false)}
                this@RelayHub.message=message;newTicket();lastPublish=finishedAt
                val response=json("v",1,"type","result","id",requestId,"ok",outcome=="confirmed","phase",outcome,"message",message,"session",epoch,"revision",session.getRevision(),"state",snapshot())
                rememberResult(requestId,response);sendWatch(response)
                commandStarts.remove(requestId)?.let{log("控制分段：手机受理至相机结果 ${maxOf(0,finishedAt-it)} ms")};log(message)
            }
            override fun onDisconnectRequired(reason: String)=ble.disconnect(reason)
        },controller,identity)
        bridge=XiaomiWatchBridge(context,object: XiaomiWatchBridge.Listener {
            override fun onMessage(text: String)=handleWatch(text)
            override fun onState(text: String){main.post{watchMessage=text;changed()}}
            override fun onLog(text: String){main.post{if(text.startsWith("控制分段"))diagnostic(text)else log(text)}}
        })
        gps=PhoneGps(context,object: PhoneGps.Listener {
            override fun onStateChanged(state: PhoneGps.State){gpsState=state.phase;gpsMessage=state.message;gpsAccuracy=state.accuracyMeters;if(!state.ready)gpsSpeed=-1f;changed()}
            override fun onDiagnostic(message: String)=log("GPS：$message")
            override fun onFix(fix: GpsProtocol.Fix){
                gpsSpeed=maxOf(0f,abs(fix.northMps)+abs(fix.eastMps))
                if(enabled && session.isReady() && !session.isBusy() && ble.supportsGps())try{ble.sendGps(GpsProtocol.encode(nextGpsSequence(),fix,SystemClock.elapsedRealtime()))}catch(error: IllegalArgumentException){log("GPS数据未发送：${error.message}")}
            }
        })
        gpsLifecycle=GpsLifecycle(object: GpsLifecycle.Driver {override fun start()=gps.start();override fun stop()=gps.stop()})
    }
    private val pulse=object: Runnable {override fun run(){
        if(!enabled)return;session.tick(SystemClock.elapsedRealtime())
        if(SystemClock.elapsedRealtime()-lastPublish>=1900)publish()
        val delay=if(session.getPhase()=="handshaking" || session.getCommandPhase()!="idle")500L else if(session.isReady() || bridge.hasRegisteredChannel())2000L else 10000L
        main.postDelayed(this,delay)
    }}
    private fun wakePulse(){if(enabled){main.removeCallbacks(pulse);main.post(pulse)}}
    fun start(){
        if(enabled)return;enabled=true;rotateEpoch();message="手机中转已开启，请搜索相机"
        bridge.start();reconfigureGps();main.post(pulse)
        if(!receiverRegistered){
            val filter=IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(bluetoothReceiver,filter,Context.RECEIVER_EXPORTED)else context.registerReceiver(bluetoothReceiver,filter)
            receiverRegistered=true
        }
        reconnectPolicy.reset();retryAddress=rememberedCameraAddress
        if((autoReconnectEnabled || reconnectRequested) && rememberedCameraAddress.isNotEmpty())scheduleReconnect();changed()
    }
    fun stop(){
        enabled=false;main.removeCallbacks(pulse);cancelReconnect();reconnectRequested=false
        if(receiverRegistered){context.unregisterReceiver(bluetoothReceiver);receiverRegistered=false}
        gpsLifecycle.stop();ble.close();busy=false;message="手机中转已关闭";publish();bridge.stop();changed()
    }
    fun scan(){if(!enabled){log("请先开启手机中转");return};cancelReconnect();reconnectRequested=false;candidates.clear();ble.scan();changed()}
    fun connect(address: String){if(!enabled || !BluetoothAdapter.checkBluetoothAddress(address))return;cancelReconnect();reconnectPolicy.reset();reconnectRequested=false;retryAddress=address;beginConnect(address)}
    private fun beginConnect(address: String){
        if(!enabled || !connectionAvailable())return
        if(address==selectedAddress && (phase=="connecting" || phase=="authorizing" || session.isReady()))return
        suppressReconnect=true
        try{ble.disconnect("正在切换连接");selectedAddress=address;verificationCode=random.nextInt(10000);ble.connect(address)}finally{suppressReconnect=false};changed()
    }
    fun requestReconnect(){
        if(rememberedCameraAddress.isEmpty() || (enabled && rememberedCameraAddress==selectedAddress && session.isReady()))return
        cancelReconnect();reconnectPolicy.reset();retryAddress=rememberedCameraAddress;reconnectRequested=true
        if(enabled)scheduleReconnect();changed()
    }
    fun reconnectRememberedCamera()=requestReconnect()
    fun setAutoReconnect(value: Boolean){
        autoReconnectEnabled=value;prefs.edit().putBoolean("auto_reconnect",value).apply()
        if(!value){cancelReconnect();reconnectRequested=false}else{reconnectPolicy.reset();onEnvironmentChanged()};changed()
    }
    fun reconfigureGps(){
        val cameraReady=session.isReady() && ble.supportsGps();gpsLifecycle.reconfigure(enabled,gpsEnabled,cameraReady)
        if(!gpsEnabled){gpsState="off";gpsMessage="GPS推送未开启";gpsAccuracy=-1f;gpsSpeed=-1f;ble.clearGps()}
        else if(enabled && !cameraReady){gpsState="waiting_camera";gpsMessage="相机连接后自动启动定位";gpsAccuracy=-1f;gpsSpeed=-1f;ble.clearGps()};changed()
    }
    fun setGpsEnabled(enabled: Boolean){
        requestedGps=enabled;prefs.edit().putBoolean("gps_enabled",enabled).apply()
        if(!enabled){gpsLifecycle.stop();gpsState="off";gpsMessage="GPS推送未开启";gpsAccuracy=-1f;gpsSpeed=-1f;ble.clearGps()};changed()
    }
    fun setGpsStartError(detail: String){gpsLifecycle.stop();ble.clearGps();gpsState="unavailable";gpsMessage=detail;gpsAccuracy=-1f;gpsSpeed=-1f;changed()}
    private fun nextGpsSequence(): Int {gpsSequence=(gpsSequence+1) and 0xffff;if(gpsSequence==0)gpsSequence=1;return gpsSequence}
    fun forgetCamera(){
        cancelReconnect();reconnectRequested=false;retryAddress=""
        prefs.edit().remove("paired_$rememberedCameraAddress").remove("paired_$selectedAddress").remove("camera_address").remove("camera_name").remove("camera_last_success").apply()
        selectedAddress="";rememberedCameraAddress="";rememberedCameraName="";rememberedCameraLastSuccess=0;ble.disconnect("已清除本应用保存的相机授权标记，请重新搜索连接")
    }
    private fun scheduleReconnect(){
        if(reconnectTask!=null || !enabled || retryAddress.isEmpty() || (!autoReconnectEnabled && !reconnectRequested) || session.isReady() || phase=="connecting" || phase=="authorizing" || !connectionAvailable())return
        val ticket=reconnectPolicy.schedule()
        if(ticket==null){if(reconnectPolicy.exhausted())log("重连已暂停，请检查相机后点击重连");return}
        val address=retryAddress
        reconnectTask=Runnable{
            reconnectTask=null
            if(reconnectPolicy.claim(ticket) && enabled && address==retryAddress && (autoReconnectEnabled || reconnectRequested) && connectionAvailable())beginConnect(address)
        }.also{main.postDelayed(it,ticket.delayMs)}
    }
    private val bluetoothReceiver=object: BroadcastReceiver(){override fun onReceive(context: Context, intent: Intent){
        if(!enabled || intent.action!=BluetoothAdapter.ACTION_STATE_CHANGED)return
        when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1)){
            BluetoothAdapter.STATE_OFF,BluetoothAdapter.STATE_TURNING_OFF -> ble.disconnect("手机蓝牙已关闭，开启后可继续重连")
            BluetoothAdapter.STATE_ON -> onEnvironmentChanged()
        }
    }}
    fun onEnvironmentChanged(){
        if(enabled && (autoReconnectEnabled || reconnectRequested) && rememberedCameraAddress.isNotEmpty() && (retryAddress.isEmpty() || retryAddress==rememberedCameraAddress) && !session.isReady() && phase!="connecting" && phase!="authorizing" && connectionAvailable()){
            cancelReconnect();reconnectPolicy.reset();retryAddress=rememberedCameraAddress;scheduleReconnect()
        }
    }
    private fun connectionAvailable(): Boolean {
        if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){message="需要附近设备权限，授权后可继续重连";changed();return false}
        if(!ble.bluetoothEnabled()){message="请打开手机蓝牙，开启后继续重连";changed();return false};return true
    }
    private fun cancelReconnect(){reconnectTask?.let(main::removeCallbacks);reconnectTask=null;reconnectPolicy.cancel()}
    private fun rotateEpoch(){epoch=UUID.randomUUID().toString();tickets.clear();ticket="";results.clear();if(::bridge.isInitialized)bridge.clearPending()}
    private fun newTicket(){
        val now=SystemClock.elapsedRealtime();ticket=UUID.randomUUID().toString();tickets[ticket]=now
        val iterator=tickets.entries.iterator();while(iterator.hasNext()){val entry=iterator.next();if(now-entry.value>6000 || tickets.size>16)iterator.remove()}
    }
    fun snapshot(): JSONObject {
        val s=session.getStatus();val fresh=session.isReady() && s!=null && session.getStatusAgeMs()<=DjiSession.STATUS_FRESH_MS
        val recording: Any=if(fresh && s.isControllableState())s.isRecording()else JSONObject.NULL
        val detail=if(session.isReady() && !fresh)"等待相机最新状态，按钮暂不可用"else message
        return json("v",1,"type","status","seq",++statusSeq,"session",epoch,"ticket",ticket,"camera",if(enabled)phase else "disconnected","recording",recording,
            "canControl",enabled && session.canControlRecording(),"canStart",enabled && session.canStartRecording(),"canPhoto",enabled && session.canTakePhoto(),"canStop",enabled && session.canStopRecording(),"busy",session.isBusy(),"pendingDesired",session.getPendingDesired() ?: JSONObject.NULL,
            "recordPhase",session.getCommandPhase(),"watchConnected",bridge.isWatchConnected(),"gpsState",gpsState,"gpsEnabled",gpsEnabled,"battery",if(fresh)s.batteryPercent else -1,"recordSeconds",if(fresh)s.recordingSeconds else -1,"gpsMessage",gpsMessage,"message",detail,
            "storageMb",if(fresh)s.remainingCapacityMb else -1,"cameraMode",if(fresh)s.mode else -1,"remainingSeconds",if(fresh)s.remainingRecordSeconds else -1,"temperatureState",if(fresh)s.temperatureState else -1,"cameraState",if(fresh)s.cameraState else -1,"videoResolution",if(fresh)s.videoResolution else -1,"fpsIndex",if(fresh)s.fpsIndex else -1,"revision",session.getRevision())
    }
    private fun controlKey()="$phase:${session.getCommandPhase()}:${session.canStartRecording()}:${session.canStopRecording()}:${session.canTakePhoto()}:${session.getStatus()?.cameraState ?: -1}"
    private fun publish(){if(!::session.isInitialized || !::bridge.isInitialized)return;newTicket();lastPublish=SystemClock.elapsedRealtime();lastPublishedControl=controlKey();sendWatch(snapshot())}
    private fun sendWatch(objectValue: JSONObject){if(enabled && ::bridge.isInitialized)bridge.send(objectValue.toString())}
    fun phoneCommand(start: Boolean)=command(start,"phone-"+UUID.randomUUID())
    private fun photoCommand(id: String){
        if(!enabled || !session.canTakePhoto()){reject(id,"相机拍照未就绪");return}
        busy=true;activeRequest=id;commandStarts[id]=SystemClock.elapsedRealtime();message="正在请求拍照"
        ble.setControlPriority(true);session.takePhoto(id);wakePulse()
        if(id==activeRequest)accepted(id);changed()
    }
    private fun command(desired: Boolean, id: String){
        if(!enabled || (if(desired)!session.canStartRecording()else !session.canStopRecording())){reject(id,"相机未就绪，或当前状态不允许此操作");return}
        busy=true;activeRequest=id;commandStarts[id]=SystemClock.elapsedRealtime();message=if(desired)"正在请求开始录像"else "正在请求停止录像"
        ble.setControlPriority(true);session.setRecording(desired,id);wakePulse()
        if(id==activeRequest)accepted(id);changed()
    }
    private fun accepted(id: String){
        val packet=json("v",1,"type","result","id",id,"ok",false,"phase","accepted","message",message,"session",epoch,"revision",session.getRevision())
        rememberResult(id,packet);sendWatch(packet)
    }
    private fun handleWatch(text: String){
        if(text.length>4096)return
        try{
            val request=JSONObject(text);if(request.optInt("v",-1)!=1)return
            val type=request.optString("type","");watchMessage="已收到手表消息"
            if(type=="hello" || type=="getStatus"){publish();changed();return};if(type!="command")return
            val id=request.optString("id","");if(id.length !in 1..64)return
            results[id]?.let{sendWatch(it);publish();return}
            val action=request.optString("action","");if(action!="start" && action!="stop" && action!="photo"){reject(id,"不支持的操作");return}
            val issued=tickets[request.optString("ticket","")]
            if(epoch!=request.optString("session","") || issued==null || SystemClock.elapsedRealtime()-issued>6000){reject(id,"指令凭据已过期，请等状态更新后重新点击");return}
            if(action=="photo")photoCommand(id)else command(action=="start",id)
        }catch(_: Exception){log("手表消息格式不符，未执行")}
    }
    private fun reject(id: String, detail: String){
        val response=json("v",1,"type","result","id",id,"ok",false,"phase","rejected","message",detail)
        rememberResult(id,response);sendWatch(response);message=detail;publish();changed()
    }
    private fun rememberResult(id: String, result: JSONObject){results[id]=result;while(results.size>128)results.remove(results.keys.first())}
    fun log(line: String){message=line;diagnostic(line)}
    private fun diagnostic(line: String){logs.add(0,line.take(200));while(logs.size>60)logs.removeAt(logs.lastIndex);changed()}
    fun addListener(listener: UiListener){if(listener !in observers)observers.add(listener)}
    fun removeListener(listener: UiListener){observers.remove(listener)}
    private var uiQueued=false
    private fun changed(){if(uiQueued || observers.isEmpty())return;uiQueued=true;main.post{uiQueued=false;for(observer in observers.toList())observer.onChanged()}}
    private fun json(vararg values: Any): JSONObject {
        val result=JSONObject();try{for(i in values.indices step 2)result.put(values[i] as String,values[i+1])}catch(_: Exception){};return result
    }
}
