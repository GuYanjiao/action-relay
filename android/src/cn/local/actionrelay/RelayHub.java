package cn.local.actionrelay;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.Manifest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.*;
import cn.local.actionrelay.protocol.*;

/** Owns local command routing; no network server or cloud account. */
public final class RelayHub {
    public interface UiListener { void onChanged(); }
    public static final class Candidate {
        public final String address,name; public final int rssi;
        Candidate(String a,String n,int r) {address=a;name=n;rssi=r;}
    }
    public final Context context;
    public final DjiBle ble;
    public final DjiSession session;
    public final XiaomiWatchBridge bridge;
    public final List<String> logs=new ArrayList<>();
    public final LinkedHashMap<String,Candidate> candidates=new LinkedHashMap<>();
    private final ArrayList<UiListener> observers=new ArrayList<>();
    private final LinkedHashMap<String,Long> tickets=new LinkedHashMap<>();
    private final LinkedHashMap<String,JSONObject> results=new LinkedHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final SecureRandom random=new SecureRandom();
    public boolean enabled,busy,gpsEnabled,autoReconnectEnabled;
    public String phase="disconnected",message="先开启手机中转",watchMessage="等待手表连接",selectedAddress="";
    public String gpsState="off",gpsMessage="GPS推送未开启",rememberedCameraName="",rememberedCameraAddress="";
    public long rememberedCameraLastSuccess;
    public float gpsAccuracy=-1,gpsSpeed=-1;
    public int verificationCode;
    private String epoch=UUID.randomUUID().toString(),ticket="";
    private long lastPublish;
    private String activeRequest="";
    private final Map<String,Long> commandStarts=new HashMap<>();
    private String lastPublishedControl="";
    private long statusSeq;
    private int gpsSequence;
    private final ReconnectPolicy reconnectPolicy=new ReconnectPolicy();
    private Runnable reconnectTask;
    private boolean reconnectRequested,suppressReconnect,receiverRegistered;
    private String retryAddress="";
    private final PhoneGps gps;
    private final GpsLifecycle gpsLifecycle;
    public RelayHub(Context ctx) {
        context=ctx.getApplicationContext(); prefs=context.getSharedPreferences("relay",0);
        rememberedCameraAddress=prefs.getString("camera_address","");
        rememberedCameraName=prefs.getString("camera_name","");
        rememberedCameraLastSuccess=prefs.getLong("camera_last_success",0);
        // Older builds also stored failed candidates. Only migrate a known paired camera.
        if(!BluetoothAdapter.checkBluetoothAddress(rememberedCameraAddress)
            ||!prefs.getBoolean("paired_"+rememberedCameraAddress,false)){
            rememberedCameraAddress="";rememberedCameraName="";rememberedCameraLastSuccess=0;
            prefs.edit().remove("camera_address").remove("camera_name").remove("camera_last_success").apply();
        }
        selectedAddress=rememberedCameraAddress;
        retryAddress=rememberedCameraAddress;
        autoReconnectEnabled=prefs.getBoolean("auto_reconnect",false);
        gpsEnabled=prefs.getBoolean("gps_enabled",false);
        byte[] identity=new byte[6];
        long identityLong=prefs.getLong("identity",0);
        if(identityLong==0) {
            random.nextBytes(identity); identity[0]=(byte)((identity[0]&0xfc)|2);
            for(int i=0;i<6;i++) identityLong|=(long)(identity[i]&255)<<(8*i);
            prefs.edit().putLong("identity",identityLong).apply();
        } else for(int i=0;i<6;i++) identity[i]=(byte)(identityLong>>>(8*i));
        long controller=prefs.getLong("controller",-1);
        if(controller<0) {controller=random.nextInt()&0xffffffffL;prefs.edit().putLong("controller",controller).apply();}
        ble=new DjiBle(context,new DjiBle.Listener(){
            public void onCandidate(String a,String n,int r){candidates.put(a,new Candidate(a,n,r));changed();}
            public void onPhase(String p,String m){phase=p;message=m;publish();changed();}
            public void onTransportReady(){
                phase="authorizing"; message=String.format(Locale.ROOT,"请在相机上允许连接 · 验证码 %04d",verificationCode);
                session.startHandshake(!prefs.getBoolean("paired_"+selectedAddress,false),verificationCode); publish();changed();
            }
            public void onBytes(byte[] bytes){session.onBytes(bytes);}
            public void onDisconnected(String reason){
                phase="disconnected"; message=reason; busy=false; rotateEpoch();
                if(session!=null) session.disconnect(); publish();changed();
                if(!suppressReconnect&&enabled&&(autoReconnectEnabled||reconnectRequested)&&!retryAddress.isEmpty()
                    &&prefs.getBoolean("paired_"+retryAddress,false)
                    &&reason!=null&&!reason.contains("拒绝")&&!reason.contains("授权")&&!reason.contains("重新建立")){
                    scheduleReconnect();
                }
            }
            public void onLog(String line){if(line.startsWith("控制分段"))diagnostic(line);else log(line);}
        });
        session=new DjiSession(SystemClock::elapsedRealtime,ble::send,new DjiSession.Listener(){
            public void onReady(boolean ready){
                if(ready) {
                    phase="ready";message="相机已连接，等待状态";reconnectRequested=false;cancelReconnect();reconnectPolicy.reset();
                    Candidate candidate=candidates.get(selectedAddress);
                    if(candidate!=null&&!candidate.name.isEmpty())rememberedCameraName=candidate.name;
                    else if(!selectedAddress.equals(rememberedCameraAddress))rememberedCameraName="DJI Osmo Action 4";
                    rememberedCameraAddress=selectedAddress;rememberedCameraLastSuccess=System.currentTimeMillis();
                    retryAddress=selectedAddress;
                    prefs.edit().putBoolean("paired_"+selectedAddress,true).putString("camera_address",selectedAddress)
                        .putString("camera_name",rememberedCameraName).putLong("camera_last_success",rememberedCameraLastSuccess).apply();
                }
                changed();
            }
            public void onStatus(DjiProtocol.CameraStatus status){
                if(!busy) message=!status.isNormalVideoMode()?"请将相机设为普通视频模式":!status.isControllableState()?"相机暂不可控，状态码 "+status.cameraState:status.isRecording()?"相机正在录像":status.isPreRecording()?"相机处于预录缓存":"相机已待机";
                if(!controlKey().equals(lastPublishedControl)||SystemClock.elapsedRealtime()-lastPublish>500)publish();changed();
            }
            public void onLog(String line){log(line);}
            public void onCommandResult(String id,boolean desired,String outcome,String detail){
                long finishedAt=SystemClock.elapsedRealtime();
                if(id.equals(activeRequest)){busy=false;activeRequest="";ble.setControlPriority(false);}
                message=detail;
                newTicket();lastPublish=finishedAt;
                JSONObject response=json("v",1,"type","result","id",id,"ok","confirmed".equals(outcome),"phase",outcome,"message",detail,
                    "session",epoch,"revision",session.getRevision(),"state",snapshot());
                rememberResult(id,response); sendWatch(response);
                Long started=commandStarts.remove(id);
                if(started!=null)log("控制分段：手机受理至相机结果 "+Math.max(0,finishedAt-started)+" ms");
                log(detail);
            }
            public void onDisconnectRequired(String reason){ble.disconnect(reason);}
        },controller,identity);
        bridge=new XiaomiWatchBridge(context,new XiaomiWatchBridge.Listener(){
            public void onMessage(String text){handleWatch(text);}
            public void onState(String text){main.post(()->{watchMessage=text;changed();});}
            public void onLog(String text){main.post(()->{if(text.startsWith("控制分段"))diagnostic(text);else log(text);});}
        });
        gps=new PhoneGps(context,new PhoneGps.Listener(){
            public void onStateChanged(PhoneGps.State state){
                gpsState=state.phase;gpsMessage=state.message;gpsAccuracy=state.accuracyMeters;if(!state.ready)gpsSpeed=-1;changed();
            }
            public void onDiagnostic(String detail){log("GPS："+detail);}
            public void onFix(GpsProtocol.Fix fix){
                gpsSpeed=Math.max(0,Math.abs(fix.northMps)+Math.abs(fix.eastMps));
                if(enabled&&session.isReady()&&!session.isBusy()&&ble.supportsGps()){
                    try{ble.sendGps(GpsProtocol.encode(nextGpsSequence(),fix,SystemClock.elapsedRealtime()));}
                    catch(IllegalArgumentException error){log("GPS数据未发送："+error.getMessage());}
                }
            }
        });
        gpsLifecycle=new GpsLifecycle(new GpsLifecycle.Driver(){
            public boolean start(){return gps.start();}
            public void stop(){gps.stop();}
        });
    }
    private final Runnable pulse=new Runnable(){public void run(){
        if(!enabled)return; session.tick(SystemClock.elapsedRealtime());
        if(SystemClock.elapsedRealtime()-lastPublish>=1900) publish(); changed(); main.postDelayed(this,500);
    }};
    public void start(){
        if(enabled)return; enabled=true;rotateEpoch();message="手机中转已开启，请搜索相机";
        bridge.start(); gpsLifecycle.reconfigure(true,gpsEnabled); main.post(pulse);
        if(!receiverRegistered){
            IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if(Build.VERSION.SDK_INT>=33)context.registerReceiver(bluetoothReceiver,filter,Context.RECEIVER_EXPORTED);
            else context.registerReceiver(bluetoothReceiver,filter);
            receiverRegistered=true;
        }
        reconnectPolicy.reset();retryAddress=rememberedCameraAddress;
        if((autoReconnectEnabled||reconnectRequested)&&!rememberedCameraAddress.isEmpty())scheduleReconnect();
        changed();
    }
    public void stop(){
        enabled=false;main.removeCallbacks(pulse);cancelReconnect();reconnectRequested=false;
        if(receiverRegistered){context.unregisterReceiver(bluetoothReceiver);receiverRegistered=false;}
        gpsLifecycle.stop();ble.close();busy=false;message="手机中转已关闭";
        publish();bridge.stop();changed();
    }
    public void scan(){if(!enabled){log("请先开启手机中转");return;}cancelReconnect();reconnectRequested=false;candidates.clear();ble.scan();changed();}
    public void connect(String address){
        if(!enabled||!BluetoothAdapter.checkBluetoothAddress(address))return;
        cancelReconnect();reconnectPolicy.reset();reconnectRequested=false;retryAddress=address;beginConnect(address);
    }
    private void beginConnect(String address){
        if(!enabled||!connectionAvailable())return;
        if(address.equals(selectedAddress)&&("connecting".equals(phase)||"authorizing".equals(phase)||session.isReady()))return;
        suppressReconnect=true;
        try{ble.disconnect("正在切换连接");selectedAddress=address;verificationCode=random.nextInt(10000);ble.connect(address);}
        finally{suppressReconnect=false;}
        changed();
    }
    public void requestReconnect(){
        if(rememberedCameraAddress.isEmpty())return;
        if(enabled&&rememberedCameraAddress.equals(selectedAddress)&&session.isReady())return;
        cancelReconnect();reconnectPolicy.reset();retryAddress=rememberedCameraAddress;reconnectRequested=true;
        if(enabled)scheduleReconnect();changed();
    }
    public void reconnectRememberedCamera(){requestReconnect();}
    public void setAutoReconnect(boolean value){
        autoReconnectEnabled=value;prefs.edit().putBoolean("auto_reconnect",value).apply();
        if(!value){cancelReconnect();reconnectRequested=false;}else{reconnectPolicy.reset();onEnvironmentChanged();}changed();
    }
    public void reconfigureGps(){
        gpsLifecycle.reconfigure(enabled,gpsEnabled);
        if(!gpsEnabled){gpsState="off";gpsMessage="GPS推送未开启";gpsAccuracy=-1;gpsSpeed=-1;ble.clearGps();}
        changed();
    }
    public void setGpsEnabled(boolean enabled){gpsEnabled=enabled;prefs.edit().putBoolean("gps_enabled",enabled).apply();if(!enabled){gpsLifecycle.stop();gpsState="off";gpsMessage="GPS推送未开启";gpsAccuracy=-1;gpsSpeed=-1;ble.clearGps();}changed();}
    public void setGpsStartError(String detail){gpsLifecycle.stop();ble.clearGps();gpsState="unavailable";gpsMessage=detail;gpsAccuracy=-1;gpsSpeed=-1;changed();}
    private int nextGpsSequence(){gpsSequence=(gpsSequence+1)&0xffff;if(gpsSequence==0)gpsSequence=1;return gpsSequence;}
    public void forgetCamera(){
        cancelReconnect();reconnectRequested=false;retryAddress="";
        String oldAddress=rememberedCameraAddress;
        prefs.edit().remove("paired_"+oldAddress).remove("paired_"+selectedAddress)
            .remove("camera_address").remove("camera_name").remove("camera_last_success").apply();
        selectedAddress="";rememberedCameraAddress="";rememberedCameraName="";rememberedCameraLastSuccess=0;
        ble.disconnect("已清除本应用保存的相机授权标记，请重新搜索连接");
    }
    private void scheduleReconnect(){
        if(reconnectTask!=null||!enabled||retryAddress.isEmpty()||(!autoReconnectEnabled&&!reconnectRequested)
            ||session.isReady()||"connecting".equals(phase)||"authorizing".equals(phase)||!connectionAvailable())return;
        final ReconnectPolicy.Ticket ticket=reconnectPolicy.schedule();
        if(ticket==null){if(reconnectPolicy.exhausted())log("重连已暂停，请检查相机后点击重连");return;}
        final String address=retryAddress;
        reconnectTask=()->{
            reconnectTask=null;
            if(!reconnectPolicy.claim(ticket)||!enabled||!address.equals(retryAddress)||(!autoReconnectEnabled&&!reconnectRequested))return;
            if(connectionAvailable())beginConnect(address);
        };
        main.postDelayed(reconnectTask,ticket.delayMs);
    }
    private final BroadcastReceiver bluetoothReceiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){
        if(!enabled||!BluetoothAdapter.ACTION_STATE_CHANGED.equals(i.getAction()))return;
        int state=i.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);
        if(state==BluetoothAdapter.STATE_OFF||state==BluetoothAdapter.STATE_TURNING_OFF)ble.disconnect("手机蓝牙已关闭，开启后可继续重连");
        else if(state==BluetoothAdapter.STATE_ON)onEnvironmentChanged();
    }};
    public void onEnvironmentChanged(){
        if(enabled&&(autoReconnectEnabled||reconnectRequested)&&!rememberedCameraAddress.isEmpty()
            &&(retryAddress.isEmpty()||retryAddress.equals(rememberedCameraAddress))
            &&!session.isReady()&&!"connecting".equals(phase)&&!"authorizing".equals(phase)&&connectionAvailable()){
            cancelReconnect();reconnectPolicy.reset();retryAddress=rememberedCameraAddress;scheduleReconnect();
        }
    }
    private boolean connectionAvailable(){
        if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED
            ||context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){
            message="需要附近设备权限，授权后可继续重连";changed();return false;
        }
        if(!ble.bluetoothEnabled()){message="请打开手机蓝牙，开启后继续重连";changed();return false;}
        return true;
    }
    private void cancelReconnect(){
        if(reconnectTask!=null)main.removeCallbacks(reconnectTask);reconnectTask=null;reconnectPolicy.cancel();
    }
    private void rotateEpoch(){
        epoch=UUID.randomUUID().toString();tickets.clear();ticket="";results.clear();
        if(bridge!=null)bridge.clearPending();
    }
    private void newTicket(){
        long now=SystemClock.elapsedRealtime();ticket=UUID.randomUUID().toString();tickets.put(ticket,now);
        Iterator<Map.Entry<String,Long>> it=tickets.entrySet().iterator();
        while(it.hasNext()){Map.Entry<String,Long>e=it.next();if(now-e.getValue()>6000||tickets.size()>16)it.remove();}
    }
    public JSONObject snapshot(){
        DjiProtocol.CameraStatus s=session.getStatus();
        boolean fresh=session.isReady()&&s!=null&&session.getStatusAgeMs()<=DjiSession.STATUS_FRESH_MS;
        Object recording=fresh&&s.isControllableState()?(Object)s.isRecording():JSONObject.NULL;
        String detail=message;
        if(session.isReady()&&!fresh)detail="等待相机最新状态，按钮暂不可用";
        Boolean pending=session.getPendingDesired();
        String commandPhase=session.getCommandPhase();
        return json("v",1,"type","status","seq",++statusSeq,"session",epoch,"ticket",ticket,
            "camera",enabled?phase:"disconnected","recording",recording,
            "canControl",enabled&&session.canControlRecording(),"canStart",enabled&&session.canStartRecording(),
            "canStop",enabled&&session.canStopRecording(),"busy",session.isBusy(),"pendingDesired",pending==null?JSONObject.NULL:pending,
            "recordPhase",commandPhase,"watchConnected",bridge.isWatchConnected(),"gpsState",gpsState,"gpsEnabled",gpsEnabled,
            "battery",fresh?s.batteryPercent:-1,"recordSeconds",fresh?s.recordingSeconds:-1,"gpsMessage",gpsMessage,"message",detail,
            "revision",session.getRevision());
    }
    private String controlKey(){
        DjiProtocol.CameraStatus status=session.getStatus();
        return phase+":"+session.getCommandPhase()+":"+session.canStartRecording()+":"+session.canStopRecording()
            +":"+(status==null?-1:status.cameraState);
    }
    private void publish(){if(session==null||bridge==null)return;newTicket();lastPublish=SystemClock.elapsedRealtime();lastPublishedControl=controlKey();sendWatch(snapshot());}
    private void sendWatch(JSONObject object){if(enabled&&bridge!=null)bridge.send(object.toString());}
    public void phoneCommand(boolean start){command(start,"phone-"+UUID.randomUUID());}
    private void command(boolean desired,String id){
        if(!enabled || (desired?!session.canStartRecording():!session.canStopRecording())){reject(id,"相机未就绪，或当前状态不允许此操作");return;}
        busy=true;activeRequest=id;commandStarts.put(id,SystemClock.elapsedRealtime());message=desired?"正在请求开始录像":"正在请求停止录像";
        ble.setControlPriority(true);
        // Dispatch camera control before generating receipts or waking UI observers.
        session.setRecording(desired,id);
        if(!id.equals(activeRequest)){changed();return;}
        JSONObject accepted=json("v",1,"type","result","id",id,"ok",false,"phase","accepted","message",message,"session",epoch,"revision",session.getRevision());
        rememberResult(id,accepted);sendWatch(accepted);changed();
    }
    private void handleWatch(String text){
        if(text==null||text.length()>4096)return;
        try{
            JSONObject request=new JSONObject(text);
            if(request.optInt("v",-1)!=1)return;
            String type=request.optString("type","");watchMessage="已收到手表消息";
            if("hello".equals(type)||"getStatus".equals(type)){publish();changed();return;}
            if(!"command".equals(type))return;
            String id=request.optString("id","");
            if(id.length()<1||id.length()>64)return;
            JSONObject existing=results.get(id);if(existing!=null){sendWatch(existing);publish();return;}
            String action=request.optString("action","");
            if(!"start".equals(action)&&!"stop".equals(action)){reject(id,"不支持的操作");return;}
            Long issued=tickets.get(request.optString("ticket",""));
            if(!epoch.equals(request.optString("session",""))||issued==null||SystemClock.elapsedRealtime()-issued>6000){reject(id,"指令凭据已过期，请等状态更新后重新点击");return;}
            command("start".equals(action),id);
        }catch(Exception error){log("手表消息格式不符，未执行");}
    }
    private void reject(String id,String detail){
        JSONObject result=json("v",1,"type","result","id",id,"ok",false,"phase","rejected","message",detail);
        rememberResult(id,result);sendWatch(result);message=detail;publish();changed();
    }
    private void rememberResult(String id,JSONObject result){
        results.put(id,result);while(results.size()>128)results.remove(results.keySet().iterator().next());
    }
    public void log(String line){
        if(line==null)return;message=line;diagnostic(line);
    }
    private void diagnostic(String line){
        logs.add(0,line.length()>200?line.substring(0,200):line);while(logs.size()>60)logs.remove(logs.size()-1);changed();
    }
    public void addListener(UiListener listener){if(!observers.contains(listener))observers.add(listener);}
    public void removeListener(UiListener listener){observers.remove(listener);}
    private boolean uiQueued;
    private void changed(){
        if(uiQueued)return;uiQueued=true;
        main.post(()->{uiQueued=false;for(UiListener observer:new ArrayList<>(observers))observer.onChanged();});
    }
    private static JSONObject json(Object... values){JSONObject result=new JSONObject();try{for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);}catch(Exception ignored){}return result;}
}
