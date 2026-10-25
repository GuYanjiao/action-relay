package cn.local.actionrelay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import cn.local.actionrelay.protocol.GpsProtocol;

/** GPS_PROVIDER only. Keeps the current fix and one preceding altitude in memory; writes no track. */
@SuppressLint("MissingPermission")
public final class PhoneGps {
    public interface Listener {
        void onStateChanged(State state);
        void onFix(GpsProtocol.Fix fix);
    }
    public static final class State {
        public final boolean enabled,ready;
        public final String phase,message;
        public final long ageMs;
        public final float accuracyMeters;
        public final int satellites;
        State(boolean enabled,boolean ready,String phase,String message,long age,float accuracy,int satellites){
            this.enabled=enabled;this.ready=ready;this.phase=phase;this.message=message;ageMs=age;accuracyMeters=accuracy;this.satellites=satellites;
        }
    }
    private final Context context;
    private final LocationManager manager;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private boolean running,nmeaRegistered,gnssRegistered;
    private long startedElapsed,emittedElapsed=-1;
    private Location location;
    private GpsProtocol.NmeaFix nmea;
    private long nmeaElapsed=-1,satellitesElapsed=-1;
    private int usedSatellites=-1;
    private GpsProtocol.Fix fix;
    private long previousElapsed=-1;
    private double previousAltitude;
    private float previousVerticalAccuracy;
    private String previousAltitudeSource="",issuePhase="off",issueMessage="GPS推送未开启";
    private GpsProtocol.VerticalSpeed currentDown;

    public PhoneGps(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        manager=this.context.getSystemService(LocationManager.class);
    }
    /** Call while the location foreground-service type and precise permission are active. */
    public boolean start(){
        if(running)return true;
        resetMeasurements();
        if(!hasPermission()){issue("permission","需要精确定位权限");notifyState();return false;}
        if(manager==null||!gpsEnabled()){issue("provider_off","请先打开手机定位和GPS");notifyState();return false;}
        running=true;startedElapsed=SystemClock.elapsedRealtime();issue("searching","正在等待真实GNSS定位，请到开阔处");
        try{
            gnssRegistered=manager.registerGnssStatusCallback(context.getMainExecutor(),gnssCallback);
            nmeaRegistered=manager.addNmeaListener(context.getMainExecutor(),nmeaListener);
            android.location.LocationRequest request=new android.location.LocationRequest.Builder(1000)
                .setMinUpdateIntervalMillis(1000).setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY).build();
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,request,context.getMainExecutor(),locationListener);
            main.post(pulse);notifyState();return true;
        }catch(SecurityException denied){shutdown();issue("permission","定位权限或前台定位服务未就绪");notifyState();return false;}
        catch(RuntimeException error){shutdown();issue("unavailable","无法启动手机GNSS："+shortError(error));notifyState();return false;}
    }
    public void stop(){shutdown();issue("off","GPS推送未开启");notifyState();}
    private void shutdown(){
        running=false;main.removeCallbacks(pulse);
        if(manager!=null){
            try{manager.removeUpdates(locationListener);}catch(RuntimeException ignored){}
            if(nmeaRegistered)try{manager.removeNmeaListener(nmeaListener);}catch(RuntimeException ignored){}
            if(gnssRegistered)try{manager.unregisterGnssStatusCallback(gnssCallback);}catch(RuntimeException ignored){}
        }
        nmeaRegistered=false;gnssRegistered=false;resetMeasurements();
    }
    private void resetMeasurements(){
        location=null;nmea=null;fix=null;nmeaElapsed=-1;satellitesElapsed=-1;usedSatellites=-1;
        previousElapsed=-1;previousAltitudeSource="";currentDown=null;emittedElapsed=-1;
    }
    private final Runnable pulse=new Runnable(){public void run(){
        if(!running)return;notifyState();main.postDelayed(this,1000);
    }};
    private final LocationListener locationListener=new LocationListener(){
        @Override public void onLocationChanged(Location result){
            if(!running||result==null)return;
            if(!LocationManager.GPS_PROVIDER.equals(result.getProvider())||result.isMock()){
                fix=null;issue("waiting_quality","仅接受真实GPS_PROVIDER定位");notifyState();return;
            }
            long elapsed=result.getElapsedRealtimeNanos()/1000000;
            if(elapsed<startedElapsed)return;
            if(location!=null&&elapsed<location.getElapsedRealtimeNanos()/1000000)return;
            location=new Location(result);rebuild();
        }
        @Override public void onProviderDisabled(String provider){if(running&&LocationManager.GPS_PROVIDER.equals(provider)){
            fix=null;previousElapsed=-1;issue("provider_off","手机GPS已关闭，已暂停推送");notifyState();
        }}
        @Override public void onProviderEnabled(String provider){if(LocationManager.GPS_PROVIDER.equals(provider)&&running){
            resetMeasurements();issue("searching","正在重新等待GNSS定位");notifyState();
        }}
    };
    private final GnssStatus.Callback gnssCallback=new GnssStatus.Callback(){
        @Override public void onSatelliteStatusChanged(GnssStatus status){
            if(!running)return;int count=0;
            for(int i=0;i<status.getSatelliteCount();i++)if(status.usedInFix(i))count++;
            usedSatellites=count;satellitesElapsed=SystemClock.elapsedRealtime();rebuild();
        }
        @Override public void onStopped(){if(running){
            resetMeasurements();issue("searching","GNSS暂未定位，已暂停推送");notifyState();
        }}
    };
    private final OnNmeaMessageListener nmeaListener=(sentence,timestamp)->{
        if(!running)return;
        GpsProtocol.NmeaFix parsed=GpsProtocol.parseGga(sentence);
        if(parsed==null)return;
        nmea=parsed;nmeaElapsed=SystemClock.elapsedRealtime();rebuild();
    };
    private boolean hasPermission(){return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private boolean gpsEnabled(){try{return manager!=null&&manager.isLocationEnabled()&&manager.isProviderEnabled(LocationManager.GPS_PROVIDER);}catch(RuntimeException ignored){return false;}}
    private int satelliteCount(long now,GpsProtocol.NmeaFix matching){
        if(satellitesElapsed>=0&&now>=satellitesElapsed&&now-satellitesElapsed<=GpsProtocol.MAX_FIX_AGE_MS)return usedSatellites;
        return matching==null?-1:matching.satellites;
    }
    private void rebuild(){
        if(!running)return;fix=null;
        if(!hasPermission()){issue("permission","精确定位权限不可用，已暂停推送");notifyState();return;}
        if(location==null){notifyState();return;}
        long now=SystemClock.elapsedRealtime(),elapsed=location.getElapsedRealtimeNanos()/1000000;
        if(now<elapsed||now-elapsed>GpsProtocol.MAX_FIX_AGE_MS){issue("stale","GNSS定位已过期，已暂停推送");notifyState();return;}
        if(!location.hasAccuracy()||!positive(location.getAccuracy())||location.getAccuracy()>GpsProtocol.MAX_HORIZONTAL_ACCURACY_METERS){
            issue("waiting_quality","等待更准确的GNSS定位");notifyState();return;
        }
        GpsProtocol.NmeaFix matching=nmea!=null&&nmeaElapsed>=0&&now>=nmeaElapsed&&now-nmeaElapsed<=GpsProtocol.MAX_FIX_AGE_MS
            &&nmea.matches(location.getTime(),location.getLatitude(),location.getLongitude(),location.getAccuracy())?nmea:null;
        double altitude;float verticalAccuracy;String altitudeSource;
        if(Build.VERSION.SDK_INT>=34&&location.hasMslAltitude()){
            altitude=location.getMslAltitudeMeters();altitudeSource="location-msl";
            verticalAccuracy=location.hasMslAltitudeAccuracy()?location.getMslAltitudeAccuracyMeters()
                :location.hasVerticalAccuracy()?location.getVerticalAccuracyMeters():-1;
        }else if(matching!=null){
            altitude=matching.altitudeMslMeters;altitudeSource="nmea-msl";
            verticalAccuracy=location.hasVerticalAccuracy()?location.getVerticalAccuracyMeters():-1;
        }else{issue("waiting_quality","等待GNSS海拔数据（MSL/GGA）");notifyState();return;}
        if(!GpsProtocol.finite(altitude)||!positive(verticalAccuracy)){
            issue("waiting_quality","等待有效的海拔和垂直精度");notifyState();return;
        }
        if(elapsed!=previousElapsed||!altitudeSource.equals(previousAltitudeSource)){
            currentDown=altitudeSource.equals(previousAltitudeSource)?GpsProtocol.deriveDown(previousAltitude,previousVerticalAccuracy,previousElapsed,altitude,verticalAccuracy,elapsed):null;
            previousAltitude=altitude;previousVerticalAccuracy=verticalAccuracy;previousElapsed=elapsed;previousAltitudeSource=altitudeSource;
        }
        if(currentDown==null){issue("waiting_quality","等待连续GNSS海拔以估计垂直速度");notifyState();return;}
        if(!location.hasSpeed()||!location.hasSpeedAccuracy()||!positive(location.getSpeedAccuracyMetersPerSecond())
            ||!GpsProtocol.finite(location.getSpeed())||location.getSpeed()<0){
            issue("waiting_quality","等待GNSS速度及速度精度");notifyState();return;
        }
        float speed=location.getSpeed();
        if(speed>0&&(!location.hasBearing()||!GpsProtocol.finite(location.getBearing()))){
            issue("waiting_quality","等待GNSS运动方位");notifyState();return;
        }
        double bearing=speed==0?0:Math.toRadians(location.getBearing());
        int satellites=satelliteCount(now,matching);
        if(satellites<1){issue("waiting_quality","等待参与定位的实测卫星数");notifyState();return;}
        GpsProtocol.Fix candidate=new GpsProtocol.Fix(location.getTime(),elapsed,location.getLatitude(),location.getLongitude(),altitude,
            (float)(speed*Math.cos(bearing)),(float)(speed*Math.sin(bearing)),currentDown.downMps,
            location.getAccuracy(),verticalAccuracy,Math.max(location.getSpeedAccuracyMetersPerSecond(),currentDown.accuracyMps),satellites);
        String invalid=GpsProtocol.invalidReason(candidate,now);
        if(invalid!=null){issue("waiting_quality",invalid);notifyState();return;}
        fix=candidate;issue("ready","手机GNSS已定位");notifyState();
        if(candidate.elapsedMillis!=emittedElapsed){emittedElapsed=candidate.elapsedMillis;listener.onFix(candidate);}
    }
    public State getState(){
        long now=SystemClock.elapsedRealtime(),age=location==null?-1:Math.max(0,now-location.getElapsedRealtimeNanos()/1000000);
        float accuracy=location!=null&&location.hasAccuracy()&&GpsProtocol.finite(location.getAccuracy())?location.getAccuracy():-1;
        int sats=satelliteCount(now,null);
        if(running&&!hasPermission())return new State(true,false,"permission","精确定位权限不可用，已暂停推送",age,accuracy,sats);
        if(running&&!gpsEnabled())return new State(true,false,"provider_off","手机GPS已关闭，已暂停推送",age,accuracy,sats);
        String invalid=GpsProtocol.invalidReason(fix,now);
        if(running&&fix!=null&&invalid==null)return new State(true,true,"ready","手机GNSS已定位",age,accuracy,fix.satellites);
        if(running&&fix!=null)return new State(true,false,"stale","GNSS定位已过期，已暂停推送",age,accuracy,sats);
        return new State(running,false,issuePhase,issueMessage,age,accuracy,sats);
    }
    public GpsProtocol.Fix getFix(){return getState().ready?fix:null;}
    private void notifyState(){listener.onStateChanged(getState());}
    private void issue(String phase,String message){issuePhase=phase;issueMessage=message;}
    private static boolean positive(double value){return GpsProtocol.finite(value)&&value>0;}
    private static String shortError(RuntimeException error){String text=error.getClass().getSimpleName();return text.length()>60?text.substring(0,60):text;}
}
