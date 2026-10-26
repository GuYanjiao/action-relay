package cn.local.actionrelay;

import android.content.Context;
import android.content.SharedPreferences;
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
    public float gpsAccuracy=-1,gpsSpeed=-1;
    public int verificationCode;
    private String epoch=UUID.randomUUID().toString(),ticket="";
    private long lastPublish;
    private String activeRequest="";
    private long statusSeq;
    private int gpsSequence;
    private final PhoneGps gps;
    private final GpsLifecycle gpsLifecycle;
    public RelayHub(Context ctx) {
        context=ctx.getApplicationContext(); prefs=context.getSharedPreferences("relay",0);
        rememberedCameraAddress=prefs.getString("camera_address","");
        rememberedCameraName=prefs.getString("camera_name","");
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
                if(enabled&&autoReconnectEnabled&&!rememberedCameraAddress.isEmpty()
                    &&reason!=null&&!reason.contains("拒绝")&&!reason.contains("授权")&&!reason.contains("重新建立")){
                    main.postDelayed(()->{if(enabled&&autoReconnectEnabled)reconnectRememberedCamera();},3000);
                }
            }
            public void onLog(String line){log(line);}
        });
        session=new DjiSession(SystemClock::elapsedRealtime,ble::send,new DjiSession.Listener(){
            public void onReady(boolean ready){
                if(ready) {phase="ready";message="相机已连接，等待状态";prefs.edit().putBoolean("paired_"+selectedAddress,true).putString("camera_address",selectedAddress).putString("camera_name",rememberedCameraName).apply();}
                changed();
            }
            public void onStatus(DjiProtocol.CameraStatus status){
                if(!busy) message=!status.isNormalVideoMode()?"请将相机设为普通视频模式":!status.isControllableState()?"相机暂不可控，状态码 "+status.cameraState:status.isRecording()?"相机正在录像":status.isPreRecording()?"相机处于预录缓存":"相机已待机";
                if(SystemClock.elapsedRealtime()-lastPublish>500) publish(); changed();
            }
            public void onLog(String line){log(line);}
            public void onCommandResult(String id,boolean desired,String outcome,String detail){
                if(id.equals(activeRequest)){busy=false;activeRequest="";}
                message=detail;
                JSONObject response=json("v",1,"type","result","id",id,"ok","confirmed".equals(outcome),"phase",outcome,"message",detail,
                    "session",epoch,"revision",session.getRevision());
                rememberResult(id,response); sendWatch(response); log(detail); publish();
            }
            public void onDisconnectRequired(String reason){ble.disconnect(reason);}
        },controller,identity);
        bridge=new XiaomiWatchBridge(context,new XiaomiWatchBridge.Listener(){
            public void onMessage(String text){main.post(()->handleWatch(text));}
            public void onState(String text){main.post(()->{watchMessage=text;changed();});}
            public void onLog(String text){main.post(()->log(text));}
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
        if(autoReconnectEnabled&&!rememberedCameraAddress.isEmpty())
            main.postDelayed(()->{if(enabled&&autoReconnectEnabled)reconnectRememberedCamera();},500);
        changed();
    }
    public void stop(){
        enabled=false;main.removeCallbacks(pulse);gpsLifecycle.stop();ble.close();busy=false;message="手机中转已关闭";
        publish();bridge.stop();changed();
    }
    public void scan(){if(!enabled){log("请先开启手机中转");return;}candidates.clear();ble.scan();changed();}
    public void connect(String address){
        if(!enabled)return; selectedAddress=address;rememberedCameraAddress=address;
        Candidate candidate=candidates.get(address);if(candidate!=null)rememberedCameraName=candidate.name;
        prefs.edit().putString("camera_address",address).putString("camera_name",rememberedCameraName).apply();
        verificationCode=random.nextInt(10000);rotateEpoch();
        ble.connect(address);changed();
    }
    public void reconnectRememberedCamera(){if(enabled&&!rememberedCameraAddress.isEmpty())ble.connect(rememberedCameraAddress);}
    public void setAutoReconnect(boolean enabled){autoReconnectEnabled=enabled;prefs.edit().putBoolean("auto_reconnect",enabled).apply();changed();}
    public void reconfigureGps(){
        gpsLifecycle.reconfigure(enabled,gpsEnabled);
        if(!gpsEnabled){gpsState="off";gpsMessage="GPS推送未开启";gpsAccuracy=-1;gpsSpeed=-1;ble.clearGps();}
        changed();
    }
    public void setGpsEnabled(boolean enabled){gpsEnabled=enabled;prefs.edit().putBoolean("gps_enabled",enabled).apply();if(!enabled){gpsLifecycle.stop();gpsState="off";gpsMessage="GPS推送未开启";gpsAccuracy=-1;gpsSpeed=-1;ble.clearGps();}changed();}
    public void setGpsStartError(String detail){gpsLifecycle.stop();ble.clearGps();gpsState="unavailable";gpsMessage=detail;gpsAccuracy=-1;gpsSpeed=-1;changed();}
    private int nextGpsSequence(){gpsSequence=(gpsSequence+1)&0xffff;if(gpsSequence==0)gpsSequence=1;return gpsSequence;}
    public void forgetCamera(){
        if(!selectedAddress.isEmpty())prefs.edit().remove("paired_"+selectedAddress).apply();
        ble.disconnect("已清除本应用保存的相机授权标记，请重新搜索连接");
    }
    private void rotateEpoch(){epoch=UUID.randomUUID().toString();tickets.clear();ticket="";}
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
    private void publish(){if(session==null||bridge==null)return;newTicket();lastPublish=SystemClock.elapsedRealtime();sendWatch(snapshot());}
    private void sendWatch(JSONObject object){if(enabled&&bridge!=null)bridge.send(object.toString());}
    public void phoneCommand(boolean start){command(start,"phone-"+UUID.randomUUID());}
    private void command(boolean desired,String id){
        if(!enabled || (desired?!session.canStartRecording():!session.canStopRecording())){reject(id,"相机未就绪，或当前状态不允许此操作");return;}
        busy=true;activeRequest=id;message=desired?"正在请求开始录像":"正在请求停止录像";
        JSONObject accepted=json("v",1,"type","result","id",id,"ok",false,"phase","accepted","message",message,"session",epoch,"revision",session.getRevision());
        rememberResult(id,accepted);sendWatch(accepted);publish();
        session.setRecording(desired,id);changed();
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
        if(line==null)return;message=line;
        logs.add(0,line.length()>200?line.substring(0,200):line);while(logs.size()>60)logs.remove(logs.size()-1);changed();
    }
    public void addListener(UiListener listener){if(!observers.contains(listener))observers.add(listener);}
    public void removeListener(UiListener listener){observers.remove(listener);}
    private void changed(){for(UiListener observer:new ArrayList<>(observers))observer.onChanged();}
    private static JSONObject json(Object... values){JSONObject result=new JSONObject();try{for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);}catch(Exception ignored){}return result;}
}
